package io.github.xefise.golsp.toolchain.integration

import io.github.xefise.golsp.toolchain.GoProcessRunner
import io.github.xefise.golsp.toolchain.GoToolInstaller
import io.github.xefise.golsp.toolchain.GoToolProcesses
import io.github.xefise.golsp.toolchain.GoToolStatusService
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.GoToolchainSettings
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolState
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import org.junit.Assume
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files

class GoToolInstallIntegrationTest : GoIntegrationTestCase() {
    private lateinit var goBin: File

    override fun setUp() {
        Assume.assumeTrue("module proxy is unreachable", proxyReachable())
        super.setUp()
        goBin = Files.createTempDirectory("golsp-gobin").toFile()
        // The installer runs on the login-shell environment; GOBIN is redirected so the user's tools stay untouched.
        GoToolProcesses.runner = GoProcessRunner { commandLine, timeoutMs, indicator ->
            GoProcessRunner.DEFAULT.run(commandLine.withEnvironment("GOBIN", goBin.path), timeoutMs, indicator)
        }
        GoToolchainSettings.getInstance().update("", "", File(goBin, "dlv").path)
    }

    override fun beforeFixtureTearDown() {
        GoToolProcesses.runner = GoProcessRunner.DEFAULT
        GoToolProcesses.clearCaches()
        GoToolchainSettings.getInstance().loadState(GoToolchainSettings.Options())
        goBin.deleteRecursively()
    }

    fun testInstalledDlvIsReportedOk() {
        copyTestProject("hello")
        val toolchain = GoToolchainService.getInstance(project)
        toolchain.refresh()
        waitUntil("Go toolchain") { toolchain.state is GoToolchainState.Ready }
        val status = GoToolStatusService.getInstance(project)

        assertTrue(GoToolInstaller.getInstance().install(project, GoTool.DLV))
        val installed = File(goBin, "dlv").path
        // A dlv already in the user's GOBIN may be reported Ok before the install ends; only the new path counts.
        waitUntil("dlv installed and checked", INSTALL_TIMEOUT_SECONDS) {
            !GoToolInstaller.getInstance().isInstalling(GoTool.DLV) && status.status(GoTool.DLV)?.path == installed
        }
        val dlv = status.status(GoTool.DLV)!!
        assertEquals(GoToolState.Ok, dlv.state)
        assertTrue(dlv.version.orEmpty(), dlv.version.orEmpty().startsWith("v1."))
    }

    private fun proxyReachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("proxy.golang.org", 443), 3_000) }
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600
    }
}
