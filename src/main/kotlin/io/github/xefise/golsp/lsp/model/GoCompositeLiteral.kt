package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind

/**
 * The caret inside `T{…}` / `pkg.T{…}` / `T[int]{…}`: [typeNameStart] points at `T` (where gopls hover resolves the type),
 * [element] is the index of the element under the caret, [key] its `Key:` if already typed.
 */
data class GoCompositeLiteralSite(
    val lbrace: Int,
    val typeName: String,
    val typeNameStart: Int,
    val qualified: Boolean,
    val element: Int,
    val key: String?,
)

/** Lexer-level guess at a composite literal with an explicit named type around the caret; gopls decides if it is a struct. */
object GoCompositeLiteral {
    // Bounds the backward scan so typing in a huge function stays cheap.
    private const val MAX_TOKENS = 2000

    // A `{` after `if x`, `for _, v := range xs`, `func() T` and the like opens a block, not a literal.
    private val BLOCK_KEYWORDS = setOf("if", "for", "switch", "select", "func", "else")

    // Tokens after which a newline ends the statement (Go's automatic semicolon rule).
    private val STATEMENT_END_KEYWORDS = setOf("break", "continue", "fallthrough", "return")

    /** [tokensBackward] are the tokens before the caret, nearest first; [text] is the document text. */
    fun find(tokensBackward: Sequence<GoIndentToken>, text: CharSequence): GoCompositeLiteralSite? {
        val tokens = GoTokenCursor(tokensBackward, text, MAX_TOKENS)
        var depth = 0
        var commas = 0
        var afterColon = false
        var keyDone = false
        var key: String? = null
        val lbrace: Int
        while (true) {
            val token = tokens.next() ?: return null
            val value = text.subSequence(token.start, token.end).toString()
            if (token.kind == GoIndentTokenKind.CLOSE) {
                depth++
                if (afterColon) keyDone = true
                continue
            }
            if (token.kind == GoIndentTokenKind.OPEN) {
                if (depth > 0) {
                    depth--
                    continue
                }
                if (value != "{") return null
                lbrace = token.start
                break
            }
            if (depth > 0) continue
            when {
                value == "," -> {
                    commas++
                    keyDone = true
                }
                value == ";" -> return null
                keyDone -> Unit
                afterColon -> {
                    if (token.kind == GoIndentTokenKind.CODE && isIdentifier(value)) key = value
                    keyDone = true
                }
                value == ":" -> afterColon = true
            }
        }

        var token = tokens.next() ?: return null
        if (tokens.crossedNewline) return null
        if (token.kind == GoIndentTokenKind.CLOSE) {
            if (text[token.start] != ']') return null
            if (!tokens.skipBrackets()) return null
            token = tokens.next() ?: return null
            // `[]T{`, `[...]T{`: the bracket group is not a type argument list.
            if (tokens.crossedNewline || !isIdentifier(token, text)) return null
        }
        if (!isIdentifier(token, text)) return null
        val typeName = text.subSequence(token.start, token.end).toString()
        val typeNameStart = token.start

        var before = tokens.next()
        var qualified = false
        if (before != null && !tokens.crossedNewline && text.subSequence(before.start, before.end) == ".") {
            val qualifier = tokens.next() ?: return null
            if (!isIdentifier(qualifier, text)) return null
            qualified = true
            before = tokens.next()
        }
        // `[]T{`, `map[K]V{`, `[N]T{`.
        if (before != null && !tokens.crossedNewline && before.kind == GoIndentTokenKind.CLOSE && text[before.start] == ']') return null

        if (opensBlock(before, tokens, text)) return null
        return GoCompositeLiteralSite(lbrace, typeName, typeNameStart, qualified, commas, key)
    }

    private fun opensBlock(first: GoIndentToken?, tokens: GoTokenCursor, text: CharSequence): Boolean {
        var token = first
        var newlineAfter = tokens.crossedNewline
        var depth = 0
        while (token != null) {
            if (depth == 0 && newlineAfter && endsStatement(token, text)) return false
            when (token.kind) {
                GoIndentTokenKind.CLOSE -> depth++
                GoIndentTokenKind.OPEN -> if (depth > 0) depth-- else return false
                GoIndentTokenKind.CODE -> if (depth == 0 && text.subSequence(token.start, token.end).toString() in BLOCK_KEYWORDS) return true
                else -> Unit
            }
            token = tokens.next()
            newlineAfter = tokens.crossedNewline
        }
        return false
    }

    private fun endsStatement(token: GoIndentToken, text: CharSequence): Boolean {
        val value = text.subSequence(token.start, token.end).toString()
        return when (token.kind) {
            GoIndentTokenKind.STRING, GoIndentTokenKind.CLOSE -> true
            GoIndentTokenKind.CODE -> value in STATEMENT_END_KEYWORDS || value == "++" || value == "--" ||
                value.first().isDigit() || GoWords.isIdentifier(value)
            else -> false
        }
    }

    private fun isIdentifier(token: GoIndentToken, text: CharSequence): Boolean =
        token.kind == GoIndentTokenKind.CODE && isIdentifier(text.subSequence(token.start, token.end).toString())

    private fun isIdentifier(value: String): Boolean = GoWords.isIdentifier(value)
}
