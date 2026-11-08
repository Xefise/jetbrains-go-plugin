package io.github.xefise.golsp.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolState
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import java.io.File
import java.nio.file.Files

class GoToolInstallerTest : BasePlatformTestCase() {
    private lateinit var dir: File
    private val commands = mutableListOf<GeneralCommandLine>()
    private val notifications = mutableListOf<Notification>()
    private var installs = 0
    private var onInstall: (GeneralCommandLine) -> ProcessOutput = { output(0) }
    private var onVersion: (String) -> ProcessOutput = { output(0, VERSION_M.replace("BIN", it)) }

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("golsp-installer-test").toFile()
        val go = executable("go", """echo '{"GOROOT":"/fake/goroot","GOVERSION":"go1.27.3"}'""")
        GoToolProcesses.clearCaches()
        GoToolProcesses.runner = GoProcessRunner { commandLine, _, _ ->
            synchronized(commands) { commands += commandLine }
            when (commandLine.parametersList.list.firstOrNull()) {
                "install" -> onInstall(commandLine).also { installs++ }
                "env" -> output(0, "go1.27.3\n")
                "version" -> onVersion(commandLine.parametersList.list.last())
                else -> output(2, stderr = "unexpected")
            }
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
        GoToolchainSettings.getInstance().update(go.path, executable("gopls", "exit 0").path, "")
        GoToolchainService.getInstance(project).refresh()
        waitUntil("toolchain ready") { GoToolchainService.getInstance(project).state is GoToolchainState.Ready }
    }

    override fun tearDown() {
        try {
            GoToolProcesses.runner = GoProcessRunner.DEFAULT
            GoToolProcesses.clearCaches()
            GoToolchainSettings.getInstance().loadState(GoToolchainSettings.Options())
            dir.deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testSecondInstallOfTheSameToolIsRejectedWhileTheFirstRuns() {
        val installer = GoToolInstaller.getInstance()
        var nested: Boolean? = null
        var otherTool: Boolean? = null
        var installingDuringRun = false
        var calls = 0
        onInstall = {
            installingDuringRun = installer.isInstalling(GoTool.GOPLS)
            // Guarded: without dedupe the nested call would recurse into this fake again.
            if (calls++ == 0) nested = installer.install(project, GoTool.GOPLS)
            otherTool = installer.isInstalling(GoTool.DLV)
            output(0)
        }
        assertTrue(installer.install(project, GoTool.GOPLS))
        assertEquals(false, nested)
        assertEquals(false, otherTool)
        assertTrue(installingDuringRun)
        assertEquals(1, installs)
        assertFalse(installer.isInstalling(GoTool.GOPLS))
    }

    fun testInstallRunsInANeutralDirectoryWithToolInstallEnvironment() {
        var workDir: File? = null
        onInstall = { workDir = it.workDirectory; assertTrue(it.workDirectory.isDirectory); output(0) }
        assertTrue(GoToolInstaller.getInstance().install(project, GoTool.DLV))
        val install = commands.single { it.parametersList.list.firstOrNull() == "install" }
        assertEquals(listOf("install", "-v", "github.com/go-delve/delve/cmd/dlv@latest"), install.parametersList.list)
        assertEquals("auto", install.environment["GOTOOLCHAIN"])
        assertFalse(install.environment.containsKey("GOFLAGS"))
        assertEquals(GeneralCommandLine.ParentEnvironmentType.NONE, install.parentEnvironmentType)
        assertFalse("temp directory is removed", workDir!!.exists())
        assertFalse(workDir!!.startsWith(File(project.basePath ?: "/nonexistent")))
    }

    fun testFailedInstallNotifiesWithTheEscapedStderrTail() {
        onInstall = { output(1, stderr = "go: downloading x\ngo: module <bad> not found\n") }
        assertTrue(GoToolInstaller.getInstance().install(project, GoTool.GOPLS))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val error = notifications.single { it.title == "Cannot install gopls" }
        assertTrue(error.content, error.content.contains("module &lt;bad&gt; not found"))
        assertFalse(GoToolInstaller.getInstance().isInstalling(GoTool.GOPLS))
    }

    fun testSuccessfulInstallPublishesTheInstalledTool() {
        val installed = mutableListOf<GoTool>()
        project.messageBus.connect(testRootDisposable).subscribe(GoToolInstallListener.TOPIC, GoToolInstallListener { installed += it })
        assertTrue(GoToolInstaller.getInstance().install(project, GoTool.GOPLS))
        assertEquals(listOf(GoTool.GOPLS), installed)
    }

    fun testFailedInstallPublishesNothing() {
        val installed = mutableListOf<GoTool>()
        project.messageBus.connect(testRootDisposable).subscribe(GoToolInstallListener.TOPIC, GoToolInstallListener { installed += it })
        onInstall = { output(1, stderr = "boom") }
        GoToolInstaller.getInstance().install(project, GoTool.GOPLS)
        assertEquals(emptyList<GoTool>(), installed)
    }

    fun testStatusOfConfiguredGoplsComesFromGoVersionM() {
        val service = GoToolStatusService.getInstance(project)
        service.refresh()
        waitUntil("gopls status") { service.status(GoTool.GOPLS) != null }
        val status = service.status(GoTool.GOPLS)!!
        assertEquals(GoToolState.Ok, status.state)
        assertEquals("v0.24.0", status.version)
        assertEquals(File(dir, "gopls").path, status.path)
    }

    fun testUnchangedBinaryIsInspectedOnce() {
        val binary = executable("other", "exit 0").path
        assertEquals("v0.24.0", GoToolProcesses.binaryInfo("go", binary)?.moduleVersion)
        GoToolProcesses.binaryInfo("go", binary)
        // The background status check inspects gopls concurrently; only this binary is counted.
        assertEquals(1, synchronized(commands) { commands.count { it.parametersList.list.lastOrNull() == binary } })
    }

    fun testFailedInspectionIsRetriedNotCached() {
        val binary = executable("broken", "exit 0").path
        onVersion = { output(1, stderr = "could not read Go build info") }
        assertNull(GoToolProcesses.binaryInfo("go", binary))
        assertNull(GoToolProcesses.binaryInfo("go", binary))
        assertEquals(2, synchronized(commands) { commands.count { it.parametersList.list.lastOrNull() == binary } })
    }

    fun testInstallingClearsOnlyWithTheRecheckedStatus() {
        val service = GoToolStatusService.getInstance(project)
        service.refresh()
        waitUntil("gopls status") { service.status(GoTool.GOPLS) != null }
        val seen = mutableListOf<GoToolState>()
        val versions = mutableListOf<String?>()
        project.messageBus.connect(testRootDisposable).subscribe(GoToolStatusListener.TOPIC, GoToolStatusListener { snapshot ->
            snapshot.status(GoTool.GOPLS)?.let { synchronized(seen) { seen += it.state; versions += it.version } }
        })
        onInstall = {
            File(dir, "gopls").appendText("# rebuilt\n")
            onVersion = { output(0, VERSION_M.replace("BIN", it).replace("v0.24.0", "v0.25.0")) }
            output(0)
        }
        assertTrue(GoToolInstaller.getInstance().install(project, GoTool.GOPLS))
        val firstSettled = synchronized(seen) { seen.indexOfFirst { it != GoToolState.Installing } }
        assertTrue(seen.toString(), firstSettled > 0)
        assertEquals("v0.25.0", versions[firstSettled])
    }

    private fun executable(name: String, body: String): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }

    private fun output(exitCode: Int, stdout: String = "", stderr: String = "") =
        ProcessOutput(stdout, stderr, exitCode, false, false)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    companion object {
        private const val VERSION_M = "BIN: go1.27.3\n\tpath\tgolang.org/x/tools/gopls\n\tmod\tgolang.org/x/tools/gopls\tv0.24.0\th1:abc=\n"
    }
}
