package io.github.xefise.golsp.run

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.execution.GoRunProfileState
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.GoToolchainService
import org.junit.Assume
import java.io.File

class GoApplicationConsoleFilterWiringTest : BasePlatformTestCase() {
    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = FileUtil.createTempDirectory("go-console-wiring", null)
        VfsRootAccess.allowRootAccess(testRootDisposable, root.path, root.canonicalPath)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(root)
        } finally {
            super.tearDown()
        }
    }

    fun testRunConsoleLinksCompilerErrorsInThePackageDirectory() {
        Assume.assumeTrue("go not found", GoToolchainService.getInstance(project).findTool(GoTool.GO) != null)
        val pkg = File(root, "app").also { it.mkdirs() }
        File(pkg, "main.go").writeText("package main\n\nfunc main() {\n\tx\n}\n")
        val settings = RunManager.getInstance(project).createConfiguration("app", runConfigurationType<GoApplicationConfigurationType>().configurationFactories[0])
        (settings.configuration as GoApplicationConfiguration).packageDirectory = pkg.path
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()

        val state = settings.configuration.getState(env.executor, env)
        assertInstanceOf(state, GoRunProfileState::class.java)
        val console = (state as CommandLineState).consoleBuilder.console as ConsoleViewImpl
        Disposer.register(testRootDisposable, console)
        console.component
        console.print("./main.go:4:2: undefined: x\n", ConsoleViewContentType.ERROR_OUTPUT)
        console.flushDeferredText()
        console.waitAllRequests()
        fun links() = console.getHyperlinks()!!.getAllHyperlinks(0, console.editor!!.document.textLength)
        // Filters run asynchronously off EDT.
        val deadline = System.currentTimeMillis() + 10_000
        while (links().isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }

        // "./main.go:4:2" spans offsets 0..13
        val link = links().single()
        assertEquals(0 to 13, link.startOffset to link.endOffset)
    }
}
