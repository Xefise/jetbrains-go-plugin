package io.github.xefise.golsp.lang

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoTypingTest : BasePlatformTestCase() {
    fun testBracketIsPaired() = type("package p\nvar x = <caret>\n", "[", "package p\nvar x = [<caret>]\n")

    fun testBraceIsPaired() = type("package p\nvar x = T<caret>\n", "{", "package p\nvar x = T{<caret>}\n")

    fun testBacktickIsPaired() = type("package p\nvar x = <caret>\n", "`", "package p\nvar x = `<caret>`\n")

    fun testRuneQuoteIsPaired() = type("package p\nvar x = <caret>\n", "'", "package p\nvar x = '<caret>'\n")

    fun testClosingBraceIsOvertyped() = type("package p\nvar x = T{<caret>}\n", "}", "package p\nvar x = T{}<caret>\n")

    fun testClosingBacktickIsOvertyped() = type("package p\nvar x = `a<caret>`\n", "`", "package p\nvar x = `a`<caret>\n")

    fun testQuoteInCommentIsNotPaired() = type("package p\n// a <caret>\n", "\"", "package p\n// a \"<caret>\n")

    fun testEnterAfterLoneBraceClosesIt() =
        type("package p\nfunc f() {\n\tif x {<caret>\n}\n", "\n", "package p\nfunc f() {\n\tif x {\n\t\t<caret>\n\t}\n}\n")

    fun testClosingBraceAtLineStartIsOutdented() =
        type("package p\nfunc f() {\n\tx()\n\t<caret>\n", "}", "package p\nfunc f() {\n\tx()\n}<caret>\n")

    fun testClosingParenAtLineStartAlignsWithOpenerLine() =
        type("package p\nfunc f() {\n\tfoo(\n\t\t1,\n\t\t<caret>\n}\n", ")", "package p\nfunc f() {\n\tfoo(\n\t\t1,\n\t)<caret>\n}\n")

    fun testClosingBracketAtLineStartAlignsWithOpenerLine() =
        type("package p\nvar x = m[\n\t\tk,\n\t\t<caret>\n", "]", "package p\nvar x = m[\n\t\tk,\n]<caret>\n")

    fun testClosingBraceInBlockCommentIsNotOutdented() =
        type("package p\nfunc f() {\n\t/*\n\t\t<caret>\n\t*/\n}\n", "}", "package p\nfunc f() {\n\t/*\n\t\t}<caret>\n\t*/\n}\n")

    fun testClosingBraceAfterCodeKeepsIndent() =
        type("package p\nfunc f() {\n\t\tx := T{1<caret>\n}\n", "}", "package p\nfunc f() {\n\t\tx := T{1}<caret>\n}\n")

    fun testClosingBraceInStringIsNotOutdented() =
        type("package p\nfunc f() {\n\tx := `\n\t\t<caret>`\n}\n", "}", "package p\nfunc f() {\n\tx := `\n\t\t}<caret>`\n}\n")

    fun testCaseColonOutdentsToSwitch() = type(
        "package p\nfunc f() {\n\tswitch x {\n\tcase 1:\n\t\ty()\n\t\tcase 2<caret>\n\t}\n}\n", ":",
        "package p\nfunc f() {\n\tswitch x {\n\tcase 1:\n\t\ty()\n\tcase 2:<caret>\n\t}\n}\n",
    )

    fun testDefaultColonOutdentsToSelect() = type(
        "package p\nfunc f() {\n\tselect {\n\tcase <-c:\n\t\ty()\n\t\tdefault<caret>\n\t}\n}\n", ":",
        "package p\nfunc f() {\n\tselect {\n\tcase <-c:\n\t\ty()\n\tdefault:<caret>\n\t}\n}\n",
    )

    fun testLabelColonKeepsIndent() =
        type("package p\nfunc f() {\n\t\tloop<caret>\n}\n", ":", "package p\nfunc f() {\n\t\tloop:<caret>\n}\n")

    fun testColonInCaseStringKeepsIndent() = type(
        "package p\nfunc f() {\n\tswitch x {\n\t\tcase \"a<caret>\"\n\t}\n}\n", ":",
        "package p\nfunc f() {\n\tswitch x {\n\t\tcase \"a:<caret>\"\n\t}\n}\n",
    )

    fun testSmartEnterOpensIfBody() = smartEnter(
        "package p\nfunc f() {\n\tif x<caret>\n}\n",
        "package p\nfunc f() {\n\tif x {\n\t\t<caret>\n\t}\n}\n",
    )

    fun testSmartEnterOpensElseIfBody() = smartEnter(
        "package p\nfunc f() {\n\tif a {\n\t} else if b<caret>\n}\n",
        "package p\nfunc f() {\n\tif a {\n\t} else if b {\n\t\t<caret>\n\t}\n}\n",
    )

    fun testSmartEnterWorksWithCaretMidLine() = smartEnter(
        "package p\nfunc f() {\n\tfor i<caret> := range xs\n}\n",
        "package p\nfunc f() {\n\tfor i := range xs {\n\t\t<caret>\n\t}\n}\n",
    )

    fun testSmartEnterCallsGoFuncLiteral() = smartEnter(
        "package p\nfunc f() {\n\tgo func(<caret>\n}\n",
        "package p\nfunc f() {\n\tgo func() {\n\t\t<caret>\n\t}()\n}\n",
    )

    fun testSmartEnterAddsCaseColonAndIndentsBody() = smartEnter(
        "package p\nfunc f() {\n\tswitch x {\n\tcase 1<caret>\n\t}\n}\n",
        "package p\nfunc f() {\n\tswitch x {\n\tcase 1:\n\t\t<caret>\n\t}\n}\n",
    )

    fun testSmartEnterClosesCallAndStartsNewLine() = smartEnter(
        "package p\nfunc f() {\n\tfoo(a<caret>\n}\n",
        "package p\nfunc f() {\n\tfoo(a)\n\t<caret>\n}\n",
    )

    fun testSmartEnterKeepsTrailingCommentOnHeader() = smartEnter(
        "package p\nfunc f() {\n\tif x<caret> // why\n}\n",
        "package p\nfunc f() {\n\tif x { // why\n\t\t<caret>\n\t}\n}\n",
    )

    private fun type(before: String, typed: String, after: String) {
        myFixture.configureByText("a.go", before)
        myFixture.type(typed)
        myFixture.checkResult(after)
    }

    private fun smartEnter(before: String, after: String) {
        myFixture.configureByText("a.go", before)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        myFixture.checkResult(after)
    }
}
