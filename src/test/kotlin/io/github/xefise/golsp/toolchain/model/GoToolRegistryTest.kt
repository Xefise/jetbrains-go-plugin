package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolRegistryTest {
    private val gopls = GoToolRegistry.GOPLS
    private val dlv = GoToolRegistry.DLV

    private fun install(spec: GoToolSpec, go: String) = GoToolRegistry.installVersion(spec, GoVersion.parse(go))
    private fun minimum(spec: GoToolSpec, go: String) = GoToolRegistry.minimumVersion(spec, GoVersion.parse(go))?.text

    @Test
    fun `gopls pins switch at go1_19 and go1_21`() {
        assertEquals("v0.14.2", install(gopls, "go1.18.10"))
        assertEquals("v0.15.3", install(gopls, "go1.19"))
        assertEquals("v0.15.3", install(gopls, "go1.20.14"))
        assertEquals("latest", install(gopls, "go1.21.0"))
        assertEquals("latest", install(gopls, "go1.27.1"))
    }

    @Test
    fun `release candidate of go1_21 is not pinned like go1_20`() {
        assertEquals("latest", install(gopls, "go1.21rc2"))
    }

    @Test
    fun `devel and unknown Go install latest`() {
        assertEquals("latest", install(gopls, "devel go1.18-abc Mon Jan 1"))
        assertEquals("latest", install(dlv, "devel go1.18-abc Mon Jan 1"))
        assertEquals("latest", install(dlv, "garbage"))
    }

    @Test
    fun `dlv pins follow the project Go minor`() {
        assertEquals("v1.20.2", install(dlv, "go1.18.10"))
        assertEquals("v1.22.1", install(dlv, "go1.19"))
        assertEquals("v1.22.1", install(dlv, "go1.20.14"))
        assertEquals("v1.25.0", install(dlv, "go1.21.13"))
        assertEquals("v1.25.2", install(dlv, "go1.22.0"))
        assertEquals("v1.25.2", install(dlv, "go1.23.12"))
        assertEquals("v1.26.3", install(dlv, "go1.24.5"))
        assertEquals("latest", install(dlv, "go1.25.0"))
        assertEquals("latest", install(dlv, "go1.28rc1"))
    }

    @Test
    fun `every dlv pin supports the Go it is chosen for`() {
        for (minor in 18..24) {
            val pin = ModuleVersion.parse(install(dlv, "go1.$minor.0"))!!
            val range = DelveSupport.supportedGoMinors(pin)!!
            assertTrue("dlv $pin for go1.$minor supports $range", minor in range)
        }
    }

    @Test
    fun `install target joins import path and version`() {
        assertEquals("github.com/go-delve/delve/cmd/dlv@v1.25.2", GoToolRegistry.installTarget(dlv, GoVersion.parse("go1.22.3")))
        assertEquals("golang.org/x/tools/gopls@latest", GoToolRegistry.installTarget(gopls, GoVersion.parse("go1.27.1")))
    }

    @Test
    fun `gopls minimum follows the old-Go pins and is absent from go1_21`() {
        assertEquals("v0.14.2", minimum(gopls, "go1.18"))
        assertEquals("v0.15.3", minimum(gopls, "go1.20.1"))
        assertNull(minimum(gopls, "go1.21.0"))
    }

    @Test
    fun `dlv minimum is the first release whose maximum covers the Go`() {
        assertEquals("v1.20.0", minimum(dlv, "go1.20.1"))
        assertEquals("v1.21.0", minimum(dlv, "go1.21.0"))
        assertEquals("v1.24.0", minimum(dlv, "go1.24.0"))
        assertEquals("v1.25.1", minimum(dlv, "go1.25.0"))
        assertEquals("v1.27.0", minimum(dlv, "go1.27.3"))
    }

    @Test
    fun `dlv minimum for a Go newer than known Delve is the newest known line`() {
        assertEquals("v1.27.0", minimum(dlv, "go1.28.0"))
    }

    @Test
    fun `go itself has no spec`() {
        assertNull(GoToolRegistry.spec(GoTool.GO))
        assertEquals(dlv, GoToolRegistry.spec(GoTool.DLV))
    }

    @Test
    fun `every pin is at or above the minimum for its Go and the newest minimum is released`() {
        for (spec in listOf(gopls, dlv)) {
            for (minor in 18..30) {
                val go = "go1.$minor.0"
                val pin = install(spec, go).takeIf { it != GoToolRegistry.LATEST } ?: continue
                val min = GoToolRegistry.minimumVersion(spec, GoVersion.parse(go)) ?: continue
                assertTrue("${spec.tool} pin $pin for $go is below $min", ModuleVersion.parse(pin)!! >= min)
            }
        }
        val newest = mapOf(gopls to "v0.24.0", dlv to "v1.27.2")
        for ((spec, latest) in newest) {
            GoToolRegistry.minimumVersion(spec, GoVersion.parse("go1.30.0"))?.let {
                assertTrue("${spec.tool} minimum $it exceeds $latest", it <= ModuleVersion.parse(latest)!!)
            }
        }
    }
}
