package io.github.xefise.golsp.lsp

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import io.github.xefise.golsp.lsp.model.GoplsSemanticTokens
import org.eclipse.lsp4j.InitializeParams

class GoplsClientFeatures : LSPClientFeatures() {
    override fun initializeParams(initializeParams: InitializeParams) {
        super.initializeParams(initializeParams)
        // LSP4IJ declares only a few modifiers and gopls sends only declared ones (defaultLibrary, type kinds...).
        val semanticTokens = initializeParams.capabilities?.textDocument?.semanticTokens ?: return
        semanticTokens.tokenModifiers = GoplsSemanticTokens.withExtraModifiers(semanticTokens.tokenModifiers)
    }
}
