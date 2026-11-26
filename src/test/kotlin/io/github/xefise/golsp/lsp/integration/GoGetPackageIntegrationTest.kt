package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import java.io.File

/**
 * gopls' "go get package" quick fix rewrites go.mod on disk when it is closed and through workspace/applyEdit when it
 * is open; either way the import must resolve without the user touching go.mod.
 */
class GoGetPackageIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testImportResolvesAfterGoGetWhenGoModIsClosed() {
        val goMod = createProject()
        openMain()
        runGoGet()
        // gopls wrote the file itself: the editor never loaded it, so the disk is the only place the require can be.
        waitUntil("go.mod on disk requires the dependency") { REQUIRE in File(goMod.path).readText() }
        waitUntil("broken import gone") { brokenImports().isEmpty() }
    }

    fun testImportResolvesAfterGoGetWhenGoModIsOpen() {
        val goMod = createProject()
        FileEditorManager.getInstance(project).openFile(goMod, false)
        openMain()
        runGoGet()
        val document = FileDocumentManager.getInstance().getDocument(goMod)!!
        waitUntil("go.mod document requires the dependency") { REQUIRE in document.text }
        waitUntil("broken import gone") { brokenImports().isEmpty() }
        assertFalse("go.mod left unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertTrue(REQUIRE in File(goMod.path).readText())
    }

    // A local module behind a replace directive: go get resolves it without the network.
    private fun createProject(): VirtualFile {
        fixture.addFileToProject("app/dep/go.mod", "module example.com/dep\n\ngo 1.22\n")
        fixture.addFileToProject("app/dep/pkg/pkg.go", "package pkg\n\nfunc Hello() string { return \"hi\" }\n")
        fixture.addFileToProject("app/main.go", MAIN)
        return fixture.addFileToProject("app/go.mod", "module example.com/app\n\ngo 1.22\n\nreplace example.com/dep => ./dep\n").virtualFile
    }

    private fun openMain() {
        fixture.configureFromExistingVirtualFile(fixture.findFileInTempDir("app/main.go"))
        fixture.editor.caretModel.moveToOffset(MAIN.indexOf(PACKAGE) + 1)
        waitUntil("gopls started") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        waitUntil("broken import reported") { brokenImports().isNotEmpty() }
    }

    // The same intention the user picks from Alt+Enter; LSP4IJ runs its command through workspace/executeCommand.
    private fun runGoGet() {
        var action: IntentionAction? = null
        waitUntil("go get quick fix offered") {
            action = fixture.availableIntentions.firstOrNull { it.text == "go get package $PACKAGE" }
            action != null
        }
        fixture.launchAction(action!!)
    }

    private fun brokenImports(): List<String> =
        fixture.doHighlighting(HighlightSeverity.ERROR).map { it.description.orEmpty() }.filter { PACKAGE in it }

    private companion object {
        const val PACKAGE = "example.com/dep/pkg"
        const val REQUIRE = "require example.com/dep v0.0.0-00010101000000-000000000000"
        const val MAIN = "package main\n\nimport \"$PACKAGE\"\n\nfunc main() { println(pkg.Hello()) }\n"
    }
}
