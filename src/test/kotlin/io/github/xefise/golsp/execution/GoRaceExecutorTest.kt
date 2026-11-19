package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.util.text.TextWithMnemonic
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.debug.GoRemoteDebugConfiguration
import io.github.xefise.golsp.debug.GoRemoteDebugConfigurationFactory
import io.github.xefise.golsp.debug.GoRemoteDebugConfigurationType
import io.github.xefise.golsp.run.GoApplicationConfiguration
import io.github.xefise.golsp.run.GoApplicationConfigurationFactory
import io.github.xefise.golsp.run.GoApplicationConfigurationType
import io.github.xefise.golsp.test.GoTestConfiguration
import io.github.xefise.golsp.test.GoTestConfigurationFactory
import io.github.xefise.golsp.test.GoTestConfigurationType
import io.github.xefise.golsp.test.GoTestRerunFailedAction
import io.github.xefise.golsp.toolchain.GoToolchainSettings

class GoRaceExecutorTest : BasePlatformTestCase() {
    private val race = GoRaceExecutor.EXECUTOR_ID

    override fun setUp() {
        super.setUp()
        GoToolchainSettings.getInstance().showRunWithRaceDetector = true
    }

    override fun tearDown() {
        try {
            GoToolchainSettings.getInstance().loadState(GoToolchainSettings.Options())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun application(flags: String = "", race: Boolean = false) =
        GoApplicationConfiguration(project, GoApplicationConfigurationFactory(GoApplicationConfigurationType()), "app").apply {
            packageDirectory = "/abs/app"
            buildFlags = flags
            raceDetector = race
        }

    private fun goTest(flags: String = "", race: Boolean = false) =
        GoTestConfiguration(project, GoTestConfigurationFactory(GoTestConfigurationType()), "t").apply {
            directory = "/abs/pkg"
            buildFlags = flags
            raceDetector = race
        }

    private fun runnerFor(executorId: String, profile: RunProfile) =
        ProgramRunner.getRunner(executorId, profile)

    fun testRaceSettingIsOffByDefault() {
        assertFalse(GoToolchainSettings.Options().showRunWithRaceDetector)
    }

    fun testExecutorIsRegisteredAndApplicableOnlyWhileTheSettingIsOn() {
        val executor = ExecutorRegistry.getInstance().getExecutorById(race)!!
        assertTrue(executor.isApplicable(project))
        GoToolchainSettings.getInstance().showRunWithRaceDetector = false
        assertFalse(executor.isApplicable(project))
    }

    fun testRaceRunnerRunsGoApplicationAndGoTest() {
        assertInstanceOf(runnerFor(race, application()), GoRaceProgramRunner::class.java)
        assertInstanceOf(runnerFor(race, goTest()), GoRaceProgramRunner::class.java)
    }

    fun testRaceRunnerRunsRerunFailedOfGoTest() {
        val wrapped = GoTestRerunFailedAction.runProfile(goTest())
        assertInstanceOf(runnerFor(race, wrapped), GoRaceProgramRunner::class.java)
    }

    fun testNoRunnerForRaceWhenTheSettingIsOff() {
        GoToolchainSettings.getInstance().showRunWithRaceDetector = false
        assertNull(runnerFor(race, application()))
        assertNull(runnerFor(race, GoTestRerunFailedAction.runProfile(goTest())))
    }

    fun testRaceRunnerRejectsRemoteDebugConfiguration() {
        val remote = GoRemoteDebugConfiguration(project, GoRemoteDebugConfigurationFactory(GoRemoteDebugConfigurationType()), "r")
        assertFalse(GoRaceProgramRunner().canRun(race, remote))
    }

    fun testRaceRunnerIgnoresOtherExecutors() {
        val runner = GoRaceProgramRunner()
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, application()))
        assertFalse(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, goTest()))
    }

    fun testRaceLaunchAddsRaceWithoutTouchingTheStoredFlags() {
        val config = application(flags = "-tags=x")
        assertEquals(listOf("-tags=x", "-race"), config.launchRequest(race).buildFlags)
        assertEquals("-tags=x", config.buildFlags)
        assertFalse(config.raceDetector)
    }

    fun testPlainRunLaunchStaysWithoutRace() {
        assertEquals(listOf("-tags=x"), application(flags = "-tags=x").launchRequest(DefaultRunExecutor.EXECUTOR_ID).buildFlags)
    }

    fun testRaceLaunchKeepsASingleRaceWhenTheConfigurationAlreadyHasIt() {
        assertEquals(listOf("-race"), application(race = true).launchRequest(race).buildFlags)
        assertEquals(listOf("-race=true", "-v"), goTest(flags = "-race=true -v").launchRequest(race).buildFlags)
    }

    fun testRaceLaunchOfGoTestAddsRace() {
        val config = goTest(flags = "-count=1")
        assertEquals(listOf("-count=1", "-race"), config.launchRequest(race).buildFlags)
        assertEquals("-count=1", config.buildFlags)
    }

    fun testStartActionTextKeepsUnderscoresOfTheConfigurationName() {
        // The action shows the text parsed as text-with-mnemonic.
        assertEquals("Run 'my_app' with Race Detector", TextWithMnemonic.parse(GoRaceExecutor().getStartActionText("my_app")).text)
        assertEquals("Run with Race Detector", GoRaceExecutor().getStartActionText(""))
    }
}
