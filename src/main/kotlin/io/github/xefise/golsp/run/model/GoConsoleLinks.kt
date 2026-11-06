// Modified from go-lang-idea-plugin (go-lang-plugin-org), commit e73dab4, src/com/goide/runconfig/GoConsoleFilter.java, Apache-2.0
package io.github.xefise.golsp.run.model

/** A `file:line[:col]` reference in one console line; offsets are within the line, line and column are 0-based. */
data class GoConsoleLink(
    val start: Int,
    val end: Int,
    val path: String,
    val line: Int,
    val column: Int,
)

object GoConsoleLinks {
    // Covers compiler errors (`./main.go:5:2: ...`), test output (`x_test.go:12: ...`) and panic frames (`\t/abs/x.go:7 +0x1d`).
    // The path starts after whitespace or an opening bracket/quote, so `(main.go:12)` links `main.go`.
    private val MESSAGE_PATTERN =
        Regex("""(?<![^\s(\[{<"'`])([^\s(\[{<"'`]+\.\w+):(\d+)(?::(\d+))?(?=[:\s)\]}>"'`,]|$)""")
    private val GO_FILE_PATTERN = Regex("""\((\w+\.go)\)""")

    /** Candidates in line order; the caller links those that resolve to a file. */
    fun find(line: String): List<GoConsoleLink> {
        val messages = MESSAGE_PATTERN.findAll(line).toList()
        if (messages.isEmpty()) {
            return GO_FILE_PATTERN.findAll(line).map {
                val name = it.groups[1]!!
                GoConsoleLink(name.range.first, name.range.last + 1, name.value, 0, 0)
            }.toList()
        }
        return messages.mapNotNull(::toLink)
    }

    private fun toLink(match: MatchResult): GoConsoleLink? {
        val path = match.groups[1]!!
        // `http://host:8080/x.go:12` is a URL, not a file.
        if ("://" in path.value) return null
        val lineNumber = match.groups[2]!!.value.toIntOrNull() ?: return null
        if (lineNumber < 1) return null
        val columnGroup = match.groups[3]
        val column = columnGroup?.value?.toIntOrNull()?.let { maxOf(it - 1, 0) } ?: 0
        val end = (columnGroup ?: match.groups[2]!!).range.last + 1
        return GoConsoleLink(path.range.first, end, path.value, lineNumber - 1, column)
    }
}
