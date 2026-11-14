package io.github.xefise.golsp.lsp.integration

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ChooserInterceptor
import com.intellij.ui.UiInterceptors
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoCodeAction
import io.github.xefise.golsp.lsp.GoCodeActions
import io.github.xefise.golsp.lsp.GoFillStructIntention
import io.github.xefise.golsp.lsp.GoRefactoringsIntention
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoRefactoringsIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testFillStructFromTheAltEnterPopup() {
        open("fill/main.go", "package main\n\ntype Point struct {\n\tX, Y int\n}\n\nfunc main() {\n\tp := Point{}\n\t_ = p\n}\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("Point{}") + "Point{".length)
        awaitKind("refactor.rewrite.fillStruct")

        chooseInPopup("Fill.*")

        waitUntil("fill struct applied") { "X: 0" in fixture.editor.document.text }
        assertEquals(
            "package main\n\ntype Point struct {\n\tX, Y int\n}\n\nfunc main() {\n\tp := Point{\n\t\tX: 0,\n\t\tY: 0,\n\t}\n\t_ = p\n}\n",
            fixture.editor.document.text,
        )
    }

    fun testFillAllFieldsIntentionFillsOtherPackageStruct() {
        fixture.addFileToProject("fillall/stock/order.go", "package stock\n\ntype Order struct {\n\tID    int\n\tLines []string\n\tnote  string\n}\n")
        open("fillall/main.go", "package main\n\nimport \"fillall/stock\"\n\nfunc main() {\n\torder := stock.Order{}\n\t_ = order\n}\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("Order{}") + "Order{".length)
        val intention = GoFillStructIntention()
        assertTrue(intention.isAvailable(project, fixture.editor, fixture.file))
        awaitKind("refactor.rewrite.fillStruct")

        intention.invoke(project, fixture.editor, fixture.file)

        waitUntil("fill struct applied") { "ID:" in fixture.editor.document.text }
        val text = fixture.editor.document.text
        assertTrue(text, text.contains("ID:    0,") && text.contains("Lines: []string{},"))
        assertFalse(text, text.contains("note"))
    }

    fun testFillAllFieldsIntentionIsHiddenInBlocks() {
        open("fillblock/main.go", "package main\n\nfunc main() {\n\tif true {\n\t}\n}\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("if true {") + "if true {".length)
        assertFalse(GoFillStructIntention().isAvailable(project, fixture.editor, fixture.file))
    }

    fun testInvertIfFromThePopup() {
        open("invert/main.go", "package main\n\nfunc f(ok bool) {\n\tif ok {\n\t\tprintln(\"a\")\n\t} else {\n\t\tprintln(\"b\")\n\t}\n}\n\nfunc main() { f(true) }\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("if ok") + 1)
        awaitKind("refactor.rewrite.invertIf")

        chooseInPopup("Invert 'if'.*")

        waitUntil("invert if applied") { "!ok" in fixture.editor.document.text }
        assertEquals(
            "package main\n\nfunc f(ok bool) {\n\tif !ok {\n\t\tprintln(\"b\")\n\t} else {\n\t\tprintln(\"a\")\n\t}\n}\n\nfunc main() { f(true) }\n",
            fixture.editor.document.text,
        )
    }

    fun testAddTestCommandCreatesTheTestFileWithoutHanging() {
        open("addtest/main.go", "package main\n\nfunc Compute(a, b int) int { return a + b }\n\nfunc main() { _ = Compute(1, 2) }\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("Compute(a") + 1)
        awaitKind("source.addTest")

        chooseInPopup(".*[Aa]dd test.*")

        // The command is fire-and-forget; its edit comes back as workspace/applyEdit, applied by LSP4IJ.
        // LSP4IJ creates the file and fills its document; the text may not be saved to disk yet.
        val path = absolutePath("addtest/main_test.go")
        waitUntil("main_test.go created by gopls") {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            file != null && "TestCompute" in FileDocumentManager.getInstance().getDocument(file)?.text.orEmpty()
        }
    }

    /** Invokes the Alt+Enter intention and picks the popup entry matching [pattern]. */
    private fun chooseInPopup(pattern: String) {
        UiInterceptors.register(ChooserInterceptor(null, pattern))
        GoRefactoringsIntention().invoke(project, fixture.editor, fixture.file)
    }

    private fun open(path: String, text: String): VirtualFile {
        val dir = path.substringBefore('/')
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        val file = fixture.addFileToProject(path, text).virtualFile
        fixture.configureFromExistingVirtualFile(file)
        return file
    }

    // gopls loads the package lazily: wait until it offers [kind] here before driving the popup once.
    private fun awaitKind(kind: String) {
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        waitUntil("gopls to offer $kind") {
            var actions: List<GoCodeAction>? = null
            GoCodeActions.request(fixture.editor, listOf(GoCodeActionCatalog.REFACTOR, GoCodeActionCatalog.SOURCE)) { actions = it }
            poll(DEFAULT_TIMEOUT_SECONDS) { actions != null }
            actions.orEmpty().any { it.kind.kind == kind }
        }
    }
}
