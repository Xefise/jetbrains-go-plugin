package io.github.xefise.golsp.test

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.test.runner.GoTestConsoleProperties
import io.github.xefise.golsp.test.runner.GoTestEventsConverter
import java.io.File

class GoTestConsoleWiringTest : BasePlatformTestCase() {
    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = FileUtil.createTempDirectory("go-test-console", null)
        VfsRootAccess.allowRootAccess(testRootDisposable, root.path, root.canonicalPath)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(root)
        } finally {
            super.tearDown()
        }
    }

    private fun console(): SMTRunnerConsoleView {
        File(root, "calc_test.go").writeText("package calc\n")
        val settings = RunManager.getInstance(project)
            .createConfiguration("calc", runConfigurationType<GoTestConfigurationType>().configurationFactories[0])
        val configuration = settings.configuration as GoTestConfiguration
        configuration.directory = root.path
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val state = GoTestRunProfileState(env, configuration, "go") { configuration.toExecutionRequest() }
        val console = state.createTestConsole(env.executor, NopProcessHandler(), root.path) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        return console
    }

    fun testRunConsoleIsAnIdBasedGoTestTree() {
        val properties = console().properties as GoTestConsoleProperties
        assertTrue(properties.isIdBasedTestTree)
        assertEquals(GoTestRunProfileState.FRAMEWORK_NAME, properties.testFrameworkName)
        assertInstanceOf(properties.createTestEventsConverter(properties.testFrameworkName, properties), GoTestEventsConverter::class.java)
    }

    fun testDebugConsoleIsTheSameTreeFedThroughTest2JsonForThePackageOfGoMod() {
        File(root, "go.mod").writeText("module example.com/dbg\n")
        File(root, "calc_test.go").writeText("package calc\n")
        val settings = RunManager.getInstance(project)
            .createConfiguration("calc", runConfigurationType<GoTestConfigurationType>().configurationFactories[0])
        val configuration = settings.configuration as GoTestConfiguration
        configuration.directory = root.path
        val console = configuration.createDebugTestConsole(DefaultDebugExecutor.getDebugExecutorInstance()) as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        val properties = console.properties as GoTestConsoleProperties
        assertTrue(properties.isIdBasedTestTree)
        assertTrue(properties.isDebug)
        assertEquals(GoTestRunProfileState.FRAMEWORK_NAME, properties.testFrameworkName)

        val handler = NopProcessHandler()
        console.attachToProcess(handler)
        handler.startNotify()
        handler.notifyTextAvailable("DAP server listening at: 127.0.0.1:1\n", ProcessOutputType.STDOUT)
        for (line in listOf("=== RUN   TestAdd\n", "--- PASS: TestAdd (0.00s)\n", "\u0016PASS\n")) {
            handler.notifyTextAvailable(line, ProcessOutputType.STDOUT)
        }
        // LSP4IJ prints its own messages and DAP output events straight into the console.
        console.print("Build Error: none\n", ConsoleViewContentType.ERROR_OUTPUT)
        handler.destroyProcess()
        val tree = console.resultsViewer.testsRootNode
        val deadline = System.currentTimeMillis() + 10_000
        while (tree.isInProgress && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        val pkg = tree.children.single()
        assertEquals("example.com/dbg", pkg.name)
        assertEquals(listOf("TestAdd"), pkg.children.map { it.name })
        assertTrue(pkg.isPassed)
        assertTrue(tree.isPassed)
    }

    fun testTestOutputLinksFilesOfThePackage() {
        val console = console().console as ConsoleViewImpl
        console.component
        console.print("    calc_test.go:1: boom\n", ConsoleViewContentType.NORMAL_OUTPUT)
        console.flushDeferredText()
        console.waitAllRequests()
        fun links() = console.getHyperlinks()!!.getAllHyperlinks(0, console.editor!!.document.textLength)
        val deadline = System.currentTimeMillis() + 10_000
        while (links().isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        // "calc_test.go:1" spans offsets 4..18
        val link = links().single()
        assertEquals(4 to 18, link.startOffset to link.endOffset)
    }
}
