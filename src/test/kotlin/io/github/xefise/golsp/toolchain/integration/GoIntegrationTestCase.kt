package io.github.xefise.golsp.toolchain.integration

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.JUnit38AssumeSupportRunner
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import io.github.xefise.golsp.toolchain.GoToolLocator
import io.github.xefise.golsp.toolchain.model.GoTool
import org.junit.Assume
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A heavy project whose single content root is a real directory on disk, so `go`, gopls and dlv see the files.
 * Tests skip themselves when a required binary is missing.
 */
// Plain JUnit 3 reports a failed Assume as a failure; this runner turns it into a skip.
@RunWith(JUnit38AssumeSupportRunner::class)
abstract class GoIntegrationTestCase : UsefulTestCase() {
    protected lateinit var fixture: CodeInsightTestFixture
    protected val project: Project get() = fixture.project

    protected open val requiredTools: List<GoTool> = listOf(GoTool.GO)

    override fun setUp() {
        // Before super.setUp(): a skip thrown later would leave the half-built test environment undisposed.
        val env = GoToolLocator.lookupEnvironment(System.getenv())
        requiredTools.forEach { Assume.assumeTrue("${it.baseName} is not installed", GoToolLocator.find(it, env) != null) }
        super.setUp()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder(name)
        fixture = factory.createCodeInsightFixture(builder.fixture, TempDirTestFixtureImpl())
        fixture.testDataPath = File(System.getProperty("golsp.projectDir"), "testdata").path
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addContentRoot(fixture.tempDirPath)
        fixture.setUp()
        // Navigation into the standard library opens files under GOROOT, outside the test project.
        goRoot()?.let { VfsRootAccess.allowRootAccess(testRootDisposable, it, File(it).canonicalPath) }
    }

    private fun goRoot(): String? {
        val go = GoToolLocator.find(GoTool.GO) ?: return null
        val output = CapturingProcessHandler(GeneralCommandLine(go, "env", "GOROOT")).runProcess(GO_ENV_TIMEOUT_MS)
        return output.stdout.trim().takeIf { output.exitCode == 0 && it.isNotEmpty() }
    }

    /** Runs before the fixture (and the project) is disposed; only called when setUp got that far. */
    protected open fun beforeFixtureTearDown() = Unit

    override fun tearDown() {
        try {
            if (::fixture.isInitialized) {
                try {
                    beforeFixtureTearDown()
                } finally {
                    fixture.tearDown()
                }
            }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Copies `testdata/<name>` into the content root under [target] ("" = the root itself), without IDE files and debug binaries. */
    protected fun copyTestProject(name: String, target: String = "") {
        val source = File(fixture.testDataPath, name)
        // .run/.idea left by the runIde sandbox would add run configurations and project settings to the test project.
        source.walkTopDown()
            .onEnter { it == source || it.name !in IDE_DIRECTORIES }
            .filter { it.isFile && !it.name.startsWith(DEBUG_BINARY_PREFIX) }
            .forEach { file ->
                val relative = file.relativeTo(source).invariantSeparatorsPath
                fixture.copyFileToProject("$name/$relative", if (target.isEmpty()) relative else "$target/$relative")
            }
    }

    protected fun absolutePath(relative: String): String = File(fixture.tempDirPath, relative).path

    // Nested waits (a background request polled inside an outer wait) share the outermost deadline.
    private var deadlineNanos: Long? = null

    /** Polls [condition] on EDT with events dispatched in between; fails with [what] after [timeoutSeconds] or the enclosing wait's deadline. */
    protected fun waitUntil(what: String, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS, condition: () -> Boolean) {
        if (!poll(timeoutSeconds, condition)) throw AssertionError("Timed out after $timeoutSeconds s waiting for: $what")
    }

    /** Like [waitUntil], but reports a timeout as `false` instead of failing; for cleanup code. */
    protected fun poll(timeoutSeconds: Int, condition: () -> Boolean): Boolean {
        val outer = deadlineNanos
        val own = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        val deadline = if (outer != null && outer - own < 0) outer else own
        deadlineNanos = deadline
        try {
            while (!condition()) {
                if (System.nanoTime() - deadline >= 0) return false
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return true
        } finally {
            deadlineNanos = outer
        }
    }

    /** Waits for [future] with events dispatched; on timeout cancels it, on failure rethrows its own cause. */
    protected fun <T> await(what: String, future: Future<T>, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS): T {
        try {
            waitUntil(what, timeoutSeconds) { future.isDone }
        } catch (e: AssertionError) {
            future.cancel(true)
            throw e
        }
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Runs [action] in a background read action, as the IDE does for anything that blocks on a language server. */
    protected fun <T> inBackgroundRead(what: String, timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS, action: () -> T): T =
        await(what, ApplicationManager.getApplication().executeOnPooledThread<T> { ReadAction.computeBlocking<T, RuntimeException>(action) }, timeoutSeconds)

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Int = 60
        private const val GO_ENV_TIMEOUT_MS = 10_000
        private const val POLL_INTERVAL_MS = 10L
        private val IDE_DIRECTORIES = setOf(".run", ".idea")
        private const val DEBUG_BINARY_PREFIX = "__debug_bin"
    }
}
