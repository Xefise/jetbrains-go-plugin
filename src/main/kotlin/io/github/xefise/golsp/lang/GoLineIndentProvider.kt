package io.github.xefise.golsp.lang

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType
import io.github.xefise.golsp.lang.model.GoIndentStyle
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind
import io.github.xefise.golsp.lang.model.GoLineIndent

/** Smart indent on Enter: Go has no formatting model here (gopls formats on save), so indent from highlighter tokens. */
class GoLineIndentProvider : LineIndentProvider, DumbAware {
    override fun isSuitableFor(language: Language?): Boolean = language == GoLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val highlighter = (editor as? EditorEx)?.highlighter ?: return null
        val document = editor.document
        if (offset < 0 || offset > document.textLength) return null
        val options = CodeStyle.getIndentOptions(project, document)
        val style = GoIndentStyle(options.USE_TAB_CHARACTER, options.TAB_SIZE, options.INDENT_SIZE)
        return GoLineIndent.compute(
            document.immutableCharSequence,
            offset,
            Sequence { goTokensBackward(highlighter, offset) },
            Sequence { forward(highlighter, offset, document.textLength) },
            style,
        )
    }

    private fun forward(highlighter: EditorHighlighter, offset: Int, length: Int): Iterator<GoIndentToken> = iterator {
        if (offset >= length) return@iterator
        val iter = highlighter.createIterator(offset)
        while (!iter.atEnd()) {
            if (iter.end > offset) yield(GoIndentToken(goIndentKind(iter.tokenType), iter.start, iter.end))
            iter.advance()
        }
    }
}

/** Highlighter tokens starting before [offset], nearest first. */
internal fun goTokensBackward(highlighter: EditorHighlighter, offset: Int): Iterator<GoIndentToken> = iterator {
    if (offset == 0) return@iterator
    val iter = highlighter.createIterator(offset - 1)
    while (!iter.atEnd()) {
        if (iter.start < offset) yield(GoIndentToken(goIndentKind(iter.tokenType), iter.start, iter.end))
        iter.retreat()
    }
}

internal fun goIndentKind(type: IElementType?): GoIndentTokenKind = when (type) {
    TokenType.WHITE_SPACE -> GoIndentTokenKind.SPACE
    GoTokenTypes.LBRACE, GoTokenTypes.LPAREN, GoTokenTypes.LBRACKET -> GoIndentTokenKind.OPEN
    GoTokenTypes.RBRACE, GoTokenTypes.RPAREN, GoTokenTypes.RBRACKET -> GoIndentTokenKind.CLOSE
    GoTokenTypes.LINE_COMMENT, GoTokenTypes.BLOCK_COMMENT -> GoIndentTokenKind.COMMENT
    GoTokenTypes.STRING, GoTokenTypes.RAW_STRING, GoTokenTypes.CHAR -> GoIndentTokenKind.STRING
    else -> GoIndentTokenKind.CODE
}
