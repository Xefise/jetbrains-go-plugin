// trimUTF8 test modified from the Go project (src/cmd/internal/test2json/test2json_test.go: TestTrimUTF8), go1.27.3,
// BSD-3-Clause, Copyright 2017 The Go Authors
package io.github.xefise.golsp.test.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTest2JsonTest {
    private val v = "\u0016"

    private fun convert(pkg: String = "p", timestamps: Boolean = false, feed: GoTest2Json.() -> Unit): List<JsonObject> {
        val lines = mutableListOf<String>()
        GoTest2Json(pkg, timestamps) { lines += it }.apply(feed)
        return lines.map { JsonParser.parseString(it).asJsonObject }
    }

    private fun List<JsonObject>.summary(): List<String> = map {
        listOfNotNull(it["Action"].asString, it["Test"]?.asString, it["Output"]?.asString?.let { o -> "'$o'" }).joinToString(" ")
    }

    @Test
    fun `start event is sent before any input`() {
        val lines = mutableListOf<String>()
        GoTest2Json("example.com/p", timestamps = false) { lines += it }
        assertEquals(listOf("""{"Action":"start","Package":"example.com/p"}"""), lines)
    }

    @Test
    fun `reported exit code comes only from a framed final line`() {
        fun code(text: String): Int? = GoTest2Json("p", timestamps = false) {}.run {
            write(text)
            reportedExitCode()
        }
        assertEquals(null, code("${v}=== RUN   TestA\n"))
        assertEquals(0, code("${v}=== RUN   TestA\n${v}--- PASS: TestA (0.00s)\n${v}PASS\n"))
        assertEquals(1, code("${v}=== RUN   TestA\n${v}--- FAIL: TestA (0.00s)\n${v}FAIL\n"))
        // TestMain printing PASS before m.Run, and after framing started.
        assertEquals(null, code("PASS\n${v}=== RUN   TestA\n"))
        assertEquals(null, code("${v}=== RUN   TestA\nPASS\n"))
    }

    @Test
    fun `successful exit appends the ok line and passes the package`() {
        val events = convert {
            write("${v}=== RUN   TestA\n${v}--- PASS: TestA (0.00s)\n${v}PASS\n")
            exited(0)
            close()
        }
        val ok = events[events.size - 2]["Output"].asString
        assertTrue(ok, ok.startsWith("ok  \tp\t"))
        assertEquals("pass", events.last()["Action"].asString)
    }

    @Test
    fun `failing exit without a final FAIL line still closes pending reports`() {
        // A panicking binary prints "--- FAIL" reports but no "FAIL" line; go test's own FAIL line flushes them.
        val events = convert {
            write("${v}=== RUN   TestP\n${v}--- FAIL: TestP (0.00s)\npanic: boom\n")
            exited(2)
            close()
        }
        val actions = events.filter { it["Action"].asString != "output" }.map { it["Action"].asString + " " + (it["Test"]?.asString ?: "") }
        assertEquals(listOf("start ", "run TestP", "fail TestP", "fail "), actions)
    }

    @Test
    fun `failing exit without any output reports the exit status`() {
        val events = convert {
            exited(3)
            close()
        }
        assertEquals("exit status 3\n", events[1]["Output"].asString)
        assertTrue(events[2]["Output"].asString.startsWith("FAIL\tp\t"))
        assertEquals("fail", events.last()["Action"].asString)
    }

    @Test
    fun `exit after output without a trailing newline ends that line first`() {
        val events = convert {
            write("partial")
            exited(1)
            close()
        }
        assertEquals("output 'partial\n'", events.summary()[1])
    }

    @Test
    fun `close without a final line or exit sends no package result`() {
        val events = convert {
            write("${v}=== RUN   TestA\n")
            close()
        }
        assertEquals(listOf("start", "run TestA", "output TestA '=== RUN   TestA\n'"), events.summary())
    }

    @Test
    fun `close flushes an incomplete trailing line`() {
        val events = convert {
            write("${v}=== RUN   TestA\nno newline")
            close()
        }
        assertEquals("output TestA 'no newline'", events.summary().last())
    }

    @Test
    fun `unmarked status line after framing markers is plain output`() {
        // With -test.v=test2json only ^V lines are framing: a test printing "--- FAIL" cannot fake a result.
        val events = convert {
            write("${v}=== RUN   TestA\n--- FAIL: TestA (0.00s)\n${v}--- PASS: TestA (0.00s)\n${v}PASS\n")
            close()
        }
        assertEquals(
            listOf("start", "run TestA", "output TestA '=== RUN   TestA\n'", "output TestA '--- FAIL: TestA (0.00s)\n'",
                "output TestA '--- PASS: TestA (0.00s)\n'", "pass TestA", "output 'PASS\n'", "pass"),
            events.summary(),
        )
    }

    @Test
    fun `timestamps add elapsed to reports and the package result`() {
        val events = convert(timestamps = true) {
            write("${v}=== RUN   TestA\n${v}--- PASS: TestA (1.25s)\n${v}PASS\n")
            close()
        }
        assertTrue(events.all { it.has("Time") })
        assertEquals(1.25, events.single { it["Action"].asString == "pass" && it.has("Test") }["Elapsed"].asDouble, 0.0)
        assertTrue(events.last().has("Elapsed"))
    }

    @Test
    fun `without timestamps there is no time or elapsed`() {
        val events = convert {
            write("${v}=== RUN   TestA\n${v}--- PASS: TestA (1.25s)\n${v}PASS\n")
            close()
        }
        assertFalse(events.any { it.has("Time") || it.has("Elapsed") })
    }

    @Test
    fun `surrogate pair split between text chunks survives`() {
        val text = "${v}=== RUN   TestA\nemoji 😀 end\n"
        val split = text.indexOf('\uDE00')
        val events = convert {
            write(text.substring(0, split))
            write(text.substring(split))
            close()
        }
        assertEquals("output TestA 'emoji 😀 end\n'", events.summary().last())
    }

    @Test
    fun `utf-8 sequence split between byte chunks survives`() {
        val bytes = "${v}=== RUN   TestA\nжук\n".toByteArray()
        val split = bytes.size - 3 // inside the two-byte "к"
        val events = convert {
            write(bytes, 0, split)
            write(bytes, split, bytes.size - split)
            close()
        }
        assertEquals("output TestA 'жук\n'", events.summary().last())
    }

    @Test
    fun `nested report indentation attributes output to the subtest`() {
        val events = convert {
            // testing prints the parent report first, then the indented reports and logs of its subtests.
            write("${v}=== RUN   TestA\n${v}=== RUN   TestA/sub\n${v}--- FAIL: TestA (0.00s)\n")
            write("${v}    --- FAIL: TestA/sub (0.00s)\n        sub_test.go:3: bad\n${v}FAIL\n")
            close()
        }
        assertEquals("TestA/sub", events.single { it["Output"]?.asString?.contains("bad") == true }["Test"].asString)
        assertEquals(listOf("fail TestA/sub", "fail TestA"), events.summary().filter { it.startsWith("fail Test") })
    }

    @Test
    fun `error markers set the error output type and are elided`() {
        val events = convert {
            write("${v}=== RUN   TestA\n\u000f    a_test.go:5: wrong\u000e\n")
            close()
        }
        val error = events.last()
        assertEquals("    a_test.go:5: wrong\n", error["Output"].asString)
        assertEquals("error", error["OutputType"].asString)
    }

    @Test
    fun `benchmark result split at its tab still passes the benchmark`() {
        val recorder = GoTest2JsonEndToEndTest.convertRaw("benchfail", "benchfail", lineChunks = false)
        assertEquals("passed", recorder.node("example.com/gotests/benchfail", "BenchmarkPasses").status)
    }

    @Test
    fun `out of range elapsed is dropped like a go parse error`() {
        val events = convert(timestamps = true) {
            write("${v}=== RUN   TestA\n${v}--- PASS: TestA (1e999s)\n${v}PASS\n")
            close()
        }
        val pass = events.single { it["Action"].asString == "pass" && it.has("Test") }
        assertEquals("TestA", pass["Test"].asString)
        assertFalse(pass.has("Elapsed"))
    }

    @Test
    fun `successful exit of a binary without test files keeps skip`() {
        val events = convert {
            write("?   \tp\t[no test files]\n")
            exited(0)
            close()
        }
        assertEquals("skip", events.last()["Action"].asString)
    }

    @Test
    fun `exit after a dangling high surrogate writes it before the summary line`() {
        val events = convert {
            write("x\uD83D")
            exited(1)
            close()
        }
        assertEquals("output 'x?\n'", events.summary()[1])
    }

    @Test
    fun `second close sends nothing`() {
        val lines = mutableListOf<String>()
        val converter = GoTest2Json("p", timestamps = false) { lines += it }
        converter.write("\u0016PASS\n")
        converter.close()
        val count = lines.size
        converter.close()
        assertEquals(count, lines.size)
    }

    @Test
    fun `trimUTF8 cuts only before an incomplete sequence`() {
        // Port of Go's TestTrimUTF8: α is 2 bytes, ☺ 3, 😂 4.
        val b = "hello α ☺ \uD83D\uDE02 world".toByteArray()
        val boundaries = (0..b.size).filter { it == b.size || (b[it].toInt() and 0xc0) != 0x80 }.toSet()
        for (i in 0..b.size) {
            val j = GoTest2JsonLineBuffer.trimUTF8(b, i)
            assertTrue("trimUTF8($i) = $j is not a rune boundary", j in boundaries)
            val skipped = b.copyOfRange(j, i)
            assertTrue("trimUTF8($i) = $j drops a whole rune", skipped.isEmpty() || i !in boundaries)
        }
    }

    @Test
    fun `benchmark name needs a non lower case rune after the prefix`() {
        val cases = mapOf("Benchmark" to true, "BenchmarkAdd-8" to true, "Benchmark_x" to true, "Benchmarkadd" to false,
            "Benchmarkéa" to false, "BenchmarkÉ" to true, "Bench" to false, "TestAdd" to false)
        cases.forEach { (name, expected) -> assertEquals(name, expected, GoTest2JsonLineBuffer.isBenchmarkName(name.toByteArray())) }
    }
}
