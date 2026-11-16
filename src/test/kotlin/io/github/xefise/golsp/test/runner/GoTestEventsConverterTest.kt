package io.github.xefise.golsp.test.runner

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.node
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.output
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.render
import io.github.xefise.golsp.test.runner.SmTreeReplay.Companion.replay

/** Captured `go test -json` streams (testData/gotest-json) through the converter into the real id-based SM tree. */
class GoTestEventsConverterTest : BasePlatformTestCase() {
    private var debugProperty: String? = null

    override fun setUp() {
        super.setUp()
        // Protocol problems (unknown parent, finishing an unknown node) become LOG.error and fail the test.
        debugProperty = System.setProperty(DEBUG_PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            if (debugProperty == null) System.clearProperty(DEBUG_PROPERTY) else System.setProperty(DEBUG_PROPERTY, debugProperty!!)
        } finally {
            super.tearDown()
        }
    }

    private fun assertTree(expected: String, root: SMTestProxy) =
        assertEquals(expected.trimIndent() + "\n", render(root))

    fun testPackageRunBuildsTheTreeWithSubtestsAndParallelTests() {
        assertTree(
            """
            example.com/gotests/calc: failed
              TestAdd: passed
              TestSub: failed
              TestSubtests: failed
                plain: passed
                with_space: passed
                a/b: passed
                nested: failed
                  ok: passed
                  fails: failed
                регэксп_[x]+$: passed
              TestParallel: passed
                first: passed
                second: passed
                third: passed
              TestParallelTopA: passed
              TestParallelTopB: passed
              TestSkipped: skipped
              FuzzReverse: passed
                seed#0: passed
                seed#1: passed
              ExampleAdd: passed
              ExampleSub: failed
            """,
            replay(project, "calc"),
        )
    }

    fun testPackageIsASuiteAndTestsWithSubtestsAreTests() {
        val calc = node(replay(project, "calc"), "example.com/gotests/calc")
        assertTrue(calc.isSuite)
        assertFalse(node(calc, "TestSubtests").isSuite)
        assertFalse(node(calc, "TestAdd").isSuite)
    }

    fun testLocationUrlsCarryImportPathAndReportedFullName() {
        val root = replay(project, "calc")
        assertEquals("gotest://example.com/gotests/calc", node(root, "example.com/gotests/calc").locationUrl)
        assertEquals(
            "gotest://example.com/gotests/calc#TestSubtests/a/b",
            node(root, "example.com/gotests/calc", "TestSubtests", "a/b").locationUrl,
        )
        assertEquals(
            "gotest://example.com/gotests/calc#FuzzReverse/seed#1",
            node(root, "example.com/gotests/calc", "FuzzReverse", "seed#1").locationUrl,
        )
    }

    fun testElapsedBecomesDurationInMilliseconds() {
        val calc = node(replay(project, "calc"), "example.com/gotests/calc")
        // "Elapsed":0.03 and 0.426 seconds.
        assertEquals(30L, node(calc, "TestParallelTopA").duration)
        assertEquals(426L, calc.duration)
    }

    fun testErrorLinesGoToStderrOfTheirTest() {
        val sub = node(replay(project, "calc"), "example.com/gotests/calc", "TestSub")
        // The failure (message, then details) is printed to stderr after the test's own output.
        assertEquals(
            "    calc_test.go:17: Sub(5, 3) = 8, want 2\nSub(5, 3) = 8, want 2\n    calc_test.go:17: Sub(5, 3) = 8, want 2\n",
            output(sub, ProcessOutputType.STDERR),
        )
        assertEquals("", output(sub, ProcessOutputType.STDOUT))
    }

    fun testFailedTestCarriesItsErrorAsMessageAndDetails() {
        val sub = node(replay(project, "calc"), "example.com/gotests/calc", "TestSub")
        assertEquals("Sub(5, 3) = 8, want 2", sub.errorMessage)
        assertEquals("    calc_test.go:17: Sub(5, 3) = 8, want 2", sub.stacktrace)
    }

    fun testTestifyEqualFailureOffersTheDiff() {
        val root = replay(project, "extra/testify")
        val strings = node(root, "example.com/cap/fail", "TestEqualStrings")
        assertEquals("Not equal: greeting for bob", strings.errorMessage)
        val diff = strings.diffViewerProvider
        assertNotNull(diff)
        assertEquals("say \"hi\"\nthere", diff!!.left)
        assertEquals("say \"bye\"\nthere", diff.right)
        assertNull(node(root, "example.com/cap/fail", "TestTrue").diffViewerProvider)
    }

    fun testPackageFailureNodeShowsTheOutputOfTestMain() {
        val root = replay(project, "extra/testmain")
        val failure = node(root, "example.com/cap/testmain", "[package failed]")
        assertEquals("leak detected: goroutine 7 still running", failure.stacktrace)
    }

    fun testInterleavedParallelSubtestKeepsItsOwnLog() {
        val first = node(replay(project, "calc"), "example.com/gotests/calc", "TestParallel", "first")
        assertEquals("    calc_test.go:41: done first\n", output(first, ProcessOutputType.STDOUT))
    }

    fun testMultiLineLogStaysWithTheSubtest() {
        val node = node(replay(project, "calc"), "example.com/gotests/calc", "TestSubtests", "with_space")
        assertEquals("    calc_test.go:24: output from a subtest\n        second line\n", output(node, ProcessOutputType.STDOUT))
    }

    fun testBuildFailureIsAFailedPackageWithCompilerOutputOnItsFailureNode() {
        val root = replay(project, "build-failure")
        assertTree(
            """
            example.com/gotests/broken: failed
              [package failed]: failed
            """,
            root,
        )
        val failure = node(root, "example.com/gotests/broken", "[package failed]")
        assertEquals("gotest://example.com/gotests/broken", failure.locationUrl)
        // The failure message follows the output in the same stream.
        val stderr = output(failure, ProcessOutputType.STDERR)
        val build = "# example.com/gotests/broken [example.com/gotests/broken.test]\nbroken/broken_test.go:6:2: undefined: undefinedFunction\n"
        assertTrue(stderr, stderr.startsWith(build))
        assertEquals("", output(node(root, "example.com/gotests/broken"), ProcessOutputType.STDERR))
    }

    fun testOnlyTestsCountAsFailedNeverPackages() {
        val replay = SmTreeReplay(project)
        val failed = mutableListOf<String>()
        replay.processor.addEventsListener(object : SMTRunnerEventsAdapter() {
            override fun onTestFailed(test: SMTestProxy) {
                failed += (if (test.isSuite) "suite " else "") + test.name
            }
        })
        (SmTreeReplay.fixture("build-failure") + SmTreeReplay.fixture("panic")).forEach { replay.stdout("$it\n") }
        replay.terminate()
        // The results form counts a failed suite as one more failed test.
        assertEquals(listOf("[package failed]", "inner", "TestPanic"), failed)
    }

    fun testPanicFailsTheTestWithTheTraceInItsOutput() {
        val root = replay(project, "panic")
        assertTree(
            """
            example.com/gotests/panics: failed
              TestBeforePanic: passed
              TestPanic: failed
                inner: failed
            """,
            root,
        )
        val panic = output(node(root, "example.com/gotests/panics", "TestPanic"), ProcessOutputType.STDOUT)
        assertTrue(panic, panic.startsWith("panic: assignment to entry in nil map"))
    }

    fun testTimedOutTestIsFailedAsUnfinished() {
        val root = replay(project, "timeout")
        assertTree(
            """
            example.com/gotests/slow: failed
              TestFast: passed
              TestSlow: failed
            """,
            root,
        )
        val slow = node(root, "example.com/gotests/slow", "TestSlow")
        assertTrue(slow.errorMessage, slow.errorMessage!!.startsWith("The test did not finish"))
    }

    fun testBenchmarkWithAResultLinePasses() {
        assertTree(
            """
            example.com/gotests/calc: passed
              BenchmarkAdd: passed
            """,
            replay(project, "bench"),
        )
    }

    fun testFailingBenchmarkKeepsTheOthersGreen() {
        assertTree(
            """
            example.com/gotests/benchfail: failed
              BenchmarkPasses: passed
              BenchmarkFails: failed
            """,
            replay(project, "benchfail"),
        )
    }

    fun testRecursiveRunHasOnePackageNodePerTestedPackage() {
        val root = replay(project, "all")
        assertEquals(
            listOf(
                "example.com/gotests/broken",
                "example.com/gotests/calc",
                "example.com/gotests/panics",
                "example.com/gotests/slow",
            ),
            root.children.map { it.name }.sorted(),
        )
        assertTrue(root.children.all { it.isSuite && it.isDefect })
        val rootOutput = output(root, ProcessOutputType.STDOUT)
        assertTrue(rootOutput, rootOutput.contains("example.com/gotests/notests\t[no test files]"))
    }

    fun testNonJsonStdoutLineIsShownAtTheRoot() {
        val replay = SmTreeReplay(project)
        replay.stdout("go: downloading example.com/dep v1.0.0\n")
        val root = replay.terminate()
        assertEquals("go: downloading example.com/dep v1.0.0\n", output(root, ProcessOutputType.STDOUT))
        assertTrue(root.children.isEmpty())
    }

    fun testStderrDuringARunningTestGoesToTheRootNotTheTest() {
        val replay = SmTreeReplay(project)
        val lines = SmTreeReplay.fixture("calc")
        // Line 2 starts TestAdd, line 4 passes it: stderr arrives while it is running.
        lines.take(3).forEach { replay.stdout("$it\n") }
        replay.stderr("go: warning from the go command\n")
        lines.drop(3).forEach { replay.stdout("$it\n") }
        val root = replay.terminate()
        assertEquals("go: warning from the go command\n", output(root, ProcessOutputType.STDERR))
        assertEquals("", output(node(root, "example.com/gotests/calc", "TestAdd"), ProcessOutputType.STDERR))
    }

    fun testJsonEventSplitAcrossReadsIsParsedWhole() {
        val replay = SmTreeReplay(project)
        SmTreeReplay.fixture("build-failure").forEach { line ->
            val half = line.length / 2
            replay.stdout(line.substring(0, half))
            replay.stdout(line.substring(half) + "\n")
        }
        assertEquals(listOf("[package failed]"), node(replay.terminate(), "example.com/gotests/broken").children.map { it.name })
    }

    fun testStoppedProcessLeavesOpenNodesToBeTerminatedByTheRunner() {
        // The runner reports nodes still running at the end as a problem, which debug mode turns into an error.
        System.setProperty(DEBUG_PROPERTY, "false")
        val replay = SmTreeReplay(project)
        // Stop right after TestSubtests/plain started: nothing above it got a result.
        val lines = SmTreeReplay.fixture("calc")
        val cut = lines.indexOfFirst { it.contains("\"Action\":\"run\"") && it.contains("\"TestSubtests/plain\"") }
        lines.take(cut + 1).forEach { replay.stdout("$it\n") }
        val root = replay.terminate(exitCode = 130)
        assertTree(
            """
            example.com/gotests/calc: terminated
              TestAdd: passed
              TestSub: failed
              TestSubtests: terminated
                plain: terminated
            """,
            root,
        )
        assertTrue(root.wasTerminated())
    }

    fun testGoFailingBeforeAnyEventIsAFailedNodeWithItsStderr() {
        val replay = SmTreeReplay(project)
        // Captured from Go 1.23 outside a module: no JSON at all, exit code 1.
        replay.stderr("pattern ./...: directory prefix . does not contain main module or its selected dependencies\n")
        val root = replay.terminate(exitCode = 1)
        assertEquals(
            "pattern ./...: directory prefix . does not contain main module or its selected dependencies\n",
            output(root, ProcessOutputType.STDERR),
        )
        assertTree("[go test failed]: failed", root)
        val failure = node(root, "[go test failed]")
        assertEquals("pattern ./...: directory prefix . does not contain main module or its selected dependencies", failure.errorMessage)
        assertEquals(failure.errorMessage, failure.stacktrace)
        assertFalse(root.isInProgress)
    }

    fun testStderrWithExitCodeZeroAddsNoNode() {
        val replay = SmTreeReplay(project)
        replay.stderr("go: warning\n")
        val root = replay.terminate(exitCode = 0)
        assertTrue(root.children.isEmpty())
        // Not attached: the runner reports that the test framework quit instead of "no tests".
        assertFalse(root.isTestsReporterAttached)
    }

    fun testStderrBeforeTheFirstEventOfAFailedRunAddsNoExtraNode() {
        val replay = SmTreeReplay(project)
        replay.stderr("go: downloading example.com/dep v1.0.0\n")
        SmTreeReplay.fixture("build-failure").forEach { replay.stdout("$it\n") }
        val root = replay.terminate(exitCode = 1)
        assertEquals(listOf("example.com/gotests/broken"), root.children.map { it.name })
    }

    fun testStoppedRunWithoutOutputAddsNoNode() {
        val replay = SmTreeReplay(project)
        replay.stdout("go: downloading example.com/dep v1.0.0\n")
        val root = replay.terminate(exitCode = 130)
        assertTrue(root.children.isEmpty())
    }

    fun testReporterIsAttachedOnceJsonArrives() {
        val replay = SmTreeReplay(project)
        replay.stdout("go: downloading example.com/dep v1.0.0\n")
        assertFalse(replay.root.isTestsReporterAttached)
        SmTreeReplay.fixture("build-failure").forEach { replay.stdout("$it\n") }
        assertTrue(replay.terminate().isTestsReporterAttached)
    }

    fun testTestsRunningAtStopAreTerminatedAndEarlierFailuresStay() {
        System.setProperty(DEBUG_PROPERTY, "false")
        var stopped = false
        val replay = SmTreeReplay(project, GoTestEventsConverter(SmTreeReplay.FRAMEWORK, { stopped }))
        for (line in SmTreeReplay.fixture("extra/stopped-after-failure")) {
            if ("signal: interrupt" in line) stopped = true
            replay.stdout("$line\n")
        }
        assertTree(
            """
            example.com/cap/stop: terminated
              TestPasses: passed
              TestFails: failed
              TestSlowParent: terminated
                child: terminated
            """,
            replay.terminate(),
        )
    }

    private companion object {
        const val DEBUG_PROPERTY = "idea.smrunner.debug"
    }
}
