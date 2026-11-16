package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Failure messages, details, skip reasons and framing, on captures from testData/gotest-json/extra. */
class GoTestTreeReducerFailureTest {
    private val pkg = "example.com/cap/fail"

    private fun run(vararg lines: String): TestTreeRecorder {
        val reducer = GoTestTreeReducer()
        val recorder = TestTreeRecorder()
        lines.forEach { recorder.apply(reducer.accept(it)) }
        recorder.apply(reducer.finish())
        recorder.assertAllFinished()
        return recorder
    }

    @Test
    fun `failure details are the error lines only when go tags them`() {
        val test = TestTreeRecorder.replay("extra/testify").node(pkg, "TestErrorf")
        assertEquals("got 1, want 2", test.message)
        // The t.Log line before it is untagged.
        assertEquals("    fail_test.go:18: got 1, want 2", test.details)
    }

    @Test
    fun `failure details are the whole own output on go without output types`() {
        val test = TestTreeRecorder.replay("extra/testify-go1.23").node(pkg, "TestErrorf")
        assertEquals("a log line before the failure", test.message)
        assertEquals("    fail_test.go:17: a log line before the failure\n    fail_test.go:18: got 1, want 2", test.details)
    }

    @Test
    fun `error continuation lines go to stderr with the first line`() {
        val test = TestTreeRecorder.replay("extra/testify").node(pkg, "TestMultiLineError")
        assertEquals("    fail_test.go:22: first line\n        second line\n", test.errorOutput.toString())
        assertEquals("", test.output.toString())
    }

    @Test
    fun `testify subtest failure carries the comparison and its parent does not`() {
        for (fixture in listOf("extra/testify", "extra/testify-go1.23")) {
            val recorder = TestTreeRecorder.replay(fixture)
            val child = recorder.node(pkg, "TestSubFails", "child")
            assertEquals(fixture, "Not equal", child.message)
            assertEquals(fixture, "x", child.expected)
            assertEquals(fixture, "y", child.actual)
            val parent = recorder.node(pkg, "TestSubFails")
            assertEquals(fixture, "", parent.message)
            assertNull(fixture, parent.expected)
        }
    }

    @Test
    fun `skipped test carries the skip message`() {
        val recorder = TestTreeRecorder.replay("extra/skips")
        assertEquals("needs a database", recorder.node("example.com/cap/skips", "TestSkipWithReason").message)
        assertEquals("skipped on darwin", recorder.node("example.com/cap/skips", "TestSkipf").message)
        assertEquals("", recorder.node("example.com/cap/skips", "TestSkipNow").message)
    }

    @Test
    fun `package failing after its tests passed has the TestMain output as details`() {
        for (fixture in listOf("extra/testmain", "extra/testmain-go1.23")) {
            val node = TestTreeRecorder.replay(fixture).node("example.com/cap/testmain")
            assertEquals(fixture, "failed", node.status)
            // PASS and FAIL summary lines are left out, framed or not.
            assertEquals(fixture, "leak detected: goroutine 7 still running", node.details)
        }
    }

    @Test
    fun `package that never reports a result has its output as details`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"output","Package":"p","Output":"setup failed\n"}""",
        )
        assertEquals("setup failed", recorder.node("p").details)
    }

    @Test
    fun `unfinished test keeps its output as details`() {
        val recorder = run(
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"    a_test.go:3: step 1\n"}""",
        )
        val test = recorder.node("p", "TestA")
        assertEquals("    a_test.go:3: step 1", test.details)
    }

    @Test
    fun `package skip closes tests still open`() {
        val recorder = run(
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"skip","Package":"p","Elapsed":0}""",
            """{"Action":"run","Package":"q","Test":"TestB"}""",
            """{"Action":"pass","Package":"q","Test":"TestB"}""",
            """{"Action":"pass","Package":"q"}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: skipped
              TestA: failed
            q: passed
              TestB: passed
            """,
            recorder,
        )
    }

    @Test
    fun `frame-like line printed by a test is kept on go without output types`() {
        for (fixture in listOf("extra/testify", "extra/testify-go1.23")) {
            val test = TestTreeRecorder.replay(fixture).node(pkg, "TestFakeFrame")
            assertEquals(fixture, "    fail_test.go:31: --- FAIL: NotATest (0.00s)\n=== RUN   Bogus\n", test.output.toString())
        }
    }

    @Test
    fun `untyped frame line naming a test of the package that is not running is output`() {
        val recorder = run(
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"=== RUN   TestA\n"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"--- FAIL: TestB (0.00s)\n"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"--- PASS: TestA (0.00s)\n"}""",
            """{"Action":"pass","Package":"p","Test":"TestA"}""",
            """{"Action":"pass","Package":"p"}""",
        )
        assertEquals("--- FAIL: TestB (0.00s)\n", recorder.node("p", "TestA").output.toString())
    }

    @Test
    fun `untyped frame of a running subtest reported under its parent is dropped`() {
        val recorder = run(
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"run","Package":"p","Test":"TestA/x"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"    --- PASS: TestA/x (0.00s)\n"}""",
            """{"Action":"pass","Package":"p","Test":"TestA/x"}""",
            """{"Action":"pass","Package":"p","Test":"TestA"}""",
            """{"Action":"pass","Package":"p"}""",
        )
        assertEquals("", recorder.node("p", "TestA").output.toString())
    }
}
