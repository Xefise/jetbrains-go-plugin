package io.github.xefise.golsp.lang

import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoSmartEnter

/** Complete Current Statement from the caret line's lexer tokens; returning false lets the platform add a plain new line. */
class GoSmartEnterProcessor : SmartEnterProcessor(), DumbAware {
    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        val highlighter = (editor as? EditorEx)?.highlighter ?: return false
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val text = document.immutableCharSequence
        val plan = GoSmartEnter.plan(text.subSequence(lineStart, lineEnd), lineTokens(highlighter, lineStart, lineEnd)) ?: return false

        val close = plan.blockClose
        if (close == null) {
            if (plan.replaceStart != plan.replaceEnd || plan.text.isNotEmpty()) {
                document.replaceString(lineStart + plan.replaceStart, lineStart + plan.replaceEnd, plan.text)
            }
            return false
        }

        val lineIndent = text.substring(lineStart, CharArrayUtil.shiftForward(text, lineStart, lineEnd, " \t"))
        document.replaceString(lineStart + plan.tailAt, lineEnd, "\n\n$lineIndent$close")
        document.replaceString(lineStart + plan.replaceStart, lineStart + plan.replaceEnd, plan.text)
        val bodyStart = document.getLineStartOffset(line + 1)
        val bodyIndent = GoLineIndentProvider().getLineIndent(project, editor, GoLanguage, bodyStart) ?: lineIndent
        document.insertString(bodyStart, bodyIndent)
        editor.caretModel.moveToOffset(bodyStart + bodyIndent.length)
        return true
    }

    private fun lineTokens(highlighter: EditorHighlighter, lineStart: Int, lineEnd: Int): List<GoIndentToken> {
        val tokens = ArrayList<GoIndentToken>()
        val iter = highlighter.createIterator(lineStart)
        while (!iter.atEnd() && iter.start < lineEnd) {
            if (iter.end > lineStart) tokens.add(GoIndentToken(goIndentKind(iter.tokenType), iter.start - lineStart, iter.end - lineStart))
            iter.advance()
        }
        return tokens
    }
}
