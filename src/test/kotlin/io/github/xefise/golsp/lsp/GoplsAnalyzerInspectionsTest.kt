package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.disableInspections
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range

class GoplsAnalyzerInspectionsTest : BasePlatformTestCase() {
    private fun ep(analyzer: String): LocalInspectionEP =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == "Gopls_$analyzer" }

    fun testEveryCatalogAnalyzerIsRegisteredWithTheSharedClass() {
        val registered = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { it.implementationClass == GoplsAnalyzerInspection::class.java.name }
            .map { it.shortName }
        assertEquals(GoplsAnalyzerInspections.catalog.analyzers.map { "Gopls_${it.name}" }, registered)
    }

    fun testSharedClassInstanceKnowsItsAnalyzer() {
        val tool = ep("SA4006").instantiateTool() as GoplsAnalyzerInspection
        assertEquals("SA4006", tool.analyzerName)
        assertEquals("Gopls_SA4006", tool.id)
    }

    fun testDescriptionComesFromTheAnalyzerDoc() {
        val description = ep("SA4006").instantiateTool().loadDescription().orEmpty()
        assertTrue(description, description.contains("never read before being overwritten"))
        assertTrue(description, description.contains("https://staticcheck.dev/docs/checks/#SA4006"))
    }

    fun testStaticcheckAnalyzersSitUnderTheGoGroup() {
        assertEquals(listOf("Go", "Staticcheck: bugs (SA)"), ep("SA4006").getGroupPath()?.toList())
        assertEquals(listOf("Go", "gopls"), ep("printf").getGroupPath()?.toList())
    }

    // Light tests run with a test profile that enables only the tools a test asks for.
    fun testAnalysesFollowInspectionsEnabledInTheProfile() {
        myFixture.enableInspections(ep("printf").instantiateTool() as LocalInspectionTool)
        val analyses = GoplsAnalyzerInspections.analyses(project)
        assertEquals(GoplsAnalyzerInspections.catalog.analyzers.size, analyses.size)
        assertEquals(true, analyses["printf"])
        assertEquals(false, analyses["shadow"])
    }

    fun testDisablingAnInspectionTurnsItsAnalyzerOff() {
        val tool = ep("printf").instantiateTool() as LocalInspectionTool
        myFixture.enableInspections(tool)
        disableInspections(project, tool)
        assertEquals(false, GoplsAnalyzerInspections.analyses(project)["printf"])
    }

    private fun tool(analyzer: String) = ep(analyzer).instantiateTool() as LocalInspectionTool

    private fun diagnostic(source: String?) = Diagnostic(Range(), "msg").also { it.source = source }

    fun testAnalyzerDiagnosticsGoOnlyToTheirOwnInspection() {
        val feature = GoplsDiagnosticFeature()
        val printf = tool("printf")
        val shadow = tool("shadow")
        assertTrue(feature.isInspectionApplicableFor(diagnostic("printf"), printf))
        assertFalse(feature.isInspectionApplicableFor(diagnostic("printf"), shadow))
    }

    fun testDiagnosticsWithUnknownSourceStayWithTheDiagnosticsInspection() {
        val feature = GoplsDiagnosticFeature()
        val other = object : LocalInspectionTool() {}
        assertTrue(feature.isInspectionApplicableFor(diagnostic("compiler"), other))
        assertTrue(feature.isInspectionApplicableFor(diagnostic(null), other))
        assertFalse(feature.isInspectionApplicableFor(diagnostic("printf"), other))
        assertFalse(feature.isInspectionApplicableFor(diagnostic("compiler"), tool("printf")))
    }

    fun testAnalyzerDiagnosticOffersSettingsThenDisableBelowGoplsFixes() {
        myFixture.enableInspections(tool("ST1000"))
        val actions = GoplsAnalyzerInspections.profileActions(GoplsAnalyzerInspections.catalog["ST1000"]!!)
        assertEquals(listOf("Edit 'ST1000' inspection settings", "Disable 'ST1000' inspection"), actions.map { it.text })
        assertEquals(listOf(PriorityAction.Priority.LOW, PriorityAction.Priority.BOTTOM), actions.map { (it as PriorityAction).priority })
        // The hover tooltip runs a fix by its unwrapped text; a delegate would make the link a no-op.
        assertTrue(actions.none { it is IntentionActionDelegate })
        assertFalse(actions[0].startInWriteAction())
    }
}
