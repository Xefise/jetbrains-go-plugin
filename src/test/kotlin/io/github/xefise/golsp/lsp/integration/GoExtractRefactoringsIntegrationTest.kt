package io.github.xefise.golsp.lsp.integration

import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoCodeAction
import io.github.xefise.golsp.lsp.GoCodeActions
import io.github.xefise.golsp.lsp.GoExtractMethodHandler
import io.github.xefise.golsp.lsp.GoInlineActionHandler
import io.github.xefise.golsp.lsp.GoIntroduceHandler
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

/** The platform Extract/Introduce/Inline handlers against a real gopls; the Rename that follows is skipped in unit-test mode. */
class GoExtractRefactoringsIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testIntroduceVariableOnASelectionDeclaresNewVarAndPutsTheCaretOnIt() {
        open("var", COMPUTE)
        select("a + b")
        warmUp(GoCodeActionCatalog.EXTRACT_VARIABLE)

        val result = runAndAwaitEdit { GoIntroduceHandler.VARIABLE.invoke(project, fixture.editor, fixture.file, null) }

        val expected = "package main\n\nfunc compute(a, b int) int {\n\tnewVar := a + b\n\tsum := newVar\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n"
        assertEquals(expected, result)
        assertEquals(expected.indexOf("newVar"), fixture.editor.caretModel.offset)
        assertFalse(fixture.editor.selectionModel.hasSelection())
    }

    fun testIntroduceVariableAtACaretInsideTheOnlyExpressionExtractsIt() {
        open("caret", COMPUTE)
        select("a + b")
        warmUp(GoCodeActionCatalog.EXTRACT_VARIABLE)
        fixture.editor.selectionModel.removeSelection()
        // On the operator the innermost selection range is the whole sum; the next one out is the statement.
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("+ b"))

        val result = runAndAwaitEdit { GoIntroduceHandler.VARIABLE.invoke(project, fixture.editor, fixture.file, null) }

        assertEquals(
            "package main\n\nfunc compute(a, b int) int {\n\tnewVar := a + b\n\tsum := newVar\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n",
            result,
        )
    }

    fun testIntroduceVariableOnAConstantExpressionDeclaresAConstant() {
        open("const", "package main\n\nfunc size(n int) int {\n\treturn n * 1024\n}\n\nfunc main() { _ = size(1) }\n")
        select("1024")
        warmUp(GoCodeActionCatalog.EXTRACT_CONSTANT)

        val result = runAndAwaitEdit { GoIntroduceHandler.VARIABLE.invoke(project, fixture.editor, fixture.file, null) }

        assertEquals("package main\n\nfunc size(n int) int {\n\tconst newConst = 1024\n\treturn n * newConst\n}\n\nfunc main() { _ = size(1) }\n", result)
    }

    fun testIntroduceConstantOnAStringLiteral() {
        open("str", "package main\n\nfunc greet(name string) string {\n\treturn \"hello, \" + name\n}\n\nfunc main() { _ = greet(\"go\") }\n")
        select("\"hello, \"")
        warmUp(GoCodeActionCatalog.EXTRACT_CONSTANT)

        val result = runAndAwaitEdit { GoIntroduceHandler.CONSTANT.invoke(project, fixture.editor, fixture.file, null) }

        assertEquals(
            "package main\n\nfunc greet(name string) string {\n\tconst newConst = \"hello, \"\n\treturn newConst + name\n}\n\nfunc main() { _ = greet(\"go\") }\n",
            result,
        )
    }

    fun testExtractMethodOnALoneCallStatementCreatesNewFunctionAndPutsTheCaretOnTheCall() {
        open("fn", REPORT)
        // gopls itself offers nothing for exactly `println(sum)`; the handler widens it to the whole line.
        select("\tprintln(sum)\n")
        warmUp(GoCodeActionCatalog.EXTRACT_FUNCTION)
        select("println(sum)")

        val result = runAndAwaitEdit { GoExtractMethodHandler.invoke(project, fixture.editor, fixture.file, null) }

        val expected = "package main\n\nfunc report(a, b int) int {\n\tsum := a + b\n\tnewFunction(sum)\n\treturn sum\n}\n\n" +
            "func newFunction(sum int) {\n\tprintln(sum)\n}\n\nfunc main() { _ = report(1, 2) }\n"
        assertEquals(expected, result)
        assertEquals(expected.indexOf("newFunction"), fixture.editor.caretModel.offset)
    }

    fun testExtractMethodOnTwoStatementsReturnsTheValueUsedAfterThem() {
        open("fn2", REPORT)
        select("sum := a + b\n\tprintln(sum)")
        warmUp(GoCodeActionCatalog.EXTRACT_FUNCTION)

        val result = runAndAwaitEdit { GoExtractMethodHandler.invoke(project, fixture.editor, fixture.file, null) }

        assertEquals(
            "package main\n\nfunc report(a, b int) int {\n\tsum := newFunction(a, b)\n\treturn sum\n}\n\n" +
                "func newFunction(a int, b int) int {\n\tsum := a + b\n\tprintln(sum)\n\treturn sum\n}\n\nfunc main() { _ = report(1, 2) }\n",
            result,
        )
    }

    fun testInlineCallReplacesTheCallByTheBody() {
        open("inline", COMPUTE.replace("\tsum := a + b\n\treturn sum\n", "\treturn a + b\n"))
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("compute(1") + 1)
        warmUp(GoCodeActionCatalog.INLINE_CALL)

        val result = runAndAwaitEdit { inlineHandler().inlineElement(project, fixture.editor, fixture.file.findElementAt(fixture.editor.caretModel.offset)) }

        assertEquals("package main\n\nfunc compute(a, b int) int {\n\treturn a + b\n}\n\nfunc main() { _ = 1 + 2 }\n", result)
    }

    fun testInlineVariableReplacesTheUseByTheInitializer() {
        open("inlinevar", "package main\n\nfunc show(x int) {\n\ts := x + 1\n\tprintln(s)\n}\n\nfunc main() { show(1) }\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("println(s") + "println(".length)
        warmUp(GoCodeActionCatalog.INLINE_VARIABLE)

        val result = runAndAwaitEdit { inlineHandler().inlineElement(project, fixture.editor, fixture.file.findElementAt(fixture.editor.caretModel.offset)) }

        // gopls leaves the now unused declaration in place.
        assertEquals("package main\n\nfunc show(x int) {\n\ts := x + 1\n\tprintln(x + 1)\n}\n\nfunc main() { show(1) }\n", result)
    }

    private fun inlineHandler() = InlineActionHandler.EP_NAME.extensionList.filterIsInstance<GoInlineActionHandler>().single()

    private fun open(dir: String, text: String): VirtualFile {
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        val file = fixture.addFileToProject("$dir/main.go", text).virtualFile
        fixture.configureFromExistingVirtualFile(file)
        return file
    }

    private fun select(text: String) {
        val start = fixture.editor.document.text.indexOf(text)
        fixture.editor.selectionModel.setSelection(start, start + text.length)
    }

    // gopls loads the package lazily and answers with nothing at first; the handlers would turn that into a hint.
    private fun warmUp(kind: String) {
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        waitUntil("gopls to offer $kind") {
            var actions: List<GoCodeAction>? = null
            GoCodeActions.request(fixture.editor, listOf(kind)) { actions = it }
            poll(DEFAULT_TIMEOUT_SECONDS) { actions != null }
            actions?.any { it.kind.kind == kind } == true
        }
    }

    private fun runAndAwaitEdit(action: () -> Unit): String {
        val document = fixture.editor.document
        val stamp = document.modificationStamp
        action()
        waitUntil("the refactoring to edit the file") { document.modificationStamp != stamp }
        return document.text
    }

    private companion object {
        const val REPORT = "package main\n\nfunc report(a, b int) int {\n\tsum := a + b\n\tprintln(sum)\n\treturn sum\n}\n\nfunc main() { _ = report(1, 2) }\n"
        const val COMPUTE = "package main\n\nfunc compute(a, b int) int {\n\tsum := a + b\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n"
    }
}
