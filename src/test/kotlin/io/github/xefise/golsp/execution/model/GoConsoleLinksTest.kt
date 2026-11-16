package io.github.xefise.golsp.execution.model

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

    @Test
    fun `unix panic frame with spaces offers the whole path before its space free tail`() {
        // "/Users/me/My Projects/app/x.go" is 30 chars after the tab; the tail "Projects/app/x.go" starts at 14
        assertEquals(
            listOf(
                GoConsoleLink(1, 33, "/Users/me/My Projects/app/x.go", 6, 0),
                GoConsoleLink(14, 33, "Projects/app/x.go", 6, 0),
            ),
            GoConsoleLinks.find("\t/Users/me/My Projects/app/x.go:7 +0x1d\n"),
        )
    }

    @Test
    fun `windows compiler error with spaces keeps drive and column`() {
        // "C:\Users\John Doe\proj\main.go" is 30 chars, ":12:3" ends at 35
        assertEquals(
            GoConsoleLink(0, 35, """C:\Users\John Doe\proj\main.go""", 11, 2),
            GoConsoleLinks.find("""C:\Users\John Doe\proj\main.go:12:3: undefined: x""").first(),
        )
    }

    @Test
    fun `spaced path starts after an opening bracket and stops at the first go line reference`() {
        // "(" at 0; "/a b/x.go" spans 1..10, ":3" ends at 12
        assertEquals(GoConsoleLink(1, 12, "/a b/x.go", 2, 0), GoConsoleLinks.find("(/a b/x.go:3) and /c d/y.go:4\n").first())
        assertEquals(
            listOf("/a b/x.go", "b/x.go", "/c d/y.go", "d/y.go"),
            GoConsoleLinks.find("(/a b/x.go:3) and /c d/y.go:4\n").map { it.path },
        )
    }

    @Test
    fun `absolute paths without spaces are offered once`() {
        assertEquals(listOf("/abs/x.go", "/abs/y.go"), GoConsoleLinks.find("\t/abs/x.go:1 +0x1 /abs/y.go:2\n").map { it.path })
    }

    @Test
    fun `slash inside a word or url does not start a spaced path`() {
        assertEquals(listOf("dir/x.go"), GoConsoleLinks.find("GET http://host/my dir/x.go:12 200\n").map { it.path })
        assertEquals(listOf("dir/x.go"), GoConsoleLinks.find("a/b c dir/x.go:12\n").map { it.path })
    }

    @Test
    fun `colon ends a spaced candidate so a message prefix is not swallowed`() {
        assertEquals(listOf("y.go"), GoConsoleLinks.find("open /tmp/x: see y.go:2\n").map { it.path })
    }

    @Test
    fun `a spaced path after an earlier spaced start is still offered`() {
        val paths = GoConsoleLinks.find("run /a b && /c d/x.go:7\n").map { it.path }
        assertEquals(true, "/c d/x.go" in paths)
    }
}
