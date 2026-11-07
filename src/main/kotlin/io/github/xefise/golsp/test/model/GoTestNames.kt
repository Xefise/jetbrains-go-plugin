package io.github.xefise.golsp.test.model

enum class GoTestKind(val prefix: String) {
    TEST("Test"),
    BENCHMARK("Benchmark"),
    FUZZ("Fuzz"),
    EXAMPLE("Example"),
}

object GoTestNames {
    /** The `go test` rule: the prefix alone or followed by a non-lowercase rune; `TestMain` is the harness, not a test. */
    fun kindOf(functionName: String): GoTestKind? {
        if (functionName == "TestMain") return null
        return GoTestKind.entries.firstOrNull { hasTestPrefix(functionName, it.prefix) }
    }

    private fun hasTestPrefix(name: String, prefix: String): Boolean {
        if (!name.startsWith(prefix)) return false
        if (name.length == prefix.length) return true
        // unicode.IsLower is the Ll category only; Character.isLowerCase also accepts Other_Lowercase such as 'ª'.
        return Character.getType(name.codePointAt(prefix.length)) != Character.LOWERCASE_LETTER.toInt()
    }
}
