package io.github.xefise.golsp.lsp.integration

import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier

class GoInlayHintsIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testAssignedAndRangeVariablesGetTypeHints() {
        fixture.addFileToProject("hints/go.mod", "module hints\n\ngo 1.27\n")
        val text = "package main\n\nimport \"errors\"\n\nfunc main() {\n\tgot := errors.New(\"x\")\n\tfor i, s := range []string{} {\n\t\t_, _ = i, s\n\t}\n\t_ = got\n}\n"
        val file = fixture.addFileToProject("hints/main.go", text).virtualFile
        fixture.configureFromExistingVirtualFile(file)
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        val server = await("gopls", LanguageServerManager.getInstance(project).getLanguageServer(GoplsLanguageServerFactory.SERVER_ID))!!
        val params = InlayHintParams(TextDocumentIdentifier(LSPIJUtils.toUriAsString(file)), Range(Position(0, 0), Position(11, 0)))
        var labels: List<String> = emptyList()
        waitUntil("gopls type hints") {
            val hints: List<InlayHint> = await("inlay hints", server.server.textDocumentService.inlayHint(params)).orEmpty()
            labels = hints.map { hint -> hint.label.left ?: hint.label.right.joinToString("") { it.value } }
            labels.size >= 3
        }
        assertEquals(listOf("error", "int", "string"), labels.map { it.trim() }.sorted())
    }
}
