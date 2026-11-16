package io.github.xefise.golsp.coverage.model

import io.github.xefise.golsp.coverage.model.GoModReplaces.Replace
import org.junit.Assert.assertEquals
import org.junit.Test

class GoModReplacesTest {
    @Test
    fun `single-line replace with a relative directory`() {
        assertEquals(listOf(Replace("example.com/lib", "../lib")), GoModReplaces.localReplaces("replace example.com/lib => ../lib\n"))
    }

    @Test
    fun `versioned left side still replaces with a directory`() {
        assertEquals(listOf(Replace("example.com/lib", "./lib")), GoModReplaces.localReplaces("replace example.com/lib v1.2.0 => ./lib"))
    }

    @Test
    fun `module replacement with a version is not a directory`() {
        assertEquals(emptyList<Replace>(), GoModReplaces.localReplaces("replace example.com/lib => example.com/fork v1.0.0"))
    }

    @Test
    fun `module path without a dot prefix is not a directory`() {
        assertEquals(emptyList<Replace>(), GoModReplaces.localReplaces("replace example.com/lib => lib"))
    }

    @Test
    fun `block with comments, absolute, windows and quoted directories`() {
        val text = """
            module example.com/app

            replace (
            	example.com/a => /abs/a // pinned
            	// example.com/skip => ./skip
            	example.com/b v0.1.0 => C:\src\b
            	example.com/c => "../with space"
            	example.com/d => example.com/d2 v0.2.0
            	example.com/e => ..\e
            )
            replace example.com/f => .
        """.trimIndent()
        assertEquals(
            listOf(
                Replace("example.com/a", "/abs/a"),
                Replace("example.com/b", """C:\src\b"""),
                Replace("example.com/c", "../with space"),
                Replace("example.com/e", """..\e"""),
                Replace("example.com/f", "."),
            ),
            GoModReplaces.localReplaces(text),
        )
    }

    @Test
    fun `go mod without replace`() {
        assertEquals(emptyList<Replace>(), GoModReplaces.localReplaces("module example.com/app\n\ngo 1.27\n"))
    }

    @Test
    fun `malformed replace without a target is skipped`() {
        assertEquals(emptyList<Replace>(), GoModReplaces.localReplaces("replace example.com/lib =>\nreplace example.com/x ../x"))
    }
}
