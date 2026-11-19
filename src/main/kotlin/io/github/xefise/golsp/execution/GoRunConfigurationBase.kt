package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfiguration
import com.intellij.execution.configurations.RefactoringListenerProvider
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
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.UndoRefactoringElementAdapter
import com.intellij.util.execution.ParametersListUtil
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRaceDetector
import io.github.xefise.golsp.execution.model.GoRunCommandBuilder
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0

/** Debug goes through the LSP4IJ DAP state (Delve); every other executor runs a plain `go` process. */
abstract class GoRunConfigurationBase<T : RunConfigurationOptions>(
    project: Project,
    factory: ConfigurationFactory?,
    name: String?,
) : DAPRunConfigurationBase<T>(project, factory, name), LocatableConfiguration, RefactoringListenerProvider {

    /** Stored paths that follow a rename or move of their file or of a directory above it. */
    protected abstract fun pathOptions(): List<KMutableProperty0<String>>

    // The platform tracks a "name changed by user" flag only for LocatableConfigurationBase, which the DAP base is not.
    override fun isGeneratedName(): Boolean = suggestedName()?.let { it == name } ?: false

    // Called for every configuration on every rename or move in the project: no file system access here.
    override fun getRefactoringElementListener(element: PsiElement?): RefactoringElementListener? {
        val root = (element as? PsiFileSystemItem)?.virtualFile?.let { Path.of(it.path).normalize() } ?: return null
        if (pathOptions().none { storedPath(it.get())?.startsWith(root) == true }) return null
        return PathsListener(root)
    }

    // The platform renames a generated name after this listener; undo comes back through refactored() as well.
    private inner class PathsListener(private var root: Path) : UndoRefactoringElementAdapter() {
        override fun refactored(element: PsiElement, oldQualifiedName: String?) {
            val target = (element as? PsiFileSystemItem)?.virtualFile?.let { Path.of(it.path).normalize() } ?: return
            for (option in pathOptions()) {
                val path = storedPath(option.get()) ?: continue
                if (path.startsWith(root)) option.set(FileUtil.toSystemIndependentName(target.resolve(root.relativize(path)).toString()))
            }
            root = target
        }
    }

    /** [resolve] for a path that may be blank or unparsable. */
    protected fun storedPath(path: String): Path? =
        if (path.isBlank()) null else try {
            resolve(path)
        } catch (_: RuntimeConfigurationError) {
            null
        }

    /** Resolved at launch: reads the env file, so it may fail with a user-facing error. */
    @Throws(ExecutionException::class)
    abstract fun toExecutionRequest(): GoExecutionRequest

    /**
     * What Run and Debug launch: [toExecutionRequest] plus checks that only make sense at launch time. Under
     * [GoRaceExecutor] `-race` is added to this launch only, never to the stored flags.
     */
    @Throws(ExecutionException::class)
    fun launchRequest(): GoExecutionRequest = launchRequest(executorId = null)

    @Throws(ExecutionException::class)
    fun launchRequest(executorId: String?): GoExecutionRequest {
        val request = toExecutionRequest()
        buildFlagsError(request.buildFlags)?.let { throw ExecutionException(it) }
        val race = executorId == GoRaceExecutor.EXECUTOR_ID
        return request.copy(buildFlags = GoRaceDetector.withRaceFlag(request.buildFlags, race))
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
    protected open fun createRunState(
        environment: ExecutionEnvironment,
        goExecutable: String,
        request: () -> GoExecutionRequest,
    ): RunProfileState = GoRunProfileState(environment, goExecutable, request)

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
        return createRunState(environment, go) { launchRequest(executor.id) }
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
    // A path the OS rejects (`C:\a<b` on Windows) is a configuration error, not a crash in checkConfiguration.
    @Throws(RuntimeConfigurationError::class)
    internal fun resolve(path: String): Path {
        val expanded = PathMacroManager.getInstance(project).expandPath(path.trim())
        val file = File(expanded)
        val base = project.basePath
        val absolute = if (file.isAbsolute || base == null) file else File(base, expanded)
        return try {
            absolute.toPath().normalize()
        } catch (_: InvalidPathException) {
            throw RuntimeConfigurationError("Invalid path: ${path.trim()}")
        }
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
