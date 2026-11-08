package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import io.github.xefise.golsp.debug.model.DelveAttachBuilder
import io.github.xefise.golsp.debug.model.DelveAttachTarget
import io.github.xefise.golsp.execution.GoRunConfigurationBase
import javax.swing.JComponent

/** Connects to the loopback `dlv dap` started by Attach to Process, which then attaches to [processId]. */
class GoAttachConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    DAPRunConfigurationBase<GoAttachOptions>(project, factory, name), GoDelveAttachProfile, DumbAware {

    override fun getOptions(): GoAttachOptions = super.getOptions() as GoAttachOptions

    var processId: Int
        get() = options.processId
        set(value) {
            options.processId = value
        }

    var port: Int
        get() = options.port
        set(value) {
            options.port = value
        }

    override fun attachTarget(): DelveAttachTarget = DelveAttachTarget.Local(port, processId)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoAttachSettingsEditor()

    override fun getDebugAdapterServer(): DebugAdapterServerDefinition? =
        DebugAdapterManager.getInstance().getDebugAdapterServerById(GoRunConfigurationBase.DELVE_SERVER_ID)

    override fun checkConfiguration() {
        DelveAttachBuilder.targetError(attachTarget())?.let { throw RuntimeConfigurationError(it) }
    }

    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException("Go run configurations are disabled in untrusted projects; trust the project to debug it")
        }
        if (executor.id != DAPDebugRunner.DEBUG_EXECUTOR_ID) {
            throw ExecutionException("Go Attach to Process only attaches the debugger; start it with Debug")
        }
        DelveAttachBuilder.targetError(attachTarget())?.let { throw ExecutionException(it) }
        return super.getState(executor, environment)
    }
}

/** Read-only: the configuration lives for one attach and is never edited by hand. */
class GoAttachSettingsEditor : SettingsEditor<GoAttachConfiguration>() {
    private val processId = JBLabel()
    private val port = JBLabel()
    private val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Process ID:", processId)
        .addLabeledComponent("dlv port:", port)
        .panel

    override fun resetEditorFrom(configuration: GoAttachConfiguration) {
        processId.text = configuration.processId.toString()
        port.text = configuration.port.toString()
    }

    override fun applyEditorTo(configuration: GoAttachConfiguration) = Unit

    override fun createEditor(): JComponent = panel
}
