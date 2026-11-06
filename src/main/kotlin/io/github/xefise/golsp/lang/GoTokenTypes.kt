// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.lang.Language
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

// Token types are per language so brace matching and commenting resolve against the right language.
open class GoTokenSet(private val language: Language, private val prefix: String) {
    private fun t(name: String) = IElementType("$prefix$name", language)

    val LINE_COMMENT: IElementType = t("LINE_COMMENT")
    val BLOCK_COMMENT: IElementType = t("BLOCK_COMMENT")
    val STRING: IElementType = t("STRING")
    val RAW_STRING: IElementType = t("RAW_STRING")
    val CHAR: IElementType = t("CHAR")
    val NUMBER: IElementType = t("NUMBER")
    val KEYWORD: IElementType = t("KEYWORD")
    val BUILTIN: IElementType = t("BUILTIN")
    val CONSTANT: IElementType = t("CONSTANT")
    val IDENTIFIER: IElementType = t("IDENTIFIER")
    val OPERATOR: IElementType = t("OPERATOR")
    val SEMICOLON: IElementType = t("SEMICOLON")
    val COMMA: IElementType = t("COMMA")
    val DOT: IElementType = t("DOT")
    val LPAREN: IElementType = t("LPAREN")
    val RPAREN: IElementType = t("RPAREN")
    val LBRACE: IElementType = t("LBRACE")
    val RBRACE: IElementType = t("RBRACE")
    val LBRACKET: IElementType = t("LBRACKET")
    val RBRACKET: IElementType = t("RBRACKET")

    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    val STRINGS: TokenSet = TokenSet.create(STRING, RAW_STRING, CHAR)
}

object GoTokenTypes : GoTokenSet(GoLanguage, "GO_")

object GoModTokenTypes : GoTokenSet(GoModLanguage, "GOMOD_")

object GoWorkTokenTypes : GoTokenSet(GoWorkLanguage, "GOWORK_")
