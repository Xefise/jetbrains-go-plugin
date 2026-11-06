package io.github.xefise.golsp.architecture

object ArchitectureRules {
    const val ROOT: String = "io.github.xefise.golsp"

    // Mirrors the package table in CLAUDE.md.
    val ALLOWED: Map<String, Set<String>> = mapOf(
        "lang" to emptySet(),
        "toolchain" to emptySet(),
        "lsp" to setOf("lang", "toolchain"),
        "execution" to setOf("toolchain"),
        "run" to setOf("execution", "lang", "toolchain"),
        "test" to setOf("execution", "lang", "toolchain"),
        "debug" to setOf("execution", "lang", "toolchain"),
        "coverage" to setOf("test", "execution"),
    )

    private const val LSP4IJ_INTERNAL = "com.redhat.devtools.lsp4ij.internal."
    private val LSP4IJ_REF = Regex("""\b(?:com\.redhat\.devtools\.lsp4ij|org\.eclipse\.lsp4j)\.""")
    private val FEATURE_REF = Regex("""\b${Regex.escape(ROOT)}\.(\w+)\.""")
    private val LSP4IJ_FEATURES = setOf("lsp", "debug")
    private const val LSP4IJ_EXCEPTION_FEATURE = "execution"
    private const val LSP4IJ_EXCEPTION_FILE = "GoRunConfigurationBase.kt"
    private val MODEL_ALLOWED_PREFIXES = listOf("kotlin.", "java.", "com.google.gson.")

    fun violations(files: List<SourceFile>): List<String> = files.flatMap(::violations)

    fun violations(file: SourceFile): List<String> {
        val pkg = file.packageName
        val feature = featureOf(pkg)
        if (feature == null || feature !in ALLOWED) {
            return listOf("${file.path}: package '$pkg' is not a feature package under $ROOT (${ALLOWED.keys})")
        }
        val result = mutableListOf<String>()
        packageMismatch(file)?.let { result += it }
        val code = file.code
        if (code.contains(LSP4IJ_INTERNAL) || file.imports.any { "$it.".startsWith(LSP4IJ_INTERNAL) }) {
            result += "${file.path}: references ${LSP4IJ_INTERNAL.removeSuffix(".")}"
        }
        if (LSP4IJ_REF.containsMatchIn(code) && !mayUseLsp4ij(feature, file)) {
            result += "${file.path}: LSP4IJ/LSP4J outside lsp/, debug/ and execution/GoRunConfigurationBase"
        }
        FEATURE_REF.findAll(code).map { it.groupValues[1] }.distinct()
            .filter { it != feature && it !in ALLOWED.getValue(feature) }
            .forEach { result += "${file.path}: '$feature' must not depend on '$it'" }
        if (isModel(pkg)) {
            file.imports.filterNot(::isAllowedInModel).forEach { result += "${file.path}: model package imports '$it'" }
        }
        return result
    }

    fun cycles(graph: Map<String, Set<String>> = ALLOWED): List<List<String>> {
        val found = mutableListOf<List<String>>()
        val done = mutableSetOf<String>()
        fun visit(node: String, path: List<String>) {
            if (node in path) {
                found += path.dropWhile { it != node } + node
                return
            }
            if (!done.add(node)) return
            graph[node].orEmpty().forEach { visit(it, path + node) }
        }
        graph.keys.forEach { visit(it, emptyList()) }
        return found
    }

    private fun packageMismatch(file: SourceFile): String? {
        val root = SourceScanner.SOURCE_ROOTS.firstOrNull { file.path.startsWith("$it/") }
            ?: return "${file.path}: not under a source root ${SourceScanner.SOURCE_ROOTS}"
        val dir = file.path.removePrefix("$root/").substringBeforeLast('/', "").replace('/', '.')
        return if (dir == file.packageName) null
        else "${file.path}: package '${file.packageName}' does not match directory '$dir'"
    }

    private fun mayUseLsp4ij(feature: String, file: SourceFile): Boolean =
        feature in LSP4IJ_FEATURES || (feature == LSP4IJ_EXCEPTION_FEATURE && file.fileName == LSP4IJ_EXCEPTION_FILE)

    private fun featureOf(name: String): String? {
        if (!name.startsWith("$ROOT.")) return null
        return name.removePrefix("$ROOT.").substringBefore('.')
    }

    private fun isModel(name: String): Boolean = name.startsWith("$ROOT.") && "model" in name.split('.')

    private fun isAllowedInModel(import: String): Boolean =
        MODEL_ALLOWED_PREFIXES.any { import.startsWith(it) } || isModel(import)
}
