package io.github.xefise.golsp.test.model

/** What a failed test shows besides its status; [expected] and [actual] are set together and enable the diff viewer. */
data class GoTestFailure(
    val message: String,
    val details: String,
    val expected: String? = null,
    val actual: String? = null,
)

/** Reads a failure or skip reason out of the output a test printed itself (framing lines already removed). */
object GoTestFailureParser {

    fun failure(output: String): GoTestFailure {
        val details = output.trimEnd()
        val lines = details.lines()
        val testify = testifyFields(lines)
        val error = testify?.get("Error")
        if (error == null) return GoTestFailure(firstMeaningfulLine(lines), details)
        val headline = error.lineSequence().first().trim().removeSuffix(":").trim()
        val messages = testify["Messages"]?.lineSequence()?.first()?.trim().orEmpty()
        val message = listOf(headline, messages).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { firstMeaningfulLine(lines) }
        val comparison = comparison(error.lines())
        return GoTestFailure(message.take(MAX_MESSAGE), details, comparison?.first, comparison?.second)
    }

    /** The last line the test printed, e.g. the `t.Skip` argument; empty for a bare `SkipNow`. */
    fun skipReason(output: String): String =
        output.lines().asReversed().firstNotNullOfOrNull { line -> meaningful(line) }.orEmpty().take(MAX_MESSAGE)

    private fun firstMeaningfulLine(lines: List<String>): String =
        lines.firstNotNullOfOrNull(::meaningful).orEmpty().take(MAX_MESSAGE)

    // "    foo_test.go:42: text" -> "text"; a bare "foo_test.go:42:" header has nothing to say.
    private fun meaningful(line: String): String? =
        LOCATION_PREFIX.replaceFirst(line.trim(), "").trim().takeIf { it.isNotEmpty() }

    /**
     * Fields of the first testify block: "\tLabel:   \tvalue" lines, each followed by "\t        \tmore" continuation
     * lines; the `testing` package indents them all.
     */
    private fun testifyFields(lines: List<String>): Map<String, String>? {
        val start = lines.indexOfFirst { TESTIFY_FIELD.matches(it) }
        if (start < 0) return null
        val fields = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in lines.subList(start, lines.size)) {
            val field = TESTIFY_FIELD.matchEntire(line)
            if (field != null) {
                current = StringBuilder(field.groupValues[2])
                fields.putIfAbsent(field.groupValues[1], current)
                continue
            }
            val more = TESTIFY_CONTINUATION.matchEntire(line) ?: break
            current?.append('\n')?.append(more.groupValues[1])
        }
        return fields.mapValues { it.value.toString() }.takeIf { "Error" in it }
    }

    // testify Equal: "Not equal: \nexpected: X\nactual  : Y" and optionally "\n\nDiff:\n...".
    private fun comparison(error: List<String>): Pair<String, String>? {
        val expectedAt = error.indexOfFirst { it.startsWith(EXPECTED) }
        if (expectedAt < 0) return null
        val actualAt = (expectedAt + 1 until error.size).firstOrNull { error[it].startsWith(ACTUAL) } ?: return null
        val diffAt = (actualAt + 1 until error.size).firstOrNull { error[it] == "Diff:" } ?: error.size
        val expected = (listOf(error[expectedAt].removePrefix(EXPECTED)) + error.subList(expectedAt + 1, actualAt)).joinToString("\n")
        val actual = (listOf(error[actualAt].removePrefix(ACTUAL)) + error.subList(actualAt + 1, diffAt)).joinToString("\n").trimEnd()
        val unquotedExpected = unquote(expected)
        val unquotedActual = unquote(actual)
        return if (unquotedExpected != null && unquotedActual != null) unquotedExpected to unquotedActual else expected to actual
    }

    /** A Go interpreted string literal (testify prints strings with %#v) to its value; null if it is not one. */
    internal fun unquote(text: String): String? {
        if (text.length < 2 || !text.startsWith('"') || !text.endsWith('"')) return null
        val out = StringBuilder()
        var i = 1
        val end = text.length - 1
        while (i < end) {
            val c = text[i++]
            if (c == '"') return null
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= end) return null
            when (val e = text[i++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '\\', '"', '\'' -> out.append(e)
                'x', 'u', 'U' -> {
                    val digits = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    if (i + digits > end) return null
                    val code = text.substring(i, i + digits).toIntOrNull(16) ?: return null
                    i += digits
                    // \x is a byte of UTF-8 that a single char cannot hold; keep it escaped.
                    if (e == 'x' && code > 0x7F) out.append("\\x").append(text, i - digits, i)
                    else if (Character.isValidCodePoint(code)) out.appendCodePoint(code) else return null
                }
                else -> return null
            }
        }
        return out.toString()
    }

    private const val MAX_MESSAGE = 500
    private const val EXPECTED = "expected: "
    private const val ACTUAL = "actual  : "
    private val LOCATION_PREFIX = Regex("""^[\w.\-/\\]+\.go:\d+:(?:\s|$)""")
    private val TESTIFY_FIELD = Regex("""^ *\t([A-Za-z][A-Za-z ]*):\s*\t(.*)$""")
    private val TESTIFY_CONTINUATION = Regex("""^ *\t +\t(.*)$""")
}
