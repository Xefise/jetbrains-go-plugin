package io.github.xefise.golsp.lang.model

/** Replace [start, end) of the file with [replacement]; [name] is the quick-fix text. */
data class GoSyntaxFix(val name: String, val start: Int, val end: Int, val replacement: String)

data class GoSyntaxProblem(val start: Int, val end: Int, val message: String, val fixes: List<GoSyntaxFix>)

/**
 * Finds C#/Java/JS habits in Go code from lexer tokens alone: a type written before the name (`[]int xs`, `int a := 0`)
 * and a spread before the argument (`f(...xs)`). Every pattern reported is a syntax error or, for a predeclared type
 * name used as the declared name (`var int a`), a declaration nobody writes on purpose.
 */
object GoForeignSyntax {
    const val TYPE_FIRST_MESSAGE: String = "Type before name: Go declares the name first"
    const val SPREAD_MESSAGE: String = "Spread before the argument: Go puts '...' after it"

    private val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto",
        "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )
    private val BUILTIN_TYPES = setOf(
        "any", "bool", "byte", "comparable", "complex64", "complex128", "error", "float32", "float64", "int", "int8", "int16",
        "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    )
    // Real code names fields and params like these (`string inputString`, `error cgoErrorMsg`), so in declarations
    // a type-first form with them is indistinguishable from valid Go.
    private val NAME_LIKE_TYPES = setOf("any", "comparable", "error", "string")
    private val STATEMENT_END_KEYWORDS = setOf("break", "continue", "fallthrough", "return")
    private val LIST_END = setOf(";", ")", "}")
    private const val MAX_TYPE_DEPTH = 32

    private enum class Kind { IDENT, KEYWORD, LITERAL, OPEN, CLOSE, OTHER }
    private enum class Role { PARAMS, GROUP, STRUCT, INTERFACE, BLOCK, OTHER }
    private enum class Context { SPEC, GROUP, STRUCT, PARAM, STATEMENT }

    private class Tok(val kind: Kind, val start: Int, val end: Int, val text: String, val newlineBefore: Boolean)

    private class Scan(text: CharSequence, tokens: List<GoIndentToken>) {
        val src: CharSequence = text
        val toks: List<Tok>
        val match: IntArray
        val parent: IntArray
        val role: Array<Role?>

        init {
            val list = ArrayList<Tok>()
            var prevEnd = 0
            for (t in tokens) {
                if (t.kind == GoIndentTokenKind.SPACE || t.kind == GoIndentTokenKind.COMMENT) continue
                val word = text.subSequence(t.start, t.end).toString()
                val newline = (prevEnd until t.start).any { text[it] == '\n' }
                list += Tok(kindOf(t.kind, word), t.start, t.end, word, newline && list.isNotEmpty())
                prevEnd = t.end
            }
            toks = list
            match = IntArray(list.size) { -1 }
            parent = IntArray(list.size) { -1 }
            role = arrayOfNulls(list.size)
            val stack = ArrayList<Int>()
            for (i in list.indices) {
                parent[i] = stack.lastOrNull() ?: -1
                when (list[i].kind) {
                    Kind.OPEN -> {
                        role[i] = roleOf(i)
                        stack += i
                    }
                    Kind.CLOSE -> {
                        val open = stack.lastOrNull() ?: continue
                        if (closerOf(list[open].text) != list[i].text) continue
                        stack.removeAt(stack.size - 1)
                        match[open] = i
                        match[i] = open
                        parent[i] = parent[open]
                    }
                    else -> Unit
                }
            }
        }

        fun text(i: Int): String = if (i in toks.indices) toks[i].text else ""

        fun ident(i: Int): Boolean = i in toks.indices && toks[i].kind == Kind.IDENT

        private fun roleOf(i: Int): Role {
            val p = i - 1
            return when (text(i)) {
                "{" -> when (text(p)) {
                    "struct" -> Role.STRUCT
                    "interface" -> Role.INTERFACE
                    else -> Role.BLOCK
                }
                "(" -> when {
                    text(p) == "var" || text(p) == "const" -> Role.GROUP
                    isSignature(i) -> Role.PARAMS
                    else -> Role.OTHER
                }
                else -> Role.OTHER
            }
        }

        // Receiver, parameter and result lists of func declarations, literals, func types and interface methods.
        private fun isSignature(i: Int): Boolean {
            val p = i - 1
            if (text(p) == "func") return true
            if (ident(p) && text(p - 1) == "func") return true
            if (ident(p) && text(p - 1) == ")" && role.getOrNull(match[p - 1]) == Role.PARAMS && text(match[p - 1] - 1) == "func") return true
            if (text(p) == "]" && match[p] >= 0 && ident(match[p] - 1) && text(match[p] - 2) == "func") return true
            if (text(p) == ")" && match[p] >= 0 && role[match[p]] == Role.PARAMS && !toks[i].newlineBefore) return true
            val outer = parent[i]
            return ident(p) && outer >= 0 && role[outer] == Role.INTERFACE &&
                (p == outer + 1 || toks[p].newlineBefore || text(p - 1) == ";")
        }
    }

    fun find(text: CharSequence, tokens: List<GoIndentToken>): List<GoSyntaxProblem> {
        val scan = Scan(text, tokens)
        val problems = ArrayList<GoSyntaxProblem>()
        var i = 0
        while (i < scan.toks.size) {
            val problem = typeFirst(scan, i) ?: spread(scan, i)
            if (problem != null) problems += problem
            i++
        }
        return problems
    }

    private fun contextAt(s: Scan, i: Int): Context? {
        val prev = i - 1
        val tok = s.toks[i]
        if ((s.text(prev) == "var" || s.text(prev) == "const") && tok.text != "(") return Context.SPEC
        val open = s.parent[i]
        if (open < 0) return null
        val lineStart = prev == open || tok.newlineBefore || s.text(prev) == ";"
        return when (s.role[open]) {
            Role.GROUP -> if (lineStart) Context.GROUP else null
            Role.STRUCT -> if (lineStart) Context.STRUCT else null
            Role.PARAMS -> if (prev == open || s.text(prev) == ",") Context.PARAM else null
            Role.BLOCK -> if (prev == open || s.text(prev) == ";" || (tok.newlineBefore && endsStatement(s, prev))) Context.STATEMENT else null
            else -> null
        }
    }

    private fun endsStatement(s: Scan, i: Int): Boolean {
        val tok = s.toks[i]
        return when (tok.kind) {
            Kind.IDENT, Kind.LITERAL, Kind.CLOSE -> true
            Kind.KEYWORD -> tok.text in STATEMENT_END_KEYWORDS
            else -> tok.text == "++" || tok.text == "--" || tok.text == ":"
        }
    }

    private fun typeFirst(s: Scan, start: Int): GoSyntaxProblem? {
        val context = contextAt(s, start) ?: return null
        val typeEnd = typeEnd(s, start, 0)
        if (typeEnd < 0 || typeEnd >= s.toks.size) return null
        val name = typeEnd
        if (!s.ident(name) || s.text(name) in BUILTIN_TYPES) return null
        if ((start + 1..name).any { s.toks[it].newlineBefore }) return null
        val follower = name + 1
        val define = s.text(follower) == ":="
        val assign = s.text(follower) == "="
        val end = follower >= s.toks.size || s.toks[follower].newlineBefore || s.text(follower) in LIST_END
        val plainType = typeEnd == start + 1
        val builtinType = plainType && s.text(start) in BUILTIN_TYPES
        val typeOnlyName = builtinType && s.text(start) !in NAME_LIKE_TYPES

        // `items [N]Item` is a name and an array type, not an instantiated generic type followed by a name.
        if (context != Context.STATEMENT && s.ident(start) && s.text(start + 1) == "[") return null

        val nameText = s.text(name)
        val typeText = s.src.subSequence(s.toks[start].start, s.toks[typeEnd - 1].end).toString()
        val swapped = "$nameText $typeText"
        val from = s.toks[start].start
        val to = s.toks[name].end
        val assignEnd = if (define || assign) s.toks[follower].end else to
        fun fix(shown: String, until: Int, replacement: String) = GoSyntaxFix("Change to '$shown'", from, until, replacement)
        fun problem(vararg fixes: GoSyntaxFix) = GoSyntaxProblem(from, to, TYPE_FIRST_MESSAGE, fixes.toList())

        return when (context) {
            Context.PARAM -> {
                val listed = s.text(follower) == "," || s.text(follower) == ")"
                if (listed && (!plainType || typeOnlyName)) problem(fix(swapped, to, swapped)) else null
            }
            Context.SPEC, Context.GROUP, Context.STRUCT -> when {
                !(define || assign || end) -> null
                context == Context.STRUCT && (define || assign) -> null
                !define -> if (plainType && !typeOnlyName) null else problem(fix(swapped, to, swapped))
                context == Context.SPEC -> {
                    val keyword = start - 1
                    val kept = fix("${s.text(keyword)} $swapped = …", assignEnd, "$swapped =")
                    if (s.text(keyword) != "var") {
                        problem(kept)
                    } else {
                        val short = GoSyntaxFix("Change to '$nameText := …'", s.toks[keyword].start, assignEnd, "$nameText :=")
                        problem(short, kept)
                    }
                }
                else -> problem(fix("$swapped = …", assignEnd, "$swapped ="))
            }
            Context.STATEMENT -> when {
                define -> problem(fix("$nameText := …", assignEnd, "$nameText :="), fix("var $swapped = …", assignEnd, "var $swapped ="))
                assign -> problem(fix("var $swapped = …", to, "var $swapped"))
                end && !(plainType && !builtinType) -> problem(fix("var $swapped", to, "var $swapped"))
                else -> null
            }
        }
    }

    // Index after a type expression starting at [i], or -1; func, struct and interface types are never reported.
    private fun typeEnd(s: Scan, i: Int, depth: Int): Int {
        if (depth > MAX_TYPE_DEPTH || i >= s.toks.size) return -1
        return when (s.text(i)) {
            "*" -> typeEnd(s, i + 1, depth + 1)
            "[" -> if (s.match[i] > i) typeEnd(s, s.match[i] + 1, depth + 1) else -1
            "map" -> if (s.text(i + 1) == "[" && s.match[i + 1] > i) typeEnd(s, s.match[i + 1] + 1, depth + 1) else -1
            "chan" -> typeEnd(s, if (s.text(i + 1) == "<-") i + 2 else i + 1, depth + 1)
            "<-" -> if (s.text(i + 1) == "chan") typeEnd(s, i + 2, depth + 1) else -1
            else -> namedTypeEnd(s, i)
        }
    }

    private fun namedTypeEnd(s: Scan, i: Int): Int {
        if (!s.ident(i)) return -1
        var k = i + 1
        if (s.text(k) == "." && s.ident(k + 1)) k += 2
        if (s.text(k) == "[" && k < s.toks.size && !s.toks[k].newlineBefore) {
            val close = s.match[k]
            // `int[] xs` is Java/C# array syntax, not a generic instantiation; left alone.
            if (close <= k + 1) return -1
            k = close + 1
        }
        return k
    }

    private fun spread(s: Scan, i: Int): GoSyntaxProblem? {
        if (s.text(i) != "...") return null
        val open = s.parent[i]
        if (open < 0 || s.text(open) != "(" || s.role[open] != Role.OTHER) return null
        if (s.text(i - 1) != "(" && s.text(i - 1) != ",") return null
        val callee = open - 1
        if (!(s.ident(callee) || s.text(callee) == ")" || s.text(callee) == "]")) return null
        val close = s.match[open]
        if (close < 0) return null
        val first = i + 1
        if (first >= close || !(s.ident(first) || s.text(first) == "(" || s.text(first) == "[")) return null
        var j = first
        var last = -1
        while (j < close) {
            if (s.text(j) == ",") {
                if (j + 1 != close) return null
                break
            }
            if (s.text(j) == "...") return null
            last = if (s.toks[j].kind == Kind.OPEN && s.match[j] > j) s.match[j] else j
            j = last + 1
        }
        val argument = s.src.subSequence(s.toks[first].start, s.toks[last].end).toString()
        val from = s.toks[i].start
        val to = s.toks[last].end
        val fixed = "$argument..."
        val shown = if (fixed.length > 40) "…" else fixed
        return GoSyntaxProblem(from, to, SPREAD_MESSAGE, listOf(GoSyntaxFix("Change to '$shown'", from, to, fixed)))
    }

    private fun kindOf(kind: GoIndentTokenKind, word: String): Kind = when (kind) {
        GoIndentTokenKind.OPEN -> Kind.OPEN
        GoIndentTokenKind.CLOSE -> Kind.CLOSE
        GoIndentTokenKind.STRING -> Kind.LITERAL
        else -> when {
            word in KEYWORDS -> Kind.KEYWORD
            word.isNotEmpty() && (word[0] == '_' || Character.isLetter(word.codePointAt(0))) -> Kind.IDENT
            word.isNotEmpty() && (word[0].isDigit() || (word[0] == '.' && word.length > 1 && word[1].isDigit())) -> Kind.LITERAL
            else -> Kind.OTHER
        }
    }

    private fun closerOf(open: String): String = when (open) {
        "(" -> ")"
        "[" -> "]"
        else -> "}"
    }
}
