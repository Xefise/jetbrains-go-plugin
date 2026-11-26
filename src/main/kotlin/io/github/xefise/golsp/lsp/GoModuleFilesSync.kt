package io.github.xefise.golsp.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.redhat.devtools.lsp4ij.LSPIJUtils
import io.github.xefise.golsp.lsp.model.GoplsModuleCommands
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Files

/**
 * After gopls rewrites go.mod/go.sum it reloads the module only once it sees them change on disk. It writes closed
 * files to disk itself, which neither the IDE nor gopls notices; open ones it edits in the editor and leaves unsaved.
 */
internal object GoModuleFilesSync {
    fun afterModuleCommand(project: Project, server: LanguageServer, editedUris: List<String>, isExpired: () -> Boolean) {
        if (editedUris.isEmpty()) refreshAndNotify(project, server, isExpired)
        else saveEdited(editedUris, isExpired) { refreshAndNotify(project, server, isExpired) }
    }

    private fun refreshAndNotify(project: Project, server: LanguageServer, isExpired: () -> Boolean) {
        ReadAction.nonBlocking<List<VirtualFile>> {
            val scope = GlobalSearchScope.projectScope(project)
            GoplsModuleCommands.MODULE_FILE_NAMES.flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                .mapNotNull { it.parent }.filter { it.isInLocalFileSystem }.distinct()
        }
            .inSmartMode(project)
            .expireWhen(isExpired)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { dirs -> if (!isExpired()) sync(dirs, server) }
    }

    // gopls ends the command only after LSP4IJ reports the edit applied, so the documents already hold it.
    // The notification must follow the save, otherwise gopls re-reads the stale go.mod from disk.
    private fun saveEdited(uris: List<String>, isExpired: () -> Boolean, then: () -> Unit) = ApplicationManager.getApplication().invokeLater({
        val manager = FileDocumentManager.getInstance()
        uris.mapNotNull { LSPIJUtils.findResourceFor(it) }
            .mapNotNull { manager.getCachedDocument(it) }
            .filter { manager.isDocumentUnsaved(it) }
            .forEach { manager.saveDocument(it) }
        then()
    }, Condition<Any?> { isExpired() })

    private fun sync(dirs: List<VirtualFile>, server: LanguageServer) {
        if (dirs.isEmpty()) return
        // Non-recursive with children reload: picks up a rewritten go.mod and a go.sum created next to it.
        VfsUtil.markDirtyAndRefresh(true, false, true, *dirs.toTypedArray())
        val events = dirs.flatMap { dir -> GoplsModuleCommands.MODULE_FILE_NAMES.map { dir.toNioPath().resolve(it) } }
            .filter { Files.isRegularFile(it) }
            .map { FileEvent(LSPIJUtils.toUri(it.toFile()).toASCIIString(), FileChangeType.Changed) }
        if (events.isNotEmpty()) server.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(events))
    }
}
