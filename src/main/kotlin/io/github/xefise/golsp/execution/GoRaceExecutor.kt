package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.openapi.wm.ToolWindowId
import io.github.xefise.golsp.toolchain.GoToolchainSettings
import javax.swing.Icon

/** A plain Run with `-race` added for this launch only; shown only while enabled in the Go settings. */
class GoRaceExecutor : Executor() {
    override fun getId(): String = EXECUTOR_ID

    override fun getContextActionId(): String = CONTEXT_ACTION_ID

    override fun getToolWindowId(): String = ToolWindowId.RUN

    override fun getToolWindowIcon(): Icon = AllIcons.Toolwindows.ToolWindowRun

    override fun getIcon(): Icon = ICON

    override fun getDisabledIcon(): Icon = IconLoader.getDisabledIcon(icon)

    override fun getDescription(): String = "Run the selected configuration with the Go race detector"

    override fun getActionName(): String = "Run with Race Detector"

    override fun getStartActionText(): String = "Run with Race Detector"

    // The name goes in after parsing, so an underscore in it is not taken for a mnemonic.
    override fun getStartActionText(configurationName: String): String {
        if (configurationName.isEmpty()) return startActionText
        return TextWithMnemonic.parse("Run '%s' with Race Detector")
            .replaceFirst("%s", shortenNameIfNeeded(configurationName)).toString()
    }

    override fun getHelpId(): String? = null

    // Honoured by the toolbar action only; the gutter and context menu hide it through GoRaceProgramRunner.canRun.
    override fun isApplicable(project: Project): Boolean = isEnabled()

    companion object {
        private val ICON: Icon = IconLoader.getIcon("/icons/runRace.svg", GoRaceExecutor::class.java)

        // Persisted in run configurations' runner settings and in keymaps; must never change.
        const val EXECUTOR_ID: String = "GoLspRunWithRace"

        // Registered as an action id, so it is persisted in keymaps; must never change.
        const val CONTEXT_ACTION_ID: String = "GoLspRunWithRaceContext"

        fun isEnabled(): Boolean = GoToolchainSettings.getInstance().showRunWithRaceDetector
    }
}

/** Runs Go Application and Go Test configurations, Rerun Failed included, under [GoRaceExecutor]. */
class GoRaceProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == GoRaceExecutor.EXECUTOR_ID && GoRaceExecutor.isEnabled() && isGoConfiguration(profile)

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    companion object {
        // Persisted in run configurations' runner settings; must never change.
        const val RUNNER_ID: String = "GoLspRaceRunner"

        // Rerun Failed wraps the configuration it runs; remote and attach debug configurations are not Go runs.
        internal fun isGoConfiguration(profile: RunProfile): Boolean = when (profile) {
            is GoRunConfigurationBase<*> -> true
            is WrappingRunConfiguration<*> -> profile.peer is GoRunConfigurationBase<*>
            else -> false
        }
    }
}
