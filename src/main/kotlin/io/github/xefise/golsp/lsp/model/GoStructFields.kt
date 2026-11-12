package io.github.xefise.golsp.lsp.model

/** A struct field as listed in parameter info; an embedded field shows its type only. */
data class GoStructField(val name: String, val type: String, val embedded: Boolean) {
    val label: String get() = if (embedded) type else "$name $type"

    val exported: Boolean get() = name.first().isUpperCase()
}

/** Fields of a struct from gopls hover markdown: the first ```go block holds `type T struct { … }`. */
object GoStructFields {
    private const val FENCE = "```go\n"
    private val DECLARATION = Regex("""type\s+[\p{L}_][\p{L}\p{Nd}_]*(?:\[.*])?\s+struct\s*\{(.*)""")
    private val NAMED = Regex("""([\p{L}_][\p{L}\p{Nd}_]*(?:\s*,\s*[\p{L}_][\p{L}\p{Nd}_]*)*)\s+(\S.*)""")
    private val TAG = Regex("`[^`]*`")

    /** Null when the hover is not a struct type declaration. */
    fun parse(hover: String): List<GoStructField>? {
        val start = hover.indexOf(FENCE)
        if (start < 0) return null
        val end = hover.indexOf("\n```", start + FENCE.length)
        if (end < 0) return null
        val lines = hover.substring(start + FENCE.length, end).lines()
        val header = DECLARATION.matchEntire(lines.first().trim()) ?: return null
        when (code(header.groupValues[1])) {
            "}" -> return emptyList()
            "" -> Unit
            else -> return null
        }
        val fields = mutableListOf<GoStructField>()
        var depth = 1
        for (line in lines.drop(1)) {
            val code = code(line)
            if (depth == 1) {
                if (code == "}") break
                if (code.isNotEmpty()) fields += field(code)
            }
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (depth <= 0) break
        }
        return fields
    }

    private fun code(line: String): String {
        val noTag = TAG.replace(line, "")
        val comment = noTag.indexOf("//")
        return (if (comment >= 0) noTag.substring(0, comment) else noTag).trim()
    }

    private fun field(code: String): List<GoStructField> {
        val named = NAMED.matchEntire(code)
        if (named == null) {
            val name = code.removePrefix("*").substringBefore('[').substringAfterLast('.')
            return listOf(GoStructField(name, code, embedded = true))
        }
        var type = named.groupValues[2].trim()
        // A nested `struct {` spans the following lines.
        if (type.endsWith("{")) type = type.removeSuffix("{").trimEnd() + "{…}"
        return named.groupValues[1].split(',').map { GoStructField(it.trim(), type, embedded = false) }
    }

    /** Index of the field under the caret: the typed key, else the element position; null when neither matches. */
    fun activeIndex(fields: List<GoStructField>, site: GoCompositeLiteralSite): Int? {
        if (site.key != null) return fields.indexOfFirst { it.name == site.key }.takeIf { it >= 0 }
        return site.element.takeIf { it < fields.size }
    }
}
