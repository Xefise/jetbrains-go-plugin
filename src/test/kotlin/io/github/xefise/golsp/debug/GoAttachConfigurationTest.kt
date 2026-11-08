package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.VirtualConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThreeState
import com.redhat.devtools.lsp4ij.dap.DebugMode
import io.github.xefise.golsp.debug.model.GoBinaryFixtures
import org.jdom.Element
import java.nio.file.Files

class GoAttachConfigurationTest : BasePlatformTestCase() {
    private val factory by lazy { GoAttachConfigurationFactory(GoAttachConfigurationType()) }

    private fun configuration(pid: Int = 4242, port: Int = 50123) =
        GoAttachConfiguration(project, factory, "attach").apply {
            processId = pid
            this.port = port
        }

    private fun process(path: String?, owned: ThreeState = ThreeState.YES) =
        ProcessInfo(4242, "server", "server", "", path, 1, "me", owned)

    private fun goBinary(): String =
        Files.createTempFile("go-attach", "").also {
            Files.write(it, GoBinaryFixtures.elf())
            it.toFile().deleteOnExit()
        }.toString()

    fun testTypeIsHiddenFromTheConfigurationsDialog() {
        assertInstanceOf(GoAttachConfigurationType(), VirtualConfigurationType::class.java)
    }

    fun testOptionsSurviveWriteAndRead() {
        val element = Element("configuration")
        configuration(pid = 777, port = 40001).writeExternal(element)
        val loaded = GoAttachConfiguration(project, factory, "x").apply { readExternal(JDOMUtil.load(JDOMUtil.writeElement(element))) }
        assertEquals(777, loaded.processId)
        assertEquals(40001, loaded.port)
    }

    fun testConfigurationSavedWithoutOptionsHasDefaults() {
        val loaded = GoAttachConfiguration(project, factory, "x").apply { readExternal(JDOMUtil.load("""<configuration name="a" type="GoLspAttach"/>""")) }
        assertEquals(0, loaded.processId)
        assertEquals(0, loaded.port)
    }

    fun testDescriptorAttachesLocallyThroughTheLoopbackServer() {
        val config = configuration()
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
        val descriptor = GoDelveDescriptor(GoAttachOptions(), environment, null)
        assertEquals(DebugMode.ATTACH, descriptor.debugMode)
        val ready = descriptor.getServerReadyConfig(DebugMode.ATTACH)
        assertEquals("127.0.0.1", ready.address)
        assertEquals(50123, ready.port)
        assertEquals(
            mapOf("type" to "go", "request" to "attach", "mode" to "local", "processId" to 4242),
            descriptor.dapParameters,
        )
    }

    fun testRunExecutorIsRejected() {
        val config = configuration()
        val run = DefaultRunExecutor.getRunExecutorInstance()
        assertThrows(ExecutionException::class.java) { config.getState(run, ExecutionEnvironmentBuilder.create(project, run, config).build()) }
    }

    fun testInvalidTargetIsRejectedAtLaunch() {
        val debug = DefaultDebugExecutor.getDebugExecutorInstance()
        for (config in listOf(configuration(port = 0), configuration(pid = 0))) {
            assertThrows(ExecutionException::class.java) { config.getState(debug, ExecutionEnvironmentBuilder.create(project, debug, config).build()) }
        }
    }

    fun testGoBinaryOwnedByTheUserIsOffered() {
        assertTrue(GoAttachDebuggerProvider.isGoProcess(process(goBinary()), UserDataHolderBase()))
    }

    fun testGoBinaryWithUnknownOwnerIsOffered() {
        assertTrue(GoAttachDebuggerProvider.isGoProcess(process(goBinary(), ThreeState.UNSURE), UserDataHolderBase()))
    }

    fun testProcessOfAnotherUserIsNotOffered() {
        assertFalse(GoAttachDebuggerProvider.isGoProcess(process(goBinary(), ThreeState.NO), UserDataHolderBase()))
    }

    fun testNonGoBinaryIsNotOffered() {
        val text = Files.createTempFile("not-go", "").also { Files.writeString(it, "#!/bin/sh\n") }.toString()
        assertFalse(GoAttachDebuggerProvider.isGoProcess(process(text), UserDataHolderBase()))
    }

    fun testProcessWithoutExecutablePathIsNotOffered() {
        assertFalse(GoAttachDebuggerProvider.isGoProcess(process(null), UserDataHolderBase()))
    }

    fun testDetectionIsCachedPerActionInvocation() {
        val binary = goBinary()
        val holder = UserDataHolderBase()
        assertTrue(GoAttachDebuggerProvider.isGoProcess(process(binary), holder))
        Files.writeString(java.nio.file.Path.of(binary), "not go any more")
        assertTrue(GoAttachDebuggerProvider.isGoProcess(process(binary), holder))
        assertFalse(GoAttachDebuggerProvider.isGoProcess(process(binary), UserDataHolderBase()))
    }
}
