package io.github.xefise.golsp.coverage

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData

/** Percentages for the project view and the Coverage tool window, computed from the loaded profile. */
@Service(Service.Level.PROJECT)
class GoCoverageAnnotator(project: Project) : BaseCoverageAnnotator(project) {

    /** Covered and executable lines of a file, or summed over every profiled file below a directory. */
    data class Lines(val covered: Int, val total: Int)

    @Volatile
    private var files: Map<String, Lines> = emptyMap()

    @Volatile
    private var directories: Map<String, Lines> = emptyMap()

    override fun onSuiteChosen(newSuite: CoverageSuitesBundle?) {
        super.onSuiteChosen(newSuite)
        files = emptyMap()
        directories = emptyMap()
    }

    override fun createRenewRequest(suite: CoverageSuitesBundle, dataManager: CoverageDataManager): Runnable = Runnable {
        val data = suite.coverageData
        if (data == null) {
            files = emptyMap()
            directories = emptyMap()
            return@Runnable
        }
        files = fileLines(data)
        directories = directoryLines(files)
    }

    override fun getFileCoverageInformationString(
        project: Project,
        file: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? = files[GoCoverageEngine.pathKey(file.path)]?.let(::describe)

    override fun getDirCoverageInformationString(
        project: Project,
        directory: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? = directories[GoCoverageEngine.pathKey(directory.path)]?.let(::describe)

    companion object {
        fun getInstance(project: Project): GoCoverageAnnotator = project.service()

        internal fun fileLines(data: ProjectData): Map<String, Lines> = data.classes.mapValues { (_, classData) ->
            val lines = classData.lines.orEmpty().filterIsInstance<LineData>()
            Lines(covered = lines.count { it.hits > 0 }, total = lines.size)
        }.filterValues { it.total > 0 }

        internal fun directoryLines(files: Map<String, Lines>): Map<String, Lines> {
            val result = HashMap<String, Lines>()
            for ((path, lines) in files) {
                var directory = path.substringBeforeLast('/', "")
                while (directory.isNotEmpty()) {
                    val sum = result[directory]
                    result[directory] = if (sum == null) lines else Lines(sum.covered + lines.covered, sum.total + lines.total)
                    directory = directory.substringBeforeLast('/', "")
                }
            }
            return result
        }

        private fun describe(lines: Lines): String? =
            if (lines.total == 0) null else "${lines.covered * 100 / lines.total}% lines covered"
    }
}
