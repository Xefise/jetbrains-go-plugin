package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind

internal object GoWords {
    val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{Nd}_]*""")

    val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go",
        "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    fun isIdentifier(value: String): Boolean = IDENTIFIER.matches(value) && value !in KEYWORDS
}

/** Backward walk over significant tokens; [crossedNewline] tells whether a line break separates the last returned token from the one after it. */
internal class GoTokenCursor(tokensBackward: Sequence<GoIndentToken>, private val text: CharSequence, maxTokens: Int) {
    private val tokens = tokensBackward.take(maxTokens).iterator()

    var crossedNewline = false
        private set

    fun next(): GoIndentToken? {
        crossedNewline = false
        while (tokens.hasNext()) {
            val token = tokens.next()
            if (token.kind != GoIndentTokenKind.SPACE && token.kind != GoIndentTokenKind.COMMENT) return token
            if ((token.start until token.end).any { text[it] == '\n' }) crossedNewline = true
        }
        return null
    }

    /** Called right after a closing bracket; consumes everything up to and including its opening one. */
    fun skipBrackets(): Boolean {
        var depth = 1
        while (true) {
            val token = next() ?: return false
            when (token.kind) {
                GoIndentTokenKind.CLOSE -> depth++
                GoIndentTokenKind.OPEN -> if (--depth == 0) return true
                else -> Unit
            }
        }
    }

    fun value(token: GoIndentToken): String = text.subSequence(token.start, token.end).toString()

    fun isIdentifier(token: GoIndentToken?): Boolean =
        token != null && token.kind == GoIndentTokenKind.CODE && GoWords.isIdentifier(value(token))

    fun isCode(token: GoIndentToken?, value: String): Boolean =
        token != null && token.kind == GoIndentTokenKind.CODE && value(token) == value

    fun isBracket(token: GoIndentToken?, bracket: Char): Boolean =
        token != null && (token.kind == GoIndentTokenKind.OPEN || token.kind == GoIndentTokenKind.CLOSE) && text[token.start] == bracket
}
