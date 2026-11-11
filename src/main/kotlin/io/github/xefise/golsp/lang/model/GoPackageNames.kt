package io.github.xefise.golsp.lang.model

object GoPackageNames {
    private const val TEST_SUFFIX = "_test"

    private val KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func",
        "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct",
        "switch", "type", "var",
    )

    // Package for a new file: the one the directory's sources already declare, else derived from the directory name.
    fun forNewFile(directoryName: String, siblingPackages: Map<String, String?>): String {
        val known = siblingPackages.entries.filter { !it.value.isNullOrEmpty() }.sortedBy { it.key }
        known.firstOrNull { !it.key.endsWith("_test.go") }?.let { return it.value!! }
        val fromTests = known.firstOrNull()?.value?.removeSuffix(TEST_SUFFIX)
        return if (fromTests.isNullOrEmpty()) fromDirectoryName(directoryName) else fromTests
    }

    fun fromDirectoryName(directoryName: String): String {
        val sanitized = buildString {
            for (c in directoryName.lowercase()) append(if (Character.isLetterOrDigit(c) || c == '_') c else '_')
        }
        return when {
            sanitized.isEmpty() || sanitized.all { it == '_' } -> "main"
            sanitized.first().isDigit() -> "_$sanitized"
            sanitized in KEYWORDS -> "${sanitized}_"
            else -> sanitized
        }
    }
}
