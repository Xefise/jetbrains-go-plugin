// Modified from the Go project (src/cmd/internal/test2json/test2json_test.go: TestGolden chunkings, diffJSON),
// go1.27.3, BSD-3-Clause, Copyright 2017 The Go Authors
package io.github.xefise.golsp.test.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.random.Random

/**
 * Go's own test2json golden cases (testData/test2json/golden, copied from go1.27.3) through the port, with the chunkings
 * Go's TestGolden uses plus single bytes, random chunks and decoded text.
 */
@RunWith(Parameterized::class)
class GoTest2JsonGoldenTest(private val fixture: String, private val chunking: Chunking) {
    enum class Chunking { LINES, BULK, CRLF, EVEN2, ODD2, TINY5, TINY6, TINY7, TINY8, BYTES1, RANDOM, TEXT_RANDOM }

    @Test
    fun `events match the go golden output`() {
        val input = File(dir, "$fixture.test").readBytes()
        val actual = convert(input).map { it.withoutCrlf() }
        val expected = File(dir, "$fixture.json").readLines().filter { it.isNotBlank() }.map(::json)
        assertEquals(merged(expected), merged(actual))
    }

    private fun convert(input: ByteArray): List<JsonObject> {
        val lines = mutableListOf<String>()
        val tiny = when (chunking) {
            Chunking.TINY5 -> 5
            Chunking.TINY6 -> 6
            Chunking.TINY7 -> 7
            Chunking.TINY8 -> 8
            else -> null
        }
        val converter = if (tiny != null) {
            GoTest2Json("", false, 64, tiny) { lines += it }
        } else {
            GoTest2Json("", timestamps = false) { lines += it }
        }
        val random = Random(fixture.hashCode())
        when (chunking) {
            Chunking.LINES -> splitAfterNewline(input).forEach { converter.write(it) }
            Chunking.BULK, Chunking.TINY5, Chunking.TINY6, Chunking.TINY7, Chunking.TINY8 -> converter.write(input)
            Chunking.CRLF -> converter.write(String(input, Charsets.ISO_8859_1).replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1))
            Chunking.EVEN2 -> chunks(input, 0, 2).forEach { converter.write(it) }
            Chunking.ODD2 -> {
                if (input.isNotEmpty()) converter.write(input.copyOfRange(0, 1))
                chunks(input, 1, 2).forEach { converter.write(it) }
            }
            Chunking.BYTES1 -> chunks(input, 0, 1).forEach { converter.write(it) }
            Chunking.RANDOM -> randomChunks(input.size, random).forEach { converter.write(input, it.first, it.last - it.first + 1) }
            Chunking.TEXT_RANDOM -> {
                val text = String(input, Charsets.UTF_8)
                randomChunks(text.length, random).forEach { converter.write(text.substring(it.first, it.last + 1)) }
            }
        }
        converter.close()
        return lines.map(::json)
    }

    private fun JsonObject.withoutCrlf(): JsonObject {
        if (chunking != Chunking.CRLF || !has("Output")) return this
        return deepCopy().apply { addProperty("Output", get("Output").asString.replace("\r\n", "\n")) }
    }

    companion object {
        val dir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"), "src/test/testData/test2json/golden")

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> {
            val fixtures = dir.listFiles { f -> f.name.endsWith(".test") }!!.map { it.name.removeSuffix(".test") }.sorted()
            check(fixtures.size >= 19) { "golden fixtures missing in $dir" }
            return fixtures.flatMap { name -> Chunking.entries.map { arrayOf(name, it) } }
        }

        fun json(line: String) = JsonParser.parseString(line).asJsonObject

        /**
         * Consecutive output events of one test merged into one, as Go's diffJSON compares them: how output is cut into
         * events (and so error vs error-continue) depends on buffer sizes.
         */
        fun merged(events: List<JsonObject>): List<String> {
            val result = mutableListOf<String>()
            var outputTest: String? = null
            val text = StringBuilder()
            fun flush() {
                if (outputTest != null) result += "output Test=$outputTest ${text.toString().quoted()}"
                outputTest = null
                text.clear()
            }
            for (e in events) {
                val test = e.get("Test")?.asString ?: ""
                if (e.get("Action").asString == "output") {
                    if (outputTest != test) flush()
                    outputTest = test
                    text.append(e.get("Output").asString)
                } else {
                    flush()
                    result += e.toString()
                }
            }
            flush()
            return result
        }

        private fun String.quoted() = buildString {
            append('"')
            for (c in this@quoted) if (c < ' ' && c != '\n') append("\\x%02x".format(c.code)) else append(c)
            append('"')
        }

        private fun splitAfterNewline(b: ByteArray): List<ByteArray> {
            val parts = mutableListOf<ByteArray>()
            var start = 0
            for (i in b.indices) if (b[i] == '\n'.code.toByte()) {
                parts += b.copyOfRange(start, i + 1)
                start = i + 1
            }
            if (start < b.size) parts += b.copyOfRange(start, b.size)
            return parts
        }

        private fun chunks(b: ByteArray, from: Int, size: Int): List<ByteArray> =
            (from until b.size step size).map { b.copyOfRange(it, minOf(it + size, b.size)) }

        fun randomChunks(length: Int, random: Random): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var start = 0
            while (start < length) {
                val end = minOf(length, start + 1 + random.nextInt(17))
                ranges += start until end
                start = end
            }
            return ranges
        }
    }
}
