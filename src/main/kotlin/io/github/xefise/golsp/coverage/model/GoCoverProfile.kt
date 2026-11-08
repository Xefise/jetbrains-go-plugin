// Modified from go-lang-idea-plugin (commit e73dab4, Apache-2.0): GoCoverageRunner.parseCoverage block-to-line expansion
package io.github.xefise.golsp.coverage.model

/** One block of a `go test -coverprofile` file: 1-based positions as go writes them, [file] as named in the profile. */
data class GoCoverBlock(
    val file: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val statements: Int,
    val count: Int,
)

data class GoCoverProfile(val mode: String, val blocks: List<GoCoverBlock>)

object GoCoverProfileParser {
    private const val MODE_PREFIX = "mode:"
    private val POSITIONS = Regex("""^(\d+)\.(\d+),(\d+)\.(\d+) (\d+) (\d+)$""")

    /**
     * Blocks repeated at the same position (one per test binary of `./...`, or concatenated profiles) are merged as
     * `go tool cover` does: `set` keeps any hit, `count`/`atomic` add up. Malformed lines are skipped.
     */
    fun parse(text: String): GoCoverProfile {
        var mode = ""
        val blocks = LinkedHashMap<Position, GoCoverBlock>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith(MODE_PREFIX)) {
                // go writes one header per profile; in concatenated profiles the first one is taken.
                if (mode.isEmpty()) mode = line.removePrefix(MODE_PREFIX).trim()
                continue
            }
            val block = parseBlock(line) ?: continue
            val position = Position(block.file, block.startLine, block.startColumn, block.endLine, block.endColumn)
            val previous = blocks[position]
            blocks[position] = if (previous == null) block else previous.copy(count = merge(mode, previous.count, block.count))
        }
        return GoCoverProfile(mode, blocks.values.toList())
    }

    // The file name may itself contain ':' (a Windows drive), the positions never do.
    private fun parseBlock(line: String): GoCoverBlock? {
        val colon = line.lastIndexOf(':')
        if (colon <= 0) return null
        val numbers = POSITIONS.matchEntire(line.substring(colon + 1))?.groupValues?.drop(1)?.map(::toCount) ?: return null
        val (startLine, startColumn, endLine, endColumn) = numbers
        if (startLine < 1 || endLine < startLine) return null
        return GoCoverBlock(line.substring(0, colon), startLine, startColumn, endLine, endColumn, numbers[4], numbers[5])
    }

    // atomic counters are uint32 in go.
    private fun toCount(digits: String): Int = digits.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE

    private fun merge(mode: String, a: Int, b: Int): Int =
        if (mode == "set") maxOf(a, b) else (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private data class Position(val file: String, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)
}

/** Coverage of one source line: the best hit count of the blocks on it, [partial] when some of them never ran. */
data class GoLineHits(val hits: Int, val partial: Boolean)

object GoCoverLines {
    /** Lines of every file [resolve] maps to a path; files it returns null for are dropped. */
    fun byFile(blocks: List<GoCoverBlock>, resolve: (String) -> String?): Map<String, Map<Int, GoLineHits>> {
        val paths = HashMap<String, String?>()
        val result = LinkedHashMap<String, MutableMap<Int, GoLineHits>>()
        for (block in blocks) {
            // A block without statements (an empty body) has nothing to execute.
            if (block.statements == 0) continue
            val path = paths.getOrPut(block.file) { resolve(block.file) } ?: continue
            val lines = result.getOrPut(path) { LinkedHashMap() }
            for (line in block.startLine..block.endLine) {
                val previous = lines[line]
                lines[line] = if (previous == null) {
                    GoLineHits(block.count, partial = false)
                } else {
                    val partial = previous.partial || (previous.hits == 0) != (block.count == 0)
                    GoLineHits(maxOf(previous.hits, block.count), partial)
                }
            }
        }
        return result
    }
}
