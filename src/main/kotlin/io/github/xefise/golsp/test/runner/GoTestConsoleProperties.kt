package io.github.xefise.golsp.test.runner

import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import io.github.xefise.golsp.test.GoTestRerunFailedAction

/**
 * Console of a `go test -json` run, or with [test2JsonPackage] of a `dlv dap` debug of that package's tests (see
 * [GoTestEventsConverter]): pass to `SMTestRunnerConnectionUtil.createAndAttachConsole`.
 */
class GoTestConsoleProperties(
    config: RunConfiguration,
    testFrameworkName: String,
    executor: Executor,
    private val test2JsonPackage: (() -> String)? = null,
) : SMTRunnerConsoleProperties(config, testFrameworkName, executor), SMCustomMessagesParsing {

    init {
        // Tests of a package run in parallel and packages interleave under ./...: nodes are addressed by id.
        isIdBasedTestTree = true
    }

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter = GoTestEventsConverter(testFrameworkName, { stoppedByUser }, test2JsonPackage)

    @Volatile
    private var stoppedByUser = false

    /**
     * Tells the converter when [handler] is being destroyed (Stop, Rerun): fired before the signal is sent, so every
     * event of the interrupted run is read after it. A natural exit fires it too, but only after all output is read.
     */
    fun watchStop(handler: ProcessHandler) {
        handler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                if (willBeDestroyed) stoppedByUser = true
            }
        })
    }

    override fun getTestLocator(): SMTestLocator = GoTestLocator

    override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
        GoTestRerunFailedAction(consoleView, this)
}
