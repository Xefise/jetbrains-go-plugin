package io.github.xefise.golsp.lang

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.codeInsight.editorActions.SelectWordUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoSelectionSteps

// All Extend Selection and double-click steps for Go come from the lexer: gopls selectionRange is switched off
// in GoplsClientFeatures, so selecting never waits for the server.
class GoWordSelectioner : ExtendWordSelectionHandlerBase(), DumbAware {
    override fun canSelect(e: PsiElement): Boolean =
        e.firstChild == null && e !is PsiWhiteSpace && e.language == GoLanguage

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> {
        val ranges = ArrayList<TextRange>()
        SelectWordUtil.addWordOrLexemeSelection(editor.settings.isCamelWords, editor, cursorOffset, ranges)
        if (e.node.elementType in GoTokenTypes.STRINGS) stringContent(e.textRange, editorText)?.let(ranges::add)
        ranges += e.textRange
        GoSelectionSteps.compute(editorText, tokens(editorText), cursorOffset).mapTo(ranges) { TextRange(it.start, it.end) }
        return ranges
    }

    private fun tokens(text: CharSequence): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<GoIndentToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += GoIndentToken(goIndentKind(type), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }

    private fun stringContent(range: TextRange, text: CharSequence): TextRange? {
        if (range.length < 3 || text[range.startOffset] != text[range.endOffset - 1]) return null
        if (text[range.startOffset] != '`') {
            // An escaped closing quote means the literal is unterminated: "abc\"
            var i = range.endOffset - 2
            while (i > range.startOffset && text[i] == '\\') i--
            if ((range.endOffset - 2 - i) % 2 == 1) return null
        }
        return TextRange(range.startOffset + 1, range.endOffset - 1)
    }
}
