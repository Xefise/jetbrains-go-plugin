// Modified from intellij-community (commit 16a8d7c, Apache-2.0): LcovSerializationUtils.normalizeFilePath
package io.github.xefise.golsp.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageAnnotator
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.view.CoverageViewExtension
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.test.GoTestConfiguration
import java.nio.file.Path

/** Coverage of Go Test runs; profile data is keyed by the normalized path of each source file. */
class GoCoverageEngine : CoverageEngine() {
    override fun getPresentableText(): String = "Go Coverage"

    override fun isApplicableTo(conf: RunConfigurationBase<*>): Boolean = conf is GoTestConfiguration

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>): CoverageEnabledConfiguration =
        GoCoverageEnabledConfiguration(conf)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
    ): CoverageSuite = GoCoverageSuite(name, project, runner, fileProvider, timestamp)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
        config: CoverageEnabledConfiguration,
    ): CoverageSuite = GoCoverageSuite(name, project, runner, fileProvider, timestamp).apply { configuration = config.configuration }

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): CoverageSuite = GoCoverageSuite()

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator = GoCoverageAnnotator.getInstance(project)

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean = isGoSource(psiFile)

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean = isGoSource(psiFile)

    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> = setOfNotNull(key(sourceFile))

    // Internal API, but the editor gutter asks only this one (getQualifiedNames is used when there are no output paths).
    override fun getQualifiedName(outputFile: Path, sourceFile: PsiFile): String? = key(sourceFile)

    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle): CoverageViewExtension =
        DirectoryCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)

    companion object {
        private const val TEST_SUFFIX = "_test.go"

        /** The ClassData name of a source file; matches VFS paths (separators, case on Windows). */
        fun pathKey(path: String): String {
            val independent = FileUtil.toSystemIndependentName(path)
            return if (SystemInfo.isWindows) independent.lowercase() else independent
        }

        internal fun configurationOf(suite: CoverageSuite?): RunConfigurationBase<*>? = (suite as? BaseCoverageSuite)?.configuration

        private fun key(file: PsiFile): String? = file.virtualFile?.path?.let(::pathKey)

        private fun isGoSource(file: PsiFile): Boolean = file.name.let { it.endsWith(".go") && !it.endsWith(TEST_SUFFIX) }
    }
}
