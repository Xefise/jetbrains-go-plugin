// Modified from the Go project (src/testing/match.go: rewrite, isSpace), go1.27.3, BSD-3-Clause
package io.github.xefise.golsp.test.model

/**
 * Builds `-run` / `-bench` patterns that select exactly the given tests.
 *
 * `go test` splits a pattern on unescaped `/` into per-level patterns and on top-level `|` into alternatives of whole
 * paths (Go 1.21+), and splits test names on `/` the same way, so a subtest named "a/b" is two levels on both sides.
 */
object GoTestRunPattern {
    private const val REGEXP_META = "\\.+*?()|[]{}^$"

    /** Selects nothing: the conventional `-run` companion of `-bench`, so tests are skipped. */
    const val NONE: String = "^$"

    /**
     * Full test names as `go test` prints them or as written in source: "TestX", "TestX/sub test".
     * Duplicate (`#01`) and empty (`#00`) subtest names must be passed as `go test` reports them.
     */
    fun exactly(testNames: Collection<String>): String {
        // An empty -run pattern selects every test.
        require(testNames.isNotEmpty()) { "no tests to select" }
        return testNames.distinct().joinToString("|") { name ->
            name.split('/').joinToString("/") { "^${quoteMeta(rewrite(it))}$" }
        }
    }

    /** What `testing` does to a subtest name before reporting and matching it. */
    fun rewrite(name: String): String = buildString(name.length) {
        var i = 0
        while (i < name.length) {
            val r = name.codePointAt(i)
            when {
                isSpace(r) -> append('_')
                !isPrint(r) -> append(quoteRune(r))
                else -> appendCodePoint(r)
            }
            i += Character.charCount(r)
        }
    }

    fun quoteMeta(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c in REGEXP_META) append('\\')
            append(c)
        }
    }

    // Not the Unicode Z class: exactly the runes testing.isSpace accepts.
    private fun isSpace(r: Int): Boolean =
        if (r < 0x2000) {
            r == '\t'.code || r == '\n'.code || r == 0x0B || r == 0x0C || r == '\r'.code || r == ' '.code ||
                r == 0x85 || r == 0xA0 || r == 0x1680
        } else {
            r <= 0x200A || r == 0x2028 || r == 0x2029 || r == 0x202F || r == 0x205F || r == 0x3000
        }

    // Approximates strconv.IsPrint: letters, marks, numbers, punctuation, symbols and the ASCII space.
    private fun isPrint(r: Int): Boolean = when (Character.getType(r).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> r == ' '.code
        else -> true
    }

    // strconv.QuoteRune without the quotes, for the runes isPrint rejects.
    private fun quoteRune(r: Int): String = when {
        r == 0x07 -> "\\a"
        r == 0x08 -> "\\b"
        r == 0x0C -> "\\f"
        r == 0x0A -> "\\n"
        r == 0x0D -> "\\r"
        r == 0x09 -> "\\t"
        r == 0x0B -> "\\v"
        r < 0x20 || r == 0x7F -> "\\x%02x".format(r)
        r < 0x10000 -> "\\u%04x".format(r)
        else -> "\\U%08x".format(r)
    }
}
