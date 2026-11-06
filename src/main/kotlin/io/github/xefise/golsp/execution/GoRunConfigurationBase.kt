package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunCommandBuilder
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool

/** Debug goes through the LSP4IJ DAP state (Delve); every other executor runs a plain `go` process. */
abstract class GoRunConfigurationBase<T : RunConfigurationOptions>(
    project: Project,
    factory: ConfigurationFactory?,
    name: String?,
) : DAPRunConfigurationBase<T>(project, factory, name) {

    /** Resolved at launch: reads the env file, so it may fail with a user-facing error. */
    @Throws(ExecutionException::class)
    abstract fun toExecutionRequest(): GoExecutionRequest

    /** What Run and Debug launch: [toExecutionRequest] plus checks that only make sense at launch time. */
    @Throws(ExecutionException::class)
    fun launchRequest(): GoExecutionRequest {
        val request = toExecutionRequest()
        GoRunCommandBuilder.buildFlagsError(request.buildFlags)?.let { throw ExecutionException(it) }
        return request
    }

    // The base delegates the editor to the DAP factory; Go configurations own their editor.
    abstract override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration>

    override fun getDebugAdapterServer(): DebugAdapterServerDefinition? =
        DebugAdapterManager.getInstance().getDebugAdapterServerById(DELVE_SERVER_ID)

    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException("Go run configurations are disabled in untrusted projects; trust the project to run or debug it")
        }
        if (executor.id == DAPDebugRunner.DEBUG_EXECUTOR_ID) {
            return super.getState(executor, environment)
        }
        val go = GoToolchainService.getInstance(project).findTool(GoTool.GO)
            ?: throw ExecutionException("Go executable not found in PATH, ~/go/bin or GOROOT")
        return GoRunProfileState(environment, go, ::launchRequest)
    }

    companion object {
        // Persisted in plugin.xml (debugAdapterServer id); must never change.
        const val DELVE_SERVER_ID: String = "go-delve"
    }
}
