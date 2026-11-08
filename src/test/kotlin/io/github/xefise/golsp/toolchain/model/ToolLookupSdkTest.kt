package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolLookupSdkTest {
    private fun unix(sdk: List<String>, home: String? = "/Users/me") =
        LookupEnvironment(path = "/usr/bin", home = home, goPath = null, goRoot = null, isWindows = false, sdkDirNames = lazyOf(sdk))

    @Test
    fun `versions sort numerically, not lexically`() {
        assertEquals(
            listOf("go1.22.10", "go1.22.9", "go1.9.7"),
            ToolLookup.newestFirstSdkDirs(listOf("go1.9.7", "go1.22.10", "go1.22.9")),
        )
    }

    @Test
    fun `release beats rc beats beta of the same version, and a later minor beats them all`() {
        assertEquals(
            listOf("go1.23rc1", "go1.22.0", "go1.22rc2", "go1.22rc1", "go1.22beta1"),
            ToolLookup.newestFirstSdkDirs(listOf("go1.22beta1", "go1.22rc1", "go1.22.0", "go1.23rc1", "go1.22rc2")),
        )
    }

    @Test
    fun `old two-part names count as patch zero`() {
        assertEquals(listOf("go1.20.1", "go1.20"), ToolLookup.newestFirstSdkDirs(listOf("go1.20", "go1.20.1")))
    }

    @Test
    fun `gotip, stray files and malformed names are ignored`() {
        assertEquals(
            listOf("go1.21.0"),
            ToolLookup.newestFirstSdkDirs(listOf("gotip", "go1.21.0", ".DS_Store", "go1.21.0.tar.gz", "go", "go1.x", "go99999.1")),
        )
    }

    @Test
    fun `sdk directories come last for go, newest first`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.GO, unix(listOf("go1.21.5", "go1.23.1")))
        assertEquals(listOf("/Users/me/sdk/go1.23.1/bin", "/Users/me/sdk/go1.21.5/bin"), dirs.takeLast(2))
        assertEquals("/usr/local/bin", dirs[dirs.size - 3])
    }

    @Test
    fun `an incomplete newest sdk falls through to the next one`() {
        val found = ToolLookup.find(GoTool.GO, unix(listOf("go1.23.1", "go1.21.5"))) { it == "/Users/me/sdk/go1.21.5/bin/go" }
        assertEquals("/Users/me/sdk/go1.21.5/bin/go", found)
    }

    @Test
    fun `sdk directories are not searched for gopls or dlv`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.GOPLS, unix(listOf("go1.23.1")))
        assertFalse(dirs.any { "/sdk/" in it })
    }

    @Test
    fun `no home means no sdk directories`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.GO, unix(listOf("go1.23.1"), home = null))
        assertFalse(dirs.any { "sdk" in it })
    }

    @Test
    fun `windows sdk path uses backslashes and exe`() {
        val env = LookupEnvironment(path = null, home = "C:\\Users\\me", goPath = null, goRoot = null, isWindows = true, sdkDirNames = lazyOf(listOf("go1.22.1")))
        assertEquals("C:\\Users\\me\\sdk\\go1.22.1\\bin\\go.exe", ToolLookup.candidates(GoTool.GO, env).last())
    }

    @Test
    fun `equal versions are ordered by name, so the order is stable`() {
        assertEquals(listOf("go1.22", "go1.22.0"), ToolLookup.newestFirstSdkDirs(listOf("go1.22.0", "go1.22")))
        assertEquals(listOf("go1.22", "go1.22.0"), ToolLookup.newestFirstSdkDirs(listOf("go1.22", "go1.22.0")))
    }

    @Test
    fun `sdk is not listed when go is found earlier or a tool is looked up`() {
        val env = LookupEnvironment(path = "/usr/bin", home = "/Users/me", goPath = null, goRoot = null, isWindows = false,
            sdkDirNames = lazy { error("~/sdk must not be listed") })
        assertEquals("/usr/bin/go", ToolLookup.find(GoTool.GO, env) { it == "/usr/bin/go" })
        assertEquals(null, ToolLookup.find(GoTool.DLV, env) { false })
    }
}
