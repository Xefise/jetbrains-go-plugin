package io.github.xefise.golsp.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Builds the gopls `source.organizeImports` request and picks the edits for one file out of the reply. */
object GoOrganizeImports {
    fun params(uri: String, lastLine: Int, lastLineLength: Int): CodeActionParams = CodeActionParams(
        TextDocumentIdentifier(uri),
        Range(Position(0, 0), Position(lastLine, lastLineLength)),
        CodeActionContext(emptyList(), listOf(CodeActionKind.SourceOrganizeImports)),
    )

    /** First organize-imports code action, preferring one that already carries an edit; bare commands are not actions. */
    fun organizeAction(actions: List<Either<Command, CodeAction>>?): CodeAction? {
        val candidates = actions.orEmpty().mapNotNull { if (it.isRight) it.right else null }.filter { it.kind.isOrganizeImports() }
        return candidates.firstOrNull { it.edit != null } ?: candidates.firstOrNull()
    }

    fun bareCommands(actions: List<Either<Command, CodeAction>>?): List<String> =
        actions.orEmpty().mapNotNull { if (it.isLeft) it.left.command else null }

    /** Text edits of [edit] for the file matched by [isTargetUri]; `documentChanges` wins unless it is empty. */
    fun editsFor(edit: WorkspaceEdit?, isTargetUri: (String) -> Boolean): List<TextEdit> {
        if (edit == null) return emptyList()
        val documentChanges = edit.documentChanges
        if (!documentChanges.isNullOrEmpty()) {
            return documentChanges.asSequence()
                .mapNotNull { if (it.isLeft) it.left else null }
                .filter { isTargetUri(it.textDocument.uri) }
                .flatMap { doc -> doc.edits.asSequence().mapNotNull { if (it.isLeft) it.left else null } }
                .toList()
        }
        return edit.changes.orEmpty().entries.filter { isTargetUri(it.key) }.flatMap { it.value }
    }

    private fun String?.isOrganizeImports(): Boolean =
        this == CodeActionKind.SourceOrganizeImports || this?.startsWith(CodeActionKind.SourceOrganizeImports + ".") == true
}
