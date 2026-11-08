// Modified from go-lang-idea-plugin (commit e73dab4, Apache-2.0): GoCoverageRunner; intellij-community (commit 16a8d7c, Apache-2.0): LcovSerializationUtils.convertToProjectData
package io.github.xefise.golsp.coverage

import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import io.github.xefise.golsp.coverage.model.GoCoverLines
import io.github.xefise.golsp.coverage.model.GoCoverProfileParser
import io.github.xefise.golsp.coverage.model.GoLineHits
import io.github.xefise.golsp.test.GoTestConfiguration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Loads `go test -coverprofile` files; called by the platform in the background. */
class GoCoverageRunner : CoverageRunner() {
    override fun getPresentableName(): String = "Go"

    // Persisted in the workspace with every coverage suite; must never change.
    override fun getId(): String = ID

    override fun getDataFileExtension(): String = "out"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is GoCoverageEngine

    override fun loadCoverageData(
        sessionDataFile: Path,
        baseCoverageSuite: CoverageSuite?,
        reporter: CoverageLoadErrorReporter,
    ): CoverageLoadingResult {
        // A suite restored after a restart has no configuration: only absolute paths of the profile resolve then.
        val configuration = GoCoverageEngine.configurationOf(baseCoverageSuite) as? GoTestConfiguration
        return load(sessionDataFile, configuration?.targetDirectory())
    }

    companion object {
        const val ID: String = "GoLspCoverage"

        // A corrupt profile line must not allocate a huge array.
        private const val MAX_LINE = 10_000_000

        internal fun load(profile: Path, targetDirectory: Path?): CoverageLoadingResult {
            // No profile means the build failed: nothing ran, which is not a loading error.
            if (!Files.exists(profile)) return SuccessCoverageLoadingResult(ProjectData())
            val text = try {
                Files.readString(profile)
            } catch (e: IOException) {
                return FailedCoverageLoadingResult("Cannot read Go cover profile $profile", e)
            }
            val resolver = GoCoverageModules.resolver(targetDirectory)
            val lines = GoCoverLines.byFile(GoCoverProfileParser.parse(text).blocks, resolver::resolve)
            return SuccessCoverageLoadingResult(projectData(lines))
        }

        internal fun projectData(files: Map<String, Map<Int, GoLineHits>>): ProjectData {
            val data = ProjectData()
            for ((path, lines) in files) {
                val maxLine = lines.keys.filter { it <= MAX_LINE }.maxOrNull() ?: continue
                // Line numbers index the array directly; slot 0 stays empty.
                val array = arrayOfNulls<LineData>(maxLine + 1)
                for ((line, hits) in lines) {
                    if (line > MAX_LINE) continue
                    array[line] = LineData(line, null).apply {
                        setHits(hits.hits)
                        if (hits.partial) setStatus(LineCoverage.PARTIAL)
                    }
                }
                data.getOrCreateClassData(GoCoverageEngine.pathKey(path)).setLines(array)
            }
            return data
        }
    }
}
