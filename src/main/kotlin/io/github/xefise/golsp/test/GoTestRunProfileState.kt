package io.github.xefise.golsp.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import io.github.xefise.golsp.execution.GoConsoleFilter
import io.github.xefise.golsp.execution.GoRunProfileState
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoTestCommandBuilder
import io.github.xefise.golsp.test.runner.GoTestConsoleProperties
import java.nio.file.Path

/** A single `go test -json` process shown as an SMTestRunner tree. */
class GoTestRunProfileState(
    environment: ExecutionEnvironment,
    private val configuration: GoTestConfiguration,
    private val goExecutable: String,
    private val requestProvider: () -> GoExecutionRequest,
) : CommandLineState(environment) {

    /** Where `go test` writes its cover profile; set by the coverage runner before execution. */
    var coverProfile: Path? = null

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler = start(requestProvider())

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val launch = requestProvider()
        val handler = start(launch)
        val console = createTestConsole(executor, handler, launch.target.directory)
        return DefaultExecutionResult(console, handler, *createActions(console, handler, executor)).apply {
            rerunFailedAction(console)?.let { setRestartActions(it) }
        }
    }

    // The platform leaves the toolbar wiring of rerun-failed to the state.
    internal fun rerunFailedAction(console: ConsoleView): AbstractRerunFailedTestsAction? {
        val view = console as? SMTRunnerConsoleView ?: return null
        val properties = view.properties as? SMTRunnerConsoleProperties ?: return null
        return properties.createRerunFailedTestsAction(console)?.apply { setModelProvider { view.resultsViewer } }
    }

    @Throws(ExecutionException::class)
    private fun start(launch: GoExecutionRequest): ProcessHandler {
        val path = GoRunProfileState.goFirstPath(environment.project, launch)
        val test = launch.test?.let { spec -> coverProfile?.let { spec.copy(coverProfile = it.toString()) } ?: spec }
        val request = launch.copy(environment = GoEnvironmentLayers.withPath(launch.environment, path), test = test)
        return GoRunProfileState.startKillable(GoRunProfileState.toCommandLine(GoTestCommandBuilder.testCommand(goExecutable, request)))
    }

    internal fun createTestConsole(executor: Executor, handler: ProcessHandler, directory: String): ConsoleView =
        createConsole(configuration, executor, directory, test2JsonPackage = null).apply {
            (properties as? GoTestConsoleProperties)?.watchStop(handler)
            attachToProcess(handler)
        }

    companion object {
        const val FRAMEWORK_NAME: String = "GoTest"

        /** Not yet attached to a process; [test2JsonPackage] as in [GoTestConsoleProperties]. */
        internal fun createConsole(
            configuration: GoTestConfiguration,
            executor: Executor,
            directory: String,
            test2JsonPackage: (() -> String)?,
        ): BaseTestsOutputConsoleView {
            val project = configuration.project
            val properties = GoTestConsoleProperties(configuration, FRAMEWORK_NAME, executor, test2JsonPackage).apply {
                // Only for "jump to source" of a failed node (getErrorNavigatable); console text is not filtered by it.
                addStackTraceFilter(GoConsoleFilter(project, directory, directory))
            }
            val console = SMTestRunnerConnectionUtil.createConsole(FRAMEWORK_NAME, properties)
            console.addMessageFilter(GoConsoleFilter(project, directory, directory))
            return console
        }
    }
}
