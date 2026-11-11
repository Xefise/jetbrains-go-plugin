package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.lsp.model.GoRefactoringsMenu

/** Every gopls refactor/source action without its own platform entry point, in one popup. */
object GoRefactorings {
    const val TITLE: String = "Go Refactorings"
    const val NOTHING_HERE: String = "No Go refactorings here"

    /** Unavailable gopls or an untrusted project end in a hint from [GoCodeActions.request]; gopls is never started. */
    @RequiresEdt
    fun show(editor: Editor) {
        GoCodeActions.request(editor, listOf(GoCodeActionCatalog.REFACTOR, GoCodeActionCatalog.SOURCE)) { found ->
            val actions = GoRefactoringsMenu.entries(found) { it.kind }
            if (actions.isEmpty()) {
                HintManager.getInstance().showInformationHint(editor, NOTHING_HERE)
            } else {
                JBPopupFactory.getInstance().createListPopup(Step(editor, actions)).showInBestPositionFor(editor)
            }
        }
    }

    /** The lexer guess for Alt+Enter; never asks gopls. Call under a read action. */
    fun mayOffer(editor: Editor, text: CharSequence): Boolean {
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        return GoRefactoringsMenu.mayOffer(text, tokensThrough(text, offset), offset, editor.selectionModel.hasSelection())
    }

    private fun tokensThrough(text: CharSequence, offset: Int): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null && lexer.tokenStart <= offset) {
            tokens += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    // toString is what popup interceptors in tests match on.
    private class Entry(val action: GoCodeAction) {
        override fun toString(): String = action.title
    }

    private class Step(private val editor: Editor, actions: List<GoCodeAction>) : BaseListPopupStep<Entry>(TITLE, actions.map(::Entry)) {
        override fun getTextFor(value: Entry): String = value.action.title

        override fun getSeparatorAbove(value: Entry): ListSeparator? {
            val index = values.indexOf(value)
            val group = value.action.kind.group
            return if (index > 0 && values[index - 1].action.kind.group != group) ListSeparator(GoRefactoringsMenu.groupTitle(group)) else null
        }

        override fun isSpeedSearchEnabled(): Boolean = true

        override fun onChosen(selectedValue: Entry, finalChoice: Boolean): PopupStep<*>? =
            doFinalStep { if (!editor.isDisposed) GoCodeActions.apply(editor, selectedValue.action) }
    }
}

/** Refactor → Go Refactorings…; extends [BaseRefactoringAction] because Refactor This lists only those. */
class GoRefactoringsAction : BaseRefactoringAction(), DumbAware {
    override fun isAvailableInEditorOnly(): Boolean = true

    override fun isEnabledOnElements(elements: Array<out PsiElement>): Boolean = false

    override fun isAvailableForLanguage(language: Language): Boolean = language.isKindOf(GoLanguage)

    override fun isAvailableForFile(file: PsiFile?): Boolean = file != null && file.language.isKindOf(GoLanguage)

    override fun getHandler(dataContext: DataContext): RefactoringActionHandler = Handler

    private object Handler : RefactoringActionHandler {
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
            if (editor != null) GoRefactorings.show(editor)
        }

        override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
    }
}

class GoRefactoringsIntention : IntentionAction, DumbAware {
    override fun getText(): String = "Go refactorings…"

    override fun getFamilyName(): String = "Go refactorings"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && file.language.isKindOf(GoLanguage) && GoRefactorings.mayOffer(editor, file.viewProvider.contents)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null) GoRefactorings.show(editor)
    }

    override fun startInWriteAction(): Boolean = false

    // The preview would run invoke() on a copy and open the popup.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY
}
