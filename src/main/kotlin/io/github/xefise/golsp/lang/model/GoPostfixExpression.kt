package io.github.xefise.golsp.lang.model

enum class GoPostfixTokenKind {
    SPACE, COMMENT, STRING, NUMBER, NAME, KEYWORD, DOT,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    OPERATOR, COMMA, SEMICOLON, OTHER,
}

class GoPostfixToken(val kind: GoPostfixTokenKind, val start: Int, val end: Int)

/**
 * Operand of a postfix template, `[start, end)`; [isStatement] when it begins a statement in a statement block,
 * [isCall] when it is a call without a unary prefix.
 */
data class GoPostfixOperand(val start: Int, val end: Int, val isStatement: Boolean, val isCall: Boolean)

/** Finds the operand left of a postfix key from lexer tokens, without a parser. */
object GoPostfixExpression {
    // Bounds the backward scan so a huge function never stalls completion.
    const val MAX_SCAN_TOKENS: Int = 100_000

    private val TOP_LEVEL_KEYWORDS = setOf("func", "type", "var", "const", "import", "package")
    private val UNARY_OPERATORS = setOf("!", "-", "+", "*", "&", "^", "<-")
    private val STATEMENT_ENDING_KEYWORDS = setOf("break", "continue", "fallthrough", "return")
    private val BLOCK_KEYWORDS = setOf("if", "for", "switch", "select", "else", "func", "go", "defer")

    /**
     * [backward]: tokens starting before [offset], nearest first. Null unless [offset] ends an operand inside a
     * function body, outside strings and comments.
     */
    fun find(text: CharSequence, offset: Int, backward: Sequence<GoPostfixToken>): GoPostfixOperand? {
        if (offset <= 0 || offset > text.length) return null
        val tokens = collect(text, offset, backward)
        if (tokens.isEmpty() || tokens[0].end != offset) return null
        return Scan(text, tokens).operand()
    }

    // Gofmt puts only top-level declarations at column 0, so a keyword there bounds the enclosing declaration.
    private fun collect(text: CharSequence, offset: Int, backward: Sequence<GoPostfixToken>): List<GoPostfixToken> {
        val result = ArrayList<GoPostfixToken>()
        for (token in backward.take(MAX_SCAN_TOKENS)) {
            if (token.start >= offset || token.end <= token.start) continue
            result += token
            if (token.kind == GoPostfixTokenKind.KEYWORD && isLineStart(text, token.start) &&
                text.substring(token.start, token.end) in TOP_LEVEL_KEYWORDS
            ) break
        }
        return result
    }

    private fun isLineStart(text: CharSequence, pos: Int): Boolean = pos == 0 || text[pos - 1] == '\n'

    private class Scan(private val text: CharSequence, private val t: List<GoPostfixToken>) {
        fun operand(): GoPostfixOperand? {
            val first = t[0].kind
            if (first == GoPostfixTokenKind.SPACE || first == GoPostfixTokenKind.COMMENT) return null
            val startIndex = unary(primary(0) ?: return null)
            val context = context(startIndex) ?: return null
            val statement = context && statementStart(startIndex)
            val plainCall = isCall() && kind(startIndex) != GoPostfixTokenKind.OPERATOR
            return GoPostfixOperand(t[startIndex].start, t[0].end, statement, plainCall)
        }

        private fun isCall(): Boolean {
            if (kind(0) != GoPostfixTokenKind.RPAREN) return false
            val open = matchBack(0) ?: return false
            val p = prev(open)
            return endsOperand(p) && !newlineBetween(p, open)
        }

        private fun kind(i: Int) = t[i].kind
        private fun text(i: Int) = text.substring(t[i].start, t[i].end)
        private fun isKeyword(i: Int, word: String) = i >= 0 && kind(i) == GoPostfixTokenKind.KEYWORD && text(i) == word

        private fun prev(i: Int): Int {
            var j = i + 1
            while (j < t.size && (kind(j) == GoPostfixTokenKind.SPACE || kind(j) == GoPostfixTokenKind.COMMENT)) j++
            return if (j < t.size) j else -1
        }

        // Go inserts a semicolon at a newline, so a suffix on the next line does not continue the operand.
        private fun newlineBetween(before: Int, after: Int): Boolean {
            for (k in t[before].end until t[after].start) if (text[k] == '\n') return true
            return false
        }

        private fun endsOperand(i: Int) = i >= 0 && when (kind(i)) {
            GoPostfixTokenKind.NAME, GoPostfixTokenKind.NUMBER, GoPostfixTokenKind.STRING,
            GoPostfixTokenKind.RPAREN, GoPostfixTokenKind.RBRACKET, GoPostfixTokenKind.RBRACE -> true
            else -> false
        }

        private fun matchBack(closer: Int): Int? {
            var depth = 0
            for (j in closer until t.size) {
                when (kind(j)) {
                    GoPostfixTokenKind.RPAREN, GoPostfixTokenKind.RBRACKET, GoPostfixTokenKind.RBRACE -> depth++
                    GoPostfixTokenKind.LPAREN, GoPostfixTokenKind.LBRACKET, GoPostfixTokenKind.LBRACE -> {
                        depth--
                        if (depth == 0) return if (pairs(j, closer)) j else null
                    }
                    else -> Unit
                }
            }
            return null
        }

        private fun pairs(open: Int, close: Int) = when (kind(open)) {
            GoPostfixTokenKind.LPAREN -> kind(close) == GoPostfixTokenKind.RPAREN
            GoPostfixTokenKind.LBRACKET -> kind(close) == GoPostfixTokenKind.RBRACKET
            else -> kind(close) == GoPostfixTokenKind.RBRACE
        }

        /** Index of the first token of the primary expression (with selectors, calls, indexes) ending at token [last]. */
        private fun primary(last: Int): Int? {
            var j = last
            while (true) {
                val start: Int = when (kind(j)) {
                    GoPostfixTokenKind.NAME, GoPostfixTokenKind.NUMBER, GoPostfixTokenKind.STRING -> j
                    GoPostfixTokenKind.RPAREN, GoPostfixTokenKind.RBRACKET -> {
                        val open = matchBack(j) ?: return null
                        val p = prev(open)
                        when {
                            endsOperand(p) && !newlineBetween(p, open) -> { j = p; continue }
                            kind(j) == GoPostfixTokenKind.RBRACKET -> return null
                            p >= 0 && kind(p) == GoPostfixTokenKind.DOT -> { j = prev(p).takeIf(::endsOperand) ?: return null; continue }
                            isKeyword(p, "func") -> p
                            else -> open
                        }
                    }
                    GoPostfixTokenKind.RBRACE -> {
                        val open = matchBack(j) ?: return null
                        val p = prev(open)
                        if (p >= 0 && kind(p) == GoPostfixTokenKind.RPAREN) { j = p; continue }
                        val type = literalType(p) ?: return null
                        funcLiteralStart(type) ?: type
                    }
                    else -> return null
                }
                val dot = prev(start)
                // A name right after `]` is a type: `[]byte(s)` converts, `[]T{}` already took its prefix.
                if (dot < 0 || kind(dot) != GoPostfixTokenKind.DOT) {
                    return if (kind(start) == GoPostfixTokenKind.NAME) typePrefix(start) else start
                }
                val before = prev(dot)
                if (!endsOperand(before) || newlineBetween(before, dot)) return null
                j = before
            }
        }

        // `func(...) T {` — the type before `{` is a result type, not a composite literal type.
        private fun funcLiteralStart(resultType: Int): Int? {
            val close = prev(resultType)
            if (close < 0 || kind(close) != GoPostfixTokenKind.RPAREN) return null
            val open = matchBack(close) ?: return null
            return prev(open).takeIf { isKeyword(it, "func") }
        }

        /** Start of a composite literal type ending at [last]: `T`, `pkg.T`, `T[int]`, `[]*T`, `map[K]V`, `struct{...}`. */
        private fun literalType(last: Int): Int? {
            if (last < 0) return null
            var s = when (kind(last)) {
                GoPostfixTokenKind.NAME -> qualified(last)
                GoPostfixTokenKind.RBRACKET -> {
                    val open = matchBack(last) ?: return null
                    val name = prev(open)
                    if (name < 0 || kind(name) != GoPostfixTokenKind.NAME) return null
                    qualified(name)
                }
                GoPostfixTokenKind.RBRACE -> {
                    val open = matchBack(last) ?: return null
                    prev(open).takeIf { isKeyword(it, "struct") } ?: return null
                }
                else -> return null
            }
            return typePrefix(s)
        }

        /** Extends a type name at [name] over `[]`, `[N]`, `map[K]`, `[]*` prefixes. */
        private fun typePrefix(name: Int): Int? {
            var s = name
            while (true) {
                var p = prev(s)
                if (p >= 0 && kind(p) == GoPostfixTokenKind.OPERATOR && text(p) == "*" &&
                    prev(p).let { it >= 0 && kind(it) == GoPostfixTokenKind.RBRACKET }
                ) p = prev(p)
                if (p < 0 || kind(p) != GoPostfixTokenKind.RBRACKET || newlineBetween(p, s)) return s
                val open = matchBack(p) ?: return null
                val before = prev(open)
                s = if (isKeyword(before, "map")) before else open
            }
        }

        private fun qualified(name: Int): Int {
            val dot = prev(name)
            if (dot < 0 || kind(dot) != GoPostfixTokenKind.DOT) return name
            val pkg = prev(dot)
            return if (pkg >= 0 && kind(pkg) == GoPostfixTokenKind.NAME) pkg else name
        }

        // An operator is unary unless an operand ends right before it: `x - y` binds only `y`.
        private fun unary(start: Int): Int {
            var s = start
            while (true) {
                val p = prev(s)
                if (p < 0 || kind(p) != GoPostfixTokenKind.OPERATOR || text(p) !in UNARY_OPERATORS) return s
                if (endsOperand(prev(p))) return s
                s = p
            }
        }

        /**
         * Null outside a function body; otherwise whether the operand sits directly in a statement block:
         * no unclosed `(`/`[` around it and the innermost `{` opens a block, not a composite literal or type.
         */
        private fun context(start: Int): Boolean? {
            val unclosed = ArrayList<Int>()
            var depth = 0
            var j = start + 1
            while (j < t.size) {
                when (kind(j)) {
                    GoPostfixTokenKind.RPAREN, GoPostfixTokenKind.RBRACKET, GoPostfixTokenKind.RBRACE -> depth++
                    GoPostfixTokenKind.LPAREN, GoPostfixTokenKind.LBRACKET, GoPostfixTokenKind.LBRACE ->
                        if (depth > 0) depth-- else unclosed += j
                    else -> Unit
                }
                j++
            }
            val braces = unclosed.filter { kind(it) == GoPostfixTokenKind.LBRACE }
            val top = t.last()
            val inFunction = braces.isNotEmpty() && kind(t.size - 1) == GoPostfixTokenKind.KEYWORD &&
                text.substring(top.start, top.end) == "func" && isLineStart(text, top.start)
            if (!inFunction) return null
            if (unclosed.first() != braces.first()) return false
            return isStatementBlock(braces, 0)
        }

        private fun isStatementBlock(braces: List<Int>, index: Int): Boolean {
            val open = braces[index]
            val p = prev(open)
            if (isKeyword(p, "struct") || isKeyword(p, "interface")) return false
            if (p >= 0 && kind(p) == GoPostfixTokenKind.RPAREN) return true
            if (statementStart(open)) return index + 1 < braces.size && isStatementBlock(braces, index + 1)
            return headerStartsBlock(open)
        }

        // Walks the line holding `{` over balanced groups: `if`/`for`/`func`... make a block, `x := T{` does not.
        private fun headerStartsBlock(open: Int): Boolean {
            var j = prev(open)
            while (j >= 0) {
                if (kind(j) == GoPostfixTokenKind.KEYWORD && text(j) == "func") return true
                if (kind(j) == GoPostfixTokenKind.RPAREN || kind(j) == GoPostfixTokenKind.RBRACKET ||
                    kind(j) == GoPostfixTokenKind.RBRACE
                ) j = matchBack(j) ?: return false
                val p = prev(j)
                if (p < 0 || newlineBetween(p, j) || kind(p) in OPENERS) break
                j = p
            }
            if (j < 0) return false
            return kind(j) == GoPostfixTokenKind.RBRACE || (kind(j) == GoPostfixTokenKind.KEYWORD && text(j) in BLOCK_KEYWORDS)
        }

        private fun statementStart(i: Int): Boolean {
            val p = prev(i)
            if (p < 0) return true
            return when (kind(p)) {
                GoPostfixTokenKind.LBRACE, GoPostfixTokenKind.SEMICOLON -> true
                GoPostfixTokenKind.OPERATOR -> text(p) == ":" || (newlineBetween(p, i) && text(p) in setOf("++", "--"))
                GoPostfixTokenKind.KEYWORD -> newlineBetween(p, i) && text(p) in STATEMENT_ENDING_KEYWORDS
                else -> newlineBetween(p, i) && endsOperand(p)
            }
        }

        companion object {
            private val OPENERS = setOf(GoPostfixTokenKind.LPAREN, GoPostfixTokenKind.LBRACKET, GoPostfixTokenKind.LBRACE)
        }
    }
}
