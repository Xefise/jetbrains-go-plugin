package io.github.xefise.golsp.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.execution.model.GoRunTarget
import io.github.xefise.golsp.execution.model.GoTestSpec
import org.jdom.Element
import java.io.File

class GoTestConfigurationTest : BasePlatformTestCase() {
    private fun configuration(configure: GoTestConfiguration.() -> Unit = {}) =
        GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "t").apply(configure)

    private val tempDir: File by lazy { FileUtil.createTempDirectory("go-test-config", null) }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    private fun testFile(name: String, text: String): File =
        File(tempDir, name).apply {
            writeText(text)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(this)
        }

    fun testDefaultsRunTheWholePackage() {
        val options = GoTestOptions()
        assertEquals(GoTestScope.PACKAGE, options.testScope)
        assertEquals("", options.directory)
        assertEquals("", options.filePath)
        assertEquals(emptyList<String>(), options.testNames)
        assertEquals("", options.testArguments)
        assertEquals(emptyMap<String, String>(), options.envs)
        assertEquals("", options.envFile)
        assertEquals("", options.buildFlags)
    }

    fun testAllOptionsSurviveSaveAndLoad() {
        val saved = configuration {
            scope = GoTestScope.FILE
            directory = "/abs/pkg"
            filePath = "/abs/pkg/calc_test.go"
            testNames = listOf("TestA/sub case", "BenchmarkB")
            testArguments = "-update"
            envs = linkedMapOf("B" to "2", "A" to "1")
            envFile = ".env"
            buildFlags = "-race -count=1"
        }
        val element = Element("configuration")
        saved.writeExternal(element)
        val written = element.getChildren("option").associate { it.getAttributeValue("name") to it.getAttributeValue("value") }
        assertEquals("FILE", written["testScope"])
        assertEquals("/abs/pkg", written["directory"])
        assertEquals("-update", written["testArguments"])
        val loaded = configuration().apply { readExternal(element) }
        assertEquals(GoTestScope.FILE, loaded.scope)
        assertEquals("/abs/pkg", loaded.directory)
        assertEquals("/abs/pkg/calc_test.go", loaded.filePath)
        assertEquals(listOf("TestA/sub case", "BenchmarkB"), loaded.testNames)
        assertEquals("-update", loaded.testArguments)
        assertEquals(mapOf("A" to "1", "B" to "2"), loaded.envs)
        assertEquals(".env", loaded.envFile)
        assertEquals("-race -count=1", loaded.buildFlags)
    }

    fun testPackageRequestTargetsTheDirectoryWithFlagsAndArguments() {
        val request = configuration {
            directory = "/abs/x/../pkg"
            buildFlags = "-race \"-ldflags=-X a=b c\""
            testArguments = "-update \"a b\""
        }.toExecutionRequest()
        assertEquals(GoRunTarget.Package("/abs/pkg"), request.target)
        assertEquals("/abs/pkg", request.workingDirectory)
        assertEquals(listOf("-race", "-ldflags=-X a=b c"), request.buildFlags)
        assertEquals(listOf("-update", "a b"), request.programArguments)
        assertEquals(GoTestSpec(), request.test)
    }

    fun testDirectoryScopeIsRecursiveWithTheNameFilter() {
        val config = configuration {
            scope = GoTestScope.DIRECTORY
            directory = "/abs/root"
            testNames = listOf("TestA")
        }
        assertTrue(config.isRecursive)
        assertEquals(GoTestSpec(recursive = true, runPattern = "^TestA$"), config.toExecutionRequest().test)
    }

    fun testFileScopeRunsItsPackageLimitedToTheFileTests() {
        val file = testFile(
            "calc_test.go",
            "package calc\n\nfunc TestAdd(t *testing.T) {}\nfunc (s *S) TestMethod(t *testing.T) {}\n" +
                "func BenchmarkAdd(b *testing.B) {}\nfunc helper() {}\nfunc ExampleAdd() {}\n",
        )
        val request = configuration {
            scope = GoTestScope.FILE
            filePath = file.path
        }.toExecutionRequest()
        assertEquals(GoRunTarget.Package(tempDir.toPath().normalize().toString()), request.target)
        assertEquals(GoTestSpec(runPattern = "^TestAdd$|^ExampleAdd$"), request.test)
    }

    fun testFileScopeWithExplicitNamesUsesThemInsteadOfTheFileTests() {
        val file = testFile("calc_test.go", "package calc\n\nfunc TestAdd(t *testing.T) {}\n")
        val request = configuration {
            scope = GoTestScope.FILE
            filePath = file.path
            testNames = listOf("BenchmarkAdd")
        }.toExecutionRequest()
        assertEquals(GoTestSpec(runPattern = "^$", benchPattern = "^BenchmarkAdd$"), request.test)
    }

    fun testFileWithoutTestsFailsTheLaunch() {
        val file = testFile("util_test.go", "package calc\n\nfunc helper() {}\n")
        val error = expectFailure<ExecutionException> {
            configuration {
                scope = GoTestScope.FILE
                filePath = file.path
            }.toExecutionRequest()
        }
        assertTrue(error.message, error.message!!.startsWith("No tests"))
    }

    fun testMissingOrNonexistentDirectoryIsAConfigurationError() {
        expectFailure<RuntimeConfigurationError> { configuration().checkConfiguration() }
        expectFailure<RuntimeConfigurationError> { configuration { directory = File(tempDir, "nope").path }.checkConfiguration() }
        configuration { directory = tempDir.path }.checkConfiguration()
    }

    fun testFileScopeRequiresAnExistingTestFile() {
        val plain = testFile("calc.go", "package calc\n")
        val test = testFile("calc_test.go", "package calc\n")
        fun fileConfig(path: String) = configuration {
            scope = GoTestScope.FILE
            filePath = path
        }
        expectFailure<RuntimeConfigurationError> { fileConfig("").checkConfiguration() }
        expectFailure<RuntimeConfigurationError> { fileConfig(File(tempDir, "missing_test.go").path).checkConfiguration() }
        expectFailure<RuntimeConfigurationError> { fileConfig(plain.path).checkConfiguration() }
        fileConfig(test.path).checkConfiguration()
    }

    fun testOutputFlagIsAConfigurationError() {
        expectFailure<RuntimeConfigurationError> {
            configuration {
                directory = tempDir.path
                buildFlags = "-count=1 -o=calc.test"
            }.checkConfiguration()
        }
    }

    fun testMissingEnvFileIsAConfigurationError() {
        expectFailure<RuntimeConfigurationError> {
            configuration {
                directory = tempDir.path
                envFile = File(tempDir, "nope.env").path
            }.checkConfiguration()
        }
    }

    fun testExplicitVariablesOverrideTheEnvFile() {
        val envFile = testFile("t.env", "A=file\nB=file\n")
        val request = configuration {
            directory = "/abs"
            this.envFile = envFile.path
            envs = mapOf("A" to "explicit")
        }.toExecutionRequest()
        assertEquals(mapOf("A" to "explicit", "B" to "file"), request.environment)
    }

    fun testDebuggingSeveralPackagesIsRejected() {
        val config = configuration {
            scope = GoTestScope.DIRECTORY
            directory = tempDir.path
        }
        assertTrue(config.debugConfigurationError()!!.contains("one package"))
    }

    fun testBenchmarkOnlyFileSaysBenchmarksRunOnlyByName() {
        val file = testFile("bench_test.go", "package calc\n\nfunc BenchmarkAdd(b *testing.B) {}\n")
        val error = expectFailure<ExecutionException> {
            configuration {
                scope = GoTestScope.FILE
                filePath = file.path
            }.toExecutionRequest()
        }
        assertTrue(error.message, error.message!!.contains("benchmarks run only by name"))
    }

    fun testFileNotYetInVfsIsReadFromDisk() {
        val file = File(tempDir, "fresh_test.go").apply { writeText("package calc\n\nfunc TestFresh(t *testing.T) {}\n") }
        val request = configuration {
            scope = GoTestScope.FILE
            filePath = file.path
        }.toExecutionRequest()
        assertEquals(GoTestSpec(runPattern = "^TestFresh$"), request.test)
    }

    fun testFileWithBomAndInvalidUtf8StillYieldsItsTests() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "func TestBom(t *testing.T) {}\nvar s = \"".toByteArray() + byteArrayOf(0xFF.toByte()) + "\"\nfunc TestAfter(t *testing.T) {}\n".toByteArray()
        val file = File(tempDir, "bom_test.go").apply { writeBytes(bytes) }
        val request = configuration {
            scope = GoTestScope.FILE
            filePath = file.path
        }.toExecutionRequest()
        assertEquals(GoTestSpec(runPattern = "^TestBom$|^TestAfter$"), request.test)
    }

    fun testFileIgnoredByBuildConstraintFailsTheLaunch() {
        val file = testFile("gen_test.go", "//go:build ignore\n\npackage main\n\nfunc TestGen(t *testing.T) {}\n")
        val error = expectFailure<ExecutionException> {
            configuration {
                scope = GoTestScope.FILE
                filePath = file.path
            }.toExecutionRequest()
        }
        assertTrue(error.message, error.message!!.contains("excluded by build constraint"))
    }

    fun testUnreadableFileFailsTheLaunchWithItsPath() {
        val missing = File(tempDir, "gone_test.go")
        val error = expectFailure<ExecutionException> {
            configuration {
                scope = GoTestScope.FILE
                filePath = missing.path
            }.toExecutionRequest()
        }
        assertTrue(error.message, error.message!!.contains(missing.path))
    }

    fun testRelativeAndMacroFilePathsResolveFromTheProjectRoot() {
        val base = File(project.basePath!!).toPath().normalize()
        assertEquals(base.resolve("calc"), configuration { scope = GoTestScope.FILE; filePath = "calc/calc_test.go" }.targetDirectory())
        assertEquals(base.resolve("calc"), configuration { directory = "\$PROJECT_DIR\$/calc" }.targetDirectory())
    }

    fun testReservedGoTestFlagIsRejectedInCheckAndAtLaunch() {
        val config = configuration {
            directory = tempDir.path
            buildFlags = "-count=1 -args -v"
        }
        val checkError = expectFailure<RuntimeConfigurationError> { config.checkConfiguration() }
        assertTrue(checkError.message, checkError.message!!.startsWith("Go test flags"))
        val launchError = expectFailure<ExecutionException> { config.launchRequest() }
        assertTrue(launchError.message, launchError.message!!.startsWith("Go test flags"))
    }

    fun testTestNamesWithQuotesSpacesAndDuplicatesSurviveTheEditor() {
        val names = listOf("TestA/sub case", "TestB/#01", "TestC/say \"hi\"")
        val editor = GoTestSettingsEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.resetFrom(configuration { testNames = names })
        val applied = configuration()
        editor.applyTo(applied)
        assertEquals(names, applied.testNames)
    }

    fun testRecursiveDebugHintFollowsTheScope() {
        val editor = GoTestSettingsEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.resetFrom(configuration { scope = GoTestScope.DIRECTORY })
        assertTrue(editor.recursiveDebugHint.isVisible)
        editor.resetFrom(configuration { scope = GoTestScope.PACKAGE })
        assertFalse(editor.recursiveDebugHint.isVisible)
        editor.resetFrom(configuration { scope = GoTestScope.FILE })
        assertFalse(editor.recursiveDebugHint.isVisible)
    }

    fun testDebugRunnerSettingsAcceptARecursiveTarget() {
        val config = configuration {
            scope = GoTestScope.DIRECTORY
            directory = tempDir.path
        }
        val runner = ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, config)
        assertNotNull("no debug runner for Go Test", runner)
        // Any exception here leaves the platform without a runner and blocks Rerun Failed -> Debug of a package copy.
        config.checkRunnerSettings(runner!!, null, null)
    }

    fun testDebugOfARecursiveTargetFailsAtLaunch() {
        val config = configuration {
            scope = GoTestScope.DIRECTORY
            directory = tempDir.path
        }
        val settings = RunManager.getInstance(project).createConfiguration(config, config.factory!!)
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), settings).build()
        val error = expectFailure<ExecutionException> { config.getState(env.executor, env) }
        assertTrue(error.message, error.message!!.contains("one package"))
    }
}
