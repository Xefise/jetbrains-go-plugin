package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolStatusesTest {
    private val gopls = GoToolRegistry.GOPLS
    private val dlv = GoToolRegistry.DLV
    private val go127 = GoVersion.parse("go1.27.3")

    private fun info(spec: GoToolSpec, version: String, builtWith: String = "go1.27.3") =
        GoBinaryInfo(GoVersion.parse(builtWith), spec.importPath, spec.modulePath, version)

    @Test
    fun `no path is missing and needs install`() {
        val status = GoToolStatuses.evaluate(gopls, null, null, go127, go127)
        assertEquals(GoToolStatus(GoTool.GOPLS, GoToolState.Missing, null, null), status)
        assertTrue(status.needsInstall)
        assertFalse(status.canUpdate)
    }

    @Test
    fun `found but unreadable is unknown and never offered for install`() {
        val status = GoToolStatuses.evaluate(gopls, "/bin/gopls", null, go127, go127)
        assertEquals(GoToolStatus(GoTool.GOPLS, GoToolState.Unknown, "/bin/gopls", null), status)
        assertFalse(status.needsInstall)
    }

    @Test
    fun `current gopls is ok with its printed version`() {
        assertEquals(
            GoToolStatus(GoTool.GOPLS, GoToolState.Ok, "/bin/gopls", "v0.24.0"),
            GoToolStatuses.evaluate(gopls, "/bin/gopls", info(gopls, "v0.24.0"), go127, go127),
        )
    }

    @Test
    fun `old dlv is outdated and can be updated`() {
        val status = GoToolStatuses.evaluate(dlv, "/bin/dlv", info(dlv, "v1.24.0"), go127, go127)
        assertTrue(status.state is GoToolState.Outdated)
        assertTrue(status.canUpdate)
        assertTrue(status.needsInstall)
    }

    @Test
    fun `dlv newer than the project Go supports is too new`() {
        val status = GoToolStatuses.evaluate(dlv, "/bin/dlv", info(dlv, "v1.27.0"), GoVersion.parse("go1.22.5"), go127)
        assertTrue(status.state is GoToolState.TooNew)
        assertTrue(status.canUpdate)
    }

    @Test
    fun `installing overlays only the installing tool`() {
        val ok = GoToolStatus(GoTool.GOPLS, GoToolState.Ok, "/bin/gopls", "v0.24.0")
        val missing = GoToolStatus(GoTool.DLV, GoToolState.Missing, null, null)
        val snapshot = GoToolStatusSnapshot(mapOf(GoTool.GOPLS to ok, GoTool.DLV to missing))
        val overlaid = snapshot.withInstalling { it == GoTool.DLV }
        assertEquals(ok, overlaid.status(GoTool.GOPLS))
        assertEquals(GoToolStatus(GoTool.DLV, GoToolState.Installing, null, null), overlaid.status(GoTool.DLV))
        assertSame(snapshot, snapshot.withInstalling { false })
    }

    @Test
    fun `not checked snapshot stays empty while installing`() {
        assertEquals(null, GoToolStatusSnapshot.NOT_CHECKED.withInstalling { true }.status(GoTool.GOPLS))
    }

    @Test
    fun `output tail keeps the last non-blank lines within the limit`() {
        val output = (1..20).joinToString("\n") { "line $it" } + "\n\n"
        assertEquals("line 18\nline 19\nline 20", GoToolStatuses.outputTail(output, maxLines = 3))
        assertEquals("20", GoToolStatuses.outputTail(output, maxLines = 3, maxChars = 2))
        assertEquals("", GoToolStatuses.outputTail(""))
    }
}
