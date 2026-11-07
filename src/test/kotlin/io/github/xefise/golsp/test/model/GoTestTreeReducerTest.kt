package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.test.model.TestTreeRecorder.Companion.assertTree
import io.github.xefise.golsp.test.model.TestTreeRecorder.Companion.replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden tests on unedited `go test -json` captures from testdata/gotests (see capture.sh, go-version.txt). */
class GoTestTreeReducerTest {
    @Test
    fun `package run builds the tree from the test sources`() {
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
            replay("calc"),
        )
    }

    @Test
    fun `t Error lines are error output of their test`() {
        val sub = replay("calc").node("example.com/gotests/calc", "TestSub")
        assertEquals("    calc_test.go:17: Sub(5, 3) = 8, want 2\n", sub.errorOutput.toString())
    }

    @Test
    fun `multi line log of a subtest stays with the subtest`() {
        val node = replay("calc").node("example.com/gotests/calc", "TestSubtests", "with_space")
        assertEquals("    calc_test.go:24: output from a subtest\n        second line\n", node.output.toString())
    }

    @Test
    fun `frame lines never reach the output`() {
        val calc = replay("calc").node("example.com/gotests/calc")
        val all = collectOutput(calc)
        assertFalse(all, all.contains("=== RUN"))
        assertFalse(all, all.contains("--- PASS"))
        assertFalse(all, all.contains("--- FAIL"))
    }

    @Test
    fun `stdout of parallel tests goes where test2json put it`() {
        // Pins upstream behaviour: test2json attributes unprefixed stdout to the last announced test.
        val b = replay("calc").node("example.com/gotests/calc", "TestParallelTopB")
        assertEquals("partial line from B without newlinestdout from A\n", b.output.toString())
    }

    @Test
    fun `build failure fails the package with the compiler output`() {
        val recorder = replay("build-failure")
        assertTree("example.com/gotests/broken: failed", recorder)
        val pkg = recorder.node("example.com/gotests/broken")
        assertTrue(pkg.errorOutput.toString(), pkg.errorOutput.contains("broken/broken_test.go:6:2: undefined: undefinedFunction\n"))
    }

    @Test
    fun `panic in a subtest fails it and its parent and later tests are absent`() {
        assertTree(
            """
            example.com/gotests/panics: failed
              TestBeforePanic: passed
              TestPanic: failed
                inner: failed
            """,
            replay("panic"),
        )
    }

    @Test
    fun `timed out test is failed as unfinished`() {
        val recorder = replay("timeout")
        assertTree(
            """
            example.com/gotests/slow: failed
              TestFast: passed
              TestSlow: failed
            """,
            recorder,
        )
        val slow = recorder.node("example.com/gotests/slow", "TestSlow")
        assertTrue(slow.output.toString(), slow.output.startsWith("panic: test timed out after 1s\n"))
        assertTrue(slow.message!!, slow.message!!.startsWith("The test did not finish"))
    }

    @Test
    fun `benchmark without its own result passes with the package`() {
        val recorder = replay("bench")
        assertTree(
            """
            example.com/gotests/calc: passed
              BenchmarkAdd: passed
            """,
            recorder,
        )
        val bench = recorder.node("example.com/gotests/calc", "BenchmarkAdd")
        assertTrue(bench.output.toString(), Regex("""BenchmarkAdd-\d+\s+100\s+\S+ ns/op\n""").containsMatchIn(bench.output))
        assertTrue(recorder.node("example.com/gotests/calc").output.startsWith("goos: darwin\n"))
    }

    @Test
    fun `failing benchmark leaves benchmarks with a result passed`() {
        val recorder = replay("benchfail")
        assertTree(
            """
            example.com/gotests/benchfail: failed
              BenchmarkPasses: passed
              BenchmarkFails: failed
            """,
            recorder,
        )
        val fails = recorder.node("example.com/gotests/benchfail", "BenchmarkFails")
        assertEquals("    benchfail_test.go:12: benchmark failure\n", fails.errorOutput.toString())
    }

    @Test
    fun `filtered run shows only the selected subtest`() {
        assertTree(
            """
            example.com/gotests/calc: passed
              TestSubtests: passed
                with_space: passed
            """,
            replay("subtest-filter"),
        )
    }

    @Test
    fun `recursive run has one node per package with tests and keeps others out`() {
        val recorder = replay("all")
        val packages = recorder.render().lines().filter { it.isNotEmpty() && !it.startsWith(" ") }.toSet()
        assertEquals(
            setOf(
                "example.com/gotests/broken: failed",
                "example.com/gotests/calc: failed",
                "example.com/gotests/panics: failed",
                "example.com/gotests/slow: failed",
            ),
            packages,
        )
        assertTrue(recorder.rootOutput, recorder.rootOutput.contains("example.com/gotests/notests\t[no test files]"))
        // benchfail has only benchmarks, which a run without -bench does not select.
        val benchfail = Regex("""ok  \texample.com/gotests/benchfail\t\S+ \[no tests to run]""")
        assertTrue(recorder.rootOutput, benchfail.containsMatchIn(recorder.rootOutput))
    }

    @Test
    fun `recursive run builds the same package subtree as a single package run`() {
        val all = replay("all").render()
        val calc = replay("calc").render()
        val calcSubtree = all.lines().dropWhile { it != "example.com/gotests/calc: failed" }
            .takeWhile { it == "example.com/gotests/calc: failed" || it.startsWith(" ") }
        assertEquals(calc.lines().filter { it.isNotEmpty() }, calcSubtree)
    }

    private fun collectOutput(node: TestTreeRecorder.Node): String =
        node.output.toString() + node.errorOutput + node.children.joinToString("") { collectOutput(it) }
}
