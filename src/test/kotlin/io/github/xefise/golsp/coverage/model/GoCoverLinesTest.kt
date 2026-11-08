package io.github.xefise.golsp.coverage.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoCoverLinesTest {
    private fun block(startLine: Int, endLine: Int, count: Int, file: String = "m/a.go", statements: Int = 1) =
        GoCoverBlock(file, startLine, 2, endLine, 3, statements, count)

    private val identity: (String) -> String? = { it }

    @Test
    fun `a block marks every line from its start to its end`() {
        val lines = GoCoverLines.byFile(listOf(block(9, 11, 4)), identity)
        assertEquals(mapOf("m/a.go" to mapOf(9 to GoLineHits(4, false), 10 to GoLineHits(4, false), 11 to GoLineHits(4, false))), lines)
    }

    @Test
    fun `a line shared by a run and a never-run block is partial with the best count`() {
        // Line 6: the `if` body ends where the following statements start.
        val lines = GoCoverLines.byFile(listOf(block(4, 6, 0), block(6, 7, 3)), identity).getValue("m/a.go")
        assertEquals(GoLineHits(0, false), lines[5])
        assertEquals(GoLineHits(3, true), lines[6])
        assertEquals(GoLineHits(3, false), lines[7])
    }

    @Test
    fun `a line shared by two run blocks is fully covered`() {
        val lines = GoCoverLines.byFile(listOf(block(1, 2, 1), block(2, 3, 5)), identity).getValue("m/a.go")
        assertEquals(GoLineHits(5, false), lines[2])
    }

    @Test
    fun `a line shared by two never-run blocks is not partial`() {
        val lines = GoCoverLines.byFile(listOf(block(1, 2, 0), block(2, 3, 0)), identity).getValue("m/a.go")
        assertEquals(GoLineHits(0, false), lines[2])
    }

    @Test
    fun `partial stays once a third block on the line also ran`() {
        val lines = GoCoverLines.byFile(listOf(block(1, 1, 2), block(1, 1, 0), block(1, 1, 7)), identity).getValue("m/a.go")
        assertEquals(GoLineHits(7, true), lines[1])
    }

    @Test
    fun `blocks without statements are ignored`() {
        // notests.go of the captured ./... profile: `3.16,3.16 0 0`.
        assertEquals(emptyMap<String, Map<Int, GoLineHits>>(), GoCoverLines.byFile(listOf(block(3, 3, 0, statements = 0)), identity))
    }

    @Test
    fun `files the resolver rejects are dropped and each file is resolved once`() {
        val asked = mutableListOf<String>()
        val resolve = { name: String -> asked += name; if (name == "m/a.go") "/src/a.go" else null }
        val lines = GoCoverLines.byFile(listOf(block(1, 1, 1), block(2, 2, 1), block(1, 1, 1, file = "other/b.go")), resolve)
        assertEquals(setOf("/src/a.go"), lines.keys)
        assertEquals(listOf("m/a.go", "other/b.go"), asked)
    }

    @Test
    fun `else line of an if that always took its then branch is partial`() {
        // if x > 0 {        // 4
        //     return 1      // 5   block 4.11,6.3 count 2
        // } else {          // 6   both blocks touch this line
        //     return 0      // 7   block 6.9,8.3 count 0
        // }                 // 8
        val blocks = listOf(
            GoCoverBlock("m/a.go", 4, 11, 6, 3, 1, 2),
            GoCoverBlock("m/a.go", 6, 9, 8, 3, 1, 0),
        )
        val lines = GoCoverLines.byFile(blocks, identity).getValue("m/a.go")
        assertEquals(GoLineHits(2, true), lines[6])
        assertEquals(GoLineHits(0, false), lines[7])
    }
}
