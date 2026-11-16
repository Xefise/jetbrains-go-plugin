package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.redhat.devtools.lsp4ij.dap.configurations.DAPCommandLineState
import io.github.xefise.golsp.test.GoTestConfiguration
import io.github.xefise.golsp.test.GoTestConfigurationFactory
import io.github.xefise.golsp.test.GoTestConfigurationType
import io.github.xefise.golsp.test.GoTestScope
import java.io.File

class GoTestDebugStateTest : BasePlatformTestCase() {
    private val tempDir: File by lazy { FileUtil.createTempDirectory("go-test-debug", null) }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(configure: GoTestConfiguration.() -> Unit) =
        GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "t").apply(configure)

    // DAPDebugRunner casts the state to DAPCommandLineState; the parameters come from our Delve descriptor.
    private fun debugState(config: GoTestConfiguration): Any? {
        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        val env = ExecutionEnvironmentBuilder.create(project, executor, config).build()
        return config.getState(executor, env)
    }

    private fun dapParameters(config: GoTestConfiguration): Map<String, Any> {
        val state = debugState(config)
        assertTrue("state: $state", state is DAPCommandLineState)
        return (state as DAPCommandLineState).dapParameters
    }

    private val packageDir: String get() = tempDir.toPath().normalize().toString()

    fun testPackageScopeDebugsTheSelectedSubtestInDlvTestMode() {
        val config = configuration {
            scope = GoTestScope.PACKAGE
            directory = tempDir.path
            testNames = listOf("TestSubtests/with space")
            testArguments = "-test.v"
            buildFlags = "-tags=x"
        }
        assertNull(config.debugConfigurationError())
        val params = dapParameters(config)
        assertEquals("test", params["mode"])
        assertEquals(packageDir, params["program"])
        assertEquals(packageDir, params["cwd"])
        assertEquals(listOf("-test.run", "^TestSubtests$/^with_space$", "-test.v=test2json"), params["args"])
        assertEquals("-tags=x", params["buildFlags"])
    }

    fun testFileScopeDebugsItsPackageLimitedToTheFileTests() {
        val file = File(tempDir, "calc_test.go").apply {
            writeText("package calc\n\nfunc TestAdd(t *testing.T) {}\nfunc BenchmarkAdd(b *testing.B) {}\nfunc FuzzRev(f *testing.F) {}\n")
        }
        val config = configuration {
            scope = GoTestScope.FILE
            filePath = file.path
        }
        assertNull(config.debugConfigurationError())
        val params = dapParameters(config)
        assertEquals(packageDir, params["program"])
        assertEquals(listOf("-test.run", "^TestAdd$|^FuzzRev$", "-test.v=test2json"), params["args"])
    }

    private fun debugFailure(config: GoTestConfiguration): ExecutionException {
        try {
            dapParameters(config)
        } catch (e: ExecutionException) {
            return e
        }
        throw AssertionError("expected ExecutionException")
    }

    fun testRecursiveDirectoryUnderDebugFailsTheLaunchWithAUserMessage() {
        val config = configuration {
            scope = GoTestScope.DIRECTORY
            directory = tempDir.path
        }
        assertTrue(debugFailure(config).message!!.contains("one package"))
    }

    fun testRerunOfSeveralPackagesUnderDebugFailsTheLaunchWithAUserMessage() {
        val config = configuration {
            scope = GoTestScope.PACKAGE
            directory = tempDir.path
            packages = listOf("./a", "./b")
        }
        assertTrue(debugFailure(config).message!!.contains("one package"))
    }

    fun testReservedGoTestFlagUnderDebugFailsTheLaunchWithItsName() {
        val config = configuration {
            scope = GoTestScope.PACKAGE
            directory = tempDir.path
            buildFlags = "-race -json"
        }
        assertTrue(debugFailure(config).message!!.contains("'-json'"))
    }
}
