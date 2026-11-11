package io.github.xefise.golsp.lang

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.model.GoForeignSyntax

// Runs the inspection directly: the highlighting daemon would also start LSP4IJ and wait for gopls.
class GoForeignSyntaxInspectionTest : BasePlatformTestCase() {
    private fun body(lines: String) = "package main\n\nfunc main() {\n$lines}\n"

    private fun problems(text: String): List<ProblemDescriptor> {
        myFixture.configureByText("a.go", text)
        return GoForeignSyntaxInspection().checkFile(myFixture.file, InspectionManager.getInstance(project), true).orEmpty().toList()
    }

    private fun applyFix(text: String, fixName: String): String {
        val descriptor = problems(text).single()
        val fix = descriptor.fixes!!.single { it.name == fixName }
        WriteCommandAction.runWriteCommandAction(project) { fix.applyFix(project, descriptor) }
        return myFixture.editor.document.text
    }

    fun testInspectionIsRegisteredForGoAsWarningEnabledByDefault() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == "GoForeignSyntax" }
        assertEquals(GoLanguage.id, ep.language)
        assertEquals("WARNING", ep.level)
        assertTrue(ep.enabledByDefault)
    }

    fun testTypeFirstDeclarationIsReportedOnTypeAndName() {
        val problem = problems(body("\t[]float64 arr := nil\n\tx := []int{1}\n")).single()
        assertEquals("[]float64 arr", problem.textRangeInElement.substring(myFixture.file.text))
        assertEquals(GoForeignSyntax.TYPE_FIRST_MESSAGE, problem.descriptionTemplate)
        assertEquals(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, problem.highlightType)
    }

    fun testSpreadIsReported() {
        val problem = problems(body("\tf(...xs)\n\tf(xs...)\n")).single()
        assertEquals("...xs", problem.textRangeInElement.substring(myFixture.file.text))
        assertEquals(GoForeignSyntax.SPREAD_MESSAGE, problem.descriptionTemplate)
    }

    fun testValidGoIsNotReported() {
        assertEmpty(problems("package main\n\nfunc f(xs ...int, a, b []int) {\n\tvar x []int\n\tg(xs...)\n}\n"))
    }

    fun testShortDeclarationFixDropsVarAndType() =
        assertEquals(body("\ta := 0\n"), applyFix(body("\tvar int a := 0\n"), "Change to 'a := …'"))

    fun testTypedDeclarationFixKeepsType() =
        assertEquals(body("\tvar a int = 0\n"), applyFix(body("\tvar int a := 0\n"), "Change to 'var a int = …'"))

    fun testParameterFixSwapsTypeAndName() = assertEquals(
        "package main\n\nfunc f(arr []float64, n int) {}\n",
        applyFix("package main\n\nfunc f([]float64 arr, n int) {}\n", "Change to 'arr []float64'"),
    )

    fun testSpreadFixMovesEllipsisAfterArgument() =
        assertEquals(body("\tappend(a, b...)\n"), applyFix(body("\tappend(a, ...b)\n"), "Change to 'b...'"))

    fun testFixOnChangedTextDoesNothing() {
        val descriptor = problems(body("\tint n := 1\n")).single()
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// x\n") }
        WriteCommandAction.runWriteCommandAction(project) { descriptor.fixes!!.first().applyFix(project, descriptor) }
        assertEquals("// x\n" + body("\tint n := 1\n"), myFixture.editor.document.text)
    }

    fun testFixPreviewShowsTheRewrittenCode() {
        val descriptor = problems(body("\tint n := 1\n")).single()
        val action = QuickFixWrapper.wrap(descriptor, 0)
        assertEquals(body("\tn := 1\n"), myFixture.getIntentionPreviewText(action))
        assertEquals(body("\tint n := 1\n"), myFixture.editor.document.text)
    }
}
