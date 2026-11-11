package io.github.xefise.golsp.lang.model

enum class GoCodeContext { LITERAL, TOP_LEVEL, STATEMENT, OTHER }

class GoTemplatePlace(val context: GoCodeContext, val startsStatement: Boolean)

/**
 * Where a live template may expand, from lexer tokens only: Go has no full parser here, so each `{` is classified
 * by the tokens before it as a block (func/if/for/switch/select/else/case body), a struct/interface type, or a
 * composite literal.
 */
object GoTemplateContexts {
    private enum class Brace { BODY, TYPE, LITERAL, PAREN }

    private val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
        "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )
    private val HEADS = setOf("if", "for", "switch", "select")
    private val ENDING_KEYWORDS = setOf("break", "continue", "fallthrough", "return")
    private val FUNC_TYPE_PREFIXES = setOf("]", "map", "chan")

    /** [tokens] cover the text in order without gaps; only those starting before [offset] are read. */
    fun classify(text: CharSequence, tokens: List<GoIndentToken>, offset: Int): GoCodeContext = locate(text, tokens, offset).context

    /**
     * [offset] is the caret after a typed key (or the selection start for Surround With). [GoTemplatePlace.startsStatement]
     * is false when the key follows a selector dot or sits in the middle of a statement, e.g. `x := err`.
     */
    fun locate(text: CharSequence, tokens: List<GoIndentToken>, offset: Int): GoTemplatePlace {
        val before = tokens.filter { it.start < offset }
        val last = before.lastOrNull()
        if (last != null && isInsideLiteral(text, last, offset)) return GoTemplatePlace(GoCodeContext.LITERAL, false)
        val scan = Scan(text, before)
        val top = scan.run()
        val context = when (top) {
            null -> GoCodeContext.TOP_LEVEL
            Brace.BODY -> GoCodeContext.STATEMENT
            else -> GoCodeContext.OTHER
        }
        return GoTemplatePlace(context, scan.startsStatement(offset))
    }

    private fun isInsideLiteral(text: CharSequence, token: GoIndentToken, offset: Int): Boolean {
        if (token.kind != GoIndentTokenKind.STRING && token.kind != GoIndentTokenKind.COMMENT) return false
        if (token.end > offset) return true
        val s = text.subSequence(token.start, token.end)
        return when {
            s.startsWith("//") -> true
            s.startsWith("/*") -> !(s.length >= 4 && s.endsWith("*/"))
            s.startsWith("`") -> !(s.length >= 2 && s.endsWith("`"))
            else -> !isClosedQuoted(s)
        }
    }

    private fun isClosedQuoted(s: CharSequence): Boolean {
        if (s.length < 2 || s.last() != s.first()) return false
        var slashes = 0
        var i = s.length - 2
        while (i > 0 && s[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 0
    }

    private class Sig(val token: GoIndentToken, val text: String, val newlineBefore: Boolean)

    private class Scan(private val source: CharSequence, tokens: List<GoIndentToken>) {
        private val sig = ArrayList<Sig>()
        private val match = HashMap<Int, Int>()
        private val kinds = HashMap<Int, Brace>()

        init {
            var newline = false
            for (t in tokens) {
                if (t.kind == GoIndentTokenKind.SPACE || t.kind == GoIndentTokenKind.COMMENT) {
                    if ((t.start until t.end).any { source[it] == '\n' }) newline = true
                    continue
                }
                sig += Sig(t, source.substring(t.start, t.end), newline)
                newline = false
            }
        }

        /** Kind of the innermost unclosed bracket, null when there is none. */
        fun run(): Brace? {
            val stack = ArrayDeque<Int>()
            for (i in sig.indices) {
                val s = sig[i]
                when (s.token.kind) {
                    GoIndentTokenKind.OPEN -> {
                        kinds[i] = if (s.text == "{") brace(i, stack.lastOrNull()?.let { kinds[it] }) else Brace.PAREN
                        stack.addLast(i)
                    }
                    GoIndentTokenKind.CLOSE -> close(i, stack)
                    else -> Unit
                }
            }
            return stack.lastOrNull()?.let { kinds[it] }
        }

        fun startsStatement(offset: Int): Boolean {
            var k = sig.lastIndex
            if (k < 0) return true
            val key = sig[k]
            val typedKey = key.token.kind == GoIndentTokenKind.CODE && key.token.end >= offset && key.text.first().isLetter()
            val keyStart = if (typedKey) key.token.start else offset
            if (typedKey) k--
            if (k < 0) return true
            val prev = sig[k]
            if (prev.text == "{" || prev.text == "}" || prev.text == ";" || prev.text == ":") return true
            val newline = (prev.token.end until keyStart.coerceAtMost(source.length)).any { source[it] == '\n' }
            return newline && insertsSemicolon(prev)
        }

        // Half-typed code: a `}` also closes any `(`/`[` left open inside its block.
        private fun close(i: Int, stack: ArrayDeque<Int>) {
            val opener = openerOf(sig[i].text)
            if (stack.isNotEmpty() && sig[stack.last()].text == opener) {
                match[i] = stack.removeLast()
                return
            }
            if (opener != "{") return
            while (stack.isNotEmpty()) {
                val o = stack.removeLast()
                if (sig[o].text == "{") {
                    match[i] = o
                    return
                }
            }
        }

        private fun brace(i: Int, parent: Brace?): Brace {
            val inBlock = if (parent == Brace.BODY) Brace.BODY else Brace.LITERAL
            val p = i - 1
            if (p < 0) return Brace.LITERAL
            val prev = sig[p]
            if (sig[i].newlineBefore && insertsSemicolon(prev)) return inBlock
            return when {
                prev.text == "struct" || prev.text == "interface" -> Brace.TYPE
                prev.text == "else" || prev.text in HEADS -> Brace.BODY
                prev.text == "{" || prev.text == ";" || prev.text == ":" -> inBlock
                prev.text == "}" -> {
                    val open = match[p]
                    if (open != null && kinds[open] == Brace.TYPE) typeBody(open) else head(i)
                }
                !insertsSemicolon(prev) -> Brace.LITERAL
                startsLiteralType(p) -> Brace.LITERAL
                else -> head(i)
            }
        }

        // `struct{...}{` is a literal, but `func f() interface{} {` has the same tokens and is a body.
        private fun typeBody(open: Int): Brace {
            val end = open - 2
            if (end < 0) return Brace.LITERAL
            val first = typeStart(end)
            val before = if (first >= 0) first - 1 else end
            return if (before >= 0 && sig[before].text == ")") Brace.BODY else Brace.LITERAL
        }

        // `[]T{`, `[N]T{`, `map[K]V{` are composite literals; after `)` the same tokens are a func result type.
        private fun startsLiteralType(p: Int): Boolean {
            if (sig[p].text == ")") return false
            val first = typeStart(p)
            if (first < 0) return false
            val startsType = sig[first].text == "[" || sig[first].text == "map"
            return startsType && (first == 0 || sig[first - 1].text != ")")
        }

        // Index of the leftmost token of the type-like run ending at [p], -1 if there is none or a `]` is unmatched.
        private fun typeStart(p: Int): Int {
            var j = p
            var first = -1
            while (j >= 0) {
                val s = sig[j]
                when {
                    s.text == "]" -> {
                        val o = match[j] ?: return -1
                        first = o
                        j = o - 1
                    }
                    s.text == "." || s.text == "*" || s.text == "map" || s.text == "chan" || isIdentifier(s) -> {
                        first = j
                        j--
                    }
                    else -> break
                }
            }
            return first
        }

        // Walks back over the statement holding the `{`: a header keyword makes it a body, otherwise a literal.
        private fun head(i: Int): Brace {
            var next = i
            var j = i - 1
            while (j >= 0) {
                if (sig[next].newlineBefore && insertsSemicolon(sig[j])) return Brace.LITERAL
                val s = sig[j]
                when {
                    s.token.kind == GoIndentTokenKind.CLOSE -> {
                        val o = match[j] ?: return Brace.LITERAL
                        next = o
                        j = o - 1
                        continue
                    }
                    s.token.kind == GoIndentTokenKind.OPEN -> return Brace.LITERAL
                    s.text in HEADS -> return Brace.BODY
                    s.text == "func" -> return if (j > 0 && sig[j - 1].text in FUNC_TYPE_PREFIXES) Brace.LITERAL else Brace.BODY
                }
                next = j
                j--
            }
            return Brace.LITERAL
        }

        private fun insertsSemicolon(s: Sig): Boolean = when (s.token.kind) {
            GoIndentTokenKind.STRING, GoIndentTokenKind.CLOSE -> true
            GoIndentTokenKind.CODE -> isIdentifier(s) || isNumber(s) || s.text in ENDING_KEYWORDS || s.text == "++" || s.text == "--"
            else -> false
        }

        private fun isIdentifier(s: Sig): Boolean {
            val c = s.text.firstOrNull() ?: return false
            return (c == '_' || Character.isLetter(c)) && s.text !in KEYWORDS
        }

        private fun isNumber(s: Sig): Boolean = s.text.firstOrNull()?.let { it.isDigit() || it == '.' && s.text.length > 1 } == true

        private fun openerOf(closer: String): String = when (closer) {
            ")" -> "("
            "]" -> "["
            else -> "{"
        }
    }
}
