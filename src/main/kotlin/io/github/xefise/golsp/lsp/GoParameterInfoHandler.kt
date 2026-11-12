package io.github.xefise.golsp.lsp

import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPParameterInfoHandler
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPSignatureHelperPsiElement
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lang.GoTokenTypes
import io.github.xefise.golsp.lsp.model.GoParameterLabel
import org.eclipse.lsp4j.SignatureInformation

/** LSP4IJ's parameter info, but with the parameter types colored like Go code (Rider-style). */
class GoParameterInfoHandler : LSPParameterInfoHandler() {
    override fun updateUI(shownSignature: SignatureInformation, context: ParameterInfoUIContext) {
        val owner = context.parameterOwner as? LSPSignatureHelperPsiElement
        val help = owner?.activeSignatureHelp
        // The popup keeps the signature it opened with; the latest reply carries the current active parameter.
        val signature = help?.signatures?.singleOrNull { it.label == shownSignature.label } ?: shownSignature
        val parameters = signature.parameters
        if (context.isSingleParameterInfo || parameters.isNullOrEmpty()) return super.updateUI(shownSignature, context)

        val active = (signature.activeParameter ?: help?.activeParameter ?: context.currentParameterIndex)
            .takeIf { it in parameters.indices } ?: 0
        val html = StringBuilder()
        parameters.forEachIndexed { index, parameter ->
            if (index > 0) html.append(", ")
            val text = parameter.label.left ?: parameter.label.right?.let { signature.label.substring(it.first, it.second) } ?: ""
            val label = GoParameterLabel.parse(text)
            val current = index == active && context.isUIComponentEnabled
            if (current) html.append("<b>")
            label.name?.let { html.append(StringUtil.escapeXmlEntities(it)).append(' ') }
            appendType(html, label.type)
            if (current) html.append("</b>")
        }
        context.setupRawUIComponentPresentation(html.toString())
    }

    private fun appendType(html: StringBuilder, type: String) {
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(GoLanguage, null, null) ?: return plain(html, type)
        val scheme = EditorColorsManager.getInstance().globalScheme
        val lexer = highlighter.highlightingLexer
        lexer.start(type)
        while (true) {
            val tokenType = lexer.tokenType ?: break
            val text = type.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
            val key: TextAttributesKey? = when {
                // `testing.T`: the qualifier is a package, the rest of the names are types.
                tokenType == GoTokenTypes.IDENTIFIER && lexer.tokenType == GoTokenTypes.DOT -> null
                tokenType == GoTokenTypes.IDENTIFIER -> DefaultLanguageHighlighterColors.CLASS_REFERENCE
                else -> highlighter.getTokenHighlights(tokenType).lastOrNull()
            }
            val color = key?.let { scheme.getAttributes(it)?.foregroundColor }
            if (color == null) {
                plain(html, text)
            } else {
                html.append("<span style=\"color:#").append(ColorUtil.toHex(color)).append("\">")
                plain(html, text)
                html.append("</span>")
            }
        }
    }

    private fun plain(html: StringBuilder, text: String) {
        html.append(StringUtil.escapeXmlEntities(text))
    }
}
