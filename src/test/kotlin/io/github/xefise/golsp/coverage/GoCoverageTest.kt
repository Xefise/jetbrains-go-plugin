package io.github.xefise.golsp.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageHelper
import com.intellij.coverage.CoverageRunnerData
import com.intellij.coverage.DefaultCoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.util.io.FileUtil
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.coverage.model.GoLineHits
import io.github.xefise.golsp.test.GoTestConfiguration
import io.github.xefise.golsp.test.GoTestConfigurationFactory
import io.github.xefise.golsp.test.GoTestConfigurationType
import org.jdom.Element
import java.io.File
import java.nio.file.Path

class GoCoverageTest : BasePlatformTestCase() {
    private val projectDir = File(System.getProperty("golsp.projectDir") ?: ".")
    private val tempDir: File by lazy { FileUtil.createTempDirectory("go-coverage", null) }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private fun fixture(name: String): Path = File(projectDir, "src/test/testData/coverage/$name").toPath()

    private fun moduleDir(): File = tempDir.apply { File(this, "go.mod").writeText("module example.com/gotests\n\ngo 1.22\n") }

    private fun loaded(profile: String, target: Path?): ProjectData {
        val result = GoCoverageRunner.load(fixture(profile), target)
        assertInstanceOf(result, SuccessCoverageLoadingResult::class.java)
        return result.projectData!!
    }

    private fun key(file: File): String = GoCoverageEngine.pathKey(file.toPath().normalize().toString())

    private fun lines(data: ProjectData, file: File): Array<Any?> = data.getClassData(key(file))!!.lines

    fun testCapturedProfileBecomesLinesOfTheResolvedFile() {
        val calc = File(moduleDir(), "calc/calc.go")
        val data = loaded("calc-partial.out", File(tempDir, "calc").toPath())
        assertEquals(setOf(key(calc)), data.classes.keys)
        val lines = lines(data, calc)
        // Last block ends on line 13: indices 0..13, slot 0 unused.
        assertEquals(14, lines.size)
        assertNull(lines[0])
        assertNull(lines[4])
        val hits = lines.withIndex().filter { it.value != null }.associate { it.index to (it.value as LineData).hits }
        assertEquals(mapOf(3 to 1, 6 to 0, 9 to 0, 10 to 0, 11 to 0, 12 to 0, 13 to 0), hits)
        assertEquals(LineCoverage.FULL.toInt(), (lines[3] as LineData).status)
        assertEquals(LineCoverage.NONE.toInt(), (lines[6] as LineData).status)
    }

    fun testPartialLineKeepsPartialStatus() {
        val data = GoCoverageRunner.projectData(mapOf("/src/a.go" to mapOf(2 to GoLineHits(3, partial = true))))
        val line = data.getClassData(GoCoverageEngine.pathKey("/src/a.go"))!!.lines[2] as LineData
        assertEquals(3, line.hits)
        assertEquals(LineCoverage.PARTIAL.toInt(), line.status)
    }

    fun testUnresolvedPackagesAndEmptyBlocksAreDropped() {
        // all.out also has notests.go with a statement-less block; ./... of another module would not resolve either.
        val data = loaded("all.out", moduleDir().toPath())
        assertEquals(setOf(key(File(tempDir, "calc/calc.go"))), data.classes.keys)
    }

    fun testWithoutConfigurationNothingResolves() {
        assertEquals(emptySet<String>(), loaded("calc-set.out", null).classes.keys)
    }

    fun testGoWorkModulesResolveToTheirDirectories() {
        val workspace = File(projectDir, "testdata/workspace")
        val data = loaded("workspace.out", File(workspace, "app").toPath())
        val main = File(workspace, "app/main.go")
        val greet = File(workspace, "lib/greet/greet.go")
        assertEquals(setOf(key(main), key(greet)), data.classes.keys)
        assertTrue(main.isFile && greet.isFile)
    }

    fun testMissingProfileLoadsAsEmptyCoverage() {
        assertEquals(emptyMap<String, Any>(), loaded("absent.out", null).classes)
    }

    fun testSuiteWithoutProfileHasEmptyDataInsteadOfNone() {
        val runner = CoverageRunner.getInstance(GoCoverageRunner::class.java)
        val provider = DefaultCoverageFileProvider(tempDir.toPath().resolve("absent.out"))
        val suite = GoCoverageSuite("t", project, runner, provider, 0, null)
        assertEquals(emptyMap<String, Any>(), suite.getCoverageData(CoverageDataManager.getInstance(project))!!.classes)
    }

    private fun suite(targetDirectory: String?): GoCoverageSuite = GoCoverageSuite(
        "t", project, CoverageRunner.getInstance(GoCoverageRunner::class.java),
        DefaultCoverageFileProvider(tempDir.toPath().resolve("cover.out")), 0, targetDirectory,
    )

    private fun restored(suite: GoCoverageSuite): GoCoverageSuite {
        val element = Element("SUITE")
        suite.writeExternal(element)
        return GoCoverageSuite().apply { readExternal(element) }
    }

    fun testRestoredSuiteResolvesAgainstItsStoredDirectory() {
        val calcDir = File(moduleDir(), "calc").path
        val copy = restored(suite(calcDir))
        assertEquals(calcDir, copy.targetDirectory)
        val data = GoCoverageRunner.load(fixture("calc-set.out"), copy.resolutionDirectory()).projectData!!
        assertEquals(setOf(key(File(tempDir, "calc/calc.go"))), data.classes.keys)
    }

    fun testSuiteSavedWithoutDirectoryRestoresWithout() {
        assertNull(restored(suite(null)).targetDirectory)
    }

    fun testSuiteWithoutDirectoryFallsBackToTheProjectRoot() {
        assertEquals(Path.of(project.basePath!!), suite(null).resolutionDirectory())
    }

    fun testSuiteKeepsTheDirectoryOfTheRunAfterTheConfigurationIsEdited() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "calc")
        configuration.directory = File(moduleDir(), "calc").path
        CoverageHelper.resetCoverageSuit(configuration)
        val suite = CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite as GoCoverageSuite
        try {
            configuration.directory = File(tempDir, "other").path
            assertEquals(File(tempDir, "calc").toPath().toAbsolutePath().normalize().toString(), suite.targetDirectory)
        } finally {
            CoverageDataManager.getInstance(project).unregisterCoverageSuite(suite)
        }
    }

    private fun profile(vararg names: String): Path = tempDir.toPath().resolve("p.out").also { file ->
        file.toFile().writeText("mode: set\n" + names.joinToString("") { "$it:3.1,3.10 1 1\n" })
    }

    fun testLocallyReplacedModuleResolvesToItsDirectory() {
        File(tempDir, "app").mkdirs()
        File(tempDir, "app/go.mod").writeText("module example.com/app\n\nrequire example.com/lib v0.0.0\n\nreplace example.com/lib => ../lib\n")
        val greet = File(tempDir, "lib/greet/greet.go").apply { parentFile.mkdirs(); writeText("package greet\n") }
        val data = GoCoverageRunner.load(profile("example.com/lib/greet/greet.go"), File(tempDir, "app").toPath()).projectData!!
        assertEquals(setOf(key(greet)), data.classes.keys)
    }

    fun testVendoredPackageResolvesUnderVendor() {
        val vendored = File(moduleDir(), "vendor/github.com/dep/pkg/a.go").apply { parentFile.mkdirs(); writeText("package pkg\n") }
        val data = GoCoverageRunner.load(profile("github.com/dep/pkg/a.go", "github.com/dep/pkg/missing.go"), tempDir.toPath()).projectData!!
        assertEquals(setOf(key(vendored)), data.classes.keys)
    }

    fun testUnreadableProfileFailsTheLoad() {
        assertInstanceOf(GoCoverageRunner.load(tempDir.toPath(), null), FailedCoverageLoadingResult::class.java)
    }

    fun testAbsurdLineNumbersAreSkipped() {
        val data = GoCoverageRunner.projectData(mapOf("/src/a.go" to mapOf(2 to GoLineHits(1, false), 50_000_000 to GoLineHits(1, false))))
        assertEquals(3, data.getClassData(GoCoverageEngine.pathKey("/src/a.go"))!!.lines.size)
    }

    fun testAnnotatorPercentagesForFilesAndTheirDirectories() {
        val data = loaded("calc-partial.out", File(moduleDir(), "calc").toPath())
        val files = GoCoverageAnnotator.fileLines(data)
        val calc = key(File(tempDir, "calc/calc.go"))
        // 7 executable lines, only line 3 ran.
        assertEquals(mapOf(calc to GoCoverageAnnotator.Lines(1, 7)), files)
        val directories = GoCoverageAnnotator.directoryLines(files)
        assertEquals(GoCoverageAnnotator.Lines(1, 7), directories[calc.substringBeforeLast('/')])
        assertEquals(GoCoverageAnnotator.Lines(1, 7), directories[key(tempDir)])
        assertNull(directories[calc])
    }

    fun testEditorKeyOfASourceFileMatchesTheLoadedClass() {
        val file = myFixture.addFileToProject("calc/calc.go", "package calc\n")
        val engine = GoCoverageEngine()
        assertEquals(setOf(GoCoverageEngine.pathKey(file.virtualFile.path)), engine.getQualifiedNames(file))
        assertTrue(engine.coverageEditorHighlightingApplicableTo(file))
        assertFalse(engine.coverageEditorHighlightingApplicableTo(myFixture.addFileToProject("calc/calc_test.go", "package calc\n")))
    }

    fun testGoTestConfigurationRunsWithCoverage() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "t")
        assertTrue(GoCoverageEngine().isApplicableTo(configuration))
        val runner = GoCoverageProgramRunner()
        assertTrue(runner.canRun(CoverageExecutor.EXECUTOR_ID, configuration))
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, configuration))
    }

    fun testRunnerIsRegisteredForTheEngine() {
        val runner = CoverageRunner.getInstance(GoCoverageRunner::class.java)
        assertEquals("GoLspCoverage", runner.id)
        assertTrue(runner.acceptsCoverageEngine(GoCoverageEngine()))
    }

    fun testOnlyANonEmptyProfileCountsAsProduced() {
        val profile = tempDir.toPath().resolve("cover.out")
        assertFalse(GoCoverageProgramRunner.hasProfile(profile))
        profile.toFile().writeText("")
        assertFalse(GoCoverageProgramRunner.hasProfile(profile))
        profile.toFile().writeText("mode: set\n")
        assertTrue(GoCoverageProgramRunner.hasProfile(profile))
        assertFalse(GoCoverageProgramRunner.hasProfile(tempDir.toPath()))
    }

    fun testRunWithoutProfileLeavesNoSuite() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "broken")
        val manager = CoverageDataManager.getInstance(project)
        CoverageHelper.resetCoverageSuit(configuration)
        val suite = CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite!!
        assertTrue(suite in manager.suites)
        GoCoverageProgramRunner.coverageFinished(configuration, CoverageRunnerData(), tempDir.toPath().resolve("absent.out"))
        assertFalse(suite in manager.suites)
        assertNull(CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite)
        assertEmpty(manager.activeSuites())
    }

    fun testSuiteIsRegisteredBeforeTheProcessStarts() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "race")
        val manager = CoverageDataManager.getInstance(project)
        var registeredAtStart = false
        val started = GoCoverageProgramRunner.withFreshSuite(configuration) {
            registeredAtStart = CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite in manager.suites
            "started"
        }
        assertEquals("started", started)
        assertTrue(registeredAtStart)
        val suite = CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite!!
        manager.unregisterCoverageSuite(suite)
    }

    fun testSuiteIsDroppedWhenNothingStarted() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "none")
        val manager = CoverageDataManager.getInstance(project)
        assertNull(GoCoverageProgramRunner.withFreshSuite<String>(configuration) { null })
        assertNull(CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite)
        assertEmpty(manager.suites.filter { it.presentableName == "none" })
    }

    fun testSuiteIsDroppedWhenTheStartFails() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "failing")
        val manager = CoverageDataManager.getInstance(project)
        assertThrows(ExecutionException::class.java) {
            GoCoverageProgramRunner.withFreshSuite<String>(configuration) { throw ExecutionException("go not found") }
        }
        assertNull(CoverageEnabledConfiguration.getOrCreate(configuration).currentCoverageSuite)
        assertEmpty(manager.suites.filter { it.presentableName == "failing" })
    }

    fun testRunWithProfileOpensItsSuite() {
        val configuration = GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "calc")
        val manager = CoverageDataManager.getInstance(project)
        CoverageHelper.resetCoverageSuit(configuration)
        val coverage = CoverageEnabledConfiguration.getOrCreate(configuration)
        val suite = coverage.currentCoverageSuite!!
        val profile = Path.of(coverage.coverageFilePath!!)
        fixture("calc-set.out").toFile().copyTo(profile.toFile(), overwrite = true)
        try {
            GoCoverageProgramRunner.coverageFinished(configuration, CoverageRunnerData(), profile)
            val bundle = manager.currentSuitesBundle
            assertTrue(bundle != null && bundle.contains(suite))
            manager.closeSuitesBundle(bundle!!)
        } finally {
            manager.unregisterCoverageSuite(suite)
            FileUtil.delete(profile.toFile())
        }
    }
}
