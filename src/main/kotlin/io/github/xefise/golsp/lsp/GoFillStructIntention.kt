package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog

/** Alt+Enter inside `T{…}`: gopls' fillStruct, which sets every missing field to its zero value. */
class GoFillStructIntention : IntentionAction, DumbAware {
    override fun getText(): String = "Fill all fields"

    override fun getFamilyName(): String = "Fill struct fields"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file != null && file.language.isKindOf(GoLanguage) &&
            GoStructLiteralInfo.siteAt(editor, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        val offset = editor.caretModel.offset
        GoCodeActions.request(editor, listOf(GoCodeActionCatalog.FILL_STRUCT), TextRange(offset, offset)) { actions ->
            val action = actions.firstOrNull()
            if (action == null) HintManager.getInstance().showInformationHint(editor, NOTHING_TO_FILL) else GoCodeActions.apply(editor, action)
        }
    }

    override fun startInWriteAction(): Boolean = false

    // The preview would run invoke() on a copy and ask gopls.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    companion object {
        const val NOTHING_TO_FILL: String = "No struct fields to fill here"
    }
}
