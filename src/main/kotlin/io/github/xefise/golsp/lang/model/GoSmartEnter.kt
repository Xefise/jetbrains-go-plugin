package io.github.xefise.golsp.lang.model

/**
 * Edit for Complete Current Statement, offsets relative to the line start: replace [replaceStart, replaceEnd) with [text].
 * [blockClose] != null: open a body after [tailAt] (end of the line's last token) and close it with [blockClose];
 * otherwise the caller falls back to a plain new line.
 */
data class GoSmartEnterPlan(
    val replaceStart: Int,
    val replaceEnd: Int,
    val text: String,
    val tailAt: Int,
    val blockClose: String?,
)

object GoSmartEnter {
    private val HEADER_WORDS = setOf("if", "for", "switch", "select", "func", "else")
    private val GROUP_WORDS = setOf("import", "var", "const", "type")
    private val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto",
        "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )
    private val FUNC_LITERAL_AFTER = setOf("=", ":=", "(", ",", "return", "go", "defer")

    /**
     * [tokens]: lexer tokens of one line in order, offsets relative to the line start; the first may start before 0
     * and the last may end after [line] when a multi-line literal crosses the line boundary.
     */
    fun plan(line: CharSequence, tokens: List<GoIndentToken>): GoSmartEnterPlan? {
        if (tokens.any { it.kind != GoIndentTokenKind.SPACE && it.end > line.length }) return null
        val code = tokens.filter { it.kind != GoIndentTokenKind.SPACE && it.kind != GoIndentTokenKind.COMMENT }
        if (code.isEmpty() || code.first().start < 0) return null
        if (code.any { it.kind == GoIndentTokenKind.STRING && isUnterminated(line, it) }) return null
        val tailAt = tokens.last { it.kind != GoIndentTokenKind.SPACE }.end
        val elseAfterBrace = code.size > 1 && word(line, code[0]) == "}" && word(line, code[1]) == "else"

        val open = ArrayList<Int>()
        var continuation = false
        for ((i, token) in code.withIndex()) {
            if (i == 0 && elseAfterBrace) continue
            when (token.kind) {
                GoIndentTokenKind.OPEN -> open.add(i)
                GoIndentTokenKind.CLOSE -> when {
                    open.isEmpty() -> continuation = true
                    pairOf(line[code[open.last()].start]) != line[token.start] -> return null
                    else -> open.removeAt(open.size - 1)
                }
                else -> Unit
            }
        }

        val first = word(line, code.first())
        val last = code.last()
        val codeEnd = last.end
        val lastBrace = open.indexOfLast { line[code[it].start] == '{' }
        if (lastBrace >= 0) {
            return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open.drop(lastBrace + 1)), tailAt, null)
        }
        if (open.isNotEmpty() && open.last() == code.lastIndex && line[last.start] == '(' && first in GROUP_WORDS && !continuation) {
            return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open.dropLast(1)), tailAt, ")")
        }
        if (continuation) return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open), tailAt, null)

        if (first == "case" || first == "default") {
            val hasColon = code.any { it.kind == GoIndentTokenKind.CODE && word(line, it) == ":" && depthAt(code, it) == 0 }
            val colon = if (hasColon) "" else ":"
            return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open) + colon, tailAt, null)
        }

        val anchor = headerAnchor(line, code, open) ?: return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open), tailAt, null)
        val inner = open.filter { it > anchor }
        val outer = open.filter { it < anchor }
        val literal = word(line, code[anchor]) == "func" && anchor > 0
        val callSuffix = if (literal && word(line, code[anchor - 1]) in setOf("go", "defer")) "()" else ""
        val blockClose = "}" + callSuffix + closers(line, code, outer)

        val body = bodyBrace(line, code, anchor)
        if (body != null) {
            if (body == code.size - 2 && line[last.start] == '}') {
                return GoSmartEnterPlan(code[body - 1].end, codeEnd, " {", tailAt, blockClose)
            }
            return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, open), tailAt, null)
        }
        return GoSmartEnterPlan(codeEnd, codeEnd, closers(line, code, inner) + " {", tailAt, blockClose)
    }

    // First `{` after the anchor at the header's own nesting level that is not a composite literal (`[]T{`, `map[K]V{`, `]{`).
    private fun bodyBrace(line: CharSequence, code: List<GoIndentToken>, anchor: Int): Int? {
        var depth = 0
        for (i in anchor + 1 until code.size) {
            val c = line[code[i].start]
            when (code[i].kind) {
                GoIndentTokenKind.OPEN -> {
                    if (c == '{' && depth == 0 && !isCompositeBrace(line, code, i, anchor)) return i
                    depth++
                }
                GoIndentTokenKind.CLOSE -> depth--
                else -> Unit
            }
        }
        return null
    }

    // Index of the token a block body belongs to: a func literal still inside an open bracket, else the first word
    // of a header (after an optional label), else a func literal anywhere in the line.
    private fun headerAnchor(line: CharSequence, code: List<GoIndentToken>, open: List<Int>): Int? {
        val words = code.map { word(line, it) }
        val literals = (1 until words.size).filter {
            words[it] == "func" && code[it].kind == GoIndentTokenKind.CODE && words[it - 1] in FUNC_LITERAL_AFTER
        }
        literals.lastOrNull { lit -> open.any { it < lit } }?.let { return it }
        val start = if (words.size > 2 && isName(line, code[0]) && words[1] == ":") 2 else 0
        if (words.size > start && words[start] in HEADER_WORDS) return start
        if (words[0] == "}" && words.getOrNull(1) == "else") return 1
        if (words[0] == "type" && words.last() in setOf("struct", "interface")) return 0
        return literals.firstOrNull()
    }

    // `[]T{`, `[N]pkg.T{`, `map[K]*V{`: a type ending in `]`-led brackets; `a[i] {` is an index, so its brace is a body.
    private fun isCompositeBrace(line: CharSequence, code: List<GoIndentToken>, brace: Int, anchor: Int): Boolean {
        var j = brace - 1
        while (j > anchor && (isName(line, code[j]) || word(line, code[j]) == "." || word(line, code[j]) == "*")) j--
        if (j <= anchor || word(line, code[j]) != "]") return false
        var depth = 0
        var k = j
        while (k > anchor) {
            if (code[k].kind == GoIndentTokenKind.CLOSE) depth++
            if (code[k].kind == GoIndentTokenKind.OPEN && --depth == 0) break
            k--
        }
        if (k <= anchor || line[code[k].start] != '[') return false
        val before = code[k - 1]
        val indexed = isName(line, before) || before.kind == GoIndentTokenKind.CLOSE || before.kind == GoIndentTokenKind.STRING
        return !indexed
    }

    private fun isName(line: CharSequence, token: GoIndentToken): Boolean = isIdentifier(line, token) && word(line, token) !in KEYWORDS

    private fun isIdentifier(line: CharSequence, token: GoIndentToken): Boolean =
        token.kind == GoIndentTokenKind.CODE && (line[token.start].isLetter() || line[token.start] == '_')

    private fun depthAt(code: List<GoIndentToken>, token: GoIndentToken): Int {
        var depth = 0
        for (t in code) {
            if (t === token) return depth
            if (t.kind == GoIndentTokenKind.OPEN) depth++ else if (t.kind == GoIndentTokenKind.CLOSE && depth > 0) depth--
        }
        return depth
    }

    private fun isUnterminated(line: CharSequence, token: GoIndentToken): Boolean {
        val quote = line[token.start]
        if (token.end - token.start < 2 || line[token.end - 1] != quote) return true
        if (quote == '`') return false
        var slashes = 0
        var i = token.end - 2
        while (i > token.start && line[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 1
    }

    private fun closers(line: CharSequence, code: List<GoIndentToken>, openers: List<Int>): String =
        openers.asReversed().joinToString("") { pairOf(line[code[it].start]).toString() }

    private fun pairOf(opener: Char): Char = when (opener) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    private fun word(line: CharSequence, token: GoIndentToken): String = line.substring(token.start, token.end)
}
