package io.github.xefise.golsp.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.ProjectData
import java.nio.file.Files
import java.nio.file.Path

class GoCoverageSuite : BaseCoverageSuite {
    // Used by the platform to restore suites from the workspace.
    constructor() : super()

    constructor(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long) :
        super(name, project, runner, fileProvider, timestamp)

    override fun getCoverageEngine(): CoverageEngine = CoverageEngine.EP_NAME.findExtensionOrFail(GoCoverageEngine::class.java)

    // A failed build writes no profile; null data would put "coverage data not found" on every open editor.
    override fun loadProjectInfo(): ProjectData? {
        val path = coverageDataFileProvider.coverageDataFilePath ?: return super.loadProjectInfo()
        return if (Files.exists(Path.of(path))) super.loadProjectInfo() else ProjectData()
    }
}

class GoCoverageEnabledConfiguration(configuration: RunConfigurationBase<*>) :
    CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(GoCoverageRunner::class.java))
