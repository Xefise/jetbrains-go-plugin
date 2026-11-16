package io.github.xefise.golsp.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.ProjectData
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class GoCoverageSuite : BaseCoverageSuite {
    /** Where `go test` ran when the profile was written; the profile's import paths resolve against its modules. */
    var targetDirectory: String? = null
        private set

    // Used by the platform to restore suites from the workspace.
    constructor() : super()

    constructor(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
        targetDirectory: String?,
    ) : super(name, project, runner, fileProvider, timestamp) {
        this.targetDirectory = targetDirectory
    }

    override fun getCoverageEngine(): CoverageEngine = CoverageEngine.EP_NAME.findExtensionOrFail(GoCoverageEngine::class.java)

    // A failed build writes no profile; null data would put "coverage data not found" on every open editor.
    override fun loadProjectInfo(): ProjectData? {
        val path = coverageDataFileProvider.coverageDataFilePath ?: return super.loadProjectInfo()
        return if (Files.exists(Path.of(path))) super.loadProjectInfo() else ProjectData()
    }

    /** The run's directory, else the project root for imported profiles and suites saved before it was stored. */
    fun resolutionDirectory(): Path? = (targetDirectory ?: project?.basePath)?.let {
        try {
            Path.of(it)
        } catch (_: InvalidPathException) {
            null
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        targetDirectory?.let { element.setAttribute(TARGET_DIRECTORY, it) }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        targetDirectory = element.getAttributeValue(TARGET_DIRECTORY)
    }

    private companion object {
        // Persisted in the workspace with every suite; must never change.
        const val TARGET_DIRECTORY = "GO_TARGET_DIRECTORY"
    }
}

class GoCoverageEnabledConfiguration(configuration: RunConfigurationBase<*>) :
    CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(GoCoverageRunner::class.java))
