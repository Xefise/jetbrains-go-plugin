package io.github.xefise.golsp.lsp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import io.github.xefise.golsp.lang.GoLanguage
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Optimize Imports (Ctrl+Alt+O and Actions on Save) via gopls `source.organizeImports`.
 * The platform calls [processFile] in a background read action and runs the returned Runnable in a write command.
 */
class GoImportOptimizer : ImportOptimizer {
    override fun supports(file: PsiFile): Boolean = file.language == GoLanguage

    override fun processFile(file: PsiFile): Runnable {
        // Waiting for gopls on EDT would freeze the UI; the platform only gets here on EDT in headless runWithoutProgress.
        if (ApplicationManager.getApplication().isDispatchThread) return NOTHING
        val project = file.project
        if (!TrustedProjects.isProjectTrusted(project)) return NOTHING
        val virtualFile = file.virtualFile ?: return NOTHING
        // LSP4IJ keeps only editor-open files in sync with gopls; for others gopls would answer from stale disk content.
        if (!FileEditorManager.getInstance(project).isFileOpen(virtualFile)) return NOTHING
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return NOTHING
        val stamp = document.modificationStamp
        val uri = LSPIJUtils.toUriAsString(virtualFile)
        val params = lastPosition(document).let { (line, column) -> GoOrganizeImports.params(uri, line, column) }
        val deadline = GoplsRequests.deadline(REQUEST_TIMEOUT_SECONDS)

        val edits = try {
            // Never starts gopls: this runs inside a read action.
            val server = GoplsRequests.startedGopls(project, deadline) ?: return NOTHING
            val actions = GoplsRequests.await(server.textDocumentService.codeAction(params), deadline)
            var action = GoOrganizeImports.organizeAction(actions)
            if (action == null) {
                GoOrganizeImports.bareCommands(actions).takeIf { it.isNotEmpty() }
                    ?.let { LOG.info("Ignoring gopls organizeImports commands without an edit for $uri: $it") }
                return NOTHING
            }
            if (action.edit == null && GoplsRequests.canResolve(server)) {
                action = GoplsRequests.await(server.textDocumentService.resolveCodeAction(action), deadline)
            }
            if (action.edit == null) {
                LOG.info("Ignoring gopls organizeImports action without an edit for $uri: command=${action.command?.command}")
                return NOTHING
            }
            GoOrganizeImports.editsFor(action.edit) { it == uri || LSPIJUtils.findResourceFor(it) == virtualFile }
        } catch (e: ExecutionException) {
            LOG.info("gopls organizeImports failed for $uri", e.cause ?: e)
            return NOTHING
        } catch (_: TimeoutException) {
            LOG.info("gopls organizeImports timed out for $uri")
            return NOTHING
        } catch (_: CancellationException) {
            return NOTHING
        }
        if (edits.isEmpty()) return NOTHING
        return Runnable {
            // gopls computed the edits for the text at `stamp`; applying them to anything newer would corrupt the file.
            if (document.modificationStamp != stamp) {
                LOG.warn("Discarding gopls organizeImports edits for $uri: document changed while waiting for gopls")
                return@Runnable
            }
            LSPIJUtils.applyEdits(null, document, edits)
        }
    }

    private fun lastPosition(document: Document): Pair<Int, Int> {
        if (document.lineCount == 0) return 0 to 0
        val line = document.lineCount - 1
        return line to document.getLineEndOffset(line) - document.getLineStartOffset(line)
    }

    private companion object {
        val LOG = logger<GoImportOptimizer>()
        val NOTHING: Runnable = EmptyRunnable.getInstance()
        const val REQUEST_TIMEOUT_SECONDS = 3L
    }
}
