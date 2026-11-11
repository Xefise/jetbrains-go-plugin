package io.github.xefise.golsp.lsp

import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPSelectionRangeFeature
import io.github.xefise.golsp.lsp.model.GoplsSemanticTokens
import org.eclipse.lsp4j.InitializeParams

class GoplsClientFeatures : LSPClientFeatures() {
    init {
        // Double-click and Extend Selection come from the lexer (GoWordSelectioner): LSP4IJ's selectioners would
        // block on textDocument/selectionRange. GoCodeActions asks gopls for selection ranges directly.
        setSelectionRangeFeature(object : LSPSelectionRangeFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        })
    }

    override fun initializeParams(initializeParams: InitializeParams) {
        super.initializeParams(initializeParams)
        // LSP4IJ declares only a few modifiers and gopls sends only declared ones (defaultLibrary, type kinds...).
        val semanticTokens = initializeParams.capabilities?.textDocument?.semanticTokens ?: return
        semanticTokens.tokenModifiers = GoplsSemanticTokens.withExtraModifiers(semanticTokens.tokenModifiers)
    }
}
