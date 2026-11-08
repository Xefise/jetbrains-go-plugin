package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleVersionTest {
    private fun v(text: String): ModuleVersion = requireNotNull(ModuleVersion.parse(text)) { text }

    @Test
    fun `release parses into numbers`() {
        assertEquals(ModuleVersion(0, 24, 0, emptyList(), "v0.24.0"), ModuleVersion.parse("v0.24.0"))
    }

    @Test
    fun `non semver strings are rejected`() {
        listOf("(devel)", "0.24.0", "v0.24", "latest", "", "v1.2.3-", "v01.2.3", "v1.2.3-01").forEach {
            assertNull(it, ModuleVersion.parse(it))
        }
        assertNull(ModuleVersion.parse(null))
    }

    @Test
    fun `build metadata is accepted and ignored for ordering`() {
        assertEquals(0, v("v2.0.0+incompatible").compareTo(v("v2.0.0")))
    }

    @Test
    fun `numbers compare numerically`() {
        assertTrue(v("v1.9.0") < v("v1.10.0"))
        assertTrue(v("v0.9.9") < v("v1.0.0"))
    }

    @Test
    fun `prerelease sorts before its release`() {
        assertTrue(v("v0.24.0-pre.3") < v("v0.24.0"))
        assertTrue(v("v0.24.0-pre.3") > v("v0.23.0"))
    }

    @Test
    fun `numeric prerelease identifiers compare numerically`() {
        assertTrue(v("v0.24.0-pre.2") < v("v0.24.0-pre.10"))
    }

    @Test
    fun `numeric identifier sorts before alphanumeric and shorter prefix sorts first`() {
        assertTrue(v("v1.0.0-1") < v("v1.0.0-alpha"))
        assertTrue(v("v1.0.0-alpha") < v("v1.0.0-alpha.1"))
    }

    @Test
    fun `pseudo-version after a release sits between it and the next patch`() {
        val pseudo = v("v0.24.1-0.20261010120000-abcdefabcdef")
        assertTrue(v("v0.24.0") < pseudo)
        assertTrue(pseudo < v("v0.24.1"))
    }

    @Test
    fun `dirty vcs-stamped pseudo-version parses`() {
        assertTrue(v("v0.24.1-0.20261010120000-abcdefabcdef+dirty").isPreRelease)
    }
}
