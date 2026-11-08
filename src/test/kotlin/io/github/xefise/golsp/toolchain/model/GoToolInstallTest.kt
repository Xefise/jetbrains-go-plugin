package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoToolInstallTest {
    private fun toolchain(install: String, project: String) = GoToolInstall.toolchain(GoVersion.parse(install), GoVersion.parse(project))

    @Test
    fun `command installs the pinned target from the neutral directory`() {
        val command = GoToolInstall.command(
            GoToolRegistry.DLV, "/usr/local/go/bin/go", GoVersion.parse("go1.27.3"), GoVersion.parse("go1.22.4"), "/tmp/golsp-tools",
        )
        assertEquals("/usr/local/go/bin/go", command.executable)
        assertEquals(listOf("install", "-v", "github.com/go-delve/delve/cmd/dlv@v1.25.2"), command.arguments)
        assertEquals(mapOf("GOTOOLCHAIN" to "auto"), command.environment)
        assertEquals(setOf("GOOS", "GOARCH", "GOROOT", "GOFLAGS", "GOENV", "GO111MODULE"), command.removedEnvironment)
        assertEquals("/tmp/golsp-tools", command.workingDirectory)
    }

    @Test
    fun `install target comes from go env because GOENV is unset`() {
        val env = GoEnv(mapOf("GOBIN" to "/custom/bin", "GOPATH" to "/custom/gopath", "GOROOT" to "/goroot"))
        val command = GoToolInstall.command(GoToolRegistry.GOPLS, "go", GoVersion.parse("go1.27.3"), GoVersion.parse("go1.27.3"), "/tmp/t", env)
        assertEquals(mapOf("GOTOOLCHAIN" to "auto", "GOBIN" to "/custom/bin", "GOPATH" to "/custom/gopath"), command.environment)
        val noBin = GoToolInstall.command(GoToolRegistry.GOPLS, "go", GoVersion.parse("go1.27.3"), GoVersion.parse("go1.27.3"), "/tmp/t", GoEnv(mapOf("GOBIN" to "")))
        assertEquals(mapOf("GOTOOLCHAIN" to "auto"), noBin.environment)
    }

    @Test
    fun `project on the same or an older Go uses plain auto`() {
        assertEquals("auto", toolchain("go1.27.3", "go1.27.3"))
        assertEquals("auto", toolchain("go1.27.3", "go1.25.0"))
    }

    @Test
    fun `project on a newer Go builds tools with at least that Go`() {
        assertEquals("go1.28.1+auto", toolchain("go1.27.3", "go1.28.1"))
        assertEquals("go1.27.4+auto", toolchain("go1.27.3", "go1.27.4"))
    }

    @Test
    fun `project on a release candidate newer than local keeps the rc name`() {
        assertEquals("go1.28rc1+auto", toolchain("go1.27.3", "go1.28rc1"))
    }

    @Test
    fun `local rc is older than the matching release`() {
        assertEquals("go1.28.0+auto", toolchain("go1.28rc2", "go1.28.0"))
    }

    @Test
    fun `devel or unknown on either side uses plain auto`() {
        assertEquals("auto", toolchain("devel go1.29-abc", "go1.28.0"))
        assertEquals("auto", toolchain("go1.27.3", "devel go1.29-abc"))
        assertEquals("auto", toolchain("", "go1.28.0"))
    }

    @Test
    fun `a project Go named without patch since go1_21 gets the dot-zero toolchain`() {
        assertEquals("go1.22.0+auto", toolchain("go1.21.5", "go1.22"))
        assertEquals("go1.22rc1+auto", toolchain("go1.21.5", "go1.22rc1"))
    }
}
