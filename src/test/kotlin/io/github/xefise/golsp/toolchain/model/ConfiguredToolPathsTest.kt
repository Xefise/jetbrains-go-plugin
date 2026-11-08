package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredToolPathsTest {
    private val executable = setOf("/cfg/go", "/cfg/gopls", "/cfg/dlv")
    private val isExecutable: (String) -> Boolean = { it in executable }

    @Test
    fun `nothing configured means everything is looked up`() {
        assertEquals(ConfiguredToolPaths.NONE, ConfiguredToolPaths.of(null, null, null, null))
        assertEquals(ConfiguredToolPaths.NONE, ConfiguredToolPaths.of("", "  ", "\t", ""))
    }

    @Test
    fun `project go override wins over the application one`() {
        assertEquals("/p/go", ConfiguredToolPaths.of("/a/go", "/p/go", null, null).goBinary)
    }

    @Test
    fun `blank project override falls back to the application go`() {
        assertEquals("/a/go", ConfiguredToolPaths.of(" /a/go ", "  ", null, null).goBinary)
    }

    @Test
    fun `tool paths are trimmed`() {
        val paths = ConfiguredToolPaths.of(null, null, " /cfg/gopls ", "/cfg/dlv\n")
        assertEquals("/cfg/gopls", paths.pathFor(GoTool.GOPLS))
        assertEquals("/cfg/dlv", paths.pathFor(GoTool.DLV))
    }

    @Test
    fun `detected go wins and lookup is not consulted`() {
        val paths = ConfiguredToolPaths.of("/cfg/go", null, null, null)
        assertEquals("/detected/go", paths.resolve(GoTool.GO, "/detected/go", isExecutable) { error("no lookup") })
    }

    @Test
    fun `configured go is used before detection settles`() {
        val paths = ConfiguredToolPaths.of("/cfg/go", null, null, null)
        assertEquals("/cfg/go", paths.resolve(GoTool.GO, null, isExecutable) { "/usr/bin/go" })
    }

    @Test
    fun `broken configured go never falls back to another go`() {
        val paths = ConfiguredToolPaths.of("/missing/go", null, null, null)
        assertNull(paths.resolve(GoTool.GO, null, isExecutable) { "/usr/bin/go" })
    }

    @Test
    fun `unconfigured go is looked up`() {
        assertEquals("/usr/bin/go", ConfiguredToolPaths.NONE.resolve(GoTool.GO, null, isExecutable) { "/usr/bin/go" })
    }

    @Test
    fun `configured executable tool wins over lookup`() {
        val paths = ConfiguredToolPaths.of(null, null, "/cfg/gopls", null)
        assertEquals("/cfg/gopls", paths.resolve(GoTool.GOPLS, "/detected/go", isExecutable) { "/gobin/gopls" })
    }

    @Test
    fun `broken configured tool falls back to lookup`() {
        val paths = ConfiguredToolPaths.of(null, null, null, "/missing/dlv")
        assertEquals("/gobin/dlv", paths.resolve(GoTool.DLV, null, isExecutable) { "/gobin/dlv" })
    }

    @Test
    fun `detected go is ignored for other tools`() {
        assertNull(ConfiguredToolPaths.NONE.resolve(GoTool.DLV, "/detected/go", isExecutable) { null })
    }
}
