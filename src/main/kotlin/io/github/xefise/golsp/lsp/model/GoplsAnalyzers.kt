package io.github.xefise.golsp.lsp.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A gopls analyzer as listed in gopls' `internal/doc/api.json`; [name] is what gopls puts into Diagnostic.source. */
data class GoplsAnalyzer(val name: String, val doc: String, val url: String, val enabledByGopls: Boolean)

data class GoplsAnalyzerCatalog(val goplsVersion: String, val analyzers: List<GoplsAnalyzer>) {
    private val byName = analyzers.associateBy { it.name }

    operator fun get(name: String?): GoplsAnalyzer? = name?.let(byName::get)

    companion object {
        /** Parses the bundled `{"goplsVersion": ..., "analyzers": [{name, doc, url, default}]}` resource. */
        fun parse(json: String): GoplsAnalyzerCatalog {
            val root = JsonParser.parseString(json).asJsonObject
            val analyzers = root.getAsJsonArray("analyzers").map { element ->
                val item = element.asJsonObject
                GoplsAnalyzer(item.string("name"), item.string("doc"), item.string("url"), item.get("default")?.asBoolean ?: false)
            }
            return GoplsAnalyzerCatalog(root.string("goplsVersion"), analyzers)
        }

        private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }
}

object GoplsAnalyses {
    /** Explicit on/off for every analyzer: gopls' own defaults (and its `staticcheck` switch) must not leak through. */
    fun map(catalog: GoplsAnalyzerCatalog, isEnabled: (GoplsAnalyzer) -> Boolean): Map<String, Boolean> =
        catalog.analyzers.associate { it.name to isEnabled(it) }
}

/** Renders api.json's plain-text docs (paragraphs, tab or 4-space indented code, `- ` lists) as inspection description HTML. */
object GoplsAnalyzerDocs {
    private val URL = Regex("""https?://[^\s<>"]+[^\s<>".,;:)]""")

    fun html(analyzer: GoplsAnalyzer): String = buildString {
        append("<html><body>")
        renderBlocks(analyzer.doc.replace("\r\n", "\n").lines(), this)
        if (analyzer.url.isNotBlank()) {
            val url = escape(analyzer.url)
            append("<p><a href=\"").append(url).append("\">").append(url).append("</a></p>")
        }
        append("</body></html>")
    }

    private fun renderBlocks(lines: List<String>, out: StringBuilder) {
        var i = 0
        while (i < lines.size) {
            when {
                lines[i].isBlank() -> i++
                isIndented(lines[i]) -> {
                    val code = mutableListOf<String>()
                    // Blank lines inside a code sample belong to it as long as indented lines follow.
                    while (i < lines.size && (isIndented(lines[i]) || lines[i].isBlank() && codeFollows(lines, i))) {
                        code += dedent(lines[i])
                        i++
                    }
                    out.append("<pre>").append(escape(code.joinToString("\n").trimEnd())).append("</pre>")
                }
                lines[i].startsWith("- ") -> {
                    out.append("<ul>")
                    while (i < lines.size && lines[i].startsWith("- ")) {
                        out.append("<li>").append(inline(lines[i].removePrefix("- ").trim())).append("</li>")
                        i++
                    }
                    out.append("</ul>")
                }
                else -> {
                    // An indented line right under text ("Available since\n    2017.1") is a value, not a code block.
                    val parts = mutableListOf(lines[i].trim())
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("- ")) {
                        parts += if (isIndented(lines[i])) "\n" + lines[i].trim() else lines[i].trim()
                        i++
                    }
                    val text = parts.joinToString(" ").replace(" \n", "\n")
                    out.append("<p>").append(text.split("\n").joinToString("<br>") { inline(it) }).append("</p>")
                }
            }
        }
    }

    private fun codeFollows(lines: List<String>, from: Int): Boolean =
        lines.subList(from, lines.size).firstOrNull { it.isNotBlank() }?.let(::isIndented) == true

    private fun isIndented(line: String): Boolean = line.startsWith("\t") || line.startsWith("    ")

    private fun dedent(line: String): String = when {
        line.startsWith("\t") -> line.substring(1)
        line.startsWith("    ") -> line.substring(4)
        else -> line.trim()
    }

    private fun inline(text: String): String = URL.replace(escape(text)) { "<a href=\"${it.value}\">${it.value}</a>" }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
