package io.github.xefise.golsp.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.features.diagnostics.LSPDiagnosticsApplier
import io.github.xefise.golsp.lsp.model.GoplsModuleCommands
import io.github.xefise.golsp.lsp.model.GoplsSettings
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressEnd
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class GoplsLanguageClient(project: Project) : LanguageClientImpl(project) {
    private val reapply = MergingUpdateQueue("gopls diagnostics reapply", REAPPLY_DELAY_MS, true, null, null, null, false)
    private val moduleCommandTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val editedModuleFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The base returns the JSON the user typed in LSP4IJ's server settings; ours are defaults under it.
    override fun createSettings(): Any =
        GoplsSettings.build(
            super.createSettings() as? JsonObject,
            GoplsOptions.getInstance().usePlaceholders,
            GoplsAnalyzerInspections.analyses(project),
            GoplsInlayHintsSettings.forGopls(project),
        )

    // LSP4IJ 0.21 drops a refresh when the document changed before it applied, and skips the refresh for an identical
    // follow-up report, so the editor could keep stale diagnostics until a different one; reapply once typing pauses.
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        super.publishDiagnostics(diagnostics)
        if (isDisposed) return
        val file = LSPIJUtils.findResourceFor(diagnostics.uri) ?: return
        reapply.queue(Update.create(file) {
            if (isDisposed) return@create
            if (!FileEditorManager.getInstance(project).isFileOpen(file)) return@create
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@create
            LSPDiagnosticsApplier.getInstance(project).scheduleRefresh(file, document)
        })
    }

    override fun notifyProgress(params: ProgressParams) {
        super.notifyProgress(params)
        val token = params.token?.let { if (it.isLeft) it.left else it.right.toString() } ?: return
        when (val value = params.value?.takeIf { it.isLeft }?.left) {
            is WorkDoneProgressBegin -> if (GoplsModuleCommands.rewritesModuleFiles(value.title)) moduleCommandTokens += token
            is WorkDoneProgressEnd -> if (moduleCommandTokens.remove(token)) {
                val edited = editedModuleFiles.toList().also { editedModuleFiles.removeAll(it.toSet()) }
                val server = languageServer ?: return
                GoModuleFilesSync.afterModuleCommand(project, server, edited) { isDisposed }
            }
            else -> Unit
        }
    }

    // gopls edits an open go.mod through applyEdit and reloads the module only once it is saved; remember which to save.
    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        if (moduleCommandTokens.isNotEmpty()) {
            val edit = params.edit
            val uris = edit?.changes?.keys.orEmpty() +
                edit?.documentChanges.orEmpty().mapNotNull { it.takeIf { c -> c.isLeft }?.left?.textDocument?.uri }
            editedModuleFiles += uris.filter { GoplsModuleCommands.isModuleFile(it) }
        }
        return super.applyEdit(params)
    }

    // LSP4IJ calls dispose() directly rather than through Disposer, so a Disposer child of the client would leak.
    override fun dispose() {
        try {
            super.dispose()
        } finally {
            Disposer.dispose(reapply)
        }
    }

    private companion object {
        const val REAPPLY_DELAY_MS = 300
    }
}
