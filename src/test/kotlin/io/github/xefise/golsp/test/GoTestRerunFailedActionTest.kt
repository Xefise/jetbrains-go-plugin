package io.github.xefise.golsp.test

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.Filter
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.execution.model.GoTestCommandBuilder
import io.github.xefise.golsp.test.runner.GoTestConsoleProperties
import io.github.xefise.golsp.test.runner.SmTreeReplay
import java.io.File
import java.nio.file.Files

class GoTestRerunFailedActionTest : BasePlatformTestCase() {
    private val root = "/work/gotests"

    private fun configuration(scope: GoTestScope, setup: GoTestConfiguration.() -> Unit = {}): GoTestConfiguration {
        val settings = RunManager.getInstance(project)
            .createConfiguration("t", runConfigurationType<GoTestConfigurationType>().configurationFactories[0])
        return (settings.configuration as GoTestConfiguration).apply {
            this.scope = scope
            directory = if (scope == GoTestScope.DIRECTORY) root else "$root/rerun"
            filePath = "$root/rerun/rerun_test.go"
            setup()
        }
    }

    private fun rerun(base: GoTestConfiguration, fixture: String, packageDirectory: (String) -> String? = { null }): GoTestConfiguration =
        rerun(base, SmTreeReplay.replay(project, fixture), packageDirectory)

    private fun rerun(
        base: GoTestConfiguration,
        tree: SMTestProxy,
        packageDirectory: (String) -> String? = { null },
        includeNonStarted: Boolean = true,
    ): GoTestConfiguration {
        val selection = requireNotNull(GoTestRerunFailedAction.selection(failed(base, tree, includeNonStarted), tree.allTests))
        return GoTestRerunFailedAction.rerunConfiguration(base, selection, packageDirectory)
    }

    // What AbstractRerunFailedTestsAction.getFailedTests selects: its filter over every node of the tree.
    private fun failed(base: GoTestConfiguration, tree: SMTestProxy, includeNonStarted: Boolean): List<AbstractTestProxy> {
        val properties = GoTestConsoleProperties(base, GoTestRunProfileState.FRAMEWORK_NAME, DefaultRunExecutor.getRunExecutorInstance())
        Disposer.register(testRootDisposable, properties)
        TestConsoleProperties.INCLUDE_NON_STARTED_IN_RERUN_FAILED.set(properties, includeNonStarted)
        @Suppress("UNCHECKED_CAST")
        val filter = AbstractRerunFailedTestsAction.getFailuresFilter(properties) as Filter<AbstractTestProxy>
        return filter.select(tree.allTests)
    }

    private fun command(configuration: GoTestConfiguration): List<String> =
        GoTestCommandBuilder.testCommand("go", configuration.toExecutionRequest()).arguments

    fun testDeepestFailuresWithDuplicateSpacedAndSlashedNamesBecomeOneExactRun() {
        val rerun = rerun(configuration(GoTestScope.PACKAGE), "rerun")
        // TestParentFails failed itself while its only subtest passed: the parent is the deepest failure.
        assertEquals(listOf("TestTable/same#01", "TestTable/with_space", "TestTable/a/b", "TestParentFails"), rerun.testNames)
        assertEquals(
            listOf("test", "-json", "-run", "^TestTable$/^same#01$|^TestTable$/^with_space$|^TestTable$/^a$/^b$|^TestParentFails$", "."),
            command(rerun),
        )
        assertEquals("$root/rerun", rerun.targetDirectory().toString())
    }

    fun testRerunIsACopyAndLeavesTheConfigurationUntouched() {
        val base = configuration(GoTestScope.PACKAGE)
        val rerun = rerun(base, "rerun")
        assertNotSame(base, rerun)
        assertEquals(emptyList<String>(), base.testNames)
    }

    fun testFileTargetStaysAFileWithTheFailedNames() {
        val rerun = rerun(configuration(GoTestScope.FILE), "rerun")
        assertEquals(GoTestScope.FILE, rerun.scope)
        assertEquals("-run" to "^TestTable$/^same#01$|^TestTable$/^with_space$|^TestTable$/^a$/^b$|^TestParentFails$", command(rerun).let { it[2] to it[3] })
    }

    fun testBuildFailureReranTheOriginalSelection() {
        assertEquals(listOf("test", "-json", "."), command(rerun(configuration(GoTestScope.PACKAGE), "build-failure")))
        val named = rerun(configuration(GoTestScope.PACKAGE) { testNames = listOf("TestX") }, "build-failure")
        assertEquals(listOf("TestX"), named.testNames)
    }

    fun testFailedBenchmarkRerunsThroughBenchWithoutTests() {
        val rerun = rerun(configuration(GoTestScope.PACKAGE), "benchfail")
        assertEquals(listOf("test", "-json", "-run", "^$", "-bench", "^BenchmarkFails$", "."), command(rerun))
    }

    fun testSeveralFailedPackagesShareOneGoTestWithTheirImportPaths() {
        val rerun = rerun(configuration(GoTestScope.DIRECTORY), "all") { "$root/should-not-be-used" }
        assertEquals(GoTestScope.DIRECTORY, rerun.scope)
        val packages = listOf("broken", "calc", "panics", "slow").map { "example.com/gotests/$it" }
        assertEquals(packages.toSet(), rerun.packages.toSet())
        // broken failed to build: it stays in the package list and adds no names.
        assertEquals(
            setOf("TestSub", "TestSubtests/nested/fails", "ExampleSub", "TestPanic/inner", "TestSlow"),
            rerun.testNames.toSet(),
        )
        val args = command(rerun)
        assertEquals(rerun.packages, args.takeLast(4))
        assertFalse(args.contains(GoTestCommandBuilder.RECURSIVE_PATTERN))
    }

    fun testSingleFailedPackageOfARecursiveRunBecomesAPackageTarget() {
        val rerun = rerun(configuration(GoTestScope.DIRECTORY), "panic") { if (it == "example.com/gotests/panics") "$root/panics" else null }
        assertEquals(GoTestScope.PACKAGE, rerun.scope)
        assertEquals("$root/panics", rerun.directory)
        assertEquals(emptyList<String>(), rerun.packages)
        assertEquals(listOf("test", "-json", "-run", "^TestPanic$/^inner$", "."), command(rerun))
        // A package target is debuggable, ./... is not.
        assertNull(rerun.debugConfigurationError())
    }

    fun testUnresolvedOrForeignPackageDirectoryKeepsTheImportPath() {
        for (resolved in listOf(null, "/elsewhere/panics")) {
            val rerun = rerun(configuration(GoTestScope.DIRECTORY), "panic") { resolved }
            assertEquals(GoTestScope.DIRECTORY, rerun.scope)
            assertEquals(listOf("test", "-json", "-run", "^TestPanic$/^inner$", "example.com/gotests/panics"), command(rerun))
        }
    }

    fun testNothingFailedOffersNoRerun() {
        val tree = SmTreeReplay.replay(project, "bench")
        assertNull(GoTestRerunFailedAction.selection(failed(configuration(GoTestScope.PACKAGE), tree, true), tree.allTests))
    }

    fun testParentThatFailedItselfWithGreenSubtestsIsRerunAsTheParent() {
        val tree = SmTreeReplay.replay(project, "rerun")
        assertEquals("passed", SmTreeReplay.status(SmTreeReplay.node(tree, "example.com/gotests/rerun", "TestParentFails", "child")))
        assertTrue(rerun(configuration(GoTestScope.PACKAGE), tree).testNames.contains("TestParentFails"))
    }

    fun testSkippedTestsAreNotRerun() {
        // calc: TestSkipped is skipped; the failures are TestSub, TestSubtests/nested/fails and ExampleSub.
        val names = rerun(configuration(GoTestScope.PACKAGE), "calc").testNames
        assertEquals(listOf("TestSub", "TestSubtests/nested/fails", "ExampleSub"), names)
    }

    fun testTestsCutByStopAreRerunWithOrWithoutNonStartedOption() {
        for (includeNonStarted in listOf(true, false)) {
            val replay = SmTreeReplay(project)
            // Line 2 of the capture starts TestAdd; the run is stopped before it reports.
            SmTreeReplay.fixture("calc").take(2).forEach { replay.stdout("$it\n") }
            val tree = replay.terminate()
            assertEquals("terminated", SmTreeReplay.status(SmTreeReplay.node(tree, "example.com/gotests/calc", "TestAdd")))
            assertEquals(listOf("TestAdd"), rerun(configuration(GoTestScope.PACKAGE), tree, includeNonStarted = includeNonStarted).testNames)
        }
    }

    fun testPackageDirectoryBehindASymlinkedRootStillBecomesAPackageTarget() {
        val real = FileUtil.createTempDirectory("go-rerun-real", null)
        val link = File(FileUtil.createTempDirectory("go-rerun-link", null), "root")
        try {
            File(real, "panics").mkdirs()
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val base = configuration(GoTestScope.DIRECTORY) { directory = link.path }
            val rerun = rerun(base, "panic") { File(real, "panics").canonicalPath }
            assertEquals(GoTestScope.PACKAGE, rerun.scope)
        } finally {
            FileUtil.delete(real)
            FileUtil.delete(link.parentFile)
        }
    }

    fun testOriginalRecursiveSettingsDoNotBlockDebugOfTheRerun() {
        val settings = RunManager.getInstance(project)
            .createConfiguration("t", runConfigurationType<GoTestConfigurationType>().configurationFactories[0])
        val dir = FileUtil.createTempDirectory("go-rerun-dir", null)
        Disposer.register(testRootDisposable) { FileUtil.delete(dir) }
        (settings.configuration as GoTestConfiguration).apply {
            scope = GoTestScope.DIRECTORY
            directory = dir.path
        }
        // The platform runs Rerun Failed against these settings; an error here opens Edit Configuration instead.
        try {
            settings.checkSettings(DefaultDebugExecutor.getDebugExecutorInstance())
        } catch (e: RuntimeConfigurationError) {
            fail("blocking error: ${e.message}")
        } catch (_: RuntimeConfigurationException) {
        }
    }

    fun testDebugExecutorFindsARunnerForTheRerunProfile() {
        val copy = rerun(configuration(GoTestScope.PACKAGE), "rerun")
        val profile = GoTestRerunFailedAction.runProfile(copy)
        // Shift+click asks for runners with the peer; Delve resolves its launch from the peer too.
        val peer = (profile as WrappingRunConfiguration<*>).peer
        assertSame(copy, peer)
        assertNotNull(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, peer))
    }

    fun testTestConsoleGetsTheGoRerunFailedAction() {
        val settings = RunManager.getInstance(project)
            .createConfiguration("t", runConfigurationType<GoTestConfigurationType>().configurationFactories[0])
        val configuration = settings.configuration as GoTestConfiguration
        configuration.directory = root
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        val state = GoTestRunProfileState(env, configuration, "go") { configuration.toExecutionRequest() }
        val console = state.createTestConsole(env.executor, NopProcessHandler(), root)
        Disposer.register(testRootDisposable, console)
        assertInstanceOf(state.rerunFailedAction(console), GoTestRerunFailedAction::class.java)
    }
}
