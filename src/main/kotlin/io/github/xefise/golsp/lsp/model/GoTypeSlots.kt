package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken

enum class GoTypeSlot {
    /** `func (Ware|) M()`: no receiver name yet. */
    RECEIVER,

    /** `func f(ctx context.Context, Ware|`: no parameter name yet. */
    PARAMETER,

    /** `func f(w Ware|`, `func (w Ware|)`: the name is already there. */
    NAMED,

    /** `func f() Ware|`, `func f() (int, Ware|`. */
    RESULT,
}

/** A type being completed in a function signature; [start] includes an already typed `*` and package qualifier. */
data class GoTypeSlotSite(val slot: GoTypeSlot, val start: Int, val pointerTyped: Boolean)

/** Lexer-level guess where in a func signature the completed type goes; [find] gets the tokens before gopls' edit range. */
object GoTypeSlots {
    private const val MAX_TOKENS = 2000

    private enum class ListKind { RECEIVER, PARAMETERS, RESULTS }

    /** [tokensBackward]: tokens before [rangeStart], nearest first. */
    fun find(tokensBackward: Sequence<GoIndentToken>, text: CharSequence, rangeStart: Int): GoTypeSlotSite? {
        val tokens = GoTokenCursor(tokensBackward, text, MAX_TOKENS)
        var start = rangeStart
        var token = tokens.next()
        if (tokens.isCode(token, ".")) {
            if (tokens.crossedNewline) return null
            val qualifier = tokens.next()
            if (!tokens.isIdentifier(qualifier) || tokens.crossedNewline) return null
            start = qualifier!!.start
            token = tokens.next()
        }
        var pointerTyped = false
        if (tokens.isCode(token, "*")) {
            pointerTyped = true
            start = token!!.start
            token = tokens.next()
        }
        if (token == null || tokens.isCode(token, "...")) return null

        if (tokens.isBracket(token, ')')) {
            // `func f(…) Ware`: a result only after a parameter list, not after a receiver (that is the method name).
            if (!tokens.skipBrackets()) return null
            return if (listKind(tokens, text) == ListKind.PARAMETERS) GoTypeSlotSite(GoTypeSlot.RESULT, start, pointerTyped) else null
        }
        var named = false
        if (tokens.isIdentifier(token)) {
            named = true
            token = tokens.next()
        }
        if (!tokens.isBracket(token, '(') && !tokens.isCode(token, ",")) return null
        if (tokens.isCode(token, ",") && !skipToListStart(tokens)) return null
        val slot = when (listKind(tokens, text) ?: return null) {
            ListKind.RESULTS -> GoTypeSlot.RESULT
            ListKind.RECEIVER -> if (named) GoTypeSlot.NAMED else GoTypeSlot.RECEIVER
            ListKind.PARAMETERS -> if (named) GoTypeSlot.NAMED else GoTypeSlot.PARAMETER
        }
        return GoTypeSlotSite(slot, start, pointerTyped)
    }

    // From inside a list, consumes tokens up to its unmatched `(`.
    private fun skipToListStart(tokens: GoTokenCursor): Boolean {
        while (true) {
            val token = tokens.next() ?: return false
            when {
                tokens.isBracket(token, ')') || tokens.isBracket(token, ']') || tokens.isBracket(token, '}') -> if (!tokens.skipBrackets()) return false
                tokens.isBracket(token, '(') -> return true
                tokens.isBracket(token, '[') || tokens.isBracket(token, '{') -> return false
            }
        }
    }

    // Classifies the `(` just consumed by what precedes it.
    private fun listKind(tokens: GoTokenCursor, text: CharSequence): ListKind? {
        var token = tokens.next() ?: return null
        if (tokens.isCode(token, "func")) {
            // gofmt puts declarations at column 0; a func literal or func type never starts a line there.
            return if (token.start == 0 || text[token.start - 1] == '\n') ListKind.RECEIVER else ListKind.PARAMETERS
        }
        if (tokens.isBracket(token, ')')) {
            if (!tokens.skipBrackets()) return null
            return if (listKind(tokens, text) == ListKind.PARAMETERS) ListKind.RESULTS else null
        }
        if (tokens.isBracket(token, ']')) {
            if (!tokens.skipBrackets()) return null
            token = tokens.next() ?: return null
        }
        if (!tokens.isIdentifier(token)) return null
        token = tokens.next() ?: return null
        if (tokens.isCode(token, "func")) return ListKind.PARAMETERS
        // `func (w *Warehouse) Reserve(`.
        if (!tokens.isBracket(token, ')') || !tokens.skipBrackets()) return null
        return if (tokens.isCode(tokens.next(), "func")) ListKind.PARAMETERS else null
    }
}
