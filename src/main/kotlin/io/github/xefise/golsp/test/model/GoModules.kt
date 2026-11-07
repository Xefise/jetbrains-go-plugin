package io.github.xefise.golsp.test.model

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Maps import paths to directories through the `module` lines of go.mod files, without running `go`. */
object GoModules {
    private val MODULE_LINE = Regex("""^\s*module\s+(\S+)""")
    private val MODULE_BLOCK = Regex("""^\s*module\s*\(\s*$""")
    private const val GO_MOD = "go.mod"

    /** A go.mod found on disk: [root] is its directory, [path] its file-system path for ordering duplicates. */
    data class Module<T>(val root: T, val modulePath: String, val path: String, val inContent: Boolean)

    /** The module path declared in go.mod [text], or null if there is none. */
    fun modulePath(text: String): String? {
        var inBlock = false
        for (line in text.lineSequence()) {
            val code = line.substringBefore("//")
            val raw = when {
                inBlock -> code.trim().takeIf { it.isNotEmpty() } ?: continue
                MODULE_BLOCK.matches(code) -> {
                    inBlock = true
                    continue
                }
                else -> MODULE_LINE.find(code)?.groupValues?.get(1) ?: continue
            }
            val path = raw.removeSurrounding("\"").removeSurrounding("`")
            return path.takeIf { it.isNotEmpty() && it != ")" }
        }
        return null
    }

    /**
     * Import path of the package in [directory] by the nearest go.mod above it on disk, as `go` resolves it; go.work
     * and GOPATH mode are not considered. Null without a go.mod or a module line.
     */
    fun importPath(directory: Path): String? {
        val absolute = directory.toAbsolutePath().normalize()
        val root = generateSequence(absolute) { it.parent }.firstOrNull { Files.isRegularFile(it.resolve(GO_MOD)) } ?: return null
        val text = try {
            Files.readString(root.resolve(GO_MOD))
        } catch (_: IOException) {
            return null
        }
        val module = modulePath(text) ?: return null
        val relative = root.relativize(absolute).joinToString("/")
        return if (relative.isEmpty()) module else "$module/$relative"
    }

    /** Directory of [importPath] relative to the root of module [modulePath]: "" for the root, null if outside. */
    fun relativeDirectory(importPath: String, modulePath: String): String? = when {
        importPath == modulePath -> ""
        importPath.startsWith("$modulePath/") -> importPath.substring(modulePath.length + 1)
        else -> null
    }

    /**
     * Modules that can own [importPath] with the package directory relative to each, best first: project content before
     * library copies (node_modules), then the longest module path (nested modules shadow their parent), then the
     * shortest file-system path (a copy under testdata), then the path in lexicographic order.
     */
    fun <T> owners(importPath: String, modules: Collection<Module<T>>): List<Pair<Module<T>, String>> =
        modules.mapNotNull { module -> relativeDirectory(importPath, module.modulePath)?.let { module to it } }
            .sortedWith(
                compareByDescending<Pair<Module<T>, String>> { it.first.inContent }
                    .thenByDescending { it.first.modulePath.length }
                    .thenBy { it.first.path.length }
                    .thenBy { it.first.path },
            )
}
