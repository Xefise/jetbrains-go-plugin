package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.random.Random

/**
 * Raw `-test.v=test2json` binary output (testData/test2json/raw, see capture.sh) through the converter and the reducer
 * gives the tree that the `go test -json` capture of the same run gives (testData/gotest-json).
 */
@RunWith(Parameterized::class)
class GoTest2JsonEndToEndTest(private val fixture: String, private val pkg: String) {
    @Test
    fun `tree from raw output is independent of chunking`() {
        assertEquals(dump(convertRaw(fixture, pkg, lineChunks = true)), dump(convertRaw(fixture, pkg, lineChunks = false)))
    }

    @Test
    fun `tree from raw output matches the go test json tree`() {
        assertEquals(dump(TestTreeRecorder.replay(fixture)), dump(convertRaw(fixture, pkg, lineChunks = true)))
    }

    companion object {
        private val dir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"), "src/test/testData/test2json/raw")

        fun convertRaw(fixture: String, pkg: String, lineChunks: Boolean): TestTreeRecorder {
            val raw = File(dir, "$fixture.out").readBytes()
            val exitCode = File(dir, "$fixture.exitcode").readText().trim().toInt()
            val reducer = GoTestTreeReducer()
            val recorder = TestTreeRecorder()
            val converter = GoTest2Json("example.com/gotests/$pkg") { recorder.apply(reducer.accept(it)) }
            if (lineChunks) {
                var start = 0
                raw.forEachIndexed { i, b ->
                    if (b == '\n'.code.toByte()) {
                        converter.write(raw, start, i + 1 - start)
                        start = i + 1
                    }
                }
                converter.write(raw, start, raw.size - start)
            } else {
                GoTest2JsonGoldenTest.randomChunks(raw.size, Random(fixture.hashCode()))
                    .forEach { converter.write(raw, it.first, it.last - it.first + 1) }
            }
            converter.exited(exitCode)
            converter.close()
            recorder.apply(reducer.finish())
            recorder.assertAllFinished()
            return recorder
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("calc", "calc"),
            arrayOf("bench", "calc"),
            arrayOf("panic", "panics"),
            arrayOf("timeout", "slow"),
            arrayOf("subtest-filter", "calc"),
            arrayOf("benchfail", "benchfail"),
        )

        /** Every node with status, message and output; timings, addresses, goroutine ids and padding vary between runs. */
        fun dump(recorder: TestTreeRecorder): String = buildString {
            fun visit(node: TestTreeRecorder.Node, depth: Int) {
                append("  ".repeat(depth)).append(node.name).append(": ").append(node.status)
                node.message?.takeIf { it.isNotEmpty() }?.let { append(" [").append(it).append(']') }
                append('\n')
                if (node.output.isNotEmpty()) append(normalize(node.output)).append('\n')
                if (node.errorOutput.isNotEmpty()) append("!").append(normalize(node.errorOutput)).append('\n')
                node.children.forEach { visit(it, depth + 1) }
            }
            recorder.node().children.forEach { visit(it, 0) }
        }

        private fun normalize(text: CharSequence): String = text.toString()
            // go test prints a cached result for the second identical run, and the exit status after unbuffered
            // (benchmark) output; neither happens for a single binary run.
            .replace("\t(cached)", "\t0s")
            .replace(Regex("(?m)^exit status \\d+\n"), "")
            .replace(Regex("0x[0-9a-f]+"), "X")
            .replace(Regex("(?m)^(Benchmark\\S*?)(-\\d+)?\\s+\\d+\\s.*$"), "$1 <result>")
            .replace(Regex("\\b\\d+(\\.\\d+)?(s|ms)\\b"), "<time>")
            .replace(Regex("goroutine \\d+"), "goroutine N")
            .replace(Regex("[ \\t]+"), " ")
            .trimEnd()
            .prependIndent("    | ")
    }
}
