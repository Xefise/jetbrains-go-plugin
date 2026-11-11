package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoCodeContext
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind
import io.github.xefise.golsp.lang.model.GoTemplateContexts

/** The "Go Refactorings" popup: where it is worth offering, and which gopls actions it lists in which order. */
object GoRefactoringsMenu {
    /** Kinds with their own platform entry points (Extract/Introduce/Inline, the test runner, Optimize Imports). */
    val HANDLED_ELSEWHERE: List<String> = listOf(
        GoCodeActionCatalog.EXTRACT_FUNCTION,
        GoCodeActionCatalog.EXTRACT_METHOD,
        GoCodeActionCatalog.EXTRACT_VARIABLE,
        GoCodeActionCatalog.EXTRACT_VARIABLE_ALL,
        GoCodeActionCatalog.EXTRACT_CONSTANT,
        GoCodeActionCatalog.EXTRACT_CONSTANT_ALL,
        "refactor.inline",
        "source.test",
        "source.organizeImports",
    )

    private val KEYWORDS_AT_CARET = setOf("if", "switch", "func")

    /**
     * A guess from lexer tokens alone (gopls must not be asked here): true where gopls usually has a rewrite —
     * a selection, a string, `if`/`switch`/`func` or a function name, inside a composite literal, struct type or
     * bracketed list (fields, parameters, arguments). [tokens] cover [text] in order from 0 at least through the
     * token starting at [offset].
     */
    fun mayOffer(text: CharSequence, tokens: List<GoIndentToken>, offset: Int, hasSelection: Boolean): Boolean {
        if (hasSelection) return true
        if (offset < 0 || offset > text.length) return false
        val touching = tokens.filter { it.start <= offset && offset <= it.end && it.kind != GoIndentTokenKind.SPACE }
        for (token in touching) {
            val word = text.subSequence(token.start, token.end).toString()
            when (token.kind) {
                GoIndentTokenKind.STRING -> if (!word.startsWith("'")) return true
                GoIndentTokenKind.CODE -> if (word in KEYWORDS_AT_CARET || isFunctionName(text, tokens, token)) return true
                GoIndentTokenKind.OPEN -> if (isInsideList(text, tokens, token.end)) return true
                else -> Unit
            }
        }
        return isInsideList(text, tokens, offset)
    }

    /** [actions] without [HANDLED_ELSEWHERE], grouped by [GoCodeActionGroup] and in catalog order within a group. */
    fun <T> entries(actions: List<T>, kindOf: (T) -> GoCodeActionKind): List<T> = actions
        .filterNot { action -> HANDLED_ELSEWHERE.any { GoCodeActionCatalog.covers(it, kindOf(action).kind) } }
        .sortedWith(compareBy({ kindOf(it).group.ordinal }, { GoCodeActionCatalog.order(kindOf(it).kind) }))

    fun groupTitle(group: GoCodeActionGroup): String = when (group) {
        GoCodeActionGroup.EXTRACT -> "Extract"
        GoCodeActionGroup.INLINE -> "Inline"
        GoCodeActionGroup.REWRITE -> "Rewrite"
        GoCodeActionGroup.SOURCE -> "Source"
    }

    // Brace kinds other than a statement block: composite literal, struct/interface type, `(`/`[` lists.
    private fun isInsideList(text: CharSequence, tokens: List<GoIndentToken>, offset: Int): Boolean =
        GoTemplateContexts.classify(text, tokens, offset) == GoCodeContext.OTHER

    private fun isFunctionName(text: CharSequence, tokens: List<GoIndentToken>, token: GoIndentToken): Boolean {
        val c = text[token.start]
        if (c != '_' && !Character.isLetter(c)) return false
        val previous = tokens.lastOrNull { it.end <= token.start && it.kind != GoIndentTokenKind.SPACE && it.kind != GoIndentTokenKind.COMMENT }
        return previous != null && text.subSequence(previous.start, previous.end).toString() == "func"
    }
}
