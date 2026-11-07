package io.github.xefise.golsp.run.integration

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.Key
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import io.github.xefise.golsp.run.GoApplicationConfiguration
import io.github.xefise.golsp.run.GoApplicationConfigurationType
import io.github.xefise.golsp.run.integration.XDebuggerProbes.Value
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import java.io.File
import java.util.concurrent.CompletableFuture

/** One debug session per test is expensive (build + dlv), so the scenario walks the whole G40 checklist in order. */
class GoApplicationDebugIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.DLV)

    private lateinit var main: VirtualFile
    private var session: XDebugSession? = null

    // Covers sessions the test never got hold of (failed before startDebug returned) and orphans of a crashed session.
    override fun beforeFixtureTearDown() {
        XDebuggerManager.getInstance(project).debugSessions.filterNot { it.isStopped }.forEach { it.stop() }
        if (!poll(CLEANUP_TIMEOUT_SECONDS) { goDescendants().isEmpty() }) {
            goDescendants().forEach { it.destroyForcibly() }
        }
    }

    fun testBreakpointInWorkerStopsWithStackVariablesGoroutinesStepAndEvaluate() {
        copyTestProject("hello")
        main = fixture.findFileInTempDir("main.go")
        val text = String(main.contentsToByteArray())
        val lines = text.lines()
        val breakpointLine = lines.indexOfFirst { it.trim() == "sum := 0" }
        // Only worker 1 stops, so a step cannot be interrupted by another goroutine hitting the same line.
        addLineBreakpoint(breakpointLine, condition = "id == 1")

        val session = startDebug()
        withConsoleOnFailure {
            waitUntil("breakpoint in worker", DEBUG_TIMEOUT_SECONDS) { session.isSuspended && session.currentPosition != null }
            // On EDT LSP4IJ hands out a placeholder file and resolves the real one in the background.
            waitUntil("stop position to resolve to a local file") { session.currentPosition?.file?.isInLocalFileSystem == true }
        }
        assertEquals(main, session.currentPosition!!.file)
        assertEquals(breakpointLine, session.currentPosition!!.line)

        val pausedGoroutine = session.suspendContext!!.activeExecutionStack!!
        val frames = await(XDebuggerProbes.frames(pausedGoroutine))
        val names = frames.map(XDebuggerProbes::frameText)
        // Frame text is "function:line, file" with a 1-based line.
        assertTrue(names.toString(), names.first().startsWith("main.worker:${breakpointLine + 1}"))
        // The paused goroutine is a worker: below it are the go-statement wrapper and runtime, not main.main itself.
        assertFalse(names.toString(), names.any { it.startsWith("main.main:") })
        assertTrue(names.toString(), names.last().startsWith("runtime.goexit"))

        val variables = await(XDebuggerProbes.children(session.currentStackFrame!!)).associateBy { it.name }
        assertTrue(variables.keys.toString(), variables.keys.containsAll(listOf("id", "points", "results", "mu", "wg")))
        // Without the condition reaching dlv, the first goroutine to run (usually the last one started) would stop.
        assertEquals("1", variables.getValue("id").text)
        val points = variables.getValue("points")
        assertTrue(points.text, points.text.contains("len: 3"))
        val first = await(XDebuggerProbes.children(points.xValue)).first()
        val fields = await(XDebuggerProbes.children(first.xValue)).associate { it.name to it.text }
        assertEquals(mapOf("X" to "0", "Y" to "0", "Name" to "\"origin\""), fields)
        assertTrue(variables.getValue("results").type.orEmpty(), variables.getValue("results").type.orEmpty().contains("map[string]int"))

        // Three workers plus main exist while the first worker is paused.
        val stacks = await(XDebuggerProbes.stacks(session.suspendContext!!))
        assertTrue("goroutines: ${stacks.map { it.displayName }}", stacks.size >= 2)

        assertEquals("3", evaluate(session.currentStackFrame!!, "len(points)").text)
        assertEquals("\"a\"", evaluate(session.currentStackFrame!!, "points[1].Name").text)

        session.stepOver(false)
        waitUntil("step over to the next line", DEBUG_TIMEOUT_SECONDS) {
            session.isSuspended && session.currentPosition?.line == breakpointLine + 1
        }
        assertEquals(pausedGoroutine.displayName, session.suspendContext!!.activeExecutionStack!!.displayName)
        val afterStep = await(XDebuggerProbes.children(session.currentStackFrame!!)).associate { it.name to it.text }
        assertEquals("0", afterStep["sum"])

        val debuggees = goDescendants().map(::executableName)
        assertTrue("dlv must be a child of the IDE while debugging: $debuggees", debuggees.any { it in DLV_NAMES })
        session.stop()
        waitUntil("session to stop", DEBUG_TIMEOUT_SECONDS) { session.isStopped }
        waitUntil("dlv and the debuggee to exit", DEBUG_TIMEOUT_SECONDS) { goDescendants().isEmpty() }
    }

    private fun addLineBreakpoint(line: Int, condition: String): XLineBreakpoint<*> {
        val util = XDebuggerUtil.getInstance()
        val type = util.lineBreakpointTypes.firstOrNull { it.canPutAt(main, line, project) }
        assertNotNull("no line breakpoint type accepts main.go:$line", type)
        return WriteAction.computeAndWait<XLineBreakpoint<*>, Throwable> {
            add(type!!, line).apply { conditionExpression = util.createExpression(condition, null, null, EvaluationMode.EXPRESSION) }
        }
    }

    private fun <P : XBreakpointProperties<*>> add(type: XLineBreakpointType<P>, line: Int): XLineBreakpoint<P> =
        XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, main.url, line, type.createBreakpointProperties(main, line))

    private val console = StringBuffer()

    private fun withConsoleOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; stopped=${session?.isStopped}; debug console:\n$console", e)
        }
    }

    private fun startDebug(): XDebugSession {
        project.messageBus.connect(testRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        console.append(event.text)
                    }
                })
            }
        })
        val typeInstance = ConfigurationTypeUtil.findConfigurationType(GoApplicationConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("debug-integration", typeInstance.configurationFactories.single())
        (settings.configuration as GoApplicationConfiguration).packageDirectory = main.parent.path
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build()
        env.runner.execute(env)
        waitUntil("debug session to start", DEBUG_TIMEOUT_SECONDS) { XDebuggerManager.getInstance(project).debugSessions.isNotEmpty() }
        return XDebuggerManager.getInstance(project).debugSessions.single().also { session = it }
    }

    private fun evaluate(frame: XStackFrame, expression: String): Value {
        val evaluator = frame.evaluator ?: throw AssertionError("frame has no evaluator")
        val result = CompletableFuture<XValue>()
        evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(value: XValue) {
                result.complete(value)
            }

            override fun errorOccurred(errorMessage: String) {
                result.completeExceptionally(AssertionError("$expression: $errorMessage"))
            }
        }, null)
        val value = await(result)
        val (type, text) = await(XDebuggerProbes.presentation(value))
        return Value(expression, type, text, value)
    }

    private fun <T> await(future: CompletableFuture<T>): T = await("debugger response", future, DEBUG_TIMEOUT_SECONDS)

    /** Live child processes that belong to a Go debug session: dlv and the binary it built. */
    private fun goDescendants(): List<ProcessHandle> = ProcessHandle.current().descendants()
        .filter { it.isAlive }
        .filter { executableName(it).let { name -> name in DLV_NAMES || name.startsWith("__debug_bin") } }
        .toList()

    private fun executableName(process: ProcessHandle): String = File(process.info().command().orElse("")).name

    private companion object {
        const val DEBUG_TIMEOUT_SECONDS = 90
        const val CLEANUP_TIMEOUT_SECONDS = 15
        val DLV_NAMES = setOf("dlv", "dlv.exe")
    }
}
