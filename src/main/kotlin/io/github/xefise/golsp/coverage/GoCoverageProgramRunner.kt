package io.github.xefise.golsp.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageHelper
import com.intellij.coverage.CoverageRunnerData
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.xefise.golsp.test.GoTestConfiguration
import io.github.xefise.golsp.test.GoTestRunProfileState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** "Run with Coverage" for Go Test: the plain `go test -json` run plus `-coverprofile` into the platform's file. */
class GoCoverageProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == CoverageExecutor.EXECUTOR_ID && goTestConfiguration(profile) != null

    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = goTestConfiguration(environment.runProfile) ?: return null
        val testState = state as? GoTestRunProfileState ?: throw ExecutionException("Coverage is supported only for plain Go test runs")
        FileDocumentManager.getInstance().saveAllDocuments()
        val profile = coverProfile(configuration)
        // The file name is fixed per configuration: a profile left by an earlier run must not pass for this one.
        // A single delete on EDT, like the platform's own createDirectories when it picks this path.
        try {
            Files.deleteIfExists(profile)
        } catch (e: IOException) {
            throw ExecutionException("Cannot delete the previous cover profile $profile: ${e.message}", e)
        }
        testState.coverProfile = profile
        val result = state.execute(environment.executor, this) ?: return null
        // Not attachToProcess: it shows the gathered suite even when the build failed and no profile was written.
        CoverageHelper.resetCoverageSuit(configuration)
        val runnerSettings = environment.runnerSettings
        result.processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                event.processHandler.removeProcessListener(this)
                coverageFinished(configuration, runnerSettings, profile)
            }
        })
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    @Throws(ExecutionException::class)
    private fun coverProfile(configuration: GoTestConfiguration): Path {
        val path = CoverageEnabledConfiguration.getOrCreate(configuration).coverageFilePath
            ?: throw ExecutionException("No coverage file for ${configuration.name}")
        return Path.of(path)
    }

    companion object {
        // Persisted in run configurations' runner settings; must never change.
        const val RUNNER_ID: String = "GoLspCoverageRunner"

        internal fun coverageFinished(configuration: GoTestConfiguration, runnerSettings: RunnerSettings?, profile: Path) {
            val project = configuration.project
            if (project.isDisposed) return
            val manager = CoverageDataManager.getInstance(project)
            if (hasProfile(profile)) {
                manager.processGatheredCoverage(configuration, runnerSettings)
                return
            }
            val coverage = CoverageEnabledConfiguration.getOrCreate(configuration)
            coverage.currentCoverageSuite?.let(manager::unregisterCoverageSuite)
            coverage.currentCoverageSuite = null
        }

        internal fun hasProfile(profile: Path): Boolean = try {
            Files.isRegularFile(profile) && Files.size(profile) > 0
        } catch (_: IOException) {
            false
        }

        // Rerun Failed wraps the configuration it runs.
        internal fun goTestConfiguration(profile: RunProfile): GoTestConfiguration? = when (profile) {
            is GoTestConfiguration -> profile
            is WrappingRunConfiguration<*> -> profile.peer as? GoTestConfiguration
            else -> null
        }
    }
}
