package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lang.goTokensBackward
import io.github.xefise.golsp.lsp.model.GoCallArguments

/**
 * Rider-style parameter info: opens while typing inside a call's parentheses and after a completed call.
 * The platform only pops it on `(`/`,` and drops the request if the caret moves before the delay ends.
 */
internal object GoParameterInfoAutoPopup {
    fun maybeShow(project: Project, editor: Editor, file: PsiFile?) {
        if (file?.language != GoLanguage || editor.isDisposed) return
        if (!CodeInsightSettings.getInstance().AUTO_POPUP_PARAMETER_INFO) return
        if (ParameterInfoControllerBase.existsWithVisibleHintForEditor(editor, false)) return
        val highlighter = (editor as? EditorEx)?.highlighter ?: return
        val offset = editor.caretModel.offset
        if (!GoCallArguments.isInside(goTokensBackward(highlighter, offset).asSequence(), editor.document.immutableCharSequence)) return
        AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
    }
}

class GoParameterInfoTypedHandler : TypedHandlerDelegate(), DumbAware {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        // gopls' own trigger characters; LSP4IJ already pops the hint for them.
        if (c != '(' && c != ',') GoParameterInfoAutoPopup.maybeShow(project, editor, file)
        return Result.CONTINUE
    }
}

class GoParameterInfoLookupListener(private val project: Project) : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        if (newLookup?.psiFile?.language != GoLanguage) return
        newLookup.addLookupListener(object : LookupListener {
            override fun itemSelected(event: LookupEvent) {
                val editor = event.lookup?.editor ?: return
                val file = event.lookup?.psiFile
                // After the insert handler and its caret moves have finished.
                ApplicationManager.getApplication().invokeLater({ GoParameterInfoAutoPopup.maybeShow(project, editor, file) }, project.disposed)
            }
        })
    }
}
