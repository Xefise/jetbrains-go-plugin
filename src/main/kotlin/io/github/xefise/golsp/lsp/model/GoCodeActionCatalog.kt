package io.github.xefise.golsp.lsp.model

enum class GoCodeActionGroup { EXTRACT, INLINE, REWRITE, SOURCE }

/** How a gopls code action kind is shown; [needsSelection]: gopls offers it only for a non-empty range. */
data class GoCodeActionKind(val kind: String, val group: GoCodeActionGroup, val title: String, val needsSelection: Boolean)

/** gopls (v0.24) refactor/source code action kinds we request ourselves; kinds are hierarchical, dot-separated. */
object GoCodeActionCatalog {
    const val REFACTOR: String = "refactor"
    const val SOURCE: String = "source"

    const val EXTRACT_FUNCTION: String = "refactor.extract.function"
    const val EXTRACT_METHOD: String = "refactor.extract.method"
    const val EXTRACT_VARIABLE: String = "refactor.extract.variable"
    const val EXTRACT_VARIABLE_ALL: String = "refactor.extract.variable-all"
    const val EXTRACT_CONSTANT: String = "refactor.extract.constant"
    const val EXTRACT_CONSTANT_ALL: String = "refactor.extract.constant-all"
    const val EXTRACT_TO_NEW_FILE: String = "refactor.extract.toNewFile"
    const val INLINE_CALL: String = "refactor.inline.call"
    const val INLINE_VARIABLE: String = "refactor.inline.variable"
    const val FILL_STRUCT: String = "refactor.rewrite.fillStruct"

    val KINDS: List<GoCodeActionKind> = listOf(
        GoCodeActionKind(EXTRACT_FUNCTION, GoCodeActionGroup.EXTRACT, "Extract Function", needsSelection = true),
        GoCodeActionKind(EXTRACT_METHOD, GoCodeActionGroup.EXTRACT, "Extract Method", needsSelection = true),
        GoCodeActionKind(EXTRACT_VARIABLE, GoCodeActionGroup.EXTRACT, "Introduce Variable", needsSelection = true),
        GoCodeActionKind(EXTRACT_VARIABLE_ALL, GoCodeActionGroup.EXTRACT, "Introduce Variable (All Occurrences)", needsSelection = true),
        GoCodeActionKind(EXTRACT_CONSTANT, GoCodeActionGroup.EXTRACT, "Introduce Constant", needsSelection = true),
        GoCodeActionKind(EXTRACT_CONSTANT_ALL, GoCodeActionGroup.EXTRACT, "Introduce Constant (All Occurrences)", needsSelection = true),
        GoCodeActionKind(EXTRACT_TO_NEW_FILE, GoCodeActionGroup.EXTRACT, "Move Declarations to New File", needsSelection = false),
        GoCodeActionKind(INLINE_CALL, GoCodeActionGroup.INLINE, "Inline Call", needsSelection = false),
        GoCodeActionKind(INLINE_VARIABLE, GoCodeActionGroup.INLINE, "Inline Variable", needsSelection = false),
        GoCodeActionKind(FILL_STRUCT, GoCodeActionGroup.REWRITE, "Fill Struct", needsSelection = false),
        rewrite("fillSwitch", "Fill Switch Cases"),
        rewrite("invertIf", "Invert If"),
        rewrite("changeQuote", "Change Quotes"),
        rewrite("joinLines", "Join Lines"),
        rewrite("splitLines", "Split Lines"),
        rewrite("removeUnusedParam", "Remove Unused Parameter"),
        rewrite("moveParamLeft", "Move Parameter Left"),
        rewrite("moveParamRight", "Move Parameter Right"),
        rewrite("addTags", "Add Struct Tags"),
        rewrite("removeTags", "Remove Struct Tags"),
        rewrite("eliminateDotImport", "Eliminate Dot Import"),
        source("addTest", "Add Test"),
        source("doc", "Browse Documentation"),
        source("freesymbols", "Browse Free Symbols"),
        source("assembly", "Browse Assembly"),
        source("splitPackage", "Split Package"),
        source("toggleCompilerOptDetails", "Toggle Compiler Optimization Details"),
    )

    /** Kinds owned elsewhere (test runner, Optimize Imports, on-save) or needing interactive resolve gopls won't do for us. */
    val EXCLUDED: Set<String> = setOf(
        "source.test",
        "source.organizeImports",
        "source.fixAll",
        "refactor.rewrite.implementInterface",
        "refactor.move",
    )

    private val byKind: Map<String, GoCodeActionKind> = KINDS.associateBy { it.kind }

    /** The catalog entry for [kind]; an unknown kind under `refactor.*`/`source.*` gets a generic entry, excluded ones none. */
    fun find(kind: String?): GoCodeActionKind? {
        if (kind.isNullOrEmpty() || EXCLUDED.any { covers(it, kind) }) return null
        byKind[kind]?.let { return it }
        val group = when {
            covers("refactor.extract", kind) -> GoCodeActionGroup.EXTRACT
            covers("refactor.inline", kind) -> GoCodeActionGroup.INLINE
            covers(REFACTOR, kind) -> GoCodeActionGroup.REWRITE
            covers(SOURCE, kind) -> GoCodeActionGroup.SOURCE
            else -> return null
        }
        return GoCodeActionKind(kind, group, kind.substringAfterLast('.'), needsSelection = false)
    }

    /** LSP kind hierarchy: `refactor.extract` covers itself and `refactor.extract.function`, but not `refactor.extractX`. */
    fun covers(parent: String, kind: String): Boolean = kind == parent || kind.startsWith("$parent.")

    fun isRequested(kind: String?, requested: Collection<String>): Boolean =
        kind != null && requested.any { covers(it, kind) } && find(kind) != null

    /** Catalog order (then the server's) so lists look the same on every call. */
    fun order(kind: String?): Int = KINDS.indexOfFirst { it.kind == kind }.let { if (it < 0) KINDS.size else it }

    private fun rewrite(name: String, title: String) =
        GoCodeActionKind("refactor.rewrite.$name", GoCodeActionGroup.REWRITE, title, needsSelection = false)

    private fun source(name: String, title: String) = GoCodeActionKind("source.$name", GoCodeActionGroup.SOURCE, title, needsSelection = false)
}
