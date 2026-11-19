package io.github.xefise.golsp.toolchain

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorNotifications
import com.intellij.util.messages.Topic
import io.github.xefise.golsp.toolchain.model.ConfiguredToolPaths
import io.github.xefise.golsp.toolchain.model.GoTool

/** Application-wide tool paths; empty means autodetect. Machine-specific, so never roams. */
@Service(Service.Level.APP)
@State(
    name = "GoLspToolchainSettings",
    storages = [Storage("golsp-toolchain.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.TOOLS,
)
class GoToolchainSettings : SimplePersistentStateComponent<GoToolchainSettings.Options>(Options()) {
    class Options : BaseState() {
        var goBinary by string()
        var goplsPath by string()
        var dlvPath by string()
        var hideGoBanner by property(false)
        var hideGoplsBanner by property(false)
        var hideDlvBanner by property(false)
        var showRunWithRaceDetector by property(false)
    }

    val goBinary: String get() = state.goBinary.orEmpty()
    val goplsPath: String get() = state.goplsPath.orEmpty()
    val dlvPath: String get() = state.dlvPath.orEmpty()

    // Read on every run-action update, so a change applies without a restart.
    var showRunWithRaceDetector: Boolean
        get() = state.showRunWithRaceDetector
        set(value) {
            state.showRunWithRaceDetector = value
        }

    /** Stores the values and notifies every open project once if anything changed. */
    fun update(goBinary: String, goplsPath: String, dlvPath: String) {
        val options = state
        val newGo = ConfiguredToolPaths.normalize(goBinary)
        val newGopls = ConfiguredToolPaths.normalize(goplsPath)
        val newDlv = ConfiguredToolPaths.normalize(dlvPath)
        if (options.goBinary == newGo && options.goplsPath == newGopls && options.dlvPath == newDlv) return
        options.goBinary = newGo
        options.goplsPath = newGopls
        options.dlvPath = newDlv
        ProjectManager.getInstance().openProjects.forEach(GoToolchainSettingsListener::publish)
    }

    fun isBannerHidden(tool: GoTool): Boolean = when (tool) {
        GoTool.GO -> state.hideGoBanner
        GoTool.GOPLS -> state.hideGoplsBanner
        GoTool.DLV -> state.hideDlvBanner
    }

    /** Not a toolchain change: only the editor banners are refreshed. */
    fun setBannerHidden(tool: GoTool, hidden: Boolean) {
        if (isBannerHidden(tool) == hidden) return
        when (tool) {
            GoTool.GO -> state.hideGoBanner = hidden
            GoTool.GOPLS -> state.hideGoplsBanner = hidden
            GoTool.DLV -> state.hideDlvBanner = hidden
        }
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
            .forEach { EditorNotifications.getInstance(it).updateAllNotifications() }
    }

    companion object {
        fun getInstance(): GoToolchainSettings = service()
    }
}

/** Per-project override of the go executable; workspace file, because the path is machine-specific. */
@Service(Service.Level.PROJECT)
@State(name = "GoLspProjectToolchainSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GoProjectToolchainSettings(private val project: Project) :
    SimplePersistentStateComponent<GoProjectToolchainSettings.Options>(Options()) {
    class Options : BaseState() {
        var goBinary by string()
    }

    val goBinary: String get() = state.goBinary.orEmpty()

    fun update(goBinary: String) {
        val newGo = ConfiguredToolPaths.normalize(goBinary)
        if (state.goBinary == newGo) return
        state.goBinary = newGo
        GoToolchainSettingsListener.publish(project)
    }

    companion object {
        fun getInstance(project: Project): GoProjectToolchainSettings = project.service()

        /** Effective paths for [project]; cheap, safe on EDT. The override arrives with the project, so it needs trust. */
        fun configuredPaths(project: Project): ConfiguredToolPaths {
            val app = GoToolchainSettings.getInstance()
            val override = if (TrustedProjects.isProjectTrusted(project)) getInstance(project).goBinary else null
            return ConfiguredToolPaths.of(app.goBinary, override, app.goplsPath, app.dlvPath)
        }
    }
}

/** Published synchronously on the project bus, on the thread that changed the settings (usually EDT). */
fun interface GoToolchainSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<GoToolchainSettingsListener> =
            Topic.create("Go toolchain settings changed", GoToolchainSettingsListener::class.java)

        fun publish(project: Project) {
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).settingsChanged()
        }
    }
}

class GoToolchainSettingsRefresher(private val project: Project) : GoToolchainSettingsListener {
    // Only re-detects where detection already ran: a settings change must not make a non-Go project run `go`.
    override fun settingsChanged() {
        if (!project.isDisposed) GoToolchainService.getInstance(project).refreshIfStarted()
    }
}
