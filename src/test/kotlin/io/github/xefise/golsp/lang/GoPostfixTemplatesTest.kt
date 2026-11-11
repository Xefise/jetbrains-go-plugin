package io.github.xefise.golsp.lang

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoPostfixTemplatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    fun testIfWrapsStatementAndPutsCaretInBody() {
        expand("ok.if")
        myFixture.checkResult(inFunc("if ok {\n\t\t<caret>\n\t}"))
    }

    fun testIfKeepsUnaryNot() {
        expand("!ok.if")
        myFixture.checkResult(inFunc("if !ok {\n\t\t<caret>\n\t}"))
    }

    fun testNnChecksNotNil() {
        expand("conn.Close().nn")
        myFixture.checkResult(inFunc("if conn.Close() != nil {\n\t\t<caret>\n\t}"))
    }

    fun testNilChecksNil() {
        expand("p.nil")
        myFixture.checkResult(inFunc("if p == nil {\n\t\t<caret>\n\t}"))
    }

    fun testErrReturnsFromNotNilBranch() {
        expand("err.err")
        myFixture.checkResult(inFunc("if err != nil {\n\t\treturn <caret>\n\t}"))
    }

    fun testIfInNestedBlockUsesThatBlockIndent() {
        myFixture.configureByText("a.go", inFunc("for {\n\t\tok.if<caret>\n\t}"))
        myFixture.type('\t')
        myFixture.checkResult(inFunc("for {\n\t\tif ok {\n\t\t\t<caret>\n\t\t}\n\t}"))
    }

    fun testNotNegatesOperandInsideExpression() {
        expand("x := ready.not")
        myFixture.checkResult(inFunc("x := !ready<caret>"))
    }

    fun testNotRemovesExistingNegation() {
        expand("x := !ready.not")
        myFixture.checkResult(inFunc("x := ready<caret>"))
    }

    fun testSingleUndoRestoresTypedKey() {
        expand("err.nn")
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)
        UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals(inFunc("err.nn"), myFixture.editor.document.text)
    }

    fun testParWrapsOnlyRightOperand() {
        expand("x := a + b.par")
        myFixture.checkResult(inFunc("x := a + (b)<caret>"))
    }

    fun testVarDeclaresVariableWithEditableName() {
        expand("load().var")
        myFixture.checkResult(inFunc("<selection>v<caret></selection> := load()"))
        myFixture.type("cfg")
        TemplateManagerImpl.getTemplateState(myFixture.editor)!!.gotoEnd(false)
        myFixture.checkResult(inFunc("cfg := load()<caret>"))
    }

    fun testForRangesOverOperand() {
        expand("items.for")
        TemplateManagerImpl.getTemplateState(myFixture.editor)!!.nextTab()
        myFixture.checkResult(inFunc("for _, v := range items {\n\t\t<caret>\n\t}"))
    }

    fun testDeferNeedsCall() {
        expand("f.Close().defer")
        myFixture.checkResult(inFunc("defer f.Close()<caret>"))
    }

    fun testDeferOnNonCallDoesNotExpand() {
        assertNotExpanded(inFunc("f.defer<caret>"))
    }

    // Tab may still expand the `go` live template; only the postfix form must not appear.
    fun testGoOnNegatedCallDoesNotExpand() {
        myFixture.configureByText("a.go", inFunc("!f().go<caret>"))
        myFixture.type('\t')
        assertFalse(myFixture.editor.document.text.contains("go !f()"))
    }

    fun testParWrapsWholeConversion() {
        expand("x := []byte(s).par")
        myFixture.checkResult(inFunc("x := ([]byte(s))<caret>"))
    }

    fun testStatementTemplateInCallArgumentDoesNotExpand() {
        assertNotExpanded(inFunc("g(x.if<caret>)"))
    }

    fun testExpressionTemplateInCallArgumentExpands() {
        expand("g(x.not)", caretBefore = ")")
        myFixture.checkResult(inFunc("g(!x<caret>)"))
    }

    fun testTemplateAtTopLevelDoesNotExpand() {
        assertNotExpanded("package main\n\nvar v = x.not<caret>\n")
    }

    fun testTemplateInStringDoesNotExpand() {
        assertNotExpanded(inFunc("s := \"x.if<caret>\""))
    }

    fun testTemplateInCommentDoesNotExpand() {
        assertNotExpanded(inFunc("// x.if<caret>"))
    }

    private fun inFunc(body: String) = "package main\n\nfunc f() {\n\t$body\n}\n"

    private fun expand(body: String, caretBefore: String? = null) {
        val text = if (caretBefore == null) inFunc("$body<caret>")
        else inFunc(body.substring(0, body.lastIndexOf(caretBefore)) + "<caret>" + body.substring(body.lastIndexOf(caretBefore)))
        myFixture.configureByText("a.go", text)
        myFixture.type('\t')
    }

    private fun assertNotExpanded(textWithCaret: String) {
        myFixture.configureByText("a.go", textWithCaret)
        val before = myFixture.editor.document.text
        val offset = myFixture.editor.caretModel.offset
        myFixture.type('\t')
        assertEquals(before.substring(0, offset) + "\t" + before.substring(offset), myFixture.editor.document.text)
    }
}
