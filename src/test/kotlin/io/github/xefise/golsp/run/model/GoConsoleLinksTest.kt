package io.github.xefise.golsp.run.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoConsoleLinksTest {
    private fun single(line: String): GoConsoleLink = GoConsoleLinks.find(line).single()

    @Test
    fun `compiler error links path line and column as zero based`() {
        // "./main.go:5:2" spans offsets 0..13
        assertEquals(GoConsoleLink(0, 13, "./main.go", 4, 1), single("./main.go:5:2: undefined: x\n"))
    }

    @Test
    fun `line without column links to column zero`() {
        // "    main_test.go:12" — path starts at 4, "main_test.go:12" is 15 chars
        assertEquals(GoConsoleLink(4, 19, "main_test.go", 11, 0), single("    main_test.go:12: got 1, want 2\n"))
    }

    @Test
    fun `panic frame with pc offset links the absolute path`() {
        // "/abs/path/file.go:123" is 21 chars after the tab
        assertEquals(GoConsoleLink(1, 22, "/abs/path/file.go", 122, 0), single("\t/abs/path/file.go:123 +0x1d\n"))
    }

    @Test
    fun `line at end of text without separator still links`() {
        assertEquals(GoConsoleLink(0, 6, "a.go", 6, 0), single("a.go:7"))
    }

    @Test
    fun `line number zero gives no link`() {
        assertEquals(emptyList<GoConsoleLink>(), GoConsoleLinks.find("file.go:0: weird\n"))
    }

    @Test
    fun `column zero is clamped to the first column`() {
        assertEquals(0, single("x.go:3:0: msg\n").column)
    }

    @Test
    fun `line number beyond int range gives no link`() {
        assertEquals(emptyList<GoConsoleLink>(), GoConsoleLinks.find("x.go:99999999999: msg\n"))
    }

    @Test
    fun `lines without a file reference give no link`() {
        listOf("", "\n", "hello world\n", "exit status 1\n", "x.go: no line\n", "main.main()\n", "ratio 3:4\n").forEach {
            assertEquals(it, emptyList<GoConsoleLink>(), GoConsoleLinks.find(it))
        }
    }

    @Test
    fun `url with port and go path gives no link`() {
        assertEquals(emptyList<GoConsoleLink>(), GoConsoleLinks.find("GET http://host:8080/x.go:12 200\n"))
    }

    @Test
    fun `file reference after a url still links`() {
        // "http://host/x.go:1" occupies 0..18, the space at 18, "y.go:2" at 19..25
        assertEquals(GoConsoleLink(19, 25, "y.go", 1, 0), single("http://host/x.go:1 y.go:2\n"))
    }

    @Test
    fun `windows path keeps the drive letter`() {
        assertEquals(GoConsoleLink(0, 11, """C:\x\y.go""", 2, 0), single("""C:\x\y.go:3: msg""" + "\n"))
    }

    @Test
    fun `parenthesised go file name links when no line reference is present`() {
        // "(main.go)" starts at 9; the name inside spans 10..17
        assertEquals(GoConsoleLink(10, 17, "main.go", 0, 0), single("built in (main.go)\n"))
    }

    @Test
    fun `every file line reference in a line is a candidate in order`() {
        assertEquals(listOf("foo.local", "main.go"), GoConsoleLinks.find("listening on foo.local:8080 see main.go:12\n").map { it.path })
    }

    @Test
    fun `opening bracket before a path is not part of it`() {
        // "(main.go:12)": the path starts after "(" at 1, "main.go:12" ends at 11
        assertEquals(GoConsoleLink(1, 11, "main.go", 11, 0), single("(main.go:12)\n"))
    }

    @Test
    fun `quoted path links without the quotes`() {
        assertEquals("a/b.go", single("open \"a/b.go:3\", done\n").path)
    }

    @Test
    fun `windows dot backslash path is kept for the resolver`() {
        assertEquals(GoConsoleLink(0, 13, ".\\main.go", 4, 1), single(".\\main.go:5:2: undefined: x\n"))
    }
}
