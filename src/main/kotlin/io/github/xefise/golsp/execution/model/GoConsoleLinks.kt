// Modified from go-lang-idea-plugin (go-lang-plugin-org), commit e73dab4, src/com/goide/runconfig/GoConsoleFilter.java, Apache-2.0
package io.github.xefise.golsp.execution.model

/** A `file:line[:col]` reference in one console line; offsets are within the line, line and column are 0-based. */
data class GoConsoleLink(
    val start: Int,
    val end: Int,
    val path: String,
    val line: Int,
    val column: Int,
)

object GoConsoleLinks {
    // Bounds the lazy scan from every `/` of a long line.
    private const val MAX_PATH = 1024
    // Each candidate costs a disk probe in the caller.
    private const val MAX_SPACED_CANDIDATES = 16

    // Covers compiler errors (`./main.go:5:2: ...`), test output (`x_test.go:12: ...`) and panic frames (`\t/abs/x.go:7 +0x1d`).
    // The path starts after whitespace or an opening bracket/quote, so `(main.go:12)` links `main.go`.
    private val MESSAGE_PATTERN =
        Regex("""(?<![^\s(\[{<"'`])([^\s(\[{<"'`]+\.\w+):(\d+)(?::(\d+))?(?=[:\s)\]}>"'`,]|$)""")
    // An absolute `.go` path may hold spaces (`/Users/me/My Projects/x.go:7`, `C:\Users\John Doe\x.go:7`); the shortest
    // span ending in `.go:N` wins, and the caller falls back to the space-free tail if this candidate does not resolve.
    private val ABSOLUTE_PATTERN =
        Regex("""(?<![^\s(\[{<"'`])((?:[A-Za-z]:[\\/]|/)[^\r\n:"'`<>|*?]{0,$MAX_PATH}?\.go):(\d+)(?::(\d+))?(?=[:\s)\]}>"'`,]|$)""")
    private val GO_FILE_PATTERN = Regex("""\((\w+\.go)\)""")

    /**
     * Candidates in line order; overlapping ones are alternatives, the longer first.
     * The caller links the first of them that resolves to a file.
     */
    fun find(line: String): List<GoConsoleLink> {
        val messages = MESSAGE_PATTERN.findAll(line).toList()
        val spaced = if (".go:" !in line) emptyList() else {
            // Every root start is a candidate, so `cd /a b && run /c d/x.go:1` still offers `/c d/x.go`.
            generateSequence(ABSOLUTE_PATTERN.find(line)) { ABSOLUTE_PATTERN.find(line, it.range.first + 1) }
                .filter { match -> match.groupValues[1].any { it.isWhitespace() } }
                .take(MAX_SPACED_CANDIDATES)
                .toList()
        }
        if (messages.isEmpty() && spaced.isEmpty()) {
            return GO_FILE_PATTERN.findAll(line).map {
                val name = it.groups[1]!!
                GoConsoleLink(name.range.first, name.range.last + 1, name.value, 0, 0)
            }.toList()
        }
        return (spaced + messages).mapNotNull(::toLink).sortedWith(compareBy({ it.start }, { -it.end }))
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
