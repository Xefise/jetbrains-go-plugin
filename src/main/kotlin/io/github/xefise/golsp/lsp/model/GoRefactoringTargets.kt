package io.github.xefise.golsp.lsp.model

/** Document offsets `[start, end)`. */
data class GoTextSpan(val start: Int, val end: Int)

/** Text-level choices around gopls extract refactorings: what to offer without a selection, where the new name landed. */
object GoRefactoringTargets {
    /** Names gopls v0.24 gives extracted declarations; on a clash it appends a number (`newVar1`). */
    val NEW_NAMES: List<String> = listOf("newVar", "newConst", "newFunction", "newMethod")

    private val STATEMENT_KEYWORDS = setOf(
        "break", "case", "const", "continue", "default", "defer", "else", "fallthrough", "for", "go", "goto",
        "if", "import", "package", "return", "select", "switch", "type", "var",
    )
    private val ASSIGNMENT_OPERATORS = listOf(":=", "<<=", ">>=", "&^=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=")

    /**
     * Expression candidates among selection ranges ordered from the innermost out: stops at the enclosing statement or
     * the first multi-line range, drops assignment targets, field/method names after `.`, and keys or labels before `:`.
     */
    fun expressions(text: CharSequence, ranges: List<GoTextSpan>): List<GoTextSpan> {
        val result = mutableListOf<GoTextSpan>()
        for (range in ranges) {
            if (range.start < 0 || range.end > text.length || range.start >= range.end) continue
            val code = text.subSequence(range.start, range.end)
            if (code.contains('\n') || isStatement(code)) break
            if (isAssignmentTarget(text, range.end) || isKeyOrLabel(text, range) || isSelectorName(text, range.start)) continue
            if (range !in result) result += range
        }
        return result
    }

    /**
     * [selection] widened to whole lines, newline included, when only indentation, trailing blanks or a trailing `//`
     * comment are left out: gopls
     * (v0.24) offers extract function for a lone `f(x)` statement only if the range is wider than the call itself.
     */
    fun statementLines(text: CharSequence, selection: GoTextSpan): GoTextSpan {
        val (start, end) = selection
        if (start < 0 || end > text.length || start >= end) return selection
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        if (!isBlank(text, lineStart, start)) return selection
        if (text[end - 1] == '\n') return GoTextSpan(lineStart, end)
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (!isBlank(text, end, lineEnd) && !isLineComment(text, skipSpaces(text, end), lineEnd)) return selection
        return GoTextSpan(lineStart, minOf(lineEnd + 1, text.length))
    }

    private fun isLineComment(text: CharSequence, from: Int, to: Int): Boolean =
        to - from >= 2 && text[from] == '/' && text[from + 1] == '/'

    private fun isBlank(text: CharSequence, from: Int, to: Int): Boolean = (from until to).all { text[it] == ' ' || text[it] == '\t' }

    /**
     * The new name gopls introduced: the first identifier in [after] named after [NEW_NAMES] that overlaps the part of the
     * text that differs from [before]; older identifiers with the same name elsewhere are outside that part.
     */
    fun newName(before: CharSequence, after: CharSequence): GoTextSpan? {
        val limit = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < limit && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        val changedEnd = after.length - suffix
        if (changedEnd <= prefix) return null
        return identifiers(after).firstOrNull { it.start < changedEnd && it.end > prefix && isNewName(after.subSequence(it.start, it.end)) }
    }

    private fun isNewName(name: CharSequence): Boolean = NEW_NAMES.any { base ->
        name.startsWith(base) && name.subSequence(base.length, name.length).all { it in '0'..'9' }
    }

    private fun isStatement(code: CharSequence): Boolean {
        val trimmed = code.trim()
        val firstWord = trimmed.takeWhile { isIdentifierPart(it) }.toString()
        if (firstWord in STATEMENT_KEYWORDS) return true
        if (trimmed.endsWith("++") || trimmed.endsWith("--")) return true
        var statement = false
        scanTopLevel(trimmed) { index ->
            val rest = trimmed.subSequence(index, trimmed.length)
            when {
                ASSIGNMENT_OPERATORS.any { rest.startsWith(it) } -> statement = true
                rest.startsWith("<-") && index > 0 -> statement = true
                rest.startsWith("=") && !rest.startsWith("==") && (index == 0 || trimmed[index - 1] !in "=!<>:") -> statement = true
            }
            !statement
        }
        return statement
    }

    private fun isAssignmentTarget(text: CharSequence, end: Int): Boolean {
        val rest = text.subSequence(skipSpaces(text, end), text.length)
        if (rest.startsWith("==")) return false
        return rest.startsWith("=") || rest.startsWith(",") && isFollowedByAssignment(rest) || rest.startsWith("++") ||
            rest.startsWith("--") || ASSIGNMENT_OPERATORS.any { rest.startsWith(it) }
    }

    // `T{Name: v}`, `Loop:`; not `case c:` or `s[lo:hi]`, whose operands before `:` are expressions.
    private fun isKeyOrLabel(text: CharSequence, range: GoTextSpan): Boolean {
        val after = skipSpaces(text, range.end)
        if (text.getOrNull(after) != ':' || text.getOrNull(after + 1) == '=') return false
        if (!text.subSequence(range.start, range.end).all { isIdentifierPart(it) }) return false
        var i = range.start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i < 0 || text[i] in "{,\n"
    }

    // `a, b := f()` / `a, b = f()`: an identifier list on the left of an assignment, up to the end of the line.
    private fun isFollowedByAssignment(rest: CharSequence): Boolean {
        val line = rest.takeWhile { it != '\n' }
        val equals = line.indexOf('=')
        if (equals <= 0 || line.getOrNull(equals + 1) == '=') return false
        val left = line.subSequence(0, equals).trimEnd(':')
        return left.all { isIdentifierPart(it) || it == ',' || it == ' ' || it == '\t' }
    }

    private fun isSelectorName(text: CharSequence, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i >= 0 && text[i] == '.' && (i == 0 || text[i - 1] != '.')
    }

    private fun skipSpaces(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** Calls [visit] with each offset of [code] outside strings, comments and brackets; stops when it returns false. */
    private fun scanTopLevel(code: CharSequence, visit: (Int) -> Boolean) {
        var depth = 0
        var i = 0
        while (i < code.length) {
            val skipped = skipLiteralOrComment(code, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                else -> if (depth == 0 && !visit(i)) return
            }
            i++
        }
    }

    private fun identifiers(text: CharSequence): Sequence<GoTextSpan> = sequence {
        var i = 0
        while (i < text.length) {
            val skipped = skipLiteralOrComment(text, i)
            when {
                skipped > i -> i = skipped
                isIdentifierStart(text[i]) && (i == 0 || !isIdentifierPart(text[i - 1])) -> {
                    var end = i + 1
                    while (end < text.length && isIdentifierPart(text[end])) end++
                    yield(GoTextSpan(i, end))
                    i = end
                }
                else -> i++
            }
        }
    }

    /** End offset of a string, rune, raw string or comment starting at [i]; [i] itself when there is none. */
    private fun skipLiteralOrComment(text: CharSequence, i: Int): Int {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        return when {
            c == '/' && next == '/' -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
            c == '/' && next == '*' -> text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
            c == '`' -> text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
            c == '"' || c == '\'' -> {
                var j = i + 1
                while (j < text.length && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                minOf(j + 1, text.length)
            }
            else -> i
        }
    }

    private fun isIdentifierStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    private fun isIdentifierPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)
}
