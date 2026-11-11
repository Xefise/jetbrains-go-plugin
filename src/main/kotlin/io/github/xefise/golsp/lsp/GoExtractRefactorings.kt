package io.github.xefise.golsp.lsp

import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.lsp.model.GoRefactoringTargets
import io.github.xefise.golsp.lsp.model.GoTextSpan

/** Platform Extract Method / Introduce Variable / Introduce Constant for Go, backed by gopls extract code actions. */
class GoRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun getExtractMethodHandler(): RefactoringActionHandler = GoExtractMethodHandler

    override fun getIntroduceVariableHandler(): RefactoringActionHandler = GoIntroduceHandler.VARIABLE

    override fun getIntroduceConstantHandler(): RefactoringActionHandler = GoIntroduceHandler.CONSTANT
}

/** Works on the editor text; the PSI-element entry point (project view, structure) has nothing to extract from. */
abstract class GoEditorRefactoringHandler(protected val name: String) : RefactoringActionHandler {
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null) return
        val hint = selectionHint(editor) ?: GoRefactoringRunner.unavailableReason(project, file)
        if (hint != null) return GoRefactoringRunner.showHint(project, editor, hint, name)
        invoke(project, editor)
    }

    /** A usage error detectable without gopls; checked first so the user learns it even while gopls is down. */
    protected open fun selectionHint(editor: Editor): String? = null

    protected abstract fun invoke(project: Project, editor: Editor)
}

object GoExtractMethodHandler : GoEditorRefactoringHandler("Extract Method") {
    private val KINDS = listOf(GoCodeActionCatalog.EXTRACT_FUNCTION, GoCodeActionCatalog.EXTRACT_METHOD)

    // gopls offers extract function only for a non-empty range of whole statements.
    override fun selectionHint(editor: Editor): String? = if (editor.selectionModel.hasSelection()) null else "Select statements to extract"

    override fun invoke(project: Project, editor: Editor) {
        val selection = editor.selectionModel
        val original = GoTextSpan(selection.selectionStart, selection.selectionEnd)
        val lines = GoRefactoringTargets.statementLines(editor.document.immutableCharSequence, original)
        selection.setSelection(lines.start, lines.end)
        GoRefactoringRunner.run(
            editor, name, KINDS, TextRange(lines.start, lines.end),
            "gopls cannot extract the selection: select complete statements inside a function body", rename = true,
        ) { if (!editor.isDisposed) selection.setSelection(original.start, original.end) }
    }
}

/** Without a selection offers the expressions around the caret, like the platform's own expression chooser. */
class GoIntroduceHandler private constructor(name: String, private val kinds: List<String>, private val noActions: String) :
    GoEditorRefactoringHandler(name) {

    override fun invoke(project: Project, editor: Editor) {
        val selection = editor.selectionModel
        if (selection.hasSelection()) return extract(editor, TextRange(selection.selectionStart, selection.selectionEnd))
        GoCodeActions.selectionRanges(editor) { ranges ->
            val document = editor.document
            val spans = GoRefactoringTargets.expressions(document.immutableCharSequence, ranges.map { GoTextSpan(it.startOffset, it.endOffset) })
            val targets = spans.map { GoExpressionTarget(document, TextRange(it.start, it.end)) }
            when (targets.size) {
                0 -> GoRefactoringRunner.showHint(project, editor, "No expression at the caret; select one", name)
                1 -> extract(editor, targets.single().textRange)
                else -> IntroduceTargetChooser.showIntroduceTargetChooser(editor, targets, { extract(editor, it.textRange) }, "Expressions", 0)
            }
        }
    }

    private fun extract(editor: Editor, range: TextRange) {
        editor.selectionModel.setSelection(range.startOffset, range.endOffset)
        GoRefactoringRunner.run(editor, name, kinds, range, noActions, rename = true)
    }

    companion object {
        // gopls offers extract.variable only for non-constant expressions and extract.constant only for constant ones.
        val VARIABLE = GoIntroduceHandler(
            "Introduce Variable",
            listOf(
                GoCodeActionCatalog.EXTRACT_VARIABLE, GoCodeActionCatalog.EXTRACT_VARIABLE_ALL,
                GoCodeActionCatalog.EXTRACT_CONSTANT, GoCodeActionCatalog.EXTRACT_CONSTANT_ALL,
            ),
            "gopls cannot extract the selection into a variable: select an expression",
        )
        val CONSTANT = GoIntroduceHandler(
            "Introduce Constant",
            listOf(GoCodeActionCatalog.EXTRACT_CONSTANT, GoCodeActionCatalog.EXTRACT_CONSTANT_ALL),
            "gopls cannot extract the selection into a constant: select a constant expression",
        )
    }
}

/** Inline Call / Inline Variable at the caret; any element of a Go file qualifies, gopls decides what is under the caret. */
class GoInlineActionHandler : InlineActionHandler() {
    override fun isEnabledForLanguage(l: Language?): Boolean = l == GoLanguage

    override fun canInlineElement(element: PsiElement?): Boolean =
        element != null && (element.language == GoLanguage || element.containingFile?.language == GoLanguage)

    override fun inlineElement(project: Project, editor: Editor?, element: PsiElement?) {
        if (editor == null) return
        GoRefactoringRunner.unavailableReason(project, editor)?.let { return GoRefactoringRunner.showHint(project, editor, it, NAME) }
        val selection = editor.selectionModel
        val range = if (selection.hasSelection()) TextRange(selection.selectionStart, selection.selectionEnd) else TextRange.from(editor.caretModel.offset, 0)
        GoRefactoringRunner.run(editor, NAME, KINDS, range, "Place the caret on a function call or on a use of a local variable", rename = false)
    }

    override fun getActionName(element: PsiElement?): String = "Inline Call or Variable..."

    private companion object {
        const val NAME = "Inline"
        val KINDS = listOf(GoCodeActionCatalog.INLINE_CALL, GoCodeActionCatalog.INLINE_VARIABLE)
    }
}
