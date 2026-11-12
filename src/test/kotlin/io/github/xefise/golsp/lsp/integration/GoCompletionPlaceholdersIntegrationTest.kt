package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.TextRange
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.lsp.GoplsOptions
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoCompletionPlaceholdersIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    private var placeholdersBefore = true
    private var autocompleteBefore = true

    override fun setUp() {
        super.setUp()
        placeholdersBefore = GoplsOptions.getInstance().usePlaceholders
        GoplsOptions.getInstance().update(true)
        // Without it the platform finishes templates right away in tests.
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        // A single matching item would be inserted without a lookup.
        autocompleteBefore = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun beforeFixtureTearDown() {
        GoplsOptions.getInstance().update(placeholdersBefore)
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteBefore
    }

    private val warehouse = """
        |package main
        |
        |type Warehouse struct{}
        |
        |func (warehouse *Warehouse) Reserve(sku string, quantity int) error { return nil }
        |
        |func main() {
        |	w := &Warehouse{}
        |	_ = w.Reserve
        |}
        |""".trimMargin()

    fun testMethodCallGetsNameOnlyPlaceholders() {
        assertEquals("\t_ = w.Reserve(sku, quantity)", complete("names", warehouse, "w.Reserve", "Reserve"))
        assertEquals("sku", fixture.editor.selectionModel.selectedText)
    }

    fun testTabAndEnterMoveBetweenArguments() {
        complete("tab", warehouse, "w.Reserve", "Reserve")
        fixture.type("\"apple\"")
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE)
        fixture.type("2")
        fixture.type("\n")
        assertNull(TemplateManager.getInstance(project).getActiveTemplate(fixture.editor))
        assertTrue(fixture.editor.document.text, fixture.editor.document.text.contains("\t_ = w.Reserve(\"apple\", 2)\n"))
    }

    fun testFuncTypedParameterKeepsOnlyItsName() {
        val text = """
            |package main
            |
            |import "testing"
            |
            |func TestX(t *testing.T) {
            |	t.Run
            |}
            |""".trimMargin()
        assertEquals("\tt.Run(name, f)", complete("run", text, "t.Run", "Run"))
    }

    // Completes [lookup] right after [after] in a fresh module [dir]; returns the caret line.
    private fun complete(dir: String, text: String, after: String, lookup: String): String {
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        fixture.configureFromExistingVirtualFile(fixture.addFileToProject("$dir/main.go", text).virtualFile)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf(after) + after.length)
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        waitUntil("gopls completion for $lookup") {
            val found = fixture.completeBasic()?.any { it.lookupString == lookup } == true
            if (!found) fixture.lookup?.hideLookup(true)
            found
        }
        fixture.lookup.currentItem = fixture.lookupElements!!.first { it.lookupString == lookup }
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val document = fixture.editor.document
        val line = document.getLineNumber(fixture.editor.caretModel.offset)
        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
}
