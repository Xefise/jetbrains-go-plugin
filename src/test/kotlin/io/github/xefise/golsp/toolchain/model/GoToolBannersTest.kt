package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolBannersTest {
    private val ready = GoToolchainState.Ready(GoToolchain("/usr/local/go/bin/go", GoEnv(mapOf("GOVERSION" to "go1.27.3"))))
    private val none: (GoTool) -> Boolean = { false }

    private fun snapshot(gopls: GoToolState?, dlv: GoToolState?) = GoToolStatusSnapshot(
        listOfNotNull(
            gopls?.let { GoTool.GOPLS to GoToolStatus(GoTool.GOPLS, it, "/bin/gopls", "v0.24.0") },
            dlv?.let { GoTool.DLV to GoToolStatus(GoTool.DLV, it, "/bin/dlv", "v1.27.0") },
        ).toMap(),
    )

    private fun decide(gopls: GoToolState?, dlv: GoToolState?, hidden: (GoTool) -> Boolean = none) =
        GoToolBanners.decide(ready, snapshot(gopls, dlv), hidden)

    @Test
    fun `go file names are recognised by extension or exact module file name`() {
        assertTrue(GoToolBanners.isGoFileName("main.go"))
        assertTrue(GoToolBanners.isGoFileName("go.mod"))
        assertTrue(GoToolBanners.isGoFileName("go.work"))
        assertFalse(GoToolBanners.isGoFileName("go.sum"))
        assertFalse(GoToolBanners.isGoFileName("main.go.txt"))
        assertFalse(GoToolBanners.isGoFileName("my.go.mod"))
    }

    @Test
    fun `go not found shows its message`() {
        val state = GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "Go executable not found")
        assertEquals(GoToolBanner.GoNotFound("Go executable not found"), GoToolBanners.decide(state, GoToolStatusSnapshot.NOT_CHECKED, none))
    }

    @Test
    fun `go env failure and timeout also offer specifying go`() {
        listOf(NotFoundReason.GO_ENV_FAILED, NotFoundReason.GO_ENV_TIMEOUT).forEach {
            assertEquals(GoToolBanner.GoNotFound("m"), GoToolBanners.decide(GoToolchainState.NotFound(it, "m"), GoToolStatusSnapshot.NOT_CHECKED, none))
        }
    }

    @Test
    fun `untrusted project and detecting show nothing`() {
        val untrusted = GoToolchainState.NotFound(NotFoundReason.PROJECT_NOT_TRUSTED, "not trusted")
        assertNull(GoToolBanners.decide(untrusted, snapshot(GoToolState.Missing, null), none))
        assertNull(GoToolBanners.decide(GoToolchainState.Detecting, snapshot(GoToolState.Missing, GoToolState.Missing), none))
    }

    @Test
    fun `hidden go banner shows nothing even though tools cannot be checked`() {
        val state = GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "m")
        assertNull(GoToolBanners.decide(state, GoToolStatusSnapshot.NOT_CHECKED) { it == GoTool.GO })
    }

    @Test
    fun `missing gopls wins over missing dlv`() {
        assertEquals(GoToolBanner.Install(GoTool.GOPLS), decide(GoToolState.Missing, GoToolState.Missing))
    }

    @Test
    fun `missing dlv is shown once gopls is fine`() {
        assertEquals(GoToolBanner.Install(GoTool.DLV), decide(GoToolState.Ok, GoToolState.Missing))
    }

    @Test
    fun `installing gopls hides the dlv banner`() {
        assertEquals(GoToolBanner.Installing(GoTool.GOPLS), decide(GoToolState.Installing, GoToolState.Missing))
    }

    @Test
    fun `outdated and too new offer an update with the reason`() {
        assertEquals(GoToolBanner.Update(GoTool.GOPLS, "old"), decide(GoToolState.Outdated("old"), GoToolState.Ok))
        assertEquals(GoToolBanner.Update(GoTool.DLV, "too new"), decide(GoToolState.Ok, GoToolState.TooNew("too new")))
    }

    @Test
    fun `ok and unrecognised tools show nothing`() {
        assertNull(decide(GoToolState.Ok, GoToolState.Unknown))
        assertNull(decide(GoToolState.Unknown, GoToolState.Ok))
    }

    @Test
    fun `unchecked tools show nothing`() {
        assertNull(decide(null, null))
    }

    @Test
    fun `hidden gopls falls through to dlv`() {
        assertEquals(GoToolBanner.Install(GoTool.DLV), decide(GoToolState.Missing, GoToolState.Missing) { it == GoTool.GOPLS })
    }

    @Test
    fun `manual install is allowed for missing and recognised tools only`() {
        assertTrue(GoToolTexts.canInstallManually(GoToolStatus(GoTool.GOPLS, GoToolState.Missing, null, null)))
        assertTrue(GoToolTexts.canInstallManually(GoToolStatus(GoTool.GOPLS, GoToolState.Ok, "/g", "v1")))
        assertFalse(GoToolTexts.canInstallManually(GoToolStatus(GoTool.GOPLS, GoToolState.Unknown, "/g", null)))
        assertFalse(GoToolTexts.canInstallManually(GoToolStatus(GoTool.GOPLS, GoToolState.Installing, null, null)))
        assertFalse(GoToolTexts.canInstallManually(null))
    }

    @Test
    fun `button says install only for a missing tool`() {
        assertEquals("Install", GoToolTexts.buttonText(GoToolStatus(GoTool.DLV, GoToolState.Missing, null, null)))
        assertEquals("Update", GoToolTexts.buttonText(GoToolStatus(GoTool.DLV, GoToolState.Outdated("r"), "/d", "v1")))
    }
}
