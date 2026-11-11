package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import io.github.xefise.golsp.lang.GoFileType
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.lsp.model.GoCodeActionKind
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** A gopls code action computed for one state of [document]; apply it with [GoCodeActions.apply]. */
class GoCodeAction internal constructor(
    val kind: GoCodeActionKind,
    /** gopls' own title, e.g. "Inline call to compute". */
    val title: String,
    internal val action: CodeAction,
    internal val document: Document,
    internal val stamp: Long,
)

enum class GoCodeActionOutcome {
    /** The edit is in the documents (one undo step); the caret is where it was. */
    EDITED,

    /** The command went to gopls; its edit, if any, arrives later through `workspace/applyEdit`. */
    COMMAND_SENT,
}

/**
 * gopls refactor/source code actions requested by us: LSP4IJ asks with an empty `only` and `Automatic`,
 * for which gopls answers quick fixes only. Entry points run on EDT and wait for gopls under a modal progress.
 */
object GoCodeActions {
    private val LOG = logger<GoCodeActions>()
    private const val TIMEOUT_SECONDS = 10L

    /** Null when code actions can be requested for [file]; otherwise a hint for the user. Cheap, never starts gopls. */
    fun unavailableReason(project: Project, file: VirtualFile?): String? = when {
        file == null || !FileTypeRegistry.getInstance().isFileOfType(file, GoFileType.INSTANCE) -> "Not a Go file"
        !TrustedProjects.isProjectTrusted(project) -> "Go refactorings are disabled in an untrusted project"
        !GoplsRequests.isStarted(project) -> "gopls is not running"
        else -> null
    }

    /**
     * Asks gopls for actions of [kinds] (e.g. [GoCodeActionCatalog.EXTRACT_VARIABLE] or [GoCodeActionCatalog.REFACTOR])
     * on [range] (default: the selection, or the caret). [onResult] gets them in catalog order on EDT; it is not called
     * when gopls is unavailable, fails or the user cancels — a hint is shown instead.
     */
    @RequiresEdt
    fun request(editor: Editor, kinds: Collection<String>, range: TextRange? = null, onResult: (List<GoCodeAction>) -> Unit) {
        require(kinds.isNotEmpty()) { "no code action kinds requested" }
        val target = prepare(editor) ?: return
        val selected = range ?: editor.selectionModel.let { TextRange(it.selectionStart, it.selectionEnd) }
        val context = CodeActionContext(emptyList(), kinds.toList()).apply { triggerKind = CodeActionTriggerKind.Invoked }
        val params = CodeActionParams(TextDocumentIdentifier(target.uri), LSPIJUtils.toRange(selected, target.document), context)
        runModal(editor, "Asking gopls for refactorings", { server, deadline ->
            GoplsRequests.await(server.textDocumentService.codeAction(params), deadline).orEmpty()
                .mapNotNull { if (it.isRight) it.right else null }
                .filter { it.disabled == null && GoCodeActionCatalog.isRequested(it.kind, kinds) }
                .sortedBy { GoCodeActionCatalog.order(it.kind) }
        }) { actions ->
            onResult(actions.map { GoCodeAction(GoCodeActionCatalog.find(it.kind)!!, it.title, it, target.document, target.stamp) })
        }
    }

    /**
     * Resolves [action] if gopls deferred its edit, then applies it: an `edit` ourselves as one undo command, a `command`
     * through `workspace/executeCommand`, not awaited. [onApplied] runs on EDT after either; it is not called when the
     * document changed since [request] (a hint is shown), gopls fails or the user cancels.
     */
    @RequiresEdt
    fun apply(editor: Editor, action: GoCodeAction, onApplied: (GoCodeActionOutcome) -> Unit = {}) {
        if (isStale(editor, action)) return
        val current = action.action
        if (current.edit != null || current.data == null) {
            perform(editor, action, current, onApplied)
            return
        }
        runModal(editor, "Applying ${action.title}", { server, deadline ->
            if (GoplsRequests.canResolve(server)) GoplsRequests.await(server.textDocumentService.resolveCodeAction(current), deadline) else current
        }) { resolved ->
            if (!isStale(editor, action)) perform(editor, action, resolved, onApplied)
        }
    }

    /** `textDocument/selectionRange` at [offset]: non-empty ranges from the innermost out, without duplicates. */
    @RequiresEdt
    fun selectionRanges(editor: Editor, offset: Int = editor.caretModel.offset, onResult: (List<TextRange>) -> Unit) {
        val target = prepare(editor) ?: return
        val params = SelectionRangeParams(TextDocumentIdentifier(target.uri), listOf(LSPIJUtils.toPosition(offset, target.document)))
        runModal(editor, "Asking gopls for expressions", { server, deadline ->
            val chain = mutableListOf<Range>()
            var current = GoplsRequests.await(server.textDocumentService.selectionRange(params), deadline).orEmpty().firstOrNull()
            while (current != null) {
                chain += current.range
                current = current.parent
            }
            chain
        }) { chain ->
            if (target.document.modificationStamp != target.stamp) return@runModal
            onResult(chain.mapNotNull { LSPIJUtils.toTextRange(it, target.document) }.filterNot { it.isEmpty }.distinct())
        }
    }

    private class Target(val document: Document, val uri: String, val stamp: Long)

    private fun prepare(editor: Editor): Target? {
        val project = editor.project ?: return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        unavailableReason(project, file)?.let {
            HintManager.getInstance().showErrorHint(editor, it)
            return null
        }
        // LSP4IJ queues didChange when the document is committed; our request starts later on another thread.
        PsiDocumentManager.getInstance(project).commitDocument(document)
        return Target(document, LSPIJUtils.toUriAsString(file!!), document.modificationStamp)
    }

    private fun isStale(editor: Editor, action: GoCodeAction): Boolean {
        if (action.document.modificationStamp == action.stamp) return false
        HintManager.getInstance().showErrorHint(editor, "The file changed after gopls computed this refactoring; run it again")
        return true
    }

    private fun perform(editor: Editor, action: GoCodeAction, resolved: CodeAction, onApplied: (GoCodeActionOutcome) -> Unit) {
        val edit = resolved.edit
        val command = resolved.command
        when {
            edit != null -> {
                applyEdit(editor.project ?: return, action.title, edit)
                onApplied(GoCodeActionOutcome.EDITED)
            }
            command != null -> {
                sendCommand(editor, command)
                onApplied(GoCodeActionOutcome.COMMAND_SENT)
            }
            else -> HintManager.getInstance().showErrorHint(editor, "gopls returned nothing to apply for ${action.title}")
        }
    }

    private fun applyEdit(project: Project, title: String, edit: WorkspaceEdit) {
        WriteCommandAction.runWriteCommandAction(project, title, null, { LSPIJUtils.applyWorkspaceEdit(edit, title) })
    }

    // Not awaited: gopls answers only after LSP4IJ applies its workspace/applyEdit, which needs EDT outside any modal dialog.
    private fun sendCommand(editor: Editor, command: Command) {
        val project = editor.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = try {
                val server = GoplsRequests.startedGopls(project, GoplsRequests.deadline(TIMEOUT_SECONDS))
                if (server == null) {
                    "gopls is not running"
                } else {
                    server.workspaceService.executeCommand(ExecuteCommandParams(command.command, command.arguments)).whenComplete { _, error ->
                        if (error != null) {
                            LOG.info("gopls command ${command.command} failed", error)
                            showLater(editor, "gopls: ${describe(error)}")
                        }
                    }
                    null
                }
            } catch (_: TimeoutException) {
                "gopls did not answer"
            } catch (e: ExecutionException) {
                "gopls: ${describe(e)}"
            } catch (_: CancellationException) {
                "gopls request was cancelled"
            }
            failure?.let { showLater(editor, it) }
        }
    }

    private fun describe(error: Throwable): String = (error.cause ?: error).let { it.message ?: it.javaClass.simpleName }

    private fun showLater(editor: Editor, message: String) = ApplicationManager.getApplication().invokeLater {
        if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, message)
    }

    private fun <T> runModal(editor: Editor, title: String, compute: (LanguageServerItem, Long) -> T, onSuccess: (T) -> Unit) {
        val project = editor.project ?: return
        ProgressManager.getInstance().run(object : Task.Modal(project, title, true) {
            private var result: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val deadline = GoplsRequests.deadline(TIMEOUT_SECONDS)
                result = try {
                    val server = GoplsRequests.startedGopls(project, deadline) ?: throw GoplsUnavailable("gopls is not running")
                    Result.success(compute(server, deadline))
                } catch (_: TimeoutException) {
                    Result.failure(GoplsUnavailable("gopls did not answer in $TIMEOUT_SECONDS s"))
                } catch (e: ExecutionException) {
                    LOG.info("gopls request failed: $title", e.cause ?: e)
                    Result.failure(GoplsUnavailable("gopls: ${describe(e)}"))
                } catch (e: ProcessCanceledException) {
                    // The user cancelled the progress: no hint.
                    throw e
                } catch (_: CancellationException) {
                    Result.failure(GoplsUnavailable("gopls request was cancelled"))
                } catch (e: GoplsUnavailable) {
                    Result.failure(e)
                }
            }

            override fun onSuccess() {
                val outcome = result ?: return
                if (editor.isDisposed) return
                outcome.onSuccess(onSuccess).onFailure { HintManager.getInstance().showErrorHint(editor, it.message.orEmpty()) }
            }
        })
    }

    private class GoplsUnavailable(message: String) : RuntimeException(message)
}
