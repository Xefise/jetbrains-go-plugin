package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunCommandBuilder
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool
import java.io.File
import java.nio.file.Path

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
        buildFlagsError(request.buildFlags)?.let { throw ExecutionException(it) }
        return request
    }

    /** Checked both in [checkConfiguration] and at launch. */
    protected open fun buildFlagsError(buildFlags: List<String>): String? = GoRunCommandBuilder.buildFlagsError(buildFlags)

    /**
     * Why this configuration cannot be debugged, or null; checked when Debug starts, not in checkRunnerSettings: Rerun
     * Failed checks the original settings, while the copy it launches may be debuggable.
     */
    open fun debugConfigurationError(): String? = null

    /** The Debug console; DAPDebugProcess shows whatever console the state's execution result carries. */
    protected open fun createDebugConsole(executor: Executor, default: () -> ConsoleView?): ConsoleView? = default()

    /** The process behind every executor except Debug. */
    protected open fun createRunState(environment: ExecutionEnvironment, goExecutable: String): RunProfileState =
        GoRunProfileState(environment, goExecutable, ::launchRequest)

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
            debugConfigurationError()?.let { throw ExecutionException(it) }
            val state = super.getState(executor, environment)
            return if (state is DAPCommandLineState) GoDebugCommandLineState(state, options, environment, this) else state
        }
        val go = GoToolchainService.getInstance(project).findTool(GoTool.GO)
            ?: throw ExecutionException("Go executable not found in PATH, ~/go/bin or GOROOT")
        return createRunState(environment, go)
    }

    /** Explicit variables over the env file, read at launch so its edits apply without touching the configuration. */
    @Throws(ExecutionException::class)
    protected fun userEnvironment(envs: Map<String, String>, envFile: String): Map<String, String> {
        val fileVars = if (envFile.isBlank()) emptyMap() else GoEnvFileLoader.load(resolve(envFile))
        return GoEnvironmentLayers.merge(fileVars, GoParentEnvironment.inline(envs))
    }

    @Throws(RuntimeConfigurationError::class)
    protected fun checkEnvFileAndBuildFlags(envFile: String, buildFlags: String) {
        if (envFile.isNotBlank()) {
            val file = resolve(envFile)
            if (!file.toFile().isFile) throw RuntimeConfigurationError("Env file does not exist: $file")
        }
        buildFlagsError(ParametersListUtil.parse(buildFlags))?.let { throw RuntimeConfigurationError(it) }
    }

    // Expands $PROJECT_DIR$-style macros; a relative path is taken from the project root.
    internal fun resolve(path: String): Path {
        val expanded = PathMacroManager.getInstance(project).expandPath(path.trim())
        val file = File(expanded)
        val base = project.basePath
        val absolute = if (file.isAbsolute || base == null) file else File(base, expanded)
        return absolute.toPath().normalize()
    }

    // DAPDebugRunner casts the state to DAPCommandLineState, so the console hook has to be a subclass of it.
    private class GoDebugCommandLineState(
        base: DAPCommandLineState,
        options: RunConfigurationOptions,
        environment: ExecutionEnvironment,
        private val configuration: GoRunConfigurationBase<*>,
    ) : DAPCommandLineState(base.serverDescriptor, options, environment) {
        override fun createConsole(executor: Executor): ConsoleView? =
            configuration.createDebugConsole(executor) { super.createConsole(executor) }
    }

    companion object {
        // Persisted in plugin.xml (debugAdapterServer id); must never change.
        const val DELVE_SERVER_ID: String = "go-delve"
    }
}
