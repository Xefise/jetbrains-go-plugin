package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.features.selectionRange.LSPExtendWordSelectionHandler
import com.redhat.devtools.lsp4ij.features.selectionRange.LSPWordSelectionFilter
import io.github.xefise.golsp.lsp.GoCodeAction
import io.github.xefise.golsp.lsp.GoCodeActionOutcome
import io.github.xefise.golsp.lsp.GoCodeActions
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.lsp.GoplsOptions
import io.github.xefise.golsp.lsp.model.GoCodeActionCatalog
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoCodeActionsIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    private var placeholdersBefore = true

    override fun setUp() {
        super.setUp()
        placeholdersBefore = GoplsOptions.getInstance().usePlaceholders
    }

    override fun beforeFixtureTearDown() = GoplsOptions.getInstance().update(placeholdersBefore)

    fun testExtractVariableOnSelectionRewritesTheDocument() {
        open("extract/main.go", "package main\n\nfunc compute(a, b int) int {\n\tsum := a + b\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n")
        val text = fixture.editor.document.text
        val start = text.indexOf("a + b")
        fixture.editor.selectionModel.setSelection(start, start + "a + b".length)
        val action = awaitActions(listOf(GoCodeActionCatalog.EXTRACT_VARIABLE)).single()
        assertEquals(GoCodeActionCatalog.EXTRACT_VARIABLE, action.kind.kind)

        var outcome: GoCodeActionOutcome? = null
        GoCodeActions.apply(fixture.editor, action) { outcome = it }
        waitUntil("extract variable applied") { outcome != null }

        assertEquals(GoCodeActionOutcome.EDITED, outcome)
        // gopls names the new variable newVar and declares it right before the statement.
        assertEquals(
            "package main\n\nfunc compute(a, b int) int {\n\tnewVar := a + b\n\tsum := newVar\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n",
            fixture.editor.document.text,
        )
    }

    fun testCaretWithoutSelectionGetsNoExtractVariable() {
        open("caret/main.go", "package main\n\nfunc compute(a, b int) int {\n\tsum := a + b\n\treturn sum\n}\n\nfunc main() { _ = compute(1, 2) }\n")
        val text = fixture.editor.document.text
        // A positive signal that gopls has loaded the package: inline call is offered at the call site.
        fixture.editor.caretModel.moveToOffset(text.indexOf("compute(1") + 1)
        assertEquals(GoCodeActionCatalog.INLINE_CALL, awaitActions(listOf(GoCodeActionCatalog.REFACTOR)).single().kind.kind)

        fixture.editor.caretModel.moveToOffset(text.indexOf("a + b") + 2)
        assertEmpty(requestOnce(listOf(GoCodeActionCatalog.EXTRACT_VARIABLE))!!)
    }

    fun testSourceTestIsNeverReturnedForSource() {
        open("src/main.go", "package main\n\nfunc compute(a, b int) int { return a + b }\n\nfunc main() { _ = compute(1, 2) }\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("compute(a") + 1)
        val kinds = awaitActions(listOf(GoCodeActionCatalog.SOURCE)).map { it.kind.kind }
        assertContainsElements(kinds, "source.addTest")
        assertDoesntContain(kinds, "source.test", "source.organizeImports")
    }

    fun testSelectionRangesGrowFromTheCaret() {
        open("sel/main.go", "package main\n\nfunc main() {\n\tx := 1 + 2*3\n\t_ = x\n}\n")
        val text = fixture.editor.document.text
        awaitServer()
        var ranges: List<TextRange>? = null
        waitUntil("selection ranges") {
            GoCodeActions.selectionRanges(fixture.editor, text.indexOf("2*3")) { ranges = it }
            ranges?.isNotEmpty() == true
        }
        val texts = ranges!!.map { it.substring(text) }
        // Innermost first: the literal, the product, the sum.
        assertEquals(listOf("2", "2*3", "1 + 2*3"), texts.take(3))
    }

    fun testWordSelectionBypassesGoplsSelectionRanges() {
        open("words/main.go", "package main\n\nfunc main() {\n\tx := 1 + 2*3\n\t_ = x\n}\n")
        val text = fixture.editor.document.text
        val server = awaitServer()
        waitUntil("gopls capabilities") { server.serverCapabilities?.selectionRangeProvider != null }
        val leaf = fixture.file.findElementAt(text.indexOf("2*3"))!!
        assertFalse(LSPExtendWordSelectionHandler().canSelect(leaf))
        assertTrue(LSPWordSelectionFilter().value(leaf))

        fixture.editor.caretModel.moveToOffset(text.indexOf("2*3"))
        val steps = List(5) {
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            fixture.editor.selectionModel.selectedText
        }
        // No "2*3" / "1 + 2*3" from gopls; the whole-lines body step comes from LSP4IJ's folding-based code block.
        val body = "x := 1 + 2*3\n\t_ = x"
        assertEquals(listOf("2", "x := 1 + 2*3", body, "\t$body\n", "{\n\t$body\n}"), steps)
    }

    fun testCompletedPrintfGetsArgumentPlaceholdersWhenOptionOn() {
        GoplsOptions.getInstance().update(true)
        assertEquals("\tfmt.Printf(format, a)", completePrintf("on"))
    }

    fun testPlaceholdersOptionOffReachesRunningGopls() {
        GoplsOptions.getInstance().update(true)
        // gopls is already running with placeholders on; the change must arrive without a restart.
        assertEquals("\tfmt.Printf(format, a)", completePrintf("before"))
        TemplateManager.getInstance(project).getActiveTemplate(fixture.editor)?.let {
            TemplateManagerImpl.getTemplateState(fixture.editor)?.gotoEnd(false)
        }
        GoplsOptions.getInstance().update(false)
        waitUntil("Printf completion without placeholders") { completePrintf("after${counter++}") == "\tfmt.Printf()" }
    }

    private var counter = 0

    /** Completes `fmt.Printf` in a fresh file of module [dir] and returns the completed line. */
    private fun completePrintf(dir: String): String {
        open("$dir/main.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Printf\n}\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("Printf") + "Printf".length)
        awaitServer()
        waitUntil("gopls completion for fmt.Printf") {
            val found = fixture.completeBasic()?.any { it.lookupString == "Printf" } == true
            if (!found) fixture.lookup?.hideLookup(true)
            found
        }
        fixture.lookup.currentItem = fixture.lookupElements!!.first { it.lookupString == "Printf" }
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val document = fixture.editor.document
        return document.getText(TextRange(document.getLineStartOffset(5), document.getLineEndOffset(5)))
    }

    private fun open(path: String, text: String): VirtualFile {
        val dir = path.substringBefore('/')
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        val file = fixture.addFileToProject(path, text).virtualFile
        fixture.configureFromExistingVirtualFile(file)
        return file
    }

    private fun awaitServer(): LanguageServerItem {
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        return await("gopls item", LanguageServerManager.getInstance(project).getLanguageServer(GoplsLanguageServerFactory.SERVER_ID))!!
    }

    /** One request through the public API; null when it produced no result (hint shown instead). */
    private fun requestOnce(kinds: List<String>): List<GoCodeAction>? {
        var result: List<GoCodeAction>? = null
        GoCodeActions.request(fixture.editor, kinds) { result = it }
        poll(DEFAULT_TIMEOUT_SECONDS) { result != null }
        return result
    }

    // gopls loads the package lazily: the first answers can be empty.
    private fun awaitActions(kinds: List<String>): List<GoCodeAction> {
        awaitServer()
        var actions: List<GoCodeAction> = emptyList()
        waitUntil("gopls code actions $kinds") {
            actions = requestOnce(kinds).orEmpty()
            actions.isNotEmpty()
        }
        return actions
    }
}
