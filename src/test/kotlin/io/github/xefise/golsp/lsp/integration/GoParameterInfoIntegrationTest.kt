package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.EditorHintFixture
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.lsp.GoplsOptions
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool
import java.util.concurrent.TimeUnit

class GoParameterInfoIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    private lateinit var hints: EditorHintFixture
    private var autocompleteBefore = true

    override fun setUp() {
        super.setUp()
        hints = EditorHintFixture(testRootDisposable)
        autocompleteBefore = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun beforeFixtureTearDown() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteBefore
    }

    private fun source(call: String) = """
        |package main
        |
        |type Warehouse struct{}
        |
        |func (warehouse *Warehouse) Reserve(sku string, quantity int) error { return nil }
        |
        |func main() {
        |	w := &Warehouse{}
        |	_ = $call
        |}
        |""".trimMargin()

    fun testShowParameterInfoActionListsParameters() {
        open("action", source("w.Reserve()"), "w.Reserve(")
        waitUntil("parameter info hint") {
            if (hints.currentHintText == null) fixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
            hints.currentHintText?.contains("quantity") == true
        }
    }

    fun testParameterTypesAreColoredLikeGoCode() {
        val text = "package main\n\nfunc NewWarehouse(invs map[string]int) {}\n\nfunc main() {\n\tNewWarehouse()\n}\n"
        open("colors", text, "\tNewWarehouse(")
        waitUntil("parameter info hint") {
            if (hints.currentHintText == null) fixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
            hints.currentHintText?.contains("invs") == true
        }
        val hint = hints.currentHintText!!
        // The name stays plain, the `map` keyword gets the scheme's keyword color.
        assertTrue(hint, hint.contains("<b>invs <span style=\"color:#"))
        assertTrue(hint, Regex("""<span style="color:#[0-9a-fA-F]{6}">map</span>""").containsMatchIn(hint))
    }

    fun testTypingInsideArgumentsPopsUpParameterInfo() {
        open("typing", source("w.Reserve()"), "w.Reserve(")
        waitUntil("gopls signature help") {
            fixture.type("x")
            AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
            hints.currentHintText?.contains("quantity") == true
        }
    }

    fun testTypingOutsideCallsShowsNothing() {
        open("outside", source("w.Reserve()"), "w := ")
        fixture.type("x")
        AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
        assertNull(hints.currentHintText)
    }

    fun testCompletedCallPopsUpParameterInfo() {
        assertFalse(GoplsOptions.getInstance().usePlaceholders)
        open("complete", source("w.Reserve"), "w.Reserve")
        waitUntil("gopls completion for Reserve") {
            val found = fixture.completeBasic()?.any { it.lookupString == "Reserve" } == true
            if (!found) fixture.lookup?.hideLookup(true)
            found
        }
        fixture.lookup.currentItem = fixture.lookupElements!!.first { it.lookupString == "Reserve" }
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertTrue(fixture.editor.document.text, fixture.editor.document.text.contains("w.Reserve()"))
        waitUntil("parameter info popup after completion") {
            AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
            hints.currentHintText?.contains("quantity") == true
        }
    }

    fun testCompletedFunctionInTestFileWithShortDelay() {
        val settings = CodeInsightSettings.getInstance()
        val delayBefore = settings.PARAMETER_INFO_DELAY
        settings.PARAMETER_INFO_DELAY = 100
        try {
            fixture.addFileToProject("short/warehouse.go", "package short\n\ntype Warehouse struct{}\n\nfunc NewWarehouse(inventories map[string]int) *Warehouse { return &Warehouse{} }\n")
            open("short", "package short\n\nimport \"testing\"\n\nfunc TestX(t *testing.T) {\n\tNewWare\n}\n", "NewWare", "short/x_test.go")
            waitUntil("gopls completion for NewWarehouse") {
                val found = fixture.completeBasic()?.any { it.lookupString == "NewWarehouse" } == true
                if (!found) fixture.lookup?.hideLookup(true)
                found
            }
            fixture.lookup.currentItem = fixture.lookupElements!!.first { it.lookupString == "NewWarehouse" }
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertTrue(fixture.editor.document.text, fixture.editor.document.text.contains("NewWarehouse()"))
            AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
            waitUntil("parameter info popup after completion", timeoutSeconds = 10) { hints.currentHintText?.contains("inventories") == true }
        } finally {
            settings.PARAMETER_INFO_DELAY = delayBefore
        }
    }

    private fun structSource(statement: String) = """
        |package main
        |
        |import "net/http"
        |
        |type OrderLine struct {
        |	SKU      string
        |	Quantity int
        |}
        |
        |type Warehouse struct{}
        |
        |func (warehouse *Warehouse) Reserve(sku string, quantity int) error { return nil }
        |
        |func main() {
        |	w := &Warehouse{}
        |	_ = w
        |	_ = http.Server{}
        |	$statement
        |}
        |""".trimMargin()

    fun testShowParameterInfoInStructLiteralListsFields() {
        open("struct", structSource("_ = OrderLine{}"), "OrderLine{")
        waitUntil("struct fields hint") {
            if (hints.currentHintText == null) fixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
            hints.currentHintText?.contains("Quantity") == true
        }
        assertTrue(hints.currentHintText!!, hints.currentHintText!!.contains("<b>SKU "))
    }

    fun testTypingKeyHighlightsItsField() {
        open("key", structSource("_ = OrderLine{}"), "OrderLine{")
        fixture.type("SKU: \"a\", Quantity: ")
        waitUntil("hint on Quantity") {
            AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
            hints.currentHintText?.contains("<b>Quantity ") == true
        }
    }

    fun testOtherPackageStructHidesUnexportedFields() {
        open("foreign", structSource(""), "http.Server{")
        // Re-invoking would cancel the pending hover, and a cold hover on the standard library is slow.
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
        waitUntil("http.Server fields hint") { hints.currentHintText?.contains("ReadTimeout") == true }
        assertFalse(hints.currentHintText!!, hints.currentHintText!!.contains("inShutdown"))
    }

    fun testMovingIntoLiteralInsideCallSwitchesToFields() {
        open("nested", structSource("_ = w.Reserve(\"a\", OrderLine{})"), "w.Reserve(")
        waitUntil("call hint") {
            if (hints.currentHintText == null) fixture.performEditorAction(IdeActions.ACTION_EDITOR_SHOW_PARAMETER_INFO)
            hints.currentHintText?.contains("quantity") == true
        }
        val text = fixture.editor.document.text
        fixture.editor.caretModel.moveToOffset(text.indexOf("OrderLine{})") + "OrderLine{".length)
        waitUntil("struct fields hint") { hints.currentHintText?.contains("Quantity int") == true }
    }

    fun testTypingInIfBlockShowsNothing() {
        open("block", structSource("if w != nil {\n\t}"), "if w != nil {")
        fixture.type("x")
        AutoPopupController.getInstance(project).waitForDelayedActions(5, TimeUnit.SECONDS)
        assertNull(hints.currentHintText)
    }

    private fun open(dir: String, text: String, caretAfter: String, path: String = "$dir/main.go") {
        fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        fixture.configureFromExistingVirtualFile(fixture.addFileToProject(path, text).virtualFile)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf(caretAfter) + caretAfter.length)
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
    }
}
