package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GoToolLookupGoBinTest {
    private val base = LookupEnvironment(path = "/usr/bin", home = "/Users/me", goPath = null, goRoot = null, isWindows = false)

    private fun goEnv(vararg values: Pair<String, String>) = GoEnv(mapOf(*values))

    @Test
    fun `GOBIN dirs come before PATH for installed tools`() {
        val env = base.copy(goBinDirs = listOf("/env/gobin")).withGoEnv(goEnv("GOBIN" to "/goenv/gobin"))
        val dirs = ToolLookup.candidateDirectories(GoTool.GOPLS, env)
        assertEquals(listOf("/env/gobin", "/goenv/gobin", "/usr/bin"), dirs.take(3))
    }

    @Test
    fun `GOBIN is not searched for go itself`() {
        val dirs = ToolLookup.candidateDirectories(GoTool.GO, base.copy(goBinDirs = listOf("/env/gobin")))
        assertEquals(false, "/env/gobin" in dirs)
    }

    @Test
    fun `gopls falls back to home go bin`() {
        val found = ToolLookup.find(GoTool.GOPLS, base) { it == "/Users/me/go/bin/gopls" }
        assertEquals("/Users/me/go/bin/gopls", found)
    }

    @Test
    fun `go env fills GOPATH and GOROOT only when the process env lacks them`() {
        val filled = base.withGoEnv(goEnv("GOPATH" to "/gp", "GOROOT" to "/sdk"))
        assertEquals("/gp", filled.goPath)
        assertEquals("/sdk", filled.goRoot)
        val kept = base.copy(goPath = "/mine", goRoot = "/mysdk").withGoEnv(goEnv("GOPATH" to "/gp", "GOROOT" to "/sdk"))
        assertEquals("/mine", kept.goPath)
        assertEquals("/mysdk", kept.goRoot)
    }

    @Test
    fun `same GOBIN from env and go env is listed once`() {
        val env = base.copy(goBinDirs = listOf("/gobin")).withGoEnv(goEnv("GOBIN" to "/gobin"))
        assertEquals(listOf("/gobin"), env.goBinDirs)
    }

    @Test
    fun `empty GOBIN from go env adds nothing`() {
        assertEquals(emptyList<String>(), base.withGoEnv(goEnv("GOBIN" to "")).goBinDirs)
    }

    @Test
    fun `null go env leaves the environment untouched`() {
        assertSame(base, base.withGoEnv(null))
    }
}
