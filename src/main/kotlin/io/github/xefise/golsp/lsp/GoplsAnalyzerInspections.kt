package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionProfile
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.profile.ProfileChangeAdapter
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature
import com.redhat.devtools.lsp4ij.inspections.LSPLocalInspectionToolBase
import io.github.xefise.golsp.lsp.model.GoplsAnalyses
import io.github.xefise.golsp.lsp.model.GoplsAnalyzer
import io.github.xefise.golsp.lsp.model.GoplsAnalyzerCatalog
import io.github.xefise.golsp.lsp.model.GoplsAnalyzerDocs
import org.eclipse.lsp4j.Diagnostic

/** The inspection profile is the source of truth for which gopls analyzers run; one inspection per analyzer. */
internal object GoplsAnalyzerInspections {
    // Inspection short names are persisted in inspection profiles; the generated plugin xml uses the same prefix.
    private const val SHORT_NAME_PREFIX = "Gopls_"
    private const val CATALOG_RESOURCE = "/golsp/gopls-analyzers.json"

    val catalog: GoplsAnalyzerCatalog by lazy {
        val text = GoplsAnalyzerInspections::class.java.getResourceAsStream(CATALOG_RESOURCE)?.use { it.readBytes().decodeToString() }
        if (text == null) {
            logger<GoplsAnalyzerInspections>().error("$CATALOG_RESOURCE is missing")
            GoplsAnalyzerCatalog("", emptyList())
        } else {
            GoplsAnalyzerCatalog.parse(text)
        }
    }

    fun shortName(analyzer: String): String = SHORT_NAME_PREFIX + analyzer

    fun analyzerName(shortName: String): String = shortName.removePrefix(SHORT_NAME_PREFIX)

    fun analyses(project: Project): Map<String, Boolean> {
        if (project.isDisposed) return emptyMap()
        val profile = profile(project)
        return GoplsAnalyses.map(catalog) { analyzer ->
            HighlightDisplayKey.find(shortName(analyzer.name))?.let(profile::isToolEnabled) ?: enabledByPlugin(analyzer)
        }
    }

    // Before the profile knows the key, our xml default (gopls' default plus our extra checks) beats gopls' own.
    private fun enabledByPlugin(analyzer: GoplsAnalyzer): Boolean =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.firstOrNull { it.shortName == shortName(analyzer.name) }?.enabledByDefault
            ?: analyzer.enabledByGopls

    /** The profile's level when the user changed it, otherwise null: gopls' per-analyzer severity is not in api.json. */
    fun configuredSeverity(project: Project, analyzer: GoplsAnalyzer): HighlightSeverity? {
        if (project.isDisposed) return null
        val shortName = shortName(analyzer.name)
        val key = HighlightDisplayKey.find(shortName) ?: return null
        val profile = profile(project)
        val tool = profile.getInspectionTool(shortName, project) ?: return null
        val level = profile.getErrorLevel(key, null)
        return level.severity.takeUnless { it == tool.defaultLevel.severity }
    }

    private fun profile(project: Project): InspectionProfile = InspectionProjectProfileManager.getInstance(project).currentProfile
}

/** Registered many times from the generated `golsp-gopls-analyzers.xml`; each instance gets its short name from its EP. */
class GoplsAnalyzerInspection : LSPLocalInspectionToolBase() {
    val analyzerName: String get() = GoplsAnalyzerInspections.analyzerName(shortName)

    override fun getStaticDescription(): String? =
        GoplsAnalyzerInspections.catalog[analyzerName]?.let(GoplsAnalyzerDocs::html)
}

/** Routes analyzer diagnostics to their inspections (Inspect Code) and applies a severity changed in the profile. */
internal class GoplsDiagnosticFeature : LSPDiagnosticFeature() {
    private fun analyzerOf(diagnostic: Diagnostic): GoplsAnalyzer? = GoplsAnalyzerInspections.catalog[diagnostic.source]

    override fun isInspectionApplicableFor(file: PsiFile, inspection: LocalInspectionTool): Boolean =
        inspection is GoplsAnalyzerInspection || super.isInspectionApplicableFor(file, inspection)

    // Compiler and type errors stay with LSP4IJ's own "Diagnostics" inspection.
    override fun isInspectionApplicableFor(diagnostic: Diagnostic, inspection: LocalInspectionTool): Boolean =
        if (inspection is GoplsAnalyzerInspection) diagnostic.source == inspection.analyzerName else analyzerOf(diagnostic) == null

    override fun getHighlightSeverity(diagnostic: Diagnostic): HighlightSeverity? {
        val analyzer = analyzerOf(diagnostic) ?: return super.getHighlightSeverity(diagnostic)
        return GoplsAnalyzerInspections.configuredSeverity(clientFeatures.project, analyzer) ?: super.getHighlightSeverity(diagnostic)
    }

    // In Inspect Code this type takes the inspection's level from the profile.
    override fun getProblemHighlightType(diagnostic: Diagnostic): ProblemHighlightType? =
        if (analyzerOf(diagnostic) != null) ProblemHighlightType.GENERIC_ERROR_OR_WARNING else super.getProblemHighlightType(diagnostic)
}

/** Enabling or disabling an analyzer inspection makes gopls pull its configuration again. */
class GoplsInspectionProfileListener(private val project: Project) : ProfileChangeAdapter {
    override fun profileChanged(profile: InspectionProfile) = notifyGopls()

    override fun profileActivated(oldProfile: InspectionProfile?, profile: InspectionProfile?) = notifyGopls()

    private fun notifyGopls() {
        if (!project.isDisposed) GoplsOptions.notifyGopls(project)
    }
}
