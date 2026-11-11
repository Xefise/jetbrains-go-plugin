package io.github.xefise.golsp.lang

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoWordSelectionTest : BasePlatformTestCase() {
    // LSP4IJ's code-block selectioner waits for gopls foldingRange without a timeout once gopls is up.
    override fun setUp() {
        super.setUp()
        val handlers = ExtendWordSelectionHandler.EP_NAME.extensionList
            .filterNot { it.javaClass.simpleName == "LSPCodeBlockExtendWordSelectionHandler" }
        ExtensionTestUtil.maskExtensions(ExtendWordSelectionHandler.EP_NAME, handlers, testRootDisposable)
    }

    private fun doubleClick(body: String, honorCamelWords: Boolean = false, camelWords: Boolean = false): String {
        myFixture.configureByText("a.go", "package main\n\nfunc main() {\n$body\n}\n")
        myFixture.editor.settings.isCamelWords = camelWords
        myFixture.editor.caretModel.currentCaret.selectWordAtCaret(honorCamelWords)
        return myFixture.editor.selectionModel.selectedText.orEmpty()
    }

    private fun extendSelection(text: String, times: Int): List<String> {
        myFixture.configureByText("a.go", text)
        return List(times) {
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            myFixture.editor.selectionModel.selectedText.orEmpty()
        }
    }

    fun testDoubleClickOnKeywordSelectsKeyword() {
        assertEquals("var", doubleClick("\tva<caret>r a := 0;"))
    }

    fun testDoubleClickOnIdentifierSelectsIdentifier() {
        assertEquals("a", doubleClick("\tvar <caret>a := 0;"))
    }

    fun testDoubleClickOnQualifierSelectsPackageName() {
        assertEquals("fmt", doubleClick("\tf<caret>mt.Println(1)"))
    }

    fun testDoubleClickOnSelectorSelectsMemberName() {
        assertEquals("Println", doubleClick("\tfmt.Print<caret>ln(1)"))
    }

    fun testDoubleClickIgnoresCamelHumpsWhenMouseSettingOff() {
        assertEquals("myLongName", doubleClick("\tmyLo<caret>ngName := 1", camelWords = true))
    }

    fun testDoubleClickHonoursCamelHumpsWhenAsked() {
        assertEquals("Long", doubleClick("\tmyLo<caret>ngName := 1", honorCamelWords = true, camelWords = true))
    }

    fun testDoubleClickOnNumberSelectsWholeLiteral() {
        assertEquals("0x1F", doubleClick("\tx := 0x<caret>1F"))
    }

    fun testDoubleClickInsideStringSelectsWord() {
        assertEquals("hello", doubleClick("\ts := \"hel<caret>lo world\""))
    }

    fun testDoubleClickInsideRawStringSelectsWord() {
        assertEquals("world", doubleClick("\ts := `hello wo<caret>rld`"))
    }

    fun testDoubleClickInsideCommentSelectsWord() {
        assertEquals("some", doubleClick("\tx := 1 // so<caret>me comment"))
    }

    fun testDoubleClickOnOperatorSelectsOperator() {
        assertEquals(":=", doubleClick("\tx <caret>:= 1"))
    }

    fun testExtendSelectionInStringGoesWordThenContentThenLiteral() {
        val steps = extendSelection("package main\n\nvar s = \"hel<caret>lo world\"\n", 3)
        assertEquals(listOf("hello", "hello world", "\"hello world\""), steps)
    }

    fun testExtendSelectionInMultilineRawStringGoesWordThenContentThenLiteral() {
        val steps = extendSelection("package main\n\nvar s = `one\ntw<caret>o three`\n", 3)
        assertEquals(listOf("two", "one\ntwo three", "`one\ntwo three`"), steps)
    }

    fun testExtendSelectionInUnterminatedStringSkipsContentStep() {
        val steps = extendSelection("package main\n\nvar s = \"ab<caret>c\\\"\n", 2)
        assertEquals(listOf("abc", "\"abc\\\""), steps)
    }

    fun testExtendSelectionInRuneGoesCharThenLiteral() {
        assertEquals(listOf("x", "'x'"), extendSelection("package main\n\nvar r = '<caret>x'\n", 2))
    }

    fun testDoubleClickInsideBlockCommentSelectsWord() {
        assertEquals("word", doubleClick("\tx := 1 /* a wo<caret>rd here */"))
    }

    fun testDoubleClickWithCaretAtKeywordEndSelectsKeyword() {
        assertEquals("var", doubleClick("\tvar<caret> a = 0"))
    }

    fun testExtendSelectionStartsFromKeyword() {
        assertEquals(listOf("var"), extendSelection("package main\n\nfunc main() {\n\tva<caret>r a = 0\n}\n", 1))
    }

    fun testExtendSelectionWalksFromArgumentToFile() {
        val text = "package main\n\nfunc main() {\n\tfmt.Println(a.b(<caret>x)[i])\n}\n"
        val body = "\tfmt.Println(a.b(x)[i])"
        assertEquals(
            listOf(
                "x", "(x)", "a.b(x)", "a.b(x)[i]", "(a.b(x)[i])", "fmt.Println(a.b(x)[i])",
                "{\n$body\n}", "func main() {\n$body\n}", text.replace("<caret>", ""),
            ),
            extendSelection(text, 9),
        )
    }

    fun testExtendSelectionSkipsBracketsInsideStrings() {
        val steps = extendSelection("package main\n\nfunc main() {\n\tf(\")(\", <caret>y)\n}\n", 4)
        assertEquals(listOf("y", "\")(\", y", "(\")(\", y)", "f(\")(\", y)"), steps)
    }

    fun testExtendSelectionGrowsBlockToIfStatement() {
        val steps = extendSelection("package main\n\nfunc main() {\n\tif ok {\n\t\t<caret>run()\n\t}\n}\n", 4)
        assertEquals(listOf("run", "run()", "{\n\t\trun()\n\t}", "if ok {\n\t\trun()\n\t}"), steps)
    }
}
