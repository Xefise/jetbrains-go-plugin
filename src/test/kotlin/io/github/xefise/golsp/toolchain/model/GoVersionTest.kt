package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoVersionTest {
    private fun release(text: String): GoVersion.Release = GoVersion.parse(text) as GoVersion.Release

    @Test
    fun `full release parses into components`() {
        assertEquals(GoVersion.Release(1, 27, 1, null, "go1.27.1"), GoVersion.parse("go1.27.1"))
    }

    @Test
    fun `pre-1_21 release without patch has null patch`() {
        assertEquals(GoVersion.Release(1, 20, null, null, "go1.20"), GoVersion.parse("go1.20"))
    }

    @Test
    fun `release candidate keeps its prerelease tag`() {
        assertEquals(GoVersion.Release(1, 21, null, "rc2", "go1.21rc2"), GoVersion.parse("go1.21rc2"))
    }

    @Test
    fun `experiment suffix after a space is ignored`() {
        assertEquals(release("go1.22.0"), GoVersion.parse("go1.22.0 X:boringcrypto"))
    }

    @Test
    fun `whole go version line is accepted`() {
        assertEquals(release("go1.27.3"), GoVersion.parse("go version go1.27.3 darwin/arm64"))
    }

    @Test
    fun `devel build is devel`() {
        val text = "devel go1.24-cd294f55ca Mon Apr 1 20:28:41 2024 +0000"
        assertEquals(GoVersion.Devel(text), GoVersion.parse(text))
    }

    @Test
    fun `vendor build with dash suffix is devel`() {
        assertEquals(GoVersion.Devel("go1.23-20240317-RC00 cl/616607620"), GoVersion.parse("go1.23-20240317-RC00 cl/616607620"))
    }

    @Test
    fun `null blank and garbage are unknown`() {
        assertEquals(GoVersion.Unknown, GoVersion.parse(null))
        assertEquals(GoVersion.Unknown, GoVersion.parse("  "))
        assertEquals(GoVersion.Unknown, GoVersion.parse("1.27.1"))
        assertEquals(GoVersion.Unknown, GoVersion.parse("gox"))
    }

    @Test
    fun `release candidate sorts before the release`() {
        assertTrue(release("go1.21rc2") < release("go1.21.0"))
        assertTrue(release("go1.21rc2") > release("go1.20.14"))
    }

    @Test
    fun `beta sorts before rc and rc numbers compare numerically`() {
        assertTrue(release("go1.22beta1") < release("go1.22rc1"))
        assertTrue(release("go1.22rc2") < release("go1.22rc10"))
    }

    @Test
    fun `missing patch equals patch zero`() {
        assertEquals(0, release("go1.20").compareTo(release("go1.20.0")))
    }

    @Test
    fun `minor compares numerically not lexically`() {
        assertTrue(release("go1.9.7") < release("go1.10"))
    }

    @Test
    fun `isBefore compares major and minor only`() {
        assertTrue(release("go1.20.14").isBefore(1, 21))
        assertFalse(release("go1.21rc1").isBefore(1, 21))
        assertFalse(release("go1.21.0").isBefore(1, 21))
    }

    @Test
    fun `equality matches ordering`() {
        assertEquals(release("go1.20"), release("go1.20.0"))
        assertEquals(release("go1.20").hashCode(), release("go1.20.0").hashCode())
    }
}
