package io.github.xefise.golsp.lsp.model

/** Semantic token modifiers gopls emits only when the client declares them in its capabilities. */
object GoplsSemanticTokens {
    val EXTRA_MODIFIERS: List<String> = listOf(
        "defaultLibrary", "definition", "readonly", "deprecated",
        "array", "bool", "chan", "interface", "map", "number", "pointer", "signature", "slice", "string", "struct",
        "shadowing",
    )

    fun withExtraModifiers(declared: List<String>?): List<String> = (declared.orEmpty() + EXTRA_MODIFIERS).distinct()
}

/** Plugin-side colour role for a gopls token; [LEXER] leaves the token to the lexer highlighting. */
enum class GoTokenStyle {
    LEXER,
    BUILTIN,
    CONSTANT,
    PACKAGE,
    TYPE,
    TYPE_PARAMETER,
    PARAMETER,
    VARIABLE,
    CHANNEL_PARAMETER,
    CHANNEL_VARIABLE,
    CHANNEL_FIELD,
    FUNCTION_DECLARATION,
    FUNCTION_CALL,
    METHOD_DECLARATION,
    METHOD_CALL,
    FIELD,
    LABEL,
}

object GoTokenStyles {
    fun styleOf(tokenType: String, modifiers: List<String>): GoTokenStyle {
        val defaultLibrary = "defaultLibrary" in modifiers
        val declaration = "definition" in modifiers || "declaration" in modifiers
        val channel = "chan" in modifiers
        return when (tokenType) {
            "namespace" -> GoTokenStyle.PACKAGE
            "type", "interface", "struct", "class", "enum" -> if (defaultLibrary) GoTokenStyle.BUILTIN else GoTokenStyle.TYPE
            "typeParameter" -> GoTokenStyle.TYPE_PARAMETER
            "parameter" -> if (channel) GoTokenStyle.CHANNEL_PARAMETER else GoTokenStyle.PARAMETER
            "variable" -> when {
                defaultLibrary -> GoTokenStyle.BUILTIN
                "readonly" in modifiers -> GoTokenStyle.CONSTANT
                channel -> GoTokenStyle.CHANNEL_VARIABLE
                else -> GoTokenStyle.VARIABLE
            }
            "property" -> if (channel) GoTokenStyle.CHANNEL_FIELD else GoTokenStyle.FIELD
            "function" -> when {
                defaultLibrary -> GoTokenStyle.BUILTIN
                declaration -> GoTokenStyle.FUNCTION_DECLARATION
                else -> GoTokenStyle.FUNCTION_CALL
            }
            "method" -> if (declaration) GoTokenStyle.METHOD_DECLARATION else GoTokenStyle.METHOD_CALL
            "label" -> GoTokenStyle.LABEL
            // keyword, comment, string, number, operator, macro: the lexer already colours them.
            else -> GoTokenStyle.LEXER
        }
    }
}
