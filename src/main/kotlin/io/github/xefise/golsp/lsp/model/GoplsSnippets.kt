package io.github.xefise.golsp.lsp.model

/** Rewrites gopls completion snippets (LSP snippet syntax). */
object GoplsSnippets {
    /** `${1:sku string}` → `${1:sku}`; any other placeholder is kept byte for byte. */
    fun argumentNamesOnly(snippet: String): String {
        if (!snippet.contains("\${")) return snippet
        val out = StringBuilder(snippet.length)
        var i = 0
        while (i < snippet.length) {
            val c = snippet[i]
            if (c == '\\' && i + 1 < snippet.length) {
                out.append(c).append(snippet[i + 1])
                i += 2
                continue
            }
            val placeholder = if (c == '$') parsePlaceholder(snippet, i) else null
            if (placeholder == null) {
                out.append(c)
                i++
                continue
            }
            out.append(placeholder.rewrite())
            i = placeholder.end
        }
        return out.toString()
    }

    private class Placeholder(val index: String, val raw: String, val end: Int, val source: String) {
        fun rewrite(): String {
            if (raw.contains('$')) return source
            val name = GoParameterLabel.parse(raw).name ?: return source
            // A bare `_` would hide the only hint, the type.
            if (name == "_") return source
            return "\${$index:$name}"
        }
    }

    // Parses `${digits:text}` at [start]; nested or escaped content stays in raw so rewrite() can bail out.
    private fun parsePlaceholder(s: String, start: Int): Placeholder? {
        if (start + 2 >= s.length || s[start + 1] != '{') return null
        var i = start + 2
        while (i < s.length && s[i].isDigit()) i++
        if (i == start + 2 || i >= s.length || s[i] != ':') return null
        val index = s.substring(start + 2, i)
        val textStart = i + 1
        var depth = 0
        i = textStart
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '$' -> if (i + 1 < s.length && s[i + 1] == '{') {
                    depth++
                    i++
                }
                '}' -> if (depth == 0) {
                    return Placeholder(index, s.substring(textStart, i), i + 1, s.substring(start, i + 1))
                } else {
                    depth--
                }
            }
            i++
        }
        return null
    }
}
