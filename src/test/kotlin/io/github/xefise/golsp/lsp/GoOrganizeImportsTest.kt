package io.github.xefise.golsp.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.StringValue
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoOrganizeImportsTest {
    private val target = "file:///p/a.go"
    private val other = "file:///p/b.go"
    private val isTarget: (String) -> Boolean = { it == target }

    private fun edit(text: String) = TextEdit(Range(Position(2, 0), Position(2, 0)), text)

    private fun action(kind: String?, workspaceEdit: WorkspaceEdit?): Either<Command, CodeAction> =
        Either.forRight(CodeAction("Organize Imports").apply { this.kind = kind; edit = workspaceEdit })

    private fun changes(vararg entries: Pair<String, List<TextEdit>>) = WorkspaceEdit(mapOf(*entries))

    @Test
    fun `takes edits for the target file from changes`() {
        val edits = GoOrganizeImports.editsFor(changes(target to listOf(edit("import \"fmt\"\n")), other to listOf(edit("x"))), isTarget)
        assertEquals(listOf(edit("import \"fmt\"\n")), edits)
    }

    @Test
    fun `takes plain text edits for the target file from documentChanges`() {
        val docEdit = TextDocumentEdit(
            VersionedTextDocumentIdentifier(target, 3),
            listOf(Either.forLeft(edit("a")), Either.forRight(SnippetTextEdit(Range(Position(0, 0), Position(0, 0)), StringValue().apply { value = "\$1" }))),
        )
        val otherEdit = TextDocumentEdit(VersionedTextDocumentIdentifier(other, 1), listOf(Either.forLeft(edit("b"))))
        val workspaceEdit = WorkspaceEdit(listOf(Either.forLeft(docEdit), Either.forLeft(otherEdit)))
        assertEquals(listOf(edit("a")), GoOrganizeImports.editsFor(workspaceEdit, isTarget))
    }

    @Test
    fun `empty documentChanges falls back to changes`() {
        val workspaceEdit = changes(target to listOf(edit("c"))).apply { documentChanges = emptyList() }
        assertEquals(listOf(edit("c")), GoOrganizeImports.editsFor(workspaceEdit, isTarget))
    }

    @Test
    fun `picks the organize-imports action, preferring one with an edit`() {
        val withEdit = action("${CodeActionKind.SourceOrganizeImports}.gopls", changes(target to listOf(edit("ok"))))
        val actions = listOf(
            Either.forLeft<Command, CodeAction>(Command("Organize Imports", "gopls.organize_imports")),
            action(CodeActionKind.QuickFix, changes(target to listOf(edit("fix")))),
            action(null, changes(target to listOf(edit("no kind")))),
            action(CodeActionKind.SourceOrganizeImports, null),
            action("source.organizeImportsSomething", changes(target to listOf(edit("lookalike")))),
            withEdit,
        )
        assertSame(withEdit.right, GoOrganizeImports.organizeAction(actions))
        assertEquals(listOf("gopls.organize_imports"), GoOrganizeImports.bareCommands(actions))
    }

    @Test
    fun `an organize-imports action without an edit is still returned so it can be resolved`() {
        val lazy = action(CodeActionKind.SourceOrganizeImports, null)
        assertSame(lazy.right, GoOrganizeImports.organizeAction(listOf(lazy)))
    }

    @Test
    fun `kind null, no reply or nothing to organize gives nothing`() {
        assertNull(GoOrganizeImports.organizeAction(listOf(action(null, changes(target to listOf(edit("x")))))))
        assertNull(GoOrganizeImports.organizeAction(null))
        assertNull(GoOrganizeImports.organizeAction(emptyList()))
        assertTrue(GoOrganizeImports.editsFor(null, isTarget).isEmpty())
        assertTrue(GoOrganizeImports.editsFor(WorkspaceEdit(), isTarget).isEmpty())
    }

    @Test
    fun `request asks only for organizeImports over the whole document`() {
        val params = GoOrganizeImports.params(target, 7, 4)
        assertEquals(target, params.textDocument.uri)
        assertEquals(Range(Position(0, 0), Position(7, 4)), params.range)
        assertEquals(listOf(CodeActionKind.SourceOrganizeImports), params.context.only)
    }
}
