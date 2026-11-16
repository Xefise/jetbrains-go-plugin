package io.github.xefise.golsp.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import io.github.xefise.golsp.execution.GoRunConfigurationBase
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunTarget
import io.github.xefise.golsp.execution.model.GoTestCommandBuilder
import io.github.xefise.golsp.test.model.GoModules
import io.github.xefise.golsp.test.model.GoTestKind
import io.github.xefise.golsp.test.model.GoTestNames
import io.github.xefise.golsp.test.model.GoTestSelection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0

class GoTestConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    GoRunConfigurationBase<GoTestOptions>(project, factory, name), DumbAware {

    override fun getOptions(): GoTestOptions = super.getOptions() as GoTestOptions

    var scope: GoTestScope
        get() = options.testScope
        set(value) {
            options.testScope = value
        }

    var directory: String
        get() = options.directory
        set(value) {
            options.directory = value
        }

    var filePath: String
        get() = options.filePath
        set(value) {
            options.filePath = value
        }

    var testNames: List<String>
        get() = options.testNames
        set(value) {
            options.testNames = value
        }

    var testArguments: String
        get() = options.testArguments
        set(value) {
            options.testArguments = value
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

    /** Where `go test` runs: the package directory (the file's for [GoTestScope.FILE]) or the root of `./...`. */
    fun targetDirectory(): Path = when (scope) {
        GoTestScope.PACKAGE, GoTestScope.DIRECTORY -> resolve(directory)
        GoTestScope.FILE -> resolve(filePath).let { it.parent ?: it }
    }

    /** Several packages: the test tree gets one package node per import path. */
    val isRecursive: Boolean get() = scope == GoTestScope.DIRECTORY

    /** Import paths that replace `./...` of [GoTestScope.DIRECTORY]; set only on rerun copies, never persisted. */
    var packages: List<String> = emptyList()
        internal set

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = GoTestSettingsEditor(project)

    override fun pathOptions(): List<KMutableProperty0<String>> = listOf(this::directory, this::filePath, this::envFile)

    /** The test's full name, the file's name, `go test ./a/b` or `go test ./a/...`; null for a hand-made list of names. */
    override fun suggestedName(): String? {
        val names = testNames.filter { it.isNotBlank() }
        return when (scope) {
            GoTestScope.FILE -> if (names.isEmpty()) storedPath(filePath)?.fileName?.toString() else null
            GoTestScope.PACKAGE -> when (names.size) {
                0 -> storedPath(directory)?.let { "go test ${relativePath(project, it)}" }
                1 -> names.single()
                else -> null
            }
            GoTestScope.DIRECTORY -> if (names.isEmpty()) storedPath(directory)?.let { "go test ${relativePath(project, it)}/..." } else null
        }
    }

    override fun checkConfiguration() {
        when (scope) {
            GoTestScope.PACKAGE, GoTestScope.DIRECTORY -> {
                if (directory.isBlank()) throw RuntimeConfigurationError("Directory is not specified")
                val dir = resolve(directory)
                if (!dir.toFile().isDirectory) throw RuntimeConfigurationError("Directory does not exist: $dir")
            }
            GoTestScope.FILE -> {
                if (filePath.isBlank()) throw RuntimeConfigurationError("Test file is not specified")
                val file = resolve(filePath)
                if (!file.toFile().isFile) throw RuntimeConfigurationError("Test file does not exist: $file")
                if (!file.fileName.toString().endsWith(TEST_FILE_SUFFIX)) {
                    throw RuntimeConfigurationError("Not a Go test file (*$TEST_FILE_SUFFIX): $file")
                }
            }
        }
        checkEnvFileAndBuildFlags(envFile, buildFlags)
    }

    // A rerun of several failed packages carries them in packages.
    override fun debugConfigurationError(): String? =
        if (scope == GoTestScope.DIRECTORY || packages.isNotEmpty()) {
            "Delve debugs one package at a time; choose a package or a file to debug tests"
        } else {
            null
        }

    override fun buildFlagsError(buildFlags: List<String>): String? = GoTestCommandBuilder.testFlagsError(buildFlags)

    override fun createRunState(environment: ExecutionEnvironment, goExecutable: String): RunProfileState =
        GoTestRunProfileState(environment, this, goExecutable, ::launchRequest)

    override fun createDebugConsole(executor: Executor, default: () -> ConsoleView?): ConsoleView? = createDebugTestConsole(executor)

    /** The test tree under Debug: dlv relays the binary's `-test.v=test2json` output, converted for one package. */
    internal fun createDebugTestConsole(executor: Executor): ConsoleView {
        val directory = targetDirectory()
        // Called on EDT; the go.mod walk runs when the binary's output arrives, on the output reader thread.
        val importPath = { GoModules.importPath(directory) ?: directory.toString() }
        return GoTestRunProfileState.createConsole(this, executor, directory.toString(), importPath)
    }

    @Throws(ExecutionException::class)
    override fun toExecutionRequest(): GoExecutionRequest {
        val dir = targetDirectory().toString()
        return GoExecutionRequest(
            target = GoRunTarget.Package(dir),
            programArguments = ParametersListUtil.parse(testArguments),
            environment = userEnvironment(envs, envFile),
            buildFlags = ParametersListUtil.parse(buildFlags),
            test = GoTestSelection.spec(selectedNames(), recursive = isRecursive)
                .copy(packages = if (isRecursive) packages else emptyList()),
        )
    }

    // A file is its package filtered to the file's own tests; explicit names replace that list.
    @Throws(ExecutionException::class)
    private fun selectedNames(): List<String> {
        val explicit = testNames.filter { it.isNotBlank() }
        if (scope != GoTestScope.FILE || explicit.isNotEmpty()) return explicit
        val file = resolve(filePath)
        // Called from state.execute, after the run runner has saved all documents; go reads the disk too.
        val text = try {
            GoTestFileFunctions.decode(Files.readAllBytes(file))
        } catch (e: IOException) {
            throw ExecutionException("Cannot read test file $file: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (GoTestFileFunctions.isIgnoredByBuildConstraint(text)) {
            throw ExecutionException("$file is excluded by build constraint 'ignore'; go test never compiles it")
        }
        val functions = GoTestFileFunctions.topLevel(text)
        return GoTestSelection.fileTests(functions).ifEmpty {
            val message = if (functions.any { GoTestNames.kindOf(it) == GoTestKind.BENCHMARK }) {
                "Only benchmarks in $file; benchmarks run only by name, add them to Test names"
            } else {
                "No tests, fuzz tests or examples in $file"
            }
            throw ExecutionException(message)
        }
    }

    companion object {
        const val TEST_FILE_SUFFIX: String = "_test.go"

        /** "." for a content root, "./a/b" beneath one; by path alone, so it holds for a directory just renamed away. */
        internal fun relativePath(project: Project, directory: Path): String {
            val roots = ReadAction.computeBlocking<List<Path>, RuntimeException> {
                ProjectRootManager.getInstance(project).contentRoots.map { Path.of(it.path).normalize() }
            }
            val root = roots.filter { directory.startsWith(it) }.maxByOrNull { it.nameCount }
                ?: return "./${directory.fileName ?: directory}"
            val relative = FileUtil.toSystemIndependentName(root.relativize(directory).toString())
            return if (relative.isEmpty()) "." else "./$relative"
        }
    }
}
