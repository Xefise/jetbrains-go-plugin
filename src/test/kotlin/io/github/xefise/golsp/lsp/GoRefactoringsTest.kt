package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.EditorHintFixture

class GoRefactoringsTest : BasePlatformTestCase() {
    private val action get() = ActionManager.getInstance().getAction("golsp.GoRefactorings")

    fun testActionIsInTheRefactorMenu() {
        assertInstanceOf(action, GoRefactoringsAction::class.java)
        val menu = ActionManager.getInstance().getAction(IdeActions.GROUP_REFACTOR) as DefaultActionGroup
        assertTrue(menu.childActionsOrStubs.any { it === action || ActionManager.getInstance().getId(it) == "golsp.GoRefactorings" })
    }

    fun testActionIsEnabledInAGoFile() {
        myFixture.configureByText("a.go", "package main\n\nvar p = Point{<caret>}\n")
        val presentation = update()
        assertTrue(presentation.isVisible && presentation.isEnabled)
    }

    fun testActionIsHiddenInANonGoFile() {
        myFixture.configureByText("a.txt", "Point{<caret>}")
        assertFalse(update().isVisible)
    }

    fun testIntentionIsRegistered() {
        val registered = IntentionManager.getInstance().intentionActions.map { IntentionActionDelegate.unwrap(it) }
        assertTrue(registered.any { it is GoRefactoringsIntention })
    }

    fun testIntentionIsAvailableInAGoCompositeLiteral() {
        myFixture.configureByText("a.go", "package main\n\nvar p = Point{<caret>}\n")
        assertTrue(GoRefactoringsIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testIntentionIsNotAvailableOnAPlainStatement() {
        myFixture.configureByText("a.go", "package main\n\nfunc main() {\n\tx := 1<caret>\n}\n")
        assertFalse(GoRefactoringsIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testIntentionIsNotAvailableInANonGoFile() {
        myFixture.configureByText("a.txt", "Point{<caret>}")
        assertFalse(GoRefactoringsIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    fun testUntrustedProjectShowsAHintInsteadOfAskingGopls() {
        myFixture.configureByText("a.go", "package main\n\nvar p = Point{<caret>}\n")
        val hints = EditorHintFixture(testRootDisposable)
        val previous = System.setProperty(TRUST_HEADLESS_DISABLED, "false")
        try {
            TrustedProjects.setProjectTrusted(project, false)
            GoRefactoringsIntention().invoke(project, myFixture.editor, myFixture.file)
        } finally {
            TrustedProjects.setProjectTrusted(project, true)
            if (previous == null) System.clearProperty(TRUST_HEADLESS_DISABLED) else System.setProperty(TRUST_HEADLESS_DISABLED, previous)
        }
        val hint = hints.currentHintText
        assertTrue(hint, hint.orEmpty().contains("<body>Go refactorings are disabled in an untrusted project</body>"))
    }

    private fun update() = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(LangDataKeys.CONTEXT_LANGUAGES, arrayOf(myFixture.file.language))
            .build(),
    ).also { action.update(it) }.presentation

    private companion object {
        const val TRUST_HEADLESS_DISABLED = "idea.trust.headless.disabled"
    }
}
