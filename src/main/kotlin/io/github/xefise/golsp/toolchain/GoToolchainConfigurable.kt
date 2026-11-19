package io.github.xefise.golsp.toolchain

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.xefise.golsp.toolchain.model.ConfiguredToolPaths
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolStatusSnapshot
import io.github.xefise.golsp.toolchain.model.GoToolTexts
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import javax.swing.JButton
import javax.swing.JLabel

/** Settings → Languages & Frameworks → Go: tool paths, the project's go override, tool status and installation. */
class GoToolchainConfigurable(private val project: Project) : BoundSearchableConfigurable("Go", ID, ID) {
    private val pending = mutableMapOf<String, String>()
    private val pendingHidden = mutableMapOf<GoTool, Boolean>()
    private val statusLabels = mutableMapOf<GoTool, JLabel>()
    private val toolButtons = mutableMapOf<GoTool, JButton>()
    private lateinit var goLabel: JLabel
    private lateinit var installAllButton: JButton

    internal lateinit var goField: TextFieldWithBrowseButton
    internal lateinit var projectGoField: TextFieldWithBrowseButton
    internal lateinit var goplsField: TextFieldWithBrowseButton
    internal lateinit var dlvField: TextFieldWithBrowseButton
    internal val bannerBoxes = mutableMapOf<GoTool, JBCheckBox>()
    internal lateinit var raceActionBox: JBCheckBox

    override fun getHelpTopic(): String? = null

    override fun createPanel(): DialogPanel {
        val settings = GoToolchainSettings.getInstance()
        val projectSettings = GoProjectToolchainSettings.getInstance(project)
        val result = panel {
            group("Go Executable") {
                row("Go executable:") {
                    goField = pathField("Select Go Executable", { settings.goBinary }, GO)
                        .comment("Empty: detect from PATH, GOROOT and standard locations").component
                }
                row("Project override:") {
                    projectGoField = pathField("Select Go Executable", { projectSettings.goBinary }, PROJECT_GO)
                        .comment("Used for this project instead of the executable above").component
                }
                row("Detected:") { goLabel = label("").component }
            }
            group("Tools") {
                row("gopls path:") {
                    goplsField = pathField("Select gopls Executable", { settings.goplsPath }, GOPLS, toolValidation = true)
                        .comment("Empty: GOBIN, GOPATH/bin, PATH").component
                }
                row("dlv path:") {
                    dlvField = pathField("Select dlv Executable", { settings.dlvPath }, DLV, toolValidation = true).component
                }
                toolRow(GoTool.GOPLS)
                toolRow(GoTool.DLV)
                row {
                    installAllButton = button("Install All") { GoToolInstaller.getInstance().installAll(project) }
                        .comment("Installs missing tools and updates outdated ones with go install").component
                }
            }
            group("Editor Banners") {
                bannerRow(GoTool.GO, "Warn when Go is not found")
                bannerRow(GoTool.GOPLS, "Suggest installing or updating gopls")
                bannerRow(GoTool.DLV, "Suggest installing or updating dlv")
            }
            group("Run") {
                row {
                    raceActionBox = checkBox("Show 'Run with Race Detector' in run actions")
                        .bindSelected(settings::showRunWithRaceDetector)
                        .comment("Runs with -race for that launch only; the configuration stays unchanged")
                        .component
                }
            }
        }
        subscribe(result)
        refreshStatus()
        return result
    }

    // DialogPanel.apply() calls the setters of modified fields only; the rest keep their stored values.
    override fun apply() {
        pending.clear()
        pendingHidden.clear()
        super.apply()
        val settings = GoToolchainSettings.getInstance()
        val projectSettings = GoProjectToolchainSettings.getInstance(project)
        settings.update(pending[GO] ?: settings.goBinary, pending[GOPLS] ?: settings.goplsPath, pending[DLV] ?: settings.dlvPath)
        pending[PROJECT_GO]?.let(projectSettings::update)
        pendingHidden.forEach { (tool, hidden) -> settings.setBannerHidden(tool, hidden) }
        // Shows the stored (trimmed) values, so the page is not left modified.
        super.reset()
    }

    private fun com.intellij.ui.dsl.builder.Row.pathField(title: String, getter: () -> String, key: String, toolValidation: Boolean = false) =
        textFieldWithBrowseButton(FileChooserDescriptorFactory.singleFile().withTitle(title), project)
            .bindText(getter) { pending[key] = it }
            .align(AlignX.FILL)
            .validationOnInput { field ->
                val path = ConfiguredToolPaths.normalize(field.text) ?: return@validationOnInput null
                when {
                    GoToolLocator.isExecutableFile(path) -> null
                    toolValidation -> warning("Not an executable file; the tool is looked up automatically instead")
                    else -> error("Not an executable file; Go will not be found")
                }
            }

    private fun Panel.toolRow(tool: GoTool) {
        row("${tool.baseName}:") {
            statusLabels[tool] = label("").component
            toolButtons[tool] = button("Install") { GoToolInstaller.getInstance().install(project, tool) }.component
        }
    }

    private fun Panel.bannerRow(tool: GoTool, text: String) {
        row {
            bannerBoxes[tool] = checkBox(text)
                .bindSelected({ !GoToolchainSettings.getInstance().isBannerHidden(tool) }) { pendingHidden[tool] = !it }
                .component
        }
    }

    private fun subscribe(panel: DialogPanel) {
        val disposable = disposable ?: return
        var alive = true
        Disposer.register(disposable) { alive = false }
        val later = {
            ApplicationManager.getApplication().invokeLater({ if (alive) refreshStatus() }, ModalityState.stateForComponent(panel))
        }
        project.messageBus.connect(disposable).apply {
            subscribe(GoToolchainListener.TOPIC, GoToolchainListener { later() })
            subscribe(GoToolStatusListener.TOPIC, GoToolStatusListener { later() })
        }
    }

    // Opening the page is a user request, so reading the state may start detection in a project without Go markers.
    private fun refreshStatus() {
        val state = GoToolchainService.getInstance(project).state
        goLabel.text = GoToolTexts.toolchain(state)
        val snapshot = if (state is GoToolchainState.Ready) GoToolStatusService.getInstance(project).snapshot else GoToolStatusSnapshot.NOT_CHECKED
        GoToolStatusSnapshot.CHECKED_TOOLS.forEach { tool ->
            val status = snapshot.status(tool)
            statusLabels[tool]?.text = GoToolTexts.status(status)
            toolButtons[tool]?.apply {
                text = GoToolTexts.buttonText(status)
                isEnabled = GoToolTexts.canInstallManually(status)
            }
        }
        installAllButton.isEnabled = snapshot.statuses.values.any { it.needsInstall }
    }

    companion object {
        const val ID = "golsp.settings"
        private const val GO = "go"
        private const val PROJECT_GO = "projectGo"
        private const val GOPLS = "gopls"
        private const val DLV = "dlv"
    }
}
