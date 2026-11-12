package io.github.xefise.golsp.lsp.model

/** A gopls parameter label `name type`; [name] is null for an unnamed parameter (`int`, `chan int`). */
data class GoParameterLabel(val name: String?, val type: String) {
    companion object {
        private val NAMED = Regex("""^([\p{L}_][\p{L}\p{Nd}_]*)\s+(\S.*)$""", setOf(RegexOption.DOT_MATCHES_ALL))

        // Type-only labels that also read as "word space rest".
        private val TYPE_KEYWORDS = setOf("chan", "func", "map", "struct", "interface")

        fun parse(label: String): GoParameterLabel {
            val match = NAMED.find(label) ?: return GoParameterLabel(null, label)
            val name = match.groupValues[1]
            if (name in TYPE_KEYWORDS) return GoParameterLabel(null, label)
            return GoParameterLabel(name, match.groupValues[2])
        }
    }
}
