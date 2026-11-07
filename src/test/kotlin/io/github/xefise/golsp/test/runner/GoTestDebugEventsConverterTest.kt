package io.github.xefise.golsp.test.runner

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.node
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.output
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.render
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.replay
import java.io.File

/**
 * A `dlv dap` process stream under Debug — dlv's ready line, then the binary's raw `-test.v=test2json` output
 * (testData/test2json/raw) — through the test2json mode of the converter into the real SM tree: it must equal the Run
 * tree of the `go test -json` capture of the same run (testData/gotest-json).
 */
class GoTestDebugEventsConverterTest : BasePlatformTestCase() {
    private var debugProperty: String? = null

    override fun setUp() {
        super.setUp()
        debugProperty = System.setProperty(DEBUG_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            if (debugProperty == null) System.clearProperty(DEBUG_PROPERTY) else System.setProperty(DEBUG_PROPERTY, debugProperty!!)
        } finally {
            super.tearDown()
        }
    }

    private fun debugReplay(pkg: String) = SmTreeReplay(project, GoTestEventsConverter(SmTreeReplay.FRAMEWORK) { "example.com/gotests/$pkg" })

    private fun rawLines(fixture: String): List<String> {
        val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        val text = File(projectDir, "src/test/testData/test2json/raw/$fixture.out").readText()
        return Regex("[^\n]*\n|[^\n]+$").findAll(text).map { it.value }.toList()
    }

    // Every line cut right after its first char (^V), inside a marker word and in the middle.
    private fun chunks(line: String, cut: Boolean): List<String> =
        if (!cut) listOf(line) else listOf(0, 1, 5, line.length / 2, line.length).map { it.coerceAtMost(line.length) }
            .distinct().sorted().zipWithNext { a, b -> line.substring(a, b) }.filter { it.isNotEmpty() }

    // dlv's exit code says nothing about the binary: 0 is what dlv returns after the debuggee ends.
    private fun debugRun(
        fixture: String,
        pkg: String,
        cut: Boolean = false,
        stderrFrom: (String) -> Boolean = { false },
    ): SMTestProxy.SMRootTestProxy {
        val replay = debugReplay(pkg)
        chunks(READY_LINE, cut).forEach(replay::stdout)
        var toStderr = false
        rawLines(fixture).forEach { line ->
            toStderr = toStderr || stderrFrom(line)
            chunks(line, cut).forEach { if (toStderr) replay.stderr(it) else replay.stdout(it) }
        }
        return replay.terminate(exitCode = 0)
    }

    private fun assertSameTreeAsRun(fixture: String, pkg: String, stderrFrom: (String) -> Boolean = { false }) {
        val expected = render(replay(project, fixture))
        for (cut in listOf(false, true)) {
            val debug = debugRun(fixture, pkg, cut, stderrFrom)
            assertEquals("cut=$cut", expected, render(debug))
            assertTrue(debug.isTestsReporterAttached)
        }
    }

    private val panicOnStderr: (String) -> Boolean = { it.startsWith("panic: ") }

    fun testPassingSubtestSelectionEqualsRunTree() {
        assertSameTreeAsRun("subtest-filter", "calc")
        val root = debugRun("subtest-filter", "calc")
        assertEquals("passed", SmTreeReplay.status(root))
        assertEquals(
            "passed",
            SmTreeReplay.status(node(root, "example.com/gotests/calc", "TestSubtests", "with_space")),
        )
    }

    fun testFailingPackageWithSubtestsEqualsRunTree() = assertSameTreeAsRun("calc", "calc")

    fun testPassingBenchmarkEqualsRunTree() = assertSameTreeAsRun("bench", "calc")

    fun testFailingBenchmarkEqualsRunTree() = assertSameTreeAsRun("benchfail", "benchfail")

    // The binary writes the panic trace to its stderr, which is dlv's stderr in local output mode.
    fun testPanicEqualsRunTree() = assertSameTreeAsRun("panic", "panics", panicOnStderr)

    fun testTimeoutEqualsRunTree() = assertSameTreeAsRun("timeout", "slow", panicOnStderr)

    fun testPanicTextOnStdoutIsNotACrash() {
        System.setProperty(DEBUG_PROPERTY, "false")
        val root = debugRun("panic", "panics")
        assertEquals("terminated", SmTreeReplay.status(node(root, "example.com/gotests/panics")))
    }

    fun testUnframedPassBeforeTheTestsDoesNotMakeAPanickingRunGreen() {
        val replay = debugReplay("calc")
        replay.stdout(READY_LINE)
        // TestMain prints PASS before m.Run; the binary's own result lines are framed.
        replay.stdout("PASS\n")
        replay.stdout("\u0016=== RUN   TestAdd\n")
        replay.stderr("panic: boom\n")
        val root = replay.terminate(exitCode = 0)
        val pkg = node(root, "example.com/gotests/calc")
        assertEquals("failed", SmTreeReplay.status(pkg))
        assertEquals("failed", SmTreeReplay.status(node(pkg, "TestAdd")))
    }

    fun testDlvReadyLineStaysAtTheRootOutOfThePackage() {
        val root = debugRun("subtest-filter", "calc")
        assertEquals(READY_LINE, output(root, ProcessOutputType.STDOUT))
        val pkg = node(root, "example.com/gotests/calc")
        assertFalse(output(pkg, ProcessOutputType.STDOUT).contains("DAP server"))
        assertTrue(output(pkg, ProcessOutputType.STDOUT).contains("ok  \texample.com/gotests/calc"))
    }

    fun testDlvStderrBeforeReadyGoesToTheRoot() {
        val replay = debugReplay("calc")
        replay.stderr("warn")
        replay.stderr("ing: dlv\n")
        replay.stdout(READY_LINE)
        rawLines("subtest-filter").forEach(replay::stdout)
        val root = replay.terminate(exitCode = 0)
        assertEquals("warning: dlv\n", output(root, ProcessOutputType.STDERR))
        assertEquals("passed", SmTreeReplay.status(node(root, "example.com/gotests/calc")))
    }

    fun testStopMidRunLeavesOpenNodesTerminated() {
        System.setProperty(DEBUG_PROPERTY, "false")
        val replay = debugReplay("calc")
        replay.stdout(READY_LINE)
        val lines = rawLines("calc")
        val cut = lines.indexOfFirst { it.contains("=== RUN   TestSubtests/plain") }
        lines.take(cut + 1).forEach(replay::stdout)
        // A killed dlv exits non-zero; neither code may decide the binary's result.
        val root = replay.terminate(exitCode = 1)
        assertEquals(
            """
            example.com/gotests/calc: terminated
              TestAdd: passed
              TestSub: failed
              TestSubtests: terminated
                plain: terminated
            """.trimIndent() + "\n",
            render(root),
        )
    }

    // The compiler error arrives as a DAP output event, printed by LSP4IJ outside the converter.
    fun testDlvThatNeverStartsTheBinaryLeavesNoTests() {
        val replay = debugReplay("calc")
        replay.stdout(READY_LINE)
        val root = replay.terminate(exitCode = 0)
        assertTrue(root.children.isEmpty())
        assertFalse(root.isInProgress)
        assertFalse(root.isTestsReporterAttached)
        assertEquals(READY_LINE, output(root, ProcessOutputType.STDOUT))
    }

    fun testDlvFailingBeforeReadyShowsItsOutput() {
        val replay = debugReplay("calc")
        replay.stdout("couldn't start listener: listen tcp: address in use\n")
        val root = replay.terminate(exitCode = 1)
        assertTrue(root.children.isEmpty())
        assertEquals("couldn't start listener: listen tcp: address in use\n", output(root, ProcessOutputType.STDOUT))
    }

    private companion object {
        const val DEBUG_PROPERTY = "idea.smrunner.debug"
        const val READY_LINE = "DAP server listening at: 127.0.0.1:54321\n"
    }
}
