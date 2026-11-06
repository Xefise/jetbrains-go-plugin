// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

// External names are persisted in color schemes; GOLSP_ avoids clashing with the official plugin's GO_.
object GoColors {
    private fun key(name: String, fallback: TextAttributesKey) =
        TextAttributesKey.createTextAttributesKey("GOLSP_$name", fallback)

    val LINE_COMMENT: TextAttributesKey = key("LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val BLOCK_COMMENT: TextAttributesKey = key("BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val KEYWORD: TextAttributesKey = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val BUILTIN: TextAttributesKey = key("BUILTIN", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
    val CONSTANT: TextAttributesKey = key("CONSTANT", DefaultLanguageHighlighterColors.CONSTANT)
    val IDENTIFIER: TextAttributesKey = key("IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val STRING: TextAttributesKey = key("STRING", DefaultLanguageHighlighterColors.STRING)
    val RAW_STRING: TextAttributesKey = key("RAW_STRING", DefaultLanguageHighlighterColors.STRING)
    val RUNE: TextAttributesKey = key("RUNE", DefaultLanguageHighlighterColors.STRING)
    val NUMBER: TextAttributesKey = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val OPERATOR: TextAttributesKey = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val PARENTHESES: TextAttributesKey = key("PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACES: TextAttributesKey = key("BRACES", DefaultLanguageHighlighterColors.BRACES)
    val BRACKETS: TextAttributesKey = key("BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val COMMA: TextAttributesKey = key("COMMA", DefaultLanguageHighlighterColors.COMMA)
    val SEMICOLON: TextAttributesKey = key("SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    val DOT: TextAttributesKey = key("DOT", DefaultLanguageHighlighterColors.DOT)
    val BAD_CHARACTER: TextAttributesKey = key("BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
    val MOD_VERSION: TextAttributesKey = key("MOD_VERSION", DefaultLanguageHighlighterColors.NUMBER)
}

class GoSyntaxHighlighter(
    private val types: GoTokenSet,
    private val lexerFactory: () -> Lexer,
) : SyntaxHighlighterBase() {
    private val versionKey = if (types === GoTokenTypes) GoColors.NUMBER else GoColors.MOD_VERSION

    override fun getHighlightingLexer(): Lexer = lexerFactory()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        val key = when (tokenType) {
            types.LINE_COMMENT -> GoColors.LINE_COMMENT
            types.BLOCK_COMMENT -> GoColors.BLOCK_COMMENT
            types.KEYWORD -> GoColors.KEYWORD
            types.BUILTIN -> GoColors.BUILTIN
            types.CONSTANT -> GoColors.CONSTANT
            types.IDENTIFIER -> GoColors.IDENTIFIER
            types.STRING -> GoColors.STRING
            types.RAW_STRING -> GoColors.RAW_STRING
            types.CHAR -> GoColors.RUNE
            types.NUMBER -> versionKey
            types.OPERATOR -> GoColors.OPERATOR
            types.LPAREN, types.RPAREN -> GoColors.PARENTHESES
            types.LBRACE, types.RBRACE -> GoColors.BRACES
            types.LBRACKET, types.RBRACKET -> GoColors.BRACKETS
            types.COMMA -> GoColors.COMMA
            types.SEMICOLON -> GoColors.SEMICOLON
            types.DOT -> GoColors.DOT
            TokenType.BAD_CHARACTER -> GoColors.BAD_CHARACTER
            else -> null
        }
        return pack(key)
    }

    companion object {
        fun go(): GoSyntaxHighlighter = GoSyntaxHighlighter(GoTokenTypes) { GoLexer() }

        fun goMod(): GoSyntaxHighlighter =
            GoSyntaxHighlighter(GoModTokenTypes) { GoModLexer(GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS) }

        fun goWork(): GoSyntaxHighlighter =
            GoSyntaxHighlighter(GoWorkTokenTypes) { GoModLexer(GoWorkTokenTypes, GoModLexer.GO_WORK_KEYWORDS) }
    }
}

class GoSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GoSyntaxHighlighter.go()
}

class GoModSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GoSyntaxHighlighter.goMod()
}

class GoWorkSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        GoSyntaxHighlighter.goWork()
}
