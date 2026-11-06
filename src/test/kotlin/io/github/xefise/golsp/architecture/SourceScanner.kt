package io.github.xefise.golsp.architecture

import java.io.File

data class SourceFile(val path: String, val text: String) {
    val fileName: String get() = path.substringAfterLast('/')

    val packageName: String
        get() = PACKAGE.find(text)?.groupValues?.get(1).orEmpty().replace("`", "")

    val imports: List<String>
        get() = IMPORT.findAll(text).map { it.groupValues[1].replace("`", "") }.toList()

    // Comments and string/char literals blanked out, backticks dropped; package line removed.
    val code: String
        get() = PACKAGE.replace(stripCommentsAndStrings(text), "").replace("`", "")

    companion object {
        private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.`]+)""")
        private val IMPORT = Regex("""(?m)^\s*import\s+(?:static\s+)?([\w.`]+(?:\.\*)?)""")

        // Approximate: string templates with nested quotes are not tracked.
        fun stripCommentsAndStrings(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            fun skipUntil(end: String, escapes: Boolean) {
                while (i < text.length && !text.startsWith(end, i)) {
                    if (text[i] == '\n') out.append('\n')
                    i += if (escapes && text[i] == '\\') 2 else 1
                }
                i += end.length
                out.append(' ')
            }
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it } }
                    text.startsWith("/*", i) -> { i += 2; skipUntil("*/", escapes = false) }
                    text.startsWith("\"\"\"", i) -> { i += 3; skipUntil("\"\"\"", escapes = false) }
                    text[i] == '"' -> { i += 1; skipUntil("\"", escapes = true) }
                    text[i] == '\'' -> { i += 1; skipUntil("'", escapes = true) }
                    else -> { out.append(text[i]); i++ }
                }
            }
            return out.toString()
        }
    }
}

object SourceScanner {
    val SOURCE_ROOTS: List<String> = listOf("src/main/kotlin", "src/main/java", "src/test/kotlin", "src/test/java")

    private val projectDir: File =
        File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))

    fun scan(vararg roots: String): List<SourceFile> = roots
        .map { File(projectDir, it) }
        .filter { it.isDirectory }
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .map { SourceFile(it.relativeTo(projectDir).invariantSeparatorsPath, it.readText()) }
                .toList()
        }
        .sortedBy { it.path }
}
