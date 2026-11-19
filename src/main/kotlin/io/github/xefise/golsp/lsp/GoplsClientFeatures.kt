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
import org.eclipse.lsp4j.ServerCapabilities

class GoplsClientFeatures : LSPClientFeatures() {
    init {
        // Double-click and Extend Selection come from the lexer (GoWordSelectioner): LSP4IJ's selectioners would
        // block on textDocument/selectionRange. GoCodeActions asks gopls for selection ranges directly.
        setSelectionRangeFeature(object : LSPSelectionRangeFeature() {
            override fun isEnabled(file: PsiFile): Boolean = false
        })
        setCompletionFeature(GoplsCompletionFeature())
        setDiagnosticFeature(GoplsDiagnosticFeature())
    }

    override fun initializeParams(initializeParams: InitializeParams) {
        super.initializeParams(initializeParams)
        // LSP4IJ declares only a few modifiers and gopls sends only declared ones (defaultLibrary, type kinds...).
        val semanticTokens = initializeParams.capabilities?.textDocument?.semanticTokens ?: return
        semanticTokens.tokenModifiers = GoplsSemanticTokens.withExtraModifiers(semanticTokens.tokenModifiers)
    }

    override fun setServerCapabilities(serverCapabilities: ServerCapabilities) {
        // gopls caches a file announced by didCreateFiles and then never reloads its package ("No packages found" until
        // restart). LSP4IJ passes this same instance to its file-operations filter after this call.
        serverCapabilities.workspace?.fileOperations?.didCreate = null
        super.setServerCapabilities(serverCapabilities)
    }
}

/** gopls argument placeholders read `sku string`, only the name is left to overtype; types in signatures get a name and `*`. */
private class GoplsCompletionFeature : LSPCompletionFeature() {
    override fun createLookupElement(item: CompletionItem, context: LSPCompletionContext): LookupElement? {
        if (item.insertTextFormat == InsertTextFormat.Snippet) {
            item.textEdit?.left?.let { it.newText = GoplsSnippets.argumentNamesOnly(it.newText) }
            item.textEdit?.right?.let { it.newText = GoplsSnippets.argumentNamesOnly(it.newText) }
            item.insertText?.let { item.insertText = GoplsSnippets.argumentNamesOnly(it) }
        }
        GoTypeSlotCompletion.rewrite(item, context)
        return super.createLookupElement(item, context)
    }
}
