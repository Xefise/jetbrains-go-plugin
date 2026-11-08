package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolVersionCheckTest {
    private fun info(spec: GoToolSpec, version: String, builtWith: String = "go1.27.3", replacement: String? = null) =
        GoBinaryInfo(GoVersion.parse(builtWith), spec.importPath, spec.modulePath, version, replacement)

    private fun check(spec: GoToolSpec, info: GoBinaryInfo, go: String) = GoToolVersionCheck.check(spec, info, GoVersion.parse(go))

    private val gopls = GoToolRegistry.GOPLS
    private val dlv = GoToolRegistry.DLV

    @Test
    fun `current gopls built with the project Go is ok`() {
        assertEquals(GoToolVersionStatus.Ok, check(gopls, info(gopls, "v0.24.0"), "go1.27.1"))
    }

    @Test
    fun `gopls built with an older Go minor than the project is outdated`() {
        assertTrue(check(gopls, info(gopls, "v0.24.0", builtWith = "go1.26.5"), "go1.27.0") is GoToolVersionStatus.Outdated)
    }

    @Test
    fun `gopls built with an older patch of the same minor is ok`() {
        assertEquals(GoToolVersionStatus.Ok, check(gopls, info(gopls, "v0.24.0", builtWith = "go1.27.0"), "go1.27.3"))
    }

    @Test
    fun `gopls built with devel Go is ok`() {
        assertEquals(GoToolVersionStatus.Ok, check(gopls, info(gopls, "v0.24.0", builtWith = "devel go1.28-abc"), "go1.27.3"))
    }

    @Test
    fun `gopls below the old-Go pin is outdated and at the pin is ok`() {
        assertTrue(check(gopls, info(gopls, "v0.15.2", builtWith = "go1.20.14"), "go1.20.14") is GoToolVersionStatus.Outdated)
        assertEquals(GoToolVersionStatus.Ok, check(gopls, info(gopls, "v0.15.3", builtWith = "go1.20.14"), "go1.20.14"))
    }

    @Test
    fun `dlv older than the first release covering the Go is outdated`() {
        assertTrue(check(dlv, info(dlv, "v1.26.3"), "go1.27.0") is GoToolVersionStatus.Outdated)
        assertEquals(GoToolVersionStatus.Ok, check(dlv, info(dlv, "v1.27.0"), "go1.27.0"))
    }

    @Test
    fun `dlv 1_25_0 does not cover go1_25 but 1_25_1 does`() {
        assertTrue(check(dlv, info(dlv, "v1.25.0"), "go1.25.0") is GoToolVersionStatus.Outdated)
        assertEquals(GoToolVersionStatus.Ok, check(dlv, info(dlv, "v1.25.1"), "go1.25.0"))
    }

    @Test
    fun `dlv that dropped the project Go is too new`() {
        assertTrue(check(dlv, info(dlv, "v1.27.2"), "go1.24.5") is GoToolVersionStatus.TooNew)
        assertEquals(GoToolVersionStatus.Ok, check(dlv, info(dlv, "v1.27.2"), "go1.25.0"))
    }

    @Test
    fun `dlv newer than every known line is ok`() {
        assertEquals(GoToolVersionStatus.Ok, check(dlv, info(dlv, "v1.28.0"), "go1.28.0"))
    }

    @Test
    fun `newest known dlv is accepted for a Go newer than the table`() {
        assertEquals(GoToolVersionStatus.Ok, check(dlv, info(dlv, "v1.27.2"), "go1.28.0"))
    }

    @Test
    fun `dlv prerelease of the minimum release is outdated`() {
        assertTrue(check(dlv, info(dlv, "v1.27.0-rc.1"), "go1.27.0") is GoToolVersionStatus.Outdated)
    }

    @Test
    fun `Go older than any known Delve is never judged`() {
        assertEquals(GoToolVersionStatus.Unknown, check(dlv, info(dlv, "v1.20.2"), "go1.17.13"))
    }

    @Test
    fun `local replaced and foreign builds are unknown`() {
        assertEquals(GoToolVersionStatus.Unknown, check(gopls, info(gopls, "(devel)"), "go1.27.0"))
        assertEquals(GoToolVersionStatus.Unknown, check(dlv, info(dlv, "v1.20.0", replacement = "../delve"), "go1.27.0"))
        assertEquals(GoToolVersionStatus.Unknown, check(gopls, info(dlv, "v1.27.2"), "go1.27.0"))
    }

    @Test
    fun `devel and unknown project Go are unknown`() {
        assertEquals(GoToolVersionStatus.Unknown, check(dlv, info(dlv, "v1.20.0"), "devel go1.28-abc"))
        assertEquals(GoToolVersionStatus.Unknown, check(gopls, info(gopls, "v0.1.0"), ""))
    }

    @Test
    fun `gopls built by an old Go is not outdated when the installing Go cannot switch toolchains`() {
        val old = info(gopls, "v0.15.3", builtWith = "go1.19.4")
        assertEquals(GoToolVersionStatus.Unknown, GoToolVersionCheck.check(gopls, old, GoVersion.parse("go1.20.1"), GoVersion.parse("go1.19.4")))
        assertTrue(GoToolVersionCheck.check(gopls, old, GoVersion.parse("go1.20.1"), GoVersion.parse("go1.20.1")) is GoToolVersionStatus.Outdated)
        assertTrue(GoToolVersionCheck.check(gopls, info(gopls, "v0.24.0", builtWith = "go1.26.5"), GoVersion.parse("go1.27.0"), GoVersion.parse("go1.26.5")) is GoToolVersionStatus.Outdated)
    }
}
