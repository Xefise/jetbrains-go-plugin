package io.github.xefise.golsp.test.model

import com.google.gson.JsonObject
import io.github.xefise.golsp.test.model.GoTest2JsonGoldenTest.Companion.dir
import io.github.xefise.golsp.test.model.GoTest2JsonGoldenTest.Companion.json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Line-by-line conversion of the Go golden cases keeps every event as is, OutputType included. */
@RunWith(Parameterized::class)
class GoTest2JsonExactGoldenTest(private val fixture: String) {
    @Test
    fun `line by line conversion keeps the exact events`() {
        assertEquals(expected(fixture).joinToString("\n"), convertLines(fixture).joinToString("\n"))
    }

    companion object {
        // Go's diffJSON ignores OutputType, and attr.json in go1.27.3 lacks the "frame" its converter emits.
        private const val STALE_OUTPUT_TYPE = "attr"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<String> =
            dir.listFiles { f -> f.name.endsWith(".test") }!!.map { it.name.removeSuffix(".test") }.sorted() - STALE_OUTPUT_TYPE

        fun expected(fixture: String) = File(dir, "$fixture.json").readLines().filter { it.isNotBlank() }.map(::json)

        fun convertLines(fixture: String): List<JsonObject> {
            val lines = mutableListOf<String>()
            val converter = GoTest2Json("", timestamps = false) { lines += it }
            File(dir, "$fixture.test").readText().split(Regex("(?<=\n)")).forEach { converter.write(it) }
            converter.close()
            return lines.map(::json)
        }
    }
}

class GoTest2JsonAttrGoldenTest {
    @Test
    fun `attr events match the go golden file apart from OutputType`() {
        val withoutType = GoTest2JsonExactGoldenTest.convertLines("attr").onEach { if (it.has("OutputType")) it.remove("OutputType") }
        assertEquals(GoTest2JsonExactGoldenTest.expected("attr").joinToString("\n"), withoutType.joinToString("\n"))
    }

    @Test
    fun `attr framing lines are typed frame`() {
        val frames = GoTest2JsonExactGoldenTest.convertLines("attr")
            .filter { it["Action"].asString == "output" }
            .map { it["Output"].asString to it["OutputType"]?.asString }
        assertEquals(
            listOf(
                "=== RUN   TestAttr\n" to "frame",
                "=== ATTR  TestAttr key value\n" to "frame",
                "=== RUN   TestAttr/sub\n" to "frame",
                "=== ATTR  TestAttr/sub key value\n" to "frame",
                "--- PASS: TestAttr (0.00s)\n" to "frame",
                "    --- PASS: TestAttr/sub (0.00s)\n" to "frame",
                "PASS\n" to "frame",
            ),
            frames,
        )
    }
}
