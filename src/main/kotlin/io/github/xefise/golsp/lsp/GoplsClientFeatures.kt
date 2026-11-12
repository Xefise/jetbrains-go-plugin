package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature
import com.redhat.devtools.lsp4ij.client.features.LSPSelectionRangeFeature
import io.github.xefise.golsp.lsp.model.GoplsSemanticTokens
import io.github.xefise.golsp.lsp.model.GoplsSnippets
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat

class GoplsClientFeatures : LSPClientFeatures() {
    init {
        // Double-click and Extend Selection come from the lexer (GoWordSelectioner): LSP4IJ's selectioners would
        // block on textDocument/selectionRange. GoCodeActions asks gopls for selection ranges directly.
        setSelectionRangeFeature(object : LSPSelectionRangeFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        })
        setCompletionFeature(GoplsCompletionFeature())
    }

    override fun initializeParams(initializeParams: InitializeParams) {
        super.initializeParams(initializeParams)
        // LSP4IJ declares only a few modifiers and gopls sends only declared ones (defaultLibrary, type kinds...).
        val semanticTokens = initializeParams.capabilities?.textDocument?.semanticTokens ?: return
        semanticTokens.tokenModifiers = GoplsSemanticTokens.withExtraModifiers(semanticTokens.tokenModifiers)
    }
}

/** gopls argument placeholders read `sku string`; only the name is left to overtype. */
private class GoplsCompletionFeature : LSPCompletionFeature() {
    override fun createLookupElement(item: CompletionItem, context: LSPCompletionContext): LookupElement? {
        if (item.insertTextFormat == InsertTextFormat.Snippet) {
            item.textEdit?.left?.let { it.newText = GoplsSnippets.argumentNamesOnly(it.newText) }
            item.textEdit?.right?.let { it.newText = GoplsSnippets.argumentNamesOnly(it.newText) }
            item.insertText?.let { item.insertText = GoplsSnippets.argumentNamesOnly(it) }
        }
        return super.createLookupElement(item, context)
    }
}
