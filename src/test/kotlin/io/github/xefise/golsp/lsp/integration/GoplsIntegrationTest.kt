package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.LanguageImportStatements
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoImportOptimizer
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoplsIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testTypeErrorIsReportedAsErrorHighlight() {
        open("diag/main.go", "package main\n\nvar x int = \"s\"\n\nfunc main() { _ = x }\n")
        val error = awaitErrors { errors -> errors.any { "cannot use \"s\"" in it.description.orEmpty() } }
            .first { "cannot use \"s\"" in it.description.orEmpty() }
        // The error is on the literal "s" (offset of the opening quote, three characters long).
        val literal = fixture.editor.document.text.indexOf("\"s\"")
        assertEquals(literal, error.startOffset)
        assertEquals(literal + 3, error.endOffset)
    }

    fun testErrorDisappearsAfterTheDocumentIsFixed() {
        open("fix/main.go", "package main\n\nvar x int = \"s\"\n\nfunc main() { _ = x }\n")
        awaitErrors { errors -> errors.isNotEmpty() }
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("\"s\"")
            document.replaceString(start, start + 3, "1")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // Unsaved edit: only didChange can tell gopls the error is gone.
        awaitErrors { errors -> errors.isEmpty() }
    }

    fun testCompletionAfterFmtPrOffersPrintlnAndInsertsIt() {
        open("comp/main.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Pr<caret>\n}\n")
        var items: List<String> = emptyList()
        waitUntil("gopls completion for fmt.Pr") {
            items = fixture.completeBasic()?.map { it.lookupString }.orEmpty()
            val found = "Println" in items
            // A lookup left open by a failed attempt would swallow the next completeBasic().
            if (!found) fixture.lookup?.hideLookup(true)
            found
        }
        assertContainsElements(items, "Print", "Printf", "Println")
        assertFalse("unexported or unrelated names leak into fmt.Pr: $items", items.any { !it.startsWith("Pr") })
        fixture.lookup.currentItem = fixture.lookupElements!!.first { it.lookupString == "Println" }
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val text = fixture.editor.document.text
        assertTrue(text, text.contains("\tfmt.Println("))
    }

    fun testGotoDeclarationJumpsToFunctionInSameFile() {
        copyTestProject("hello")
        val main = fixture.findFileInTempDir("main.go")
        fixture.configureFromExistingVirtualFile(main)
        val text = fixture.editor.document.text
        val call = text.indexOf("total += square(i)") + "total += ".length
        val target = awaitDeclaration(call)
        assertEquals(main, target.containingFile.virtualFile)
        assertEquals(text.indexOf("func square(") + "func ".length, target.textOffset)
    }

    fun testGotoDeclarationIntoStandardLibrary() {
        copyTestProject("hello")
        fixture.configureFromExistingVirtualFile(fixture.findFileInTempDir("main.go"))
        val text = fixture.editor.document.text
        val call = text.indexOf("fmt.Sprintf") + "fmt.".length
        val target = awaitDeclaration(call)
        val path = target.containingFile.virtualFile.path
        assertTrue(path, path.endsWith("/src/fmt/print.go"))
    }

    fun testReformatAppliesGofmt() {
        open("fmt/main.go", "package main\nfunc main(){\nx:=1\n_ = x\n}\n")
        val expected = "package main\n\nfunc main() {\n\tx := 1\n\t_ = x\n}\n"
        waitUntil("gopls formatting") {
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
            fixture.editor.document.text == expected
        }
    }

    fun testOrganizeImportsRemovesUnusedAndAddsMissingImport() {
        open(
            "imports/main.go",
            "package main\n\nimport (\n\t\"os\"\n\t\"fmt\"\n)\n\nfunc main() {\n\tfmt.Println(strings.ToUpper(\"x\"))\n}\n",
        )
        val expected = "package main\n\nimport (\n\t\"fmt\"\n\t\"strings\"\n)\n\nfunc main() {\n\tfmt.Println(strings.ToUpper(\"x\"))\n}\n"
        waitUntil("gopls organize imports") {
            optimizeImports()
            fixture.editor.document.text == expected
        }
    }

    fun testOrganizeImportsLeavesCleanFileUntouched() {
        val source = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println()\n}\n"
        open("clean/main.go", source)
        // A positive signal that gopls has loaded the package; "no edits" alone could just mean "not ready".
        awaitDeclaration(source.indexOf("Println"))
        val stamp = fixture.editor.document.modificationStamp
        optimizeImports()
        assertEquals(source, fixture.editor.document.text)
        assertEquals(stamp, fixture.editor.document.modificationStamp)
    }

    fun testSaveAllReformatsAndOrganizesImports() {
        val source = "package main\nimport (\n\"os\"\n\"fmt\"\n)\nfunc main(){\nfmt.Println(strings.ToUpper(\"x\"))\n}\n"
        val file = open("onsave/main.go", source)
        awaitDeclaration(source.indexOf("Println"))
        val document = fixture.editor.document
        // Save All only processes unsaved documents.
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // Cmd+S / Ctrl+S in the default keymaps.
        fixture.performEditorAction("SaveAll")
        val expected = "package main\n\nimport (\n\t\"fmt\"\n\t\"strings\"\n)\n\nfunc main() {\n\tfmt.Println(strings.ToUpper(\"x\"))\n}\n"
        waitUntil("Actions on Save to reformat, organize imports and save") {
            document.text == expected && !FileDocumentManager.getInstance().isDocumentUnsaved(document)
        }
        assertEquals(expected, VfsUtilCore.loadText(file))
    }

    fun testWorkspaceModulesResolveEachOtherThroughGoWork() {
        copyTestProject("workspace")
        val main = fixture.findFileInTempDir("app/main.go")
        fixture.configureFromExistingVirtualFile(main)
        val text = fixture.editor.document.text
        val target = awaitDeclaration(text.indexOf("greet.Hello") + "greet.".length)
        assertEquals(fixture.findFileInTempDir("lib/greet/greet.go"), target.containingFile.virtualFile)
        // An empty result is also what "not published yet" looks like; a canary error proves this file's diagnostics arrived.
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, CANARY) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val errors = awaitErrors { errors -> errors.any { it.isCanary() } }
        assertEmpty(errors.filterNot { it.isCanary() }.map { it.description })
    }

    /** Writes [text] as [path] inside a fresh module named after its directory and opens it; `<caret>` marks the caret. */
    private fun open(path: String, text: String): VirtualFile {
        val dir = path.substringBefore('/')
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        val caret = text.indexOf(CARET)
        val file = fixture.addFileToProject(path, text.replace(CARET, "")).virtualFile
        fixture.configureFromExistingVirtualFile(file)
        if (caret >= 0) fixture.editor.caretModel.moveToOffset(caret)
        return file
    }

    private fun awaitServerStarted() {
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
    }

    private fun HighlightInfo.isCanary(): Boolean = "cannot use \"canary\"" in description.orEmpty()

    private fun errors(): List<HighlightInfo> = fixture.doHighlighting(HighlightSeverity.ERROR)

    private fun awaitErrors(condition: (List<HighlightInfo>) -> Boolean): List<HighlightInfo> {
        awaitServerStarted()
        var last: List<HighlightInfo> = emptyList()
        waitUntil("gopls diagnostics") {
            last = errors()
            condition(last)
        }
        return last
    }

    private fun awaitDeclaration(offset: Int): PsiElement {
        awaitServerStarted()
        var target: PsiElement? = null
        val file = fixture.file
        val editor = fixture.editor
        waitUntil("gopls definition at $offset") {
            // LSP4IJ blocks on the server here, so the call must stay off EDT just like Ctrl+B does.
            target = inBackgroundRead("one definition request") {
                val source = file.findElementAt(offset)
                GotoDeclarationHandler.EP_NAME.extensionList.asSequence()
                    .mapNotNull { it.getGotoDeclarationTargets(source, offset, editor)?.singleOrNull() }
                    .firstOrNull()
            }
            target != null
        }
        return target!!
    }

    /** The platform calls the optimizer in a background read action; on EDT it deliberately does nothing. */
    private fun optimizeImports() {
        val file = fixture.file
        val optimizer = LanguageImportStatements.INSTANCE.forFile(file).filterIsInstance<GoImportOptimizer>().single()
        val apply = inBackgroundRead("organize imports request") { optimizer.processFile(file) }
        WriteCommandAction.runWriteCommandAction(project) { apply.run() }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private companion object {
        const val CARET = "<caret>"
        const val CANARY = "\nvar _ int = \"canary\"\n"
    }
}
