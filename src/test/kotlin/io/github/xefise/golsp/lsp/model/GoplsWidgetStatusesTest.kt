package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.toolchain.model.GoEnv
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolState
import io.github.xefise.golsp.toolchain.model.GoToolStatus
import io.github.xefise.golsp.toolchain.model.GoToolchain
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.NotFoundReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class GoplsWidgetStatusesTest {
    private val ready = GoToolchainState.Ready(GoToolchain("/usr/local/go/bin/go", GoEnv(emptyMap())))

    private fun gopls(state: GoToolState, version: String? = null) = GoToolStatus(GoTool.GOPLS, state, "/go/bin/gopls", version)

    private fun status(goFile: Boolean = true, started: Boolean = false, toolchain: GoToolchainState = ready, tool: GoToolStatus? = null) =
        GoplsWidgetStatuses.status(goFile, started, { toolchain }, { tool })

    @Test
    fun `no item and no toolchain read without a Go file and a running gopls`() {
        assertNull(GoplsWidgetStatuses.status(false, false, { fail("toolchain read"); ready }, { fail("status read"); null }))
    }

    @Test
    fun `Go file in the editor puts the item in the current file section`() {
        assertEquals(GoplsWidgetStatus(true, "gopls", false), status(goFile = true))
    }

    @Test
    fun `running gopls without a Go file puts the item in the other section`() {
        assertEquals(GoplsWidgetStatus(false, "gopls", false), status(goFile = false, started = true))
    }

    @Test
    fun `known version is shown`() {
        assertEquals("gopls v0.20.0", status(tool = gopls(GoToolState.Ok, "v0.20.0"))?.text)
    }

    @Test
    fun `blank version falls back to the bare name`() {
        assertEquals("gopls", status(tool = gopls(GoToolState.Unknown, " "))?.text)
    }

    @Test
    fun `missing gopls is an error`() {
        assertEquals(GoplsWidgetStatus(true, "gopls not found", true), status(tool = GoToolStatus(GoTool.GOPLS, GoToolState.Missing, null, null)))
    }

    @Test
    fun `missing Go is an error`() {
        val notFound = GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "go not found")
        assertEquals(GoplsWidgetStatus(true, "gopls (Go not found)", true), status(toolchain = notFound))
    }

    @Test
    fun `untrusted project is shown but not as an error`() {
        val untrusted = GoToolchainState.NotFound(NotFoundReason.PROJECT_NOT_TRUSTED, "not trusted")
        assertEquals(GoplsWidgetStatus(true, "gopls (project not trusted)", false), status(toolchain = untrusted))
    }

    @Test
    fun `go env failure does not hide a found gopls`() {
        val failed = GoToolchainState.NotFound(NotFoundReason.GO_ENV_FAILED, "go env failed")
        assertEquals(GoplsWidgetStatus(true, "gopls", false), status(toolchain = failed))
    }

    @Test
    fun `installing gopls is not an error`() {
        assertEquals(GoplsWidgetStatus(true, "gopls (installing)", false), status(tool = gopls(GoToolState.Installing, "v0.19.0")))
    }
}
