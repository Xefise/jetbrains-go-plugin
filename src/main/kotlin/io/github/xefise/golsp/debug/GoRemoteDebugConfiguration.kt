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
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import io.github.xefise.golsp.debug.model.DelveAttachBuilder
import io.github.xefise.golsp.debug.model.DelveAttachTarget
import io.github.xefise.golsp.debug.model.SubstitutePathRule
import io.github.xefise.golsp.execution.GoRunConfigurationBase

/** Attaches the Delve DAP client to a dlv server the user started; there is nothing to Run. */
class GoRemoteDebugConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    DAPRunConfigurationBase<GoRemoteDebugOptions>(project, factory, name), GoDelveAttachProfile, DumbAware {

    override fun getOptions(): GoRemoteDebugOptions = super.getOptions() as GoRemoteDebugOptions

    var host: String
        get() = options.host
        set(value) {
            options.host = value
        }

    var port: Int
        get() = options.port
        set(value) {
            options.port = value
        }

    var substitutePath: List<SubstitutePathRule>
        get() = options.substitutePath.map { SubstitutePathRule(it.local.orEmpty(), it.remote.orEmpty()) }
        set(value) {
            options.substitutePath = value.mapTo(mutableListOf()) { rule ->
                GoSubstitutePathEntry().apply {
                    local = rule.from
                    remote = rule.to
                }
            }
        }

    override fun attachTarget(): DelveAttachTarget = DelveAttachTarget.Remote(host.trim(), port, substitutePath)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoRemoteDebugSettingsEditor()

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
            throw ExecutionException("Go Remote Debug only attaches the debugger; start it with Debug")
        }
        DelveAttachBuilder.targetError(attachTarget())?.let { throw ExecutionException(it) }
        return super.getState(executor, environment)
    }
}
