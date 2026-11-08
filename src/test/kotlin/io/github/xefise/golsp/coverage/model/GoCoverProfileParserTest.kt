package io.github.xefise.golsp.coverage.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GoCoverProfileParserTest {
    private fun fixture(name: String): String =
        File(System.getProperty("golsp.projectDir") ?: ".", "src/test/testData/coverage/$name").readText()

    private val calc = "example.com/gotests/calc/calc.go"

    @Test
    fun `captured count profile yields every block with its counter`() {
        val profile = GoCoverProfileParser.parse(fixture("calc-count.out"))
        assertEquals("count", profile.mode)
        assertEquals(
            listOf(
                GoCoverBlock(calc, 3, 26, 3, 40, 1, 2),
                GoCoverBlock(calc, 6, 26, 6, 40, 1, 2),
                GoCoverBlock(calc, 9, 2, 10, 50, 2, 4),
                GoCoverBlock(calc, 11, 3, 12, 1, 1, 6),
                GoCoverBlock(calc, 13, 2, 13, 18, 1, 4),
            ),
            profile.blocks,
        )
    }

    @Test
    fun `captured set and atomic profiles keep their mode`() {
        assertEquals("set", GoCoverProfileParser.parse(fixture("calc-set.out")).mode)
        assertEquals(listOf(1, 1, 1, 1, 1), GoCoverProfileParser.parse(fixture("calc-set.out")).blocks.map { it.count })
        assertEquals("atomic", GoCoverProfileParser.parse(fixture("calc-atomic.out")).mode)
    }

    @Test
    fun `captured multi-package profile has one header and blocks of every package`() {
        val profile = GoCoverProfileParser.parse(fixture("all.out"))
        assertEquals("set", profile.mode)
        assertEquals(
            setOf(calc, "example.com/gotests/notests/notests.go"),
            profile.blocks.map { it.file }.toSet(),
        )
    }

    @Test
    fun `duplicate blocks in count mode add up`() {
        val text = "mode: count\na/b.go:1.1,2.2 1 3\na/b.go:1.1,2.2 1 4\n"
        assertEquals(listOf(GoCoverBlock("a/b.go", 1, 1, 2, 2, 1, 7)), GoCoverProfileParser.parse(text).blocks)
    }

    @Test
    fun `duplicate blocks in set mode stay a single hit`() {
        val text = "mode: set\na/b.go:1.1,2.2 1 1\na/b.go:1.1,2.2 1 0\na/b.go:1.1,2.2 1 1\n"
        assertEquals(listOf(1), GoCoverProfileParser.parse(text).blocks.map { it.count })
    }

    @Test
    fun `concatenated profiles keep the first mode and skip later headers`() {
        val text = "mode: atomic\na/b.go:1.1,1.9 1 1\nmode: atomic\na/b.go:1.1,1.9 1 2\n"
        val profile = GoCoverProfileParser.parse(text)
        assertEquals("atomic", profile.mode)
        assertEquals(listOf(3), profile.blocks.map { it.count })
    }

    @Test
    fun `malformed lines are skipped`() {
        val text = listOf(
            "mode: set",
            "no colon at all",
            "a/b.go:1.1,2.2 1",
            "a/b.go:1.1-2.2 1 1",
            "a/b.go:x.1,2.2 1 1",
            "a/b.go:3.1,2.2 1 1",
            "a/b.go:0.1,2.2 1 1",
            ":1.1,2.2 1 1",
            "a/b.go:4.1,5.2 1 1",
        ).joinToString("\n")
        assertEquals(listOf(GoCoverBlock("a/b.go", 4, 1, 5, 2, 1, 1)), GoCoverProfileParser.parse(text).blocks)
    }

    @Test
    fun `windows drive in the file name and CRLF endings are handled`() {
        val text = "mode: set\r\nC:\\work\\m\\x.go:1.2,3.4 2 1\r\n"
        assertEquals(listOf(GoCoverBlock("C:\\work\\m\\x.go", 1, 2, 3, 4, 2, 1)), GoCoverProfileParser.parse(text).blocks)
    }

    @Test
    fun `atomic counter above Int range is clamped`() {
        // uint32 max = 4294967295 > Int.MAX_VALUE
        val text = "mode: atomic\na/b.go:1.1,1.2 1 4294967295\n"
        assertEquals(listOf(Int.MAX_VALUE), GoCoverProfileParser.parse(text).blocks.map { it.count })
    }

    @Test
    fun `empty text is an empty profile`() {
        assertEquals(GoCoverProfile("", emptyList()), GoCoverProfileParser.parse(""))
    }
}
