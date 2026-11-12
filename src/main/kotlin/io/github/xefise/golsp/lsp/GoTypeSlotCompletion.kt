package io.github.xefise.golsp.lsp

import com.intellij.openapi.editor.ex.EditorEx
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature.LSPCompletionContext
import io.github.xefise.golsp.lang.goTokensBackward
import io.github.xefise.golsp.lsp.model.GoTypeCompletion
import io.github.xefise.golsp.lsp.model.GoTypeSlots
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat

/** Types completed in a func signature get a parameter name and `*` for structs: `func NewX(Ware` → `warehouse *Warehouse`. */
internal object GoTypeSlotCompletion {
    private val TYPE_KINDS = setOf(CompletionItemKind.Struct, CompletionItemKind.Interface, CompletionItemKind.Class)
    private val TYPE_NAME = Regex("""(?:[\p{L}_][\p{L}\p{Nd}_]*\.)?[\p{L}_][\p{L}\p{Nd}_]*""")

    fun rewrite(item: CompletionItem, context: LSPCompletionContext) {
        if (item.kind !in TYPE_KINDS) return
        val edit = item.textEdit?.left ?: return
        if (!TYPE_NAME.matches(edit.newText)) return
        val editor = context.parameters.editor
        val highlighter = (editor as? EditorEx)?.highlighter ?: return
        val document = editor.document
        val text = document.immutableCharSequence
        val rangeStart = LSPIJUtils.toOffset(edit.range.start, document)
        val site = GoTypeSlots.find(goTokensBackward(highlighter, rangeStart).asSequence(), text, rangeStart) ?: return
        // The `*` and `pkg.` before gopls' range are rewritten too.
        val typed = text.subSequence(site.start, rangeStart).toString()
        val type = typed.removePrefix("*") + edit.newText
        val insert = GoTypeCompletion.insertText(site, type, item.kind == CompletionItemKind.Struct) ?: return
        if (insert == edit.newText) return
        if (typed.isNotEmpty()) {
            edit.range.start = LSPIJUtils.toPosition(site.start, document)
            // LSP4IJ strips the filter text off the typed prefix, so `http.Cli` still matches the label `Client`.
            item.filterText = typed
        }
        edit.newText = insert
        item.insertTextFormat = InsertTextFormat.Snippet
    }
}
