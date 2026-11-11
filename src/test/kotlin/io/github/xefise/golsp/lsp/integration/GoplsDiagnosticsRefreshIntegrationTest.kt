package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.psi.PsiDocumentManager
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

/**
 * A keystroke that lands between gopls publishing diagnostics and LSP4IJ applying them must not leave the editor
 * with stale highlighting: the follow-up report is identical, so only the client's reapply shows it.
 */
class GoplsDiagnosticsRefreshIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testErrorAppearsWhenAKeystrokeFollowsThePublish() {
        openHello()
        editThenKeystroke { insert(document.text.indexOf(ANCHOR) + ANCHOR.length, UNUSED) }
        waitUntil("unused variable error shown", REFRESH_TIMEOUT_SECONDS) { shownErrors().any { UNUSED_MESSAGE in it } }
    }

    fun testFixedErrorDisappearsWhenAKeystrokeFollowsThePublish() {
        openHello()
        insert(document.text.indexOf(ANCHOR) + ANCHOR.length, UNUSED)
        waitUntil("unused variable error shown") { shownErrors().any { UNUSED_MESSAGE in it } }
        // A use of the variable, not its deletion: deleting the text would drop the stale highlighter along with it.
        editThenKeystroke { insert(document.text.indexOf(UNUSED) + UNUSED.length, "\n\t_ = unused") }
        waitUntil("unused variable error gone", REFRESH_TIMEOUT_SECONDS) { shownErrors().none { UNUSED_MESSAGE in it } }
    }

    private val document get() = fixture.editor.document

    private fun openHello() {
        copyTestProject("hello")
        fixture.configureFromExistingVirtualFile(fixture.findFileInTempDir("main.go"))
        waitUntil("gopls started") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
    }

    // The EDT is held while gopls publishes, so LSP4IJ's apply of that report is still queued when the keystroke lands.
    private fun editThenKeystroke(edit: () -> Unit) {
        edit()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        Thread.sleep(PUBLISH_WAIT_MS)
        // Trailing whitespace at the end of the file: gopls reports the same diagnostics at the same positions again.
        insert(document.textLength, " ")
    }

    private fun insert(offset: Int, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    // The markup model, not doHighlighting(): running the daemon would reapply the stored diagnostics and hide the bug.
    private fun shownErrors(): List<String> =
        DocumentMarkupModel.forDocument(document, project, true).allHighlighters
            .mapNotNull { HighlightInfo.fromRangeHighlighter(it) }
            .filter { it.severity == HighlightSeverity.ERROR }
            .map { it.description.orEmpty() }

    private companion object {
        const val ANCHOR = "fmt.Println(\"hello\")"
        const val UNUSED = "\n\tunused := 1"
        const val UNUSED_MESSAGE = "declared and not used: unused"
        const val PUBLISH_WAIT_MS = 1_500L
        const val REFRESH_TIMEOUT_SECONDS = 2
    }
}
