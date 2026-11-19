package io.github.xefise.golsp.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.EnvironmentUtil
import io.github.xefise.golsp.debug.model.DelveLaunchBuilder
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoRunCommandBuilder
import io.github.xefise.golsp.execution.model.GoRunTarget
import io.github.xefise.golsp.toolchain.model.ToolLookup
import org.jdom.Element
import java.io.File

class GoApplicationConfigurationTest : BasePlatformTestCase() {
    private fun configuration(dir: String = "") =
        GoApplicationConfiguration(project, GoApplicationConfigurationFactory(GoApplicationConfigurationType()), "t")
            .apply { packageDirectory = dir }

    private val base: String get() = File(project.basePath!!).toPath().normalize().toString()

    private val tempDir: File by lazy { FileUtil.createTempDirectory("go-app-config", null) }

    override fun tearDown() {
        try {
            FileUtil.delete(tempDir)
        } finally {
            super.tearDown()
        }
    }

    private inline fun <reified T : Throwable> expectFailure(type: Class<T>, block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return e as T
            throw e
        }
        throw AssertionError("expected ${type.simpleName}")
    }

    fun testRelativePackageDirectoryResolvesFromProjectRoot() {
        assertEquals(GoRunTarget.Package(File(base, "cmd/app").path), configuration("cmd/app").toExecutionRequest().target)
    }

    fun testProjectDirMacroIsExpanded() {
        assertEquals(GoRunTarget.Package(File(base, "cmd").path), configuration("\$PROJECT_DIR\$/cmd").toExecutionRequest().target)
    }

    fun testAbsolutePackageDirectoryIsKeptAndNormalized() {
        assertEquals(GoRunTarget.Package("/abs/pkg"), configuration("/abs/x/../pkg/").toExecutionRequest().target)
    }

    fun testProgramArgumentsAreSplitLikeAShell() {
        val config = configuration("/abs").apply { programArguments = "-n 3 \"a b\"" }
        assertEquals(listOf("-n", "3", "a b"), config.toExecutionRequest().programArguments)
    }

    fun testBuildFlagsAreSplitLikeAShell() {
        val config = configuration("/abs").apply { buildFlags = "-tags=x \"-ldflags=-X main.v=a b\"" }
        assertEquals(listOf("-tags=x", "-ldflags=-X main.v=a b"), config.toExecutionRequest().buildFlags)
    }

    fun testNewOptionsDefaultToTheOldBehaviour() {
        val options = GoApplicationOptions()
        assertEquals(GoRunKind.PACKAGE, options.runKind)
        assertEquals("", options.filePath)
        assertEquals("", options.workingDirectory)
        assertEquals(emptyMap<String, String>(), options.envs)
        assertEquals("", options.envFile)
        assertEquals("", options.buildFlags)
        assertFalse(options.raceDetector)
    }

    fun testRaceDetectorAppendsRaceToTheBuildFlags() {
        val config = configuration("/abs").apply { buildFlags = "-tags=x"; raceDetector = true }
        assertEquals(listOf("-tags=x", "-race"), config.toExecutionRequest().buildFlags)
    }

    fun testConfigurationSavedBeforeNewOptionsRunsThePackageInItsDirectory() {
        val config = configuration()
        // Same shape as testdata/hello/.run/hello.run.xml.
        config.readExternal(
            JDOMUtil.load(
                """
                <configuration default="false" name="hello" type="GoLspApplication" factoryName="GoLspApplication">
                  <option name="packageDirectory" value="/abs/hello" />
                  <option name="programArguments" value="-v" />
                  <method v="2" />
                </configuration>
                """.trimIndent(),
            ),
        )
        val request = config.toExecutionRequest()
        assertEquals(GoRunTarget.Package("/abs/hello"), request.target)
        assertEquals("/abs/hello", request.workingDirectory)
        assertEquals(listOf("-v"), request.programArguments)
        assertEquals(emptyMap<String, String>(), request.environment)
        assertEquals(emptyList<String>(), request.buildFlags)
    }

    fun testAllOptionsSurviveSaveAndLoad() {
        val saved = configuration("/abs/pkg").apply {
            runKind = GoRunKind.FILE
            filePath = "/abs/pkg/tool.go"
            workingDirectory = "/abs/wd"
            envs = linkedMapOf("B" to "2", "A" to "1")
            envFile = ".env"
            buildFlags = "-race"
            raceDetector = true
        }
        val element = Element("configuration")
        saved.writeExternal(element)
        val loaded = configuration().apply { readExternal(element) }
        assertEquals(GoRunKind.FILE, loaded.runKind)
        assertEquals("/abs/pkg/tool.go", loaded.filePath)
        assertEquals("/abs/wd", loaded.workingDirectory)
        assertEquals(mapOf("A" to "1", "B" to "2"), loaded.envs)
        assertEquals(".env", loaded.envFile)
        assertEquals("-race", loaded.buildFlags)
        assertTrue(loaded.raceDetector)
        assertEquals("/abs/pkg", loaded.packageDirectory)
    }

    fun testFileKindTargetsTheFileInItsDirectory() {
        val config = configuration("/ignored").apply {
            runKind = GoRunKind.FILE
            filePath = "scripts/tool.go"
        }
        val request = config.toExecutionRequest()
        assertEquals(GoRunTarget.File(File(base, "scripts").path, "tool.go"), request.target)
        assertEquals(File(base, "scripts").path, request.workingDirectory)
    }

    fun testRelativeWorkingDirectoryResolvesFromProjectRoot() {
        val config = configuration("/abs/pkg").apply { workingDirectory = "data" }
        assertEquals(File(base, "data").path, config.toExecutionRequest().workingDirectory)
    }

    fun testExplicitVariablesOverrideTheEnvFile() {
        val envFile = File(tempDir, "app.env").apply { writeText("A=file\nB=file\n") }
        val config = configuration("/abs").apply {
            this.envFile = envFile.path
            envs = mapOf("A" to "explicit")
        }
        assertEquals(mapOf("A" to "explicit", "B" to "file"), config.toExecutionRequest().environment)
    }

    fun testParentReferencesExpandInVariablesButNotInTheEnvFile() {
        val envFile = File(tempDir, "app.env").apply { writeText("F=file:\$PATH\$\n") }
        val config = configuration("/abs").apply {
            this.envFile = envFile.path
            envs = mapOf("PATH" to "/my/bin:\$PATH\$", "U" to "\$GO_PLUGIN_NOPE\$")
        }
        val ideEnv = EnvironmentUtil.getEnvironmentMap()
        assertEquals(
            mapOf("F" to "file:\$PATH\$", "PATH" to "/my/bin:${ideEnv["PATH"]}", "U" to "\$GO_PLUGIN_NOPE\$"),
            config.toExecutionRequest().environment,
        )
    }

    fun testGoStaysFirstOnAnExpandedPathForRunAndDebug() {
        val config = configuration("/abs").apply { envs = mapOf("PATH" to "/my/bin:\$PATH\$") }
        val request = config.toExecutionRequest()
        val sep = File.pathSeparatorChar
        val path = ToolLookup.prependToPath(request.environment["PATH"], "/go/bin", sep)
        val launch = request.copy(environment = GoEnvironmentLayers.withPath(request.environment, path))
        val expected = "/go/bin$sep/my/bin$sep${EnvironmentUtil.getEnvironmentMap()["PATH"]}"
        assertEquals(expected, GoRunCommandBuilder.buildCommand("go", launch, "bin").environment["PATH"])
        assertEquals(expected, GoRunCommandBuilder.programCommand(launch, "bin").environment["PATH"])
        assertEquals(expected, DelveLaunchBuilder.serverCommand("dlv", request, path).environment["PATH"])
    }

    fun testMissingEnvFileFailsTheLaunchWithItsResolvedPath() {
        val config = configuration("/abs").apply { envFile = "conf/missing.env" }
        val error = expectFailure(ExecutionException::class.java) { config.toExecutionRequest() }
        assertTrue(error.message, error.message!!.contains(File(base, "conf/missing.env").path))
    }

    fun testPackageKindRequiresAnExistingDirectory() {
        expectFailure(RuntimeConfigurationError::class.java) { configuration(File(tempDir, "nope").path).checkConfiguration() }
        configuration(tempDir.path).checkConfiguration()
    }

    fun testFileKindRequiresAnExistingGoFile() {
        val goFile = File(tempDir, "tool.go").apply { writeText("package main\n") }
        val textFile = File(tempDir, "notes.txt").apply { writeText("") }
        fun fileConfig(path: String) = configuration().apply {
            runKind = GoRunKind.FILE
            filePath = path
        }
        expectFailure(RuntimeConfigurationError::class.java) { fileConfig("").checkConfiguration() }
        expectFailure(RuntimeConfigurationError::class.java) { fileConfig(File(tempDir, "missing.go").path).checkConfiguration() }
        expectFailure(RuntimeConfigurationError::class.java) { fileConfig(textFile.path).checkConfiguration() }
        fileConfig(goFile.path).checkConfiguration()
    }

    fun testFileKindDoesNotRequireAPackageDirectory() {
        val goFile = File(tempDir, "solo.go").apply { writeText("package main\n") }
        configuration("").apply {
            runKind = GoRunKind.FILE
            filePath = goFile.path
        }.checkConfiguration()
    }

    fun testOutputFlagInBuildFlagsIsAConfigurationError() {
        for (flags in listOf("-o bin/app", "-race -o=app")) {
            expectFailure(RuntimeConfigurationError::class.java) {
                configuration(tempDir.path).apply { buildFlags = flags }.checkConfiguration()
            }
        }
        configuration(tempDir.path).apply { buildFlags = "-race -ldflags=-s" }.checkConfiguration()
    }

    fun testMissingWorkingDirectoryOrEnvFileIsAConfigurationError() {
        expectFailure(RuntimeConfigurationError::class.java) {
            configuration(tempDir.path).apply { workingDirectory = File(tempDir, "nope").path }.checkConfiguration()
        }
        expectFailure(RuntimeConfigurationError::class.java) {
            configuration(tempDir.path).apply { envFile = File(tempDir, "nope.env").path }.checkConfiguration()
        }
    }

    // NUL is rejected by every OS path parser, like `C:\a<b` on Windows.
    fun testPathTheOsRejectsIsAConfigurationError() {
        val error = expectFailure(RuntimeConfigurationError::class.java) { configuration("bad\u0000dir").checkConfiguration() }
        assertEquals("Invalid path: bad\u0000dir", error.localizedMessage)
        expectFailure(RuntimeConfigurationError::class.java) {
            configuration(tempDir.path).apply { workingDirectory = "bad\u0000dir" }.checkConfiguration()
        }
        expectFailure(RuntimeConfigurationError::class.java) {
            configuration(tempDir.path).apply { envFile = "bad\u0000.env" }.checkConfiguration()
        }
    }
}
