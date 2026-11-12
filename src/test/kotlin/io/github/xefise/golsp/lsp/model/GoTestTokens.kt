package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind

/** Like GoLexer for these tests: identifiers and numbers, strings, comments, brackets, operators split per token. */
internal fun goTokensBefore(text: String, offset: Int): Sequence<GoIndentToken> {
    val tokens = mutableListOf<GoIndentToken>()
    var i = 0
    while (i < offset) {
        val c = text[i]
        val start = i
        val kind = when {
            c in "([{" -> { i++; GoIndentTokenKind.OPEN }
            c in ")]}" -> { i++; GoIndentTokenKind.CLOSE }
            c.isWhitespace() -> { while (i < offset && text[i].isWhitespace()) i++; GoIndentTokenKind.SPACE }
            text.startsWith("//", i) -> { while (i < offset && text[i] != '\n') i++; GoIndentTokenKind.COMMENT }
            c == '"' -> { i++; while (i < offset && text[i] != '"') i++; if (i < offset) i++; GoIndentTokenKind.STRING }
            c.isLetterOrDigit() || c == '_' -> { while (i < offset && (text[i].isLetterOrDigit() || text[i] == '_')) i++; GoIndentTokenKind.CODE }
            else -> { i += listOf(":=", "++", "--", "...").firstOrNull { text.startsWith(it, i) }?.length ?: 1; GoIndentTokenKind.CODE }
        }
        tokens += GoIndentToken(kind, start, i)
    }
    return tokens.asReversed().asSequence()
}
