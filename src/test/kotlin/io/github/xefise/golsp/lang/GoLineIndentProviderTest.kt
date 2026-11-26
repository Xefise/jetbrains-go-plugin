package io.github.xefise.golsp.lang

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoLineIndentProviderTest : BasePlatformTestCase() {
    fun testEnterAfterFuncBraceIndentsBody() {
        enter("package main\n\nfunc main() {<caret>\n}\n")
        myFixture.checkResult("package main\n\nfunc main() {\n\t<caret>\n}\n")
    }

    fun testEnterBetweenBracesSplitsThem() {
        enter("package main\n\nfunc main() {<caret>}\n")
        myFixture.checkResult("package main\n\nfunc main() {\n\t<caret>\n}\n")
    }

    fun testEnterBetweenNestedBracesKeepsClosingBraceAtOpenerIndent() {
        enter("package main\n\nfunc main() {\n\tif true {<caret>}\n}\n")
        myFixture.checkResult("package main\n\nfunc main() {\n\tif true {\n\t\t<caret>\n\t}\n}\n")
    }

    fun testEnterBeforeClosingBraceAlignsItWithOpener() {
        enter("package main\n\nfunc main() {\n\tx()<caret>}\n")
        myFixture.checkResult("package main\n\nfunc main() {\n\tx()\n<caret>}\n")
    }

    fun testEnterAfterCaseIndentsClauseBody() {
        enter("package main\n\nfunc f(x int) {\n\tswitch x {\n\tcase 1:<caret>\n\t}\n}\n")
        myFixture.checkResult("package main\n\nfunc f(x int) {\n\tswitch x {\n\tcase 1:\n\t\t<caret>\n\t}\n}\n")
    }

    fun testEnterBetweenSelectBracesDoesNotIndent() {
        enter("package main\n\nfunc f() {\n\tselect {<caret>}\n}\n")
        myFixture.checkResult("package main\n\nfunc f() {\n\tselect {\n\t<caret>\n\t}\n}\n")
    }

    fun testEnterAfterMultiLineCallReturnsToStatementIndent() {
        enter("package main\n\nfunc f() {\n\tfoo(\n\t\t1,\n\t)<caret>\n}\n")
        myFixture.checkResult("package main\n\nfunc f() {\n\tfoo(\n\t\t1,\n\t)\n\t<caret>\n}\n")
    }

    // Inside a literal the provider steps aside; the platform default copies the previous line's indent.
    fun testEnterInsideRawStringKeepsPlatformDefault() {
        enter("package main\n\nfunc f() {\n\tfoo(`a<caret>b`,\n\t)\n}\n")
        myFixture.checkResult("package main\n\nfunc f() {\n\tfoo(`a\n\t<caret>b`,\n\t)\n}\n")
    }

    fun testEnterUsesSpacesWhenCodeStyleSaysSo() {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCommonSettings(GoLanguage).indentOptions!!.USE_TAB_CHARACTER = false
            enter("package main\n\nfunc main() {<caret>\n}\n")
            myFixture.checkResult("package main\n\nfunc main() {\n    <caret>\n}\n")
        }
    }

    fun testProviderIsOnlyForGo() {
        val provider = GoLineIndentProvider()
        assertTrue(provider.isSuitableFor(GoLanguage))
        assertFalse(provider.isSuitableFor(GoModLanguage))
        assertFalse(provider.isSuitableFor(null))
    }

    private fun enter(text: String) {
        myFixture.configureByText("a.go", text)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
    }
}
