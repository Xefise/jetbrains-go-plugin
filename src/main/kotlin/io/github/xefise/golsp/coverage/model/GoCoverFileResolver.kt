package io.github.xefise.golsp.coverage.model

import io.github.xefise.golsp.test.model.GoModules

/**
 * Maps profile file names (`<import path>/<file>.go`) to paths through the modules of the run, without `go list`:
 * [modules] is module path → root directory with '/' separators. Packages outside every module resolve to null.
 */
class GoCoverFileResolver(modules: Map<String, String>) {
    private val modules = modules.mapValues { it.value.trimEnd('/') }.entries.sortedByDescending { it.key.length }

    fun resolve(name: String): String? {
        val slashed = name.replace('\\', '/')
        if (isAbsolute(slashed)) return slashed
        // A package outside any module and GOPATH is named by its directory: `_/abs/dir/file.go`.
        if (slashed.startsWith("_/")) return slashed.substring(1).takeIf(::isAbsolute)
        val directory = slashed.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } ?: return null
        val file = slashed.substringAfterLast('/')
        // Nested modules shadow their parent: the longest module path wins.
        for ((modulePath, root) in modules) {
            val relative = GoModules.relativeDirectory(directory, modulePath) ?: continue
            return if (relative.isEmpty()) "$root/$file" else "$root/$relative/$file"
        }
        return null
    }

    companion object {
        private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:/""")
        private val USE_LINE = Regex("""^\s*use\s+(\S+)""")
        private val USE_BLOCK = Regex("""^\s*use\s*\(\s*$""")

        private fun isAbsolute(path: String): Boolean = path.startsWith("/") || WINDOWS_ABSOLUTE.containsMatchIn(path)

        /** Directories of the `use` directives in go.work [text], as written (usually relative to the go.work). */
        fun goWorkUses(text: String): List<String> {
            val result = mutableListOf<String>()
            var inBlock = false
            for (line in text.lineSequence()) {
                val code = line.substringBefore("//").trim()
                when {
                    inBlock && code == ")" -> inBlock = false
                    inBlock -> code.takeIf { it.isNotEmpty() }?.let { result += unquote(it) }
                    USE_BLOCK.matches(code) -> inBlock = true
                    else -> USE_LINE.find(code)?.groupValues?.get(1)?.let { result += unquote(it) }
                }
            }
            return result.filter { it.isNotEmpty() }
        }

        private fun unquote(value: String): String = value.removeSurrounding("\"").removeSurrounding("`")
    }
}
