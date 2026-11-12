package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.util.TextRange
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoSignatureTypeCompletionIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    private var autocompleteBefore = true

    override fun setUp() {
        super.setUp()
        // Without it the platform finishes templates right away in tests.
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        // A single matching item would be inserted without a lookup.
        autocompleteBefore = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun beforeFixtureTearDown() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteBefore
    }

    private fun source(declaration: String) = """
        |package main
        |
        |import "net/http"
        |
        |type Warehouse struct{}
        |
        |type Order struct{}
        |
        |$declaration
        |
        |var _ = http.Get
        |""".trimMargin()

    fun testParameterGetsEditableNameAndPointer() {
        assertEquals("func NewX(warehouse *Warehouse) {}", complete("param", source("func NewX(Ware) {}"), "NewX(Ware", "Warehouse"))
        assertEquals("warehouse", fixture.editor.selectionModel.selectedText)
        assertNotNull(TemplateManager.getInstance(project).getActiveTemplate(fixture.editor))
        assertEquals(listOf("warehouse", "w"), fixture.lookupElementStrings)
    }

    fun testReceiverGetsShortName() {
        assertEquals("func (w *Warehouse) Reserve() {}", complete("receiver", source("func (Ware) Reserve() {}"), "(Ware", "Warehouse"))
    }

    fun testNamedParameterGetsPointerOnly() {
        val text = source("func (w *Warehouse) Update(o Ord) {}")
        assertEquals("func (w *Warehouse) Update(o *Order) {}", complete("named", text, "o Ord", "Order"))
    }

    fun testResultGetsPointerOnly() {
        assertEquals("func NewX() *Warehouse { return nil }", complete("result", source("func NewX() Ware { return nil }"), ") Ware", "Warehouse"))
    }

    fun testQualifiedTypeIsReplacedWithItsPackage() {
        assertEquals("func NewX(client *http.Client) {}", complete("qualified", source("func NewX(http.Cli) {}"), "http.Cli", "Client"))
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
