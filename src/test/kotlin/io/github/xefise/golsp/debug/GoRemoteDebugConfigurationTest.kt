package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.dap.DebugMode
import io.github.xefise.golsp.debug.model.DelveAttachTarget
import io.github.xefise.golsp.debug.model.SubstitutePathRule
import org.jdom.Element

class GoRemoteDebugConfigurationTest : BasePlatformTestCase() {
    private val factory by lazy { GoRemoteDebugConfigurationFactory(GoRemoteDebugConfigurationType()) }

    private fun configuration() = GoRemoteDebugConfiguration(project, factory, "remote")

    private fun descriptor(config: GoRemoteDebugConfiguration): GoDelveDescriptor {
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
        return GoDelveDescriptor(GoRemoteDebugOptions(), environment, null)
    }

    private fun checkError(config: GoRemoteDebugConfiguration): String? = try {
        config.checkConfiguration()
        null
    } catch (e: RuntimeConfigurationError) {
        e.localizedMessage
    }

    fun testDefaults() {
        val config = configuration()
        assertEquals("localhost", config.host)
        assertEquals(2345, config.port)
        assertEquals(emptyList<SubstitutePathRule>(), config.substitutePath)
        assertNull(checkError(config))
    }

    fun testOptionsSurviveWriteAndRead() {
        val rules = listOf(SubstitutePathRule("/home/me/app", "/app"), SubstitutePathRule("", "/go/src"), SubstitutePathRule("/a", "/b"))
        val saved = configuration().apply {
            host = "10.0.0.5"
            port = 40000
            substitutePath = rules
        }
        val element = Element("configuration")
        saved.writeExternal(element)
        val loaded = configuration().apply { readExternal(JDOMUtil.load(JDOMUtil.writeElement(element))) }
        assertEquals("10.0.0.5", loaded.host)
        assertEquals(40000, loaded.port)
        assertEquals(rules, loaded.substitutePath)
    }

    fun testConfigurationSavedWithoutOptionsKeepsDefaults() {
        val loaded = configuration().apply { readExternal(JDOMUtil.load("""<configuration name="r" type="GoLspRemoteDebug"/>""")) }
        assertEquals("localhost", loaded.host)
        assertEquals(2345, loaded.port)
        assertEquals(emptyList<SubstitutePathRule>(), loaded.substitutePath)
    }

    fun testCheckConfigurationErrors() {
        assertNotNull(checkError(configuration().apply { host = " " }))
        assertNotNull(checkError(configuration().apply { port = 0 }))
        assertNotNull(checkError(configuration().apply { port = 70000 }))
        assertNotNull(checkError(configuration().apply { substitutePath = listOf(SubstitutePathRule("", "")) }))
        assertNull(checkError(configuration().apply { substitutePath = listOf(SubstitutePathRule("/src", "")) }))
    }

    fun testHostIsTrimmedInTheTarget() {
        assertEquals(DelveAttachTarget.Remote("h", 1), configuration().apply { host = " h "; port = 1 }.attachTarget())
    }

    fun testRunExecutorIsRejected() {
        val config = configuration()
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), config).build()
        val error = try {
            config.getState(DefaultRunExecutor.getRunExecutorInstance(), environment)
            null
        } catch (e: ExecutionException) {
            e
        }
        assertTrue(error?.message, error?.message.orEmpty().contains("start it with Debug"))
    }

    fun testDescriptorAttachesToTheConfiguredServer() {
        val config = configuration().apply {
            host = "10.0.0.5"
            port = 40000
            substitutePath = listOf(SubstitutePathRule("/l", "/r"))
        }
        val descriptor = descriptor(config)
        assertEquals(DebugMode.ATTACH, descriptor.debugMode)
        val ready = descriptor.getServerReadyConfig(DebugMode.ATTACH)
        assertEquals("10.0.0.5", ready.address)
        assertEquals(40000, ready.port)
        assertNull(ready.debugServerReadyPattern)
        assertEquals(
            mapOf(
                "type" to "go",
                "request" to "attach",
                "mode" to "remote",
                "substitutePath" to listOf(mapOf("from" to "/l", "to" to "/r")),
            ),
            descriptor.dapParameters,
        )
        assertThrows(ExecutionException::class.java) { descriptor.startServer() }
    }

    private fun debugStateError(config: GoRemoteDebugConfiguration): ExecutionException? {
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, config).build()
        return try {
            config.getState(executor, environment)
            null
        } catch (e: ExecutionException) {
            e
        }
    }

    fun testInvalidTargetIsRejectedBeforeTheDescriptorIsUsed() {
        val error = debugStateError(configuration().apply { port = 0 })
        assertNotNull(error)
        assertTrue(error!!.message, error.message!!.contains("Port"))
    }

    fun testUntrustedProjectRefusesToDebug() {
        val previous = System.setProperty(TRUST_HEADLESS_DISABLED, "false")
        try {
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))
            val error = debugStateError(configuration())
            assertNotNull("getState must refuse an untrusted project", error)
            assertTrue(error!!.message, error.message!!.contains("untrusted"))
        } finally {
            TrustedProjects.setProjectTrusted(project, true)
            if (previous == null) System.clearProperty(TRUST_HEADLESS_DISABLED) else System.setProperty(TRUST_HEADLESS_DISABLED, previous)
        }
    }

    private companion object {
        const val TRUST_HEADLESS_DISABLED = "idea.trust.headless.disabled"
    }
}
