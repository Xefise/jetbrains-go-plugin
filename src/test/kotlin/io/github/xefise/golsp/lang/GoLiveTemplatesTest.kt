package io.github.xefise.golsp.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.InvokeTemplateAction
import com.intellij.codeInsight.template.impl.SurroundWithTemplateHandler
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoLiveTemplatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    fun testErrExpandsInsideFuncBody() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() error {\n\terr<caret>\n}\n")
        expandByTab()
        checkText("package main\n\nfunc f() error {\n\tif err != nil {\n\t\treturn <caret>\n\t}\n}\n")
    }

    fun testErrIsNotExpandedAtTopLevel() {
        myFixture.configureByText("main.go", "package main\n\nerr<caret>\n")
        expandByTab()
        checkText("package main\n\nerr<caret>\n")
    }

    fun testErrIsNotExpandedInsideString() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n\ts := \"err<caret>\"\n}\n")
        expandByTab()
        checkText("package main\n\nfunc f() {\n\ts := \"err<caret>\"\n}\n")
    }

    fun testStatementTemplateIsNotExpandedAfterSelectorDot() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n\tx.fp<caret>\n}\n")
        expandByTab()
        checkText("package main\n\nfunc f() {\n\tx.fp<caret>\n}\n")
    }

    fun testStatementTemplateIsNotExpandedMidStatement() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n\tx := err<caret>\n}\n")
        expandByTab()
        checkText("package main\n\nfunc f() {\n\tx := err<caret>\n}\n")
    }

    fun testMainExpandsAtTopLevel() {
        myFixture.configureByText("main.go", "package main\n\nmain<caret>\n")
        expandByTab()
        checkText("package main\n\nfunc main() {\n\t<caret>\n}\n")
    }

    fun testMethReceiverFollowsTypedType() {
        myFixture.configureByText("main.go", "package main\n\nmeth<caret>\n")
        expandByTab(finish = false)
        myFixture.type("*Server")
        nextTab()
        finishTemplate()
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.startsWith("package main\n\nfunc (s *Server) name() "))
    }

    fun testSurroundTemplatesOfferedInsideFuncBody() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n<selection>\tg()\n</selection>}\n")
        val keys = surroundActions().map { it.template.key }
        assertContainsElements(keys, "if", "ife", "for", "fori", "forr", "block", "closure", "go", "def")
    }

    fun testSurroundTemplatesNotOfferedAtTopLevel() {
        myFixture.configureByText("main.go", "package main\n\n<selection>var x = 1\n</selection>")
        val keys = surroundActions().map { it.template.key }
        assertDoesntContain(keys, "if", "for")
    }

    fun testSurroundWithIfWrapsSelectedLines() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n<selection>\tg()\n\th()\n</selection>}\n")
        surroundWith("if")
        checkText("package main\n\nfunc f() {\n\tif condition {\n\t\tg()\n\t\th()\n\t}\n}\n")
    }

    fun testSurroundWithForWrapsSelectedLines() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n<selection>\tg()\n</selection>}\n")
        surroundWith("for")
        checkText("package main\n\nfunc f() {\n\tfor condition {\n\t\tg()\n\t}\n}\n")
    }

    fun testContextTypesAreRegistered() {
        myFixture.configureByText("main.go", "package main\n\nfunc f() {\n\t<caret>\n}\n")
        val ids = TemplateManagerImpl.getApplicableContextTypes(TemplateActionContext.expanding(myFixture.file, myFixture.editor))
            .map { it.contextId }
        assertContainsElements(ids, "GOLSP_GO_STATEMENT")
    }

    private fun expandByTab(finish: Boolean = true) {
        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
        if (finish) finishTemplate()
    }

    private fun checkText(expected: String) {
        val editor = myFixture.editor
        val text = editor.document.text
        val actual = if (expected.contains("<caret>")) text.substring(0, editor.caretModel.offset) + "<caret>" + text.substring(editor.caretModel.offset) else text
        assertEquals(expected, actual)
    }

    private fun nextTab() {
        WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.nextTab() }
    }

    private fun finishTemplate() {
        WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }
    }

    private fun surroundActions(): List<InvokeTemplateAction> =
        SurroundWithTemplateHandler.createActionGroup(myFixture.editor, myFixture.file, HashSet()).filterIsInstance<InvokeTemplateAction>()

    private fun surroundWith(key: String) {
        surroundActions().single { it.template.key == key }.perform()
        finishTemplate()
    }
}
