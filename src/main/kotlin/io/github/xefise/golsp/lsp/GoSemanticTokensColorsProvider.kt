package io.github.xefise.golsp.lsp

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.SemanticTokensColorsProvider
import io.github.xefise.golsp.lang.GoColors
import io.github.xefise.golsp.lsp.model.GoTokenStyle
import io.github.xefise.golsp.lsp.model.GoTokenStyles

class GoSemanticTokensColorsProvider : SemanticTokensColorsProvider {
    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>, file: PsiFile): TextAttributesKey? =
        when (GoTokenStyles.styleOf(tokenType, tokenModifiers)) {
            GoTokenStyle.LEXER -> null
            GoTokenStyle.BUILTIN -> GoColors.BUILTIN
            GoTokenStyle.CONSTANT -> GoColors.CONSTANT
            GoTokenStyle.PACKAGE -> DefaultLanguageHighlighterColors.IDENTIFIER
            GoTokenStyle.TYPE -> DefaultLanguageHighlighterColors.CLASS_REFERENCE
            GoTokenStyle.TYPE_PARAMETER -> DefaultLanguageHighlighterColors.CLASS_REFERENCE
            GoTokenStyle.PARAMETER -> DefaultLanguageHighlighterColors.PARAMETER
            GoTokenStyle.VARIABLE -> DefaultLanguageHighlighterColors.LOCAL_VARIABLE
            GoTokenStyle.CHANNEL_PARAMETER -> GoColors.CHANNEL_PARAMETER
            GoTokenStyle.CHANNEL_VARIABLE -> GoColors.CHANNEL_VARIABLE
            GoTokenStyle.CHANNEL_FIELD -> GoColors.CHANNEL_FIELD
            GoTokenStyle.FUNCTION_DECLARATION -> DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
            GoTokenStyle.FUNCTION_CALL -> DefaultLanguageHighlighterColors.FUNCTION_CALL
            GoTokenStyle.METHOD_DECLARATION -> DefaultLanguageHighlighterColors.INSTANCE_METHOD
            GoTokenStyle.METHOD_CALL -> DefaultLanguageHighlighterColors.INSTANCE_METHOD
            GoTokenStyle.FIELD -> DefaultLanguageHighlighterColors.INSTANCE_FIELD
            GoTokenStyle.LABEL -> DefaultLanguageHighlighterColors.LABEL
        }
}
