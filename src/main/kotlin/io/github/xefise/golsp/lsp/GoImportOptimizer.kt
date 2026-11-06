package io.github.xefise.golsp.lsp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lang.GoLanguage
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS)

        val edits = try {
            val server = startedGopls(project, deadline) ?: return NOTHING
            val actions = await(server.textDocumentService.codeAction(params), deadline)
            var action = GoOrganizeImports.organizeAction(actions)
            if (action == null) {
                GoOrganizeImports.bareCommands(actions).takeIf { it.isNotEmpty() }
                    ?.let { LOG.info("Ignoring gopls organizeImports commands without an edit for $uri: $it") }
                return NOTHING
            }
            if (action.edit == null && canResolve(server)) {
                action = await(server.textDocumentService.resolveCodeAction(action), deadline)
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

    // Never start gopls from here: this runs inside a read action, and starting it is the toolchain's job.
    private fun startedGopls(project: Project, deadline: Long): LanguageServerItem? {
        val manager = LanguageServerManager.getInstance(project)
        val status = manager.getServerStatus(GoplsLanguageServerFactory.SERVER_ID)
        if (status != ServerStatus.started) {
            LOG.debug("Skipping organizeImports: gopls is $status")
            return null
        }
        return await(manager.getLanguageServer(GoplsLanguageServerFactory.SERVER_ID), deadline)
    }

    private fun canResolve(server: LanguageServerItem): Boolean {
        val provider = server.serverCapabilities?.codeActionProvider ?: return false
        return provider.isRight && provider.right.resolveProvider == true
    }

    private fun lastPosition(document: Document): Pair<Int, Int> {
        if (document.lineCount == 0) return 0 to 0
        val line = document.lineCount - 1
        return line to document.getLineEndOffset(line) - document.getLineStartOffset(line)
    }

    private fun <T> await(future: CompletableFuture<T>, deadline: Long): T {
        while (true) {
            try {
                ProgressManager.checkCanceled()
            } catch (e: ProcessCanceledException) {
                future.cancel(true)
                throw e
            }
            if (System.nanoTime() >= deadline) {
                future.cancel(true)
                throw TimeoutException()
            }
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Short polls let a pending write action cancel this read action.
            }
        }
    }

    private companion object {
        val LOG = logger<GoImportOptimizer>()
        val NOTHING: Runnable = EmptyRunnable.getInstance()
        const val REQUEST_TIMEOUT_SECONDS = 3L
        const val POLL_MS = 20L
    }
}
