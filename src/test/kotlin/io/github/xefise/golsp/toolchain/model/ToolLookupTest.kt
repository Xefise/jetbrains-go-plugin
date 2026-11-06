package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolLookupTest {
    private fun unix(path: String? = null, goPath: String? = null, goRoot: String? = null) =
        LookupEnvironment(path = path, home = "/Users/me", goPath = goPath, goRoot = goRoot, isWindows = false)

    @Test
    fun `PATH entries come before Go install locations`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.GO, unix(path = "/opt/bin:/usr/bin", goRoot = "/sdk/go"))
        assertEquals(
            listOf("/opt/bin", "/usr/bin", "/Users/me/go/bin", "/sdk/go/bin", "/usr/local/go/bin", "/opt/homebrew/bin", "/usr/local/bin"),
            dirs,
        )
    }

    @Test
    fun `empty and blank PATH entries are skipped`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.DLV, unix(path = ":/a:: :/b:"))
        assertEquals(listOf("/a", "/b"), dirs.take(2))
    }

    @Test
    fun `GOROOT bin is searched for go but not for dlv`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.DLV, unix(goRoot = "/sdk/go"))
        assertEquals(false, "/sdk/go/bin" in dirs)
    }

    @Test
    fun `only the first GOPATH entry contributes a bin directory`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.DLV, unix(goPath = "/gp1:/gp2"))
        assertEquals("/gp1/bin", dirs.first())
        assertEquals(false, "/gp2/bin" in dirs)
    }

    @Test
    fun `trailing slash duplicates of the same directory collapse`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.DLV, unix(path = "/Users/me/go/bin/"))
        assertEquals(1, dirs.count { it == "/Users/me/go/bin" })
    }

    @Test
    fun `windows uses exe suffix and semicolon separator`() {
        val env = LookupEnvironment(path = "C:\\Go\\bin;D:\\tools", home = "C:\\Users\\me", goPath = null, goRoot = null, isWindows = true)
        assertEquals(
            listOf("C:\\Go\\bin\\go.exe", "D:\\tools\\go.exe", "C:\\Users\\me\\go\\bin\\go.exe"),
            ToolLookup.candidates(GoTool.GO, env),
        )
    }

    @Test
    fun `find returns the first executable candidate`() {
        val found = ToolLookup.find(GoTool.DLV, unix(path = "/a:/b")) { it == "/b/dlv" || it == "/Users/me/go/bin/dlv" }
        assertEquals("/b/dlv", found)
    }

    @Test
    fun `find falls back to home go bin when PATH has no dlv`() {
        val found = ToolLookup.find(GoTool.DLV, unix(path = "/usr/bin")) { it == "/Users/me/go/bin/dlv" }
        assertEquals("/Users/me/go/bin/dlv", found)
    }

    @Test
    fun `find returns null when nothing is executable`() {
        assertNull(ToolLookup.find(GoTool.GO, unix(path = "/usr/bin")) { false })
    }

    @Test
    fun `prependToPath puts the directory first`() {
        assertEquals("/sdk/go/bin:/usr/bin", ToolLookup.prependToPath("/usr/bin", "/sdk/go/bin", ':'))
    }

    @Test
    fun `prependToPath keeps PATH unchanged when the directory is already there`() {
        assertEquals("/usr/bin:/sdk/go/bin", ToolLookup.prependToPath("/usr/bin:/sdk/go/bin", "/sdk/go/bin", ':'))
    }

    @Test
    fun `prependToPath on missing or blank PATH yields null instead of a go-only PATH`() {
        assertNull(ToolLookup.prependToPath(null, "/sdk/go/bin", ':'))
        assertNull(ToolLookup.prependToPath(" : ", "/sdk/go/bin", ':'))
    }
}
