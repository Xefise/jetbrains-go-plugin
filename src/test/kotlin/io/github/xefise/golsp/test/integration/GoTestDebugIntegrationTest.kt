package io.github.xefise.golsp.test.integration

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import io.github.xefise.golsp.run.integration.XDebuggerProbes
import io.github.xefise.golsp.test.GoTestConfiguration
import io.github.xefise.golsp.test.GoTestConfigurationType
import io.github.xefise.golsp.test.GoTestScope
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import java.io.File

/** Each scenario selects one test and plants a decoy breakpoint in an unselected one: stopping there means the filter was lost. */
class GoTestDebugIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.DLV)

    private lateinit var testFile: VirtualFile
    private var session: XDebugSession? = null
    private val console = StringBuffer()

    override fun beforeFixtureTearDown() {
        XDebuggerManager.getInstance(project).debugSessions.filterNot { it.isStopped }.forEach { it.stop() }
        if (!poll(CLEANUP_TIMEOUT_SECONDS) { goDescendants().isEmpty() }) {
            goDescendants().forEach { it.destroyForcibly() }
        }
    }

    override fun setUp() {
        super.setUp()
        copyTestProject("gotests")
        testFile = fixture.findFileInTempDir("calc/calc_test.go")
    }

    fun testBreakpointInSelectedTestStopsAndUnselectedTestNeverRuns() {
        val target = lineOf("if got := Add(2, 3); got != 5 {")
        val decoy = lineOf("if got := Sub(5, 3); got != 2 {")
        addLineBreakpoint(target)
        addLineBreakpoint(decoy)

        val session = startDebug(listOf("TestAdd"))
        waitForStopAt(session, target)
        val frames = await(XDebuggerProbes.frames(session.suspendContext!!.activeExecutionStack!!)).map(XDebuggerProbes::frameText)
        // Frame text is "function:line, file" with a 1-based line.
        assertTrue(frames.toString(), frames.first().startsWith("calc.TestAdd:${target + 1}"))

        resumeToEnd(session, decoy, "TestAdd")
    }

    fun testSubtestSelectionStopsInsideThatSubtestOnly() {
        val target = lineOf("t.Log(\"output from a subtest\\nsecond line\")")
        val decoy = lineOf("t.Error(\"nested failure\")")
        addLineBreakpoint(target)
        addLineBreakpoint(decoy)

        val session = startDebug(listOf("TestSubtests/with space"))
        waitForStopAt(session, target)
        val frames = await(XDebuggerProbes.frames(session.suspendContext!!.activeExecutionStack!!)).map(XDebuggerProbes::frameText)
        assertTrue(frames.toString(), frames.first().let { it.startsWith("calc.TestSubtests.func") && it.contains(":${target + 1}") })

        resumeToEnd(session, decoy, "TestSubtests", "with_space")
    }

    private fun lineOf(code: String): Int {
        val line = String(testFile.contentsToByteArray()).lines().indexOfFirst { it.trim() == code }
        assertTrue("no line '$code' in calc_test.go", line >= 0)
        return line
    }

    private fun waitForStopAt(session: XDebugSession, line: Int) {
        withConsoleOnFailure {
            waitUntil("breakpoint at line ${line + 1}", DEBUG_TIMEOUT_SECONDS) { session.isSuspended && session.currentPosition != null }
            // On EDT LSP4IJ hands out a placeholder file and resolves the real one in the background.
            waitUntil("stop position to resolve to a local file") { session.currentPosition?.file?.isInLocalFileSystem == true }
        }
        assertEquals(testFile, session.currentPosition!!.file)
        assertEquals(line, session.currentPosition!!.line)
        // Test mode builds the same __debug_bin* name as debug mode; cleanup relies on matching it.
        val live = goDescendants().map(::executableName)
        assertTrue("dlv and the test binary while stopped: $live", live.any { it in DLV_NAMES } && live.any { it.startsWith(DEBUG_BINARY) })
    }

    // The test binary exits on its own after the selected test; a stop at the decoy would hang the session instead.
    private fun resumeToEnd(session: XDebugSession, decoy: Int, vararg passedTest: String) {
        session.resume()
        withConsoleOnFailure {
            waitUntil("session to finish", DEBUG_TIMEOUT_SECONDS) {
                session.isStopped || (session.isSuspended && session.currentPosition?.line == decoy)
            }
        }
        assertTrue("stopped at the unselected test's breakpoint, line ${decoy + 1}", session.isStopped)
        assertTrue(console.toString(), console.contains("PASS"))
        waitUntil("dlv and the test binary to exit", DEBUG_TIMEOUT_SECONDS) { goDescendants().isEmpty() }
        assertTestTreePassed(session, *passedTest)
    }

    // dlv exits on its own code, so a red root or an "exited before reporting" node means the binary's result was lost.
    private fun assertTestTreePassed(session: XDebugSession, vararg path: String) {
        val view = session.runContentDescriptor.executionConsole
        assertTrue("debug console is ${view.javaClass.name}", view is SMTRunnerConsoleView)
        val tree = (view as SMTRunnerConsoleView).resultsViewer.testsRootNode
        waitUntil("test tree to finish") { !tree.isInProgress }
        val pkg = tree.children.singleOrNull()
        assertNotNull("one package node: ${tree.children.map { it.name }}; console:\n$console", pkg)
        assertEquals("example.com/gotests/calc", pkg!!.name)
        val test = path.fold(pkg as SMTestProxy) { node, name -> node.children.single { it.name == name } }
        assertTrue("${path.joinToString("/")} passed", test.isPassed)
        assertTrue("package passed", pkg.isPassed)
        assertFalse(tree.isDefect)
        assertTrue(pkg.allTests.none { it.name == "[package failed]" })
    }

    private fun addLineBreakpoint(line: Int): XLineBreakpoint<*> {
        val type = XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(testFile, line, project) }
        assertNotNull("no line breakpoint type accepts calc_test.go:$line", type)
        return WriteAction.computeAndWait<XLineBreakpoint<*>, Throwable> { add(type!!, line) }
    }

    private fun <P : XBreakpointProperties<*>> add(type: XLineBreakpointType<P>, line: Int): XLineBreakpoint<P> =
        XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, testFile.url, line, type.createBreakpointProperties(testFile, line))

    private fun withConsoleOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; stopped=${session?.isStopped}; debug console:\n$console", e)
        }
    }

    private fun startDebug(testNames: List<String>): XDebugSession {
        project.messageBus.connect(testRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        console.append(event.text)
                    }
                })
            }
        })
        val type = ConfigurationTypeUtil.findConfigurationType(GoTestConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("debug-test-integration", type.configurationFactories.single())
        (settings.configuration as GoTestConfiguration).apply {
            scope = GoTestScope.PACKAGE
            directory = testFile.parent.path
            this.testNames = testNames
        }
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build()
        env.runner.execute(env)
        waitUntil("debug session to start", DEBUG_TIMEOUT_SECONDS) { XDebuggerManager.getInstance(project).debugSessions.isNotEmpty() }
        return XDebuggerManager.getInstance(project).debugSessions.single().also { session = it }
    }

    private fun <T> await(future: java.util.concurrent.CompletableFuture<T>): T = await("debugger response", future, DEBUG_TIMEOUT_SECONDS)

    private fun goDescendants(): List<ProcessHandle> = ProcessHandle.current().descendants()
        .filter { it.isAlive }
        .filter { executableName(it).let { name -> name in DLV_NAMES || name.startsWith(DEBUG_BINARY) } }
        .toList()

    private fun executableName(process: ProcessHandle): String = File(process.info().command().orElse("")).name

    private companion object {
        // A cold `go test -c` of the package and its test dependencies takes longer than a plain build.
        const val DEBUG_TIMEOUT_SECONDS = 180
        const val DEBUG_BINARY = "__debug_bin"
        const val CLEANUP_TIMEOUT_SECONDS = 15
        val DLV_NAMES = setOf("dlv", "dlv.exe")
    }
}
