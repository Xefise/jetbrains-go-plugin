package io.github.xefise.golsp.lsp

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.introduce.IntroduceTarget
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.xefise.golsp.lsp.model.GoRefactoringTargets

/** Request → (choose) → apply → rename flow shared by the Go extract/introduce/inline handlers. Call on EDT. */
internal object GoRefactoringRunner {
    fun unavailableReason(project: Project, file: PsiFile?): String? = GoCodeActions.unavailableReason(project, file?.virtualFile)

    fun unavailableReason(project: Project, editor: Editor): String? =
        GoCodeActions.unavailableReason(project, FileDocumentManager.getInstance().getFile(editor.document))

    fun showHint(project: Project, editor: Editor, message: String, refactoringName: String) =
        CommonRefactoringUtil.showErrorHint(project, editor, message, refactoringName, null)

    /**
     * Asks gopls for [kinds] on [range]; several answers go to a chooser. With [rename], the caret lands on the name gopls
     * gave the new declaration and Rename starts on it.
     */
    fun run(
        editor: Editor, name: String, kinds: List<String>, range: TextRange, noActions: String, rename: Boolean,
        onNoActions: () -> Unit = {},
    ) {
        val project = editor.project ?: return
        GoCodeActions.request(editor, kinds, range) { actions ->
            when (actions.size) {
                0 -> {
                    onNoActions()
                    showHint(project, editor, noActions, name)
                }
                1 -> apply(editor, actions.single(), rename)
                else -> choose(editor, name, actions) { apply(editor, it, rename) }
            }
        }
    }

    private fun choose(editor: Editor, title: String, actions: List<GoCodeAction>, onChosen: (GoCodeAction) -> Unit) {
        val step = object : BaseListPopupStep<GoCodeAction>(title, actions) {
            override fun getTextFor(value: GoCodeAction): String = value.title

            override fun onChosen(selectedValue: GoCodeAction, finalChoice: Boolean): PopupStep<*>? =
                doFinalStep { if (!editor.isDisposed) onChosen(selectedValue) }
        }
        JBPopupFactory.getInstance().createListPopup(step).showInBestPositionFor(editor)
    }

    private fun apply(editor: Editor, action: GoCodeAction, rename: Boolean) {
        val before = editor.document.immutableCharSequence
        GoCodeActions.apply(editor, action) { outcome ->
            if (!rename || outcome != GoCodeActionOutcome.EDITED) return@apply
            val name = GoRefactoringTargets.newName(before, editor.document.immutableCharSequence) ?: return@apply
            editor.selectionModel.removeSelection()
            editor.caretModel.moveToOffset(name.start)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            startRename(editor)
        }
    }

    // LSP4IJ's rename handler serves the Rename action for Go; in unit-test mode it needs a test hook we don't install.
    private fun startRename(editor: Editor) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode) return
        application.invokeLater {
            val project = editor.project ?: return@invokeLater
            if (editor.isDisposed || project.isDisposed) return@invokeLater
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            val actionManager = ActionManager.getInstance()
            val renameAction = actionManager.getAction(IdeActions.ACTION_RENAME) ?: return@invokeLater
            actionManager.tryToExecute(renameAction, null, editor.contentComponent, ActionPlaces.UNKNOWN, true)
        }
    }
}

/** An expression offered by the introduce chooser; stale once the document changes. */
internal class GoExpressionTarget(private val document: Document, private val range: TextRange) : IntroduceTarget {
    private val stamp = document.modificationStamp

    override fun getTextRange(): TextRange = range

    override fun getPlace(): PsiElement? = null

    override fun render(): String = range.subSequence(document.immutableCharSequence).toString()

    override fun isValid(): Boolean = document.modificationStamp == stamp
}
