package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolchainSettingTest {
    private val goRootEnv = "# This file contains the initial defaults for go command configuration.\nGOPROXY=https://proxy.golang.org,direct\nGOTOOLCHAIN=auto\n"

    @Test
    fun `process value wins over both files`() {
        assertEquals("local", GoToolchainSetting.effective("local", "GOTOOLCHAIN=go1.27.0\n", goRootEnv))
    }

    @Test
    fun `empty process value falls back to the user file`() {
        assertEquals("local", GoToolchainSetting.effective("", "GOTOOLCHAIN=local\n", goRootEnv))
    }

    @Test
    fun `user file wins over GOROOT go env`() {
        assertEquals("go1.27.0+auto", GoToolchainSetting.effective(null, "GOPATH=/x\nGOTOOLCHAIN=go1.27.0+auto\n", goRootEnv))
    }

    @Test
    fun `an empty value in the user file still shadows GOROOT go env`() {
        assertEquals("", GoToolchainSetting.effective(null, "GOTOOLCHAIN=\n", goRootEnv))
    }

    @Test
    fun `GOROOT go env is the default`() {
        assertEquals("auto", GoToolchainSetting.effective(null, null, goRootEnv))
        assertEquals("auto", GoToolchainSetting.effective(null, "GOPATH=/x\n", goRootEnv))
    }

    @Test
    fun `nothing set gives null`() {
        assertNull(GoToolchainSetting.effective(null, null, null))
        assertNull(GoToolchainSetting.effective(null, "", "GOPROXY=off\n"))
    }

    @Test
    fun `env file skips comments, lowercase keys and lines without equals`() {
        val parsed = GoToolchainSetting.parseEnvFile("# GOTOOLCHAIN=local\n gotoolchain=local\nGOTOOLCHAIN\n\nGOFLAGS=-mod=mod\n")
        assertEquals(mapOf("GOFLAGS" to "-mod=mod"), parsed)
    }

    @Test
    fun `env file takes values verbatim and the last duplicate wins`() {
        val parsed = GoToolchainSetting.parseEnvFile("GOTOOLCHAIN=auto\nGOTOOLCHAIN= local\n")
        assertEquals(" local", parsed["GOTOOLCHAIN"])
        assertFalse(GoToolchainSetting.neverSwitches(parsed["GOTOOLCHAIN"]))
    }

    @Test
    fun `only local never switches`() {
        assertTrue(GoToolchainSetting.neverSwitches("local"))
        listOf(null, "", "auto", "path", "local+auto", "go1.27.0", "go1.27.0+auto", "LOCAL").forEach {
            assertFalse("'$it' may switch", GoToolchainSetting.neverSwitches(it))
        }
    }
}
