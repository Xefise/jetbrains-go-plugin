package io.github.xefise.golsp.lang.model

enum class GoIndentTokenKind { SPACE, COMMENT, STRING, OPEN, CLOSE, CODE }

class GoIndentToken(val kind: GoIndentTokenKind, val start: Int, val end: Int)

data class GoIndentStyle(val useTabs: Boolean, val tabSize: Int, val indentSize: Int)

/** Indent of a new line from the tokens around the caret; null means "let the platform decide". */
object GoLineIndent {
    // Bounds the bracket-matching scan so a huge file never stalls Enter.
    const val MAX_SCAN_TOKENS: Int = 100_000

    /**
     * [backward]: tokens starting before [offset], nearest first; [forward]: tokens ending after [offset], nearest first.
     * Both must be re-iterable.
     */
    fun compute(
        text: CharSequence,
        offset: Int,
        backward: Sequence<GoIndentToken>,
        forward: Sequence<GoIndentToken>,
        style: GoIndentStyle,
    ): String? {
        if (offset < 0 || offset > text.length) return null
        val around = backward.firstOrNull()
        if (around != null && around.end > offset && around.kind.isLiteral()) return null

        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val next = forward.firstOrNull { it.start >= offset && it.kind != GoIndentTokenKind.SPACE }
        if (next != null && next.start < lineEnd && next.kind == GoIndentTokenKind.CLOSE) {
            val opener = findOpener(text, backward, offset, text[next.start]) ?: return null
            return lineIndent(text, opener.start)
        }

        val last = backward.firstOrNull { it.end <= offset && it.kind.isSignificant() } ?: return ""
        return when {
            last.kind == GoIndentTokenKind.OPEN -> plusUnit(lineIndent(text, last.start), style)
            last.kind == GoIndentTokenKind.CLOSE -> {
                val opener = findOpener(text, backward, last.start, text[last.start]) ?: return null
                lineIndent(text, opener.start)
            }
            isCaseColon(text, last, backward) -> plusUnit(lineIndent(text, last.start), style)
            else -> lineIndent(text, last.start)
        }
    }

    // Brackets must pair by kind: on a mismatch (half-typed code) the answer would be a guess.
    private fun findOpener(text: CharSequence, backward: Sequence<GoIndentToken>, before: Int, closer: Char): GoIndentToken? {
        val expected = ArrayDeque<Char>()
        expected.addLast(openerOf(closer) ?: return null)
        for (token in backward.take(MAX_SCAN_TOKENS)) {
            if (token.start >= before) continue
            when (token.kind) {
                GoIndentTokenKind.CLOSE -> expected.addLast(openerOf(text[token.start]) ?: return null)
                GoIndentTokenKind.OPEN -> {
                    if (expected.removeLast() != text[token.start]) return null
                    if (expected.isEmpty()) return token
                }
                else -> Unit
            }
        }
        return null
    }

    private fun openerOf(closer: Char): Char? = when (closer) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> null
    }

    // gofmt indents case bodies one level deeper than the case line; other colons (labels, slices, maps) do not indent.
    private fun isCaseColon(text: CharSequence, colon: GoIndentToken, backward: Sequence<GoIndentToken>): Boolean {
        if (colon.kind != GoIndentTokenKind.CODE || colon.end - colon.start != 1 || text[colon.start] != ':') return false
        val lineStart = lineStartOf(text, colon.start)
        var first: GoIndentToken? = null
        for (token in backward.take(MAX_SCAN_TOKENS)) {
            if (token.start >= colon.start) continue
            if (token.start < lineStart) {
                if (token.end > lineStart && token.kind != GoIndentTokenKind.SPACE) return false
                break
            }
            if (token.kind.isSignificant()) first = token
        }
        val word = first?.takeIf { it.kind == GoIndentTokenKind.CODE }?.let { text.substring(it.start, it.end) }
        return word == "case" || word == "default"
    }

    private fun lineStartOf(text: CharSequence, pos: Int): Int = text.lastIndexOf('\n', pos - 1) + 1

    private fun lineIndent(text: CharSequence, pos: Int): String {
        val start = lineStartOf(text, pos)
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(start, end)
    }

    private fun plusUnit(indent: String, style: GoIndentStyle): String {
        val tabSize = style.tabSize.coerceAtLeast(1)
        var columns = 0
        for (c in indent) columns = if (c == '\t') columns + tabSize - columns % tabSize else columns + 1
        columns += style.indentSize.coerceAtLeast(0)
        return if (style.useTabs) "\t".repeat(columns / tabSize) + " ".repeat(columns % tabSize) else " ".repeat(columns)
    }

    private fun GoIndentTokenKind.isLiteral() = this == GoIndentTokenKind.STRING || this == GoIndentTokenKind.COMMENT

    private fun GoIndentTokenKind.isSignificant() = this != GoIndentTokenKind.SPACE && this != GoIndentTokenKind.COMMENT
}
