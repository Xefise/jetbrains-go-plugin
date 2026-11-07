package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream shapes the captures do not cover: hand-written lines in the real event format. */
class GoTestTreeReducerEdgeTest {
    private fun run(vararg lines: String): TestTreeRecorder {
        val reducer = GoTestTreeReducer()
        val recorder = TestTreeRecorder()
        lines.forEach { recorder.apply(reducer.accept(it)) }
        recorder.apply(reducer.finish())
        recorder.assertAllFinished()
        return recorder
    }

    @Test
    fun `process exit with open tests fails them and their package`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"run","Package":"p","Test":"TestA/x"}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: failed
              TestA: failed
                x: failed
            """,
            recorder,
        )
    }

    @Test
    fun `old go without output type still drops frame lines`() {
        val recorder = run(
            """{"Action":"run","Package":"p","Test":"TestA"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"=== RUN   TestA\n"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"    a_test.go:3: hi\n"}""",
            """{"Action":"output","Package":"p","Test":"TestA","Output":"--- PASS: TestA (0.00s)\n"}""",
            """{"Action":"pass","Package":"p","Test":"TestA","Elapsed":0}""",
            """{"Action":"pass","Package":"p","Elapsed":0.01}""",
        )
        assertEquals("    a_test.go:3: hi\n", recorder.node("p", "TestA").output.toString())
    }

    @Test
    fun `plain text lines go to the root`() {
        val recorder = run("go: downloading example.com/dep v1.0.0")
        assertEquals("go: downloading example.com/dep v1.0.0\n", recorder.rootOutput)
    }

    @Test
    fun `result without a run event still creates the test`() {
        TestTreeRecorder.assertTree(
            """
            p: passed
              TestA: passed
            """,
            run(
                """{"Action":"pass","Package":"p","Test":"TestA","Elapsed":0}""",
                """{"Action":"pass","Package":"p","Elapsed":0}""",
            ),
        )
    }

    @Test
    fun `repeated run with count creates a second node`() {
        TestTreeRecorder.assertTree(
            """
            p: passed
              TestA: passed
              TestA: passed
            """,
            run(
                """{"Action":"run","Package":"p","Test":"TestA"}""",
                """{"Action":"pass","Package":"p","Test":"TestA","Elapsed":0}""",
                """{"Action":"run","Package":"p","Test":"TestA"}""",
                """{"Action":"pass","Package":"p","Test":"TestA","Elapsed":0}""",
                """{"Action":"pass","Package":"p","Elapsed":0}""",
            ),
        )
    }

    @Test
    fun `interleaved packages keep their tests apart`() {
        TestTreeRecorder.assertTree(
            """
            a: passed
              TestX: passed
            b: failed
              TestX: failed
            """,
            run(
                """{"Action":"run","Package":"a","Test":"TestX"}""",
                """{"Action":"run","Package":"b","Test":"TestX"}""",
                """{"Action":"fail","Package":"b","Test":"TestX","Elapsed":0}""",
                """{"Action":"pass","Package":"a","Test":"TestX","Elapsed":0}""",
                """{"Action":"pass","Package":"a","Elapsed":0}""",
                """{"Action":"fail","Package":"b","Elapsed":0}""",
            ),
        )
    }

    @Test
    fun `elapsed seconds become rounded milliseconds`() {
        val ops = GoTestTreeReducer().run {
            accept("""{"Action":"run","Package":"p","Test":"TestA"}""")
            accept("""{"Action":"pass","Package":"p","Test":"TestA","Elapsed":1.2345}""")
        }
        // 1.2345 s = 1234.5 ms, rounded half up to 1235
        assertEquals(GoTestTreeOp.Passed(2, 1235), ops.single())
    }

    @Test
    fun `build output of a dependency is attached to the package that failed on it`() {
        val recorder = run(
            """{"ImportPath":"dep","Action":"build-output","Output":"# dep\n"}""",
            """{"ImportPath":"dep","Action":"build-output","Output":"dep/d.go:1:1: oops\n"}""",
            """{"ImportPath":"dep","Action":"build-fail"}""",
            """{"Action":"start","Package":"p"}""",
            """{"Action":"fail","Package":"p","Elapsed":0,"FailedBuild":"dep"}""",
        )
        assertEquals("# dep\ndep/d.go:1:1: oops\n", recorder.node("p").errorOutput.toString())
        assertEquals("", recorder.rootOutput)
    }

    @Test
    fun `build output nobody claimed is shown at the root`() {
        val recorder = run("""{"ImportPath":"dep","Action":"build-output","Output":"dep/d.go:1:1: oops\n"}""")
        assertEquals("dep/d.go:1:1: oops\n", recorder.rootOutput)
    }

    @Test
    fun `build output of a shared dependency is attached to every package that failed on it`() {
        val recorder = run(
            """{"ImportPath":"dep","Action":"build-output","Output":"dep/d.go:1:1: oops\n"}""",
            """{"ImportPath":"dep","Action":"build-fail"}""",
            """{"Action":"start","Package":"p1"}""",
            """{"Action":"fail","Package":"p1","Elapsed":0,"FailedBuild":"dep"}""",
            """{"Action":"start","Package":"p2"}""",
            """{"Action":"fail","Package":"p2","Elapsed":0,"FailedBuild":"dep"}""",
        )
        assertEquals("dep/d.go:1:1: oops\n", recorder.node("p1").errorOutput.toString())
        assertEquals("dep/d.go:1:1: oops\n", recorder.node("p2").errorOutput.toString())
        assertEquals("", recorder.rootOutput)
    }

    @Test
    fun `failing sub-benchmark fails its parent while a passing sibling stays passed`() {
        // Real Go 1.27.3 stream of b.Run("ok") and a b.Fatal in b.Run("bad"); timestamps and frame lines dropped.
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent","Output":"BenchmarkParent\n"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent/ok"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/ok","Output":"BenchmarkParent/ok\n"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/ok","Output":"BenchmarkParent/ok-8         \t      10\t        25.00 ns/op\n"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent/bad"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/bad","Output":"    b_test.go:10: x\n","OutputType":"error"}""",
            """{"Action":"fail","Package":"p","Test":"BenchmarkParent/bad"}""",
            """{"Action":"fail","Package":"p","Test":"BenchmarkParent"}""",
            """{"Action":"fail","Package":"p","Elapsed":0.453}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: failed
              BenchmarkParent: failed
                ok: passed
                bad: failed
            """,
            recorder,
        )
    }

    @Test
    fun `benchmark parent whose sub-benchmark printed a result passes when the package fails`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent/sub"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/sub","Output":"BenchmarkParent/sub-8         \t      10\t      1821 ns/op\n"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkFails"}""",
            """{"Action":"fail","Package":"p","Test":"BenchmarkFails"}""",
            """{"Action":"fail","Package":"p","Elapsed":0.425}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: failed
              BenchmarkParent: passed
                sub: passed
              BenchmarkFails: failed
            """,
            recorder,
        )
    }

    @Test
    fun `benchmark without a result line did not finish when the package fails`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkCut"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkCut","Output":"BenchmarkCut\n"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkCut","Output":"panic: boom\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":0.1}""",
        )
        val cut = recorder.node("p", "BenchmarkCut")
        assertEquals("failed", cut.status)
        assertTrue(cut.message!!, cut.message!!.startsWith("The test did not finish"))
    }

    @Test
    fun `test output shaped like a benchmark result does not pass a cut off test`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"TestX"}""",
            """{"Action":"output","Package":"p","Test":"TestX","Output":"TestX-8   \t     100\t  1.250 ns/op\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":0.1}""",
        )
        assertEquals("failed", recorder.node("p", "TestX").status)
    }

    @Test
    fun `bench event output is kept as output of its benchmark`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkA"}""",
            """{"Action":"bench","Package":"p","Test":"BenchmarkA","Output":"    a_test.go:3: logged\n"}""",
            """{"Action":"pass","Package":"p","Elapsed":0.1}""",
        )
        assertEquals("    a_test.go:3: logged\n", recorder.node("p", "BenchmarkA").output.toString())
    }

    @Test
    fun `bench event without output adds nothing`() {
        // test2json emits the --- BENCH: report itself without Output.
        val reducer = GoTestTreeReducer()
        reducer.accept("""{"Action":"run","Package":"p","Test":"BenchmarkA"}""")
        assertEquals(emptyList<GoTestTreeOp>(), reducer.accept("""{"Action":"bench","Package":"p","Test":"BenchmarkA"}"""))
    }

    @Test
    fun `empty build output of a failed dependency adds no output op`() {
        val reducer = GoTestTreeReducer()
        reducer.accept("""{"ImportPath":"dep","Action":"build-output","Output":""}""")
        reducer.accept("""{"Action":"start","Package":"p"}""")
        val ops = reducer.accept("""{"Action":"fail","Package":"p","Elapsed":0,"FailedBuild":"dep"}""")
        assertEquals(listOf(GoTestTreeOp.PackageStarted(1, "p"), GoTestTreeOp.Failed(1, "", 0)), ops)
    }

    @Test
    fun `benchmark parent with a cut off sub-benchmark did not finish though a sibling has a result`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent/one"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/one","Output":"BenchmarkParent/one-8 \t 10\t 25.00 ns/op\n"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkParent/two"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkParent/two","Output":"panic: test timed out after 1s\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":1.0}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: failed
              BenchmarkParent: failed
                one: passed
                two: failed
            """,
            recorder,
        )
    }

    @Test
    fun `benchmark printing its own name and a number is not a result line`() {
        // Real Go 1.27.3 output of fmt.Println("BenchmarkX/size=10 7 bar") inside that benchmark.
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkX/size=10"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkX/size=10","Output":"BenchmarkX/size=10 7 bar\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":1.0}""",
        )
        assertEquals("failed", recorder.node("p", "BenchmarkX/size=10").status)
    }

    @Test
    fun `result line without the GOMAXPROCS suffix and without ns per op counts`() {
        // Real Go 1.27.3 line under -cpu 1 with b.ReportMetric(0, "ns/op") and a custom metric.
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkX/size=10"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkX/size=10","Output":"BenchmarkX/size=10         \t       3\t         3.000 widgets/op\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":1.0}""",
        )
        assertEquals("passed", recorder.node("p", "BenchmarkX/size=10").status)
    }

    @Test
    fun `repeated benchmark run does not inherit the result of the previous run`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkA"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"BenchmarkA-8 \t 10\t 1.250 ns/op\n"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkA"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"panic: boom\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":1.0}""",
        )
        TestTreeRecorder.assertTree(
            """
            p: failed
              BenchmarkA: passed
              BenchmarkA: failed
            """,
            recorder,
        )
    }

    private fun benchmarkStatus(vararg outputs: String): String? {
        val lines = listOf(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkA"}""",
        ) + outputs.map { """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"$it"}""" } +
            """{"Action":"fail","Package":"p","Elapsed":1.0}"""
        return run(*lines.toTypedArray()).node("p", "BenchmarkA").status
    }

    @Test
    fun `result line split in two events counts`() {
        assertEquals("passed", benchmarkStatus("BenchmarkA-8   \t", "     100\t  1.250 ns/op\n"))
    }

    @Test
    fun `result line split in three events counts`() {
        assertEquals("passed", benchmarkStatus("BenchmarkA-8 \t  10", "0\t  1.2", "50 ns/op\t  0 B/op\n"))
    }

    @Test
    fun `result line split right after the name counts`() {
        assertEquals("passed", benchmarkStatus("BenchmarkA", "-8 \t 100\t 1.250 ns/op\n"))
    }

    @Test
    fun `result line still unterminated when the package ends counts`() {
        assertEquals("passed", benchmarkStatus("BenchmarkA-8 \t", " 100\t 1.250 ns/op"))
    }

    @Test
    fun `split line that is not a result does not count`() {
        assertEquals("failed", benchmarkStatus("BenchmarkA-8 \t 100", " bar\n"))
    }

    @Test
    fun `chunks of different lines are not joined into a result`() {
        assertEquals("failed", benchmarkStatus("BenchmarkA-8 \t 100\n", "\t 1.250 ns/op\n"))
    }

    @Test
    fun `overlong unterminated output does not let its tail pass for a result`() {
        assertEquals("failed", benchmarkStatus("x".repeat(5000), "BenchmarkA-8 \t 100\t 1.250 ns/op\n"))
    }

    @Test
    fun `frame lines between the chunks of a result line are ignored`() {
        val recorder = run(
            """{"Action":"start","Package":"p"}""",
            """{"Action":"run","Package":"p","Test":"BenchmarkA"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"BenchmarkA-8   \t"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"=== NAME  BenchmarkA\n","OutputType":"frame"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"--- BENCH: BenchmarkA\n"}""",
            """{"Action":"output","Package":"p","Test":"BenchmarkA","Output":"     100\t  1.250 ns/op\n"}""",
            """{"Action":"fail","Package":"p","Elapsed":1.0}""",
        )
        val bench = recorder.node("p", "BenchmarkA")
        assertEquals("passed", bench.status)
        assertEquals("BenchmarkA-8   \t     100\t  1.250 ns/op\n", bench.output.toString())
    }
}
