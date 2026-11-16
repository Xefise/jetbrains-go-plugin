package io.github.xefise.golsp.coverage.model

/** `replace` directives of go.mod and go.work whose target is a directory rather than another module version. */
object GoModReplaces {
    /** [directory] is as written: relative to the file that declares it, or absolute. */
    data class Replace(val modulePath: String, val directory: String)

    private val REPLACE_LINE = Regex("""^replace\s+(.*)$""")
    private val REPLACE_BLOCK = Regex("""^replace\s*\(\s*$""")
    private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/]""")

    fun localReplaces(text: String): List<Replace> {
        val result = mutableListOf<Replace>()
        var inBlock = false
        for (line in text.lineSequence()) {
            val code = line.substringBefore("//").trim()
            when {
                inBlock && code == ")" -> inBlock = false
                inBlock -> parse(code)?.let(result::add)
                REPLACE_BLOCK.matches(code) -> inBlock = true
                else -> REPLACE_LINE.find(code)?.groupValues?.get(1)?.let(::parse)?.let(result::add)
            }
        }
        return result
    }

    // `old [version] => new [version]`; a version on the right means a module, not a directory.
    private fun parse(entry: String): Replace? {
        val tokens = tokens(entry)
        val arrow = tokens.indexOf("=>")
        if (arrow !in 1..2 || tokens.size != arrow + 2) return null
        val directory = tokens[arrow + 1]
        return if (isLocalPath(directory)) Replace(tokens[0], directory) else null
    }

    // Mirrors modfile.IsDirectoryPath: only these forms name a directory.
    private fun isLocalPath(path: String): Boolean =
        path == "." || path == ".." || path.startsWith("./") || path.startsWith("../") ||
            path.startsWith(".\\") || path.startsWith("..\\") || path.startsWith("/") || WINDOWS_ABSOLUTE.containsMatchIn(path)

    private fun tokens(entry: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < entry.length) {
            val ch = entry[i]
            when {
                ch.isWhitespace() -> i++
                ch == '"' || ch == '`' -> {
                    val token = StringBuilder()
                    i++
                    while (i < entry.length && entry[i] != ch) {
                        if (ch == '"' && entry[i] == '\\' && i + 1 < entry.length) i++
                        token.append(entry[i++])
                    }
                    i++
                    result += token.toString()
                }
                else -> {
                    val start = i
                    while (i < entry.length && !entry[i].isWhitespace()) i++
                    result += entry.substring(start, i)
                }
            }
        }
        return result
    }
}
