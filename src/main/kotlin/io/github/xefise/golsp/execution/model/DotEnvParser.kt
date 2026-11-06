// Modified from vscode-go (golang/vscode-go), commit 1608cb5, extension/src/utils/envUtils.ts, MIT
package io.github.xefise.golsp.execution.model

/** `.env` files in the format vscode-go reads, so files shared with VS Code and Docker setups behave the same. */
object DotEnvParser {
    private val LINE = Regex("""^\s*(export\s+)?([\w.\-]+)\s*=\s*(.*)?\s*$""")
    private val SURROUNDING_QUOTE = Regex("""^['"]|['"]$""")
    private val SUBSTITUTION = Regex("""\$\{([a-zA-Z]\w*)?([^}\w].*)?}""")
    private const val BOM = '﻿'

    /**
     * Lines that are not `KEY=VALUE` are ignored; a later key overwrites an earlier one.
     * `${VAR}` is looked up in the keys defined above it, then in [inherited].
     */
    fun parse(text: String, inherited: Map<String, String>): Map<String, String> {
        val env = linkedMapOf<String, String>()
        text.removePrefix(BOM.toString()).split('\n').forEach { line ->
            val match = LINE.find(line) ?: return@forEach
            var value = match.groups[3]?.value.orEmpty()
            if (value.isNotEmpty() && value.first() == '"' && value.last() == '"') {
                value = value.replace("\\n", "\n")
            }
            env[match.groupValues[2]] = substitute(value.replace(SURROUNDING_QUOTE, ""), env, inherited)
        }
        return env
    }

    // A malformed `${...}` anywhere leaves the whole value unsubstituted, as in vscode-go.
    private fun substitute(value: String, local: Map<String, String>, inherited: Map<String, String>): String {
        var invalid = false
        val replaced = SUBSTITUTION.replace(value) { match ->
            val start = match.range.first
            if (start > 0 && value[start - 1] == '\\') return@replace match.value
            val name = match.groups[1]?.value
            if (!match.groups[2]?.value.isNullOrEmpty() || name.isNullOrEmpty()) {
                invalid = true
                return@replace match.value
            }
            local[name].takeUnless { it.isNullOrEmpty() } ?: inherited[name].orEmpty()
        }
        return (if (invalid) value else replaced).replace("\\$", "$")
    }
}
