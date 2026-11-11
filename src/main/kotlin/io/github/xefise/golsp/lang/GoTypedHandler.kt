package io.github.xefise.golsp.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import io.github.xefise.golsp.lang.model.GoIndentTokenKind
import io.github.xefise.golsp.lang.model.GoLineIndent

/** Outdents a closing bracket typed at line start and a `case`/`default` line on its `:` (no formatter does it for us). */
class GoTypedHandler : TypedHandlerDelegate(), DumbAware {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '}' && c != ')' && c != ']' && c != ':') return Result.CONTINUE
        if (file.language != GoLanguage) return Result.CONTINUE
        val highlighter = (editor as? EditorEx)?.highlighter ?: return Result.CONTINUE
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = editor.caretModel.offset - 1
        if (offset < 0 || offset >= text.length || text[offset] != c) return Result.CONTINUE
        if (goIndentKind(highlighter.createIterator(offset).tokenType) != kindOf(c)) return Result.CONTINUE

        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val firstCode = CharArrayUtil.shiftForward(text, lineStart, " \t")
        val indent = if (c == ':') {
            val first = highlighter.createIterator(firstCode)
            val word = if (first.atEnd() || first.tokenType != GoTokenTypes.KEYWORD) null else text.substring(first.start, first.end)
            if (word != "case" && word != "default") return Result.CONTINUE
            GoLineIndent.enclosingBlockIndent(text, firstCode, Sequence { goTokensBackward(highlighter, firstCode) })
        } else {
            if (firstCode != offset) return Result.CONTINUE
            GoLineIndentProvider().getLineIndent(project, editor, GoLanguage, offset)
        } ?: return Result.CONTINUE

        if (text.substring(lineStart, firstCode) != indent) document.replaceString(lineStart, firstCode, indent)
        return Result.CONTINUE
    }

    private fun kindOf(c: Char): GoIndentTokenKind = if (c == ':') GoIndentTokenKind.CODE else GoIndentTokenKind.CLOSE
}
