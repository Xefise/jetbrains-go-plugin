package io.github.xefise.golsp.test.model

/** Source-level rules of `go test` (cmd/go `isTestFunc`, go/doc examples) on plain text. */
object GoTestSource {
    private const val IDENT = """[\p{L}_][\p{L}\p{N}_]*"""
    private val OUTPUT_PREFIX = Regex("""^\s*(unordered )?output:""", RegexOption.IGNORE_CASE)
    private val DIRECTIVE = Regex("""^[a-z0-9]+:[a-z0-9]""")

    /** The parameter type a test function of [kind] must take: `*T` for tests, none for examples. */
    fun parameterType(kind: GoTestKind): String? = when (kind) {
        GoTestKind.TEST -> "T"
        GoTestKind.BENCHMARK -> "B"
        GoTestKind.FUZZ -> "F"
        GoTestKind.EXAMPLE -> null
    }

    /**
     * Whether [parametersText] ("(t *testing.T)", parentheses included) is exactly one parameter of type `*T` or
     * `*pkg.T` for [type] "T" (at most one name), or empty for a null [type].
     */
    fun hasSignature(parametersText: String, type: String?): Boolean {
        val inner = parametersText.trim().removePrefix("(").removeSuffix(")")
        if (type == null) return inner.isBlank()
        return parameter(type).matches(inner)
    }

    /** The name of the single `*pkg.T` parameter in [parametersText], null if it is unnamed or not that signature. */
    fun parameterName(parametersText: String, type: String): String? {
        val inner = parametersText.trim().removePrefix("(").removeSuffix(")")
        return parameter(type).matchEntire(inner)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    }

    private fun parameter(type: String): Regex = Regex("""\s*(?:($IDENT)\s+)?\*\s*(?:$IDENT\s*\.\s*)?$type\s*,?\s*""")

    /**
     * Whether a comment group, given as the raw texts of its comments (`// x`, `/* x */`), starts with `Output:` the way
     * go/doc reads it: markers stripped, directives (`//go:x`, `//line `) dropped, leading blank lines skipped,
     * the prefix matched case-insensitively.
     */
    fun isOutputGroup(comments: List<String>): Boolean {
        val lines = comments.flatMap { raw ->
            when {
                raw.startsWith("//") -> {
                    val text = raw.substring(2)
                    when {
                        text.startsWith(" ") -> listOf(text.substring(1))
                        isDirective(text) -> emptyList()
                        else -> listOf(text)
                    }
                }
                raw.startsWith("/*") -> raw.removePrefix("/*").removeSuffix("*/").split('\n')
                else -> listOf(raw)
            }
        }
        val text = lines.dropWhile { it.isBlank() }.joinToString("\n")
        return OUTPUT_PREFIX.containsMatchIn(text)
    }

    private fun isDirective(text: String): Boolean =
        text.startsWith("line ") || text.startsWith("extern ") || text.startsWith("export ") || DIRECTIVE.containsMatchIn(text)
}
