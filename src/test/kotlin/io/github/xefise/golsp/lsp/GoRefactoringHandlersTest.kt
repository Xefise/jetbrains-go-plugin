package io.github.xefise.golsp.lsp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.GoLanguage

class GoRefactoringHandlersTest : BasePlatformTestCase() {
    private companion object {
        const val UNTRUSTED = "Go refactorings are disabled in an untrusted project"
        const val TRUST_HEADLESS_DISABLED = "idea.trust.headless.disabled"
    }

    private val goText = "package main\n\nfunc compute(a, b int) int {\n\tsum := <caret>a + b\n\treturn sum\n}\n"

    fun testRefactoringSupportForGoProvidesExtractAndIntroduceHandlers() {
        val provider = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)
        assertInstanceOf(provider, GoRefactoringSupportProvider::class.java)
        assertSame(GoExtractMethodHandler, provider!!.extractMethodHandler)
        assertSame(GoIntroduceHandler.VARIABLE, provider.introduceVariableHandler)
        assertSame(GoIntroduceHandler.CONSTANT, provider.introduceConstantHandler)
    }

    fun testInlineHandlerAcceptsTheLeafUnderTheCaretInAGoFile() {
        myFixture.configureByText("main.go", goText)
        val handler = InlineActionHandler.EP_NAME.extensionList.filterIsInstance<GoInlineActionHandler>().single()
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(handler.isEnabledForLanguage(GoLanguage))
        assertTrue(handler.canInlineElementInEditor(leaf, myFixture.editor))
    }

    fun testPlatformRefactoringActionsAreEnabledInAGoFile() {
        myFixture.configureByText("main.go", goText)
        myFixture.editor.selectionModel.setSelection(myFixture.caretOffset, myFixture.caretOffset + "a + b".length)
        for (id in listOf("ExtractMethod", "IntroduceVariable", "IntroduceConstant", "Inline")) {
            assertTrue("$id should be enabled", isEnabled(id))
        }
    }

    fun testExtractMethodIsNotOfferedInGoMod() {
        myFixture.configureByText("go.mod", "module <caret>example\n\ngo 1.27\n")
        assertFalse(isEnabled("ExtractMethod"))
    }

    fun testIntroduceVariableInAnUntrustedProjectShowsWhyInsteadOfAskingGopls() {
        myFixture.configureByText("main.go", goText)
        myFixture.editor.selectionModel.setSelection(myFixture.caretOffset, myFixture.caretOffset + "a + b".length)
        assertEquals(UNTRUSTED, untrusted { hintOf { GoIntroduceHandler.VARIABLE.invoke(project, myFixture.editor, myFixture.file, null) } })
    }

    fun testInlineInAnUntrustedProjectShowsWhy() {
        myFixture.configureByText("main.go", goText)
        val handler = InlineActionHandler.EP_NAME.extensionList.filterIsInstance<GoInlineActionHandler>().single()
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)
        assertEquals(UNTRUSTED, untrusted { hintOf { handler.inlineElement(project, myFixture.editor, leaf) } })
    }

    fun testExtractMethodWithoutSelectionAsksToSelectStatements() {
        myFixture.configureByText("main.go", goText)
        assertEquals("Select statements to extract", hintOf { GoExtractMethodHandler.invoke(project, myFixture.editor, myFixture.file, null) })
    }

    // Headless runs treat every project as trusted unless this property says otherwise.
    private fun <T> untrusted(action: () -> T): T {
        val previous = System.setProperty(TRUST_HEADLESS_DISABLED, "false")
        TrustedProjects.setProjectTrusted(project, false)
        try {
            return action()
        } finally {
            TrustedProjects.setProjectTrusted(project, true)
            if (previous == null) System.clearProperty(TRUST_HEADLESS_DISABLED) else System.setProperty(TRUST_HEADLESS_DISABLED, previous)
        }
    }

    private fun hintOf(action: () -> Unit): String? = try {
        action()
        null
    } catch (e: RefactoringErrorHintException) {
        e.message
    }

    private fun isEnabled(actionId: String): Boolean {
        val action = ActionManager.getInstance().getAction(actionId)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation.isEnabledAndVisible
    }
}
