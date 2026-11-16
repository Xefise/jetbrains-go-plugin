package io.github.xefise.golsp.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import io.github.xefise.golsp.execution.GoConsoleFilter
import io.github.xefise.golsp.execution.GoRunConfigurationBase
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunTarget
import kotlin.reflect.KMutableProperty0

class GoApplicationConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    GoRunConfigurationBase<GoApplicationOptions>(project, factory, name), DumbAware {

    override fun getOptions(): GoApplicationOptions = super.getOptions() as GoApplicationOptions

    var packageDirectory: String
        get() = options.packageDirectory
        set(value) {
            options.packageDirectory = value
        }

    var programArguments: String
        get() = options.programArguments
        set(value) {
            options.programArguments = value
        }

    var runKind: GoRunKind
        get() = options.runKind
        set(value) {
            options.runKind = value
        }

    var filePath: String
        get() = options.filePath
        set(value) {
            options.filePath = value
        }

    var workingDirectory: String
        get() = options.workingDirectory
        set(value) {
            options.workingDirectory = value
        }

    var envs: Map<String, String>
        get() = options.envs
        set(value) {
            options.envs = value
        }

    var envFile: String
        get() = options.envFile
        set(value) {
            options.envFile = value
        }

    var buildFlags: String
        get() = options.buildFlags
        set(value) {
            options.buildFlags = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoApplicationSettingsEditor(project)

    override fun pathOptions(): List<KMutableProperty0<String>> =
        listOf(this::packageDirectory, this::filePath, this::workingDirectory, this::envFile)

    /** The package directory's or the file's name. */
    override fun suggestedName(): String? =
        storedPath(if (runKind == GoRunKind.PACKAGE) packageDirectory else filePath)?.fileName?.toString()

    override fun checkConfiguration() {
        when (runKind) {
            GoRunKind.PACKAGE -> {
                if (packageDirectory.isBlank()) throw RuntimeConfigurationError("Package directory is not specified")
                val dir = resolve(packageDirectory)
                if (!dir.toFile().isDirectory) throw RuntimeConfigurationError("Package directory does not exist: $dir")
            }
            GoRunKind.FILE -> {
                if (filePath.isBlank()) throw RuntimeConfigurationError("Go file is not specified")
                val file = resolve(filePath)
                if (!file.toFile().isFile) throw RuntimeConfigurationError("Go file does not exist: $file")
                if (!file.fileName.toString().endsWith(".go")) throw RuntimeConfigurationError("Not a Go file: $file")
            }
        }
        if (workingDirectory.isNotBlank()) {
            val dir = resolve(workingDirectory)
            if (!dir.toFile().isDirectory) throw RuntimeConfigurationError("Working directory does not exist: $dir")
        }
        checkEnvFileAndBuildFlags(envFile, buildFlags)
    }

    // Run and DAP debug states are both CommandLineState with a TextConsoleBuilder, so either console gets the links.
    @Throws(ExecutionException::class)
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        super.getState(executor, environment).also { state ->
            if (state is CommandLineState) {
                val target = target()
                state.addConsoleFilters(GoConsoleFilter(project, resolvedWorkingDirectory(target), target.directory))
            }
        }

    @Throws(ExecutionException::class)
    override fun toExecutionRequest(): GoExecutionRequest {
        val target = target()
        return GoExecutionRequest(
            target = target,
            programArguments = ParametersListUtil.parse(programArguments),
            environment = userEnvironment(envs, envFile),
            workingDirectory = resolvedWorkingDirectory(target),
            buildFlags = ParametersListUtil.parse(buildFlags),
        )
    }

    private fun resolvedWorkingDirectory(target: GoRunTarget): String =
        if (workingDirectory.isBlank()) target.directory else resolve(workingDirectory).toString()

    private fun target(): GoRunTarget = when (runKind) {
        GoRunKind.PACKAGE -> GoRunTarget.Package(resolve(packageDirectory).toString())
        GoRunKind.FILE -> {
            val file = resolve(filePath)
            GoRunTarget.File(file.parent?.toString() ?: file.toString(), file.fileName?.toString().orEmpty())
        }
    }
}
