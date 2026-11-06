package io.github.xefise.golsp.lang

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoEditorSupportTest : BasePlatformTestCase() {
    fun testHighlighterKeysUseGolspPrefix() {
        val highlighter = GoSyntaxHighlighter.go()
        assertEquals("GOLSP_KEYWORD", highlighter.getTokenHighlights(GoTokenTypes.KEYWORD).single().externalName)
        assertEquals("GOLSP_RAW_STRING", highlighter.getTokenHighlights(GoTokenTypes.RAW_STRING).single().externalName)
        assertEquals("GOLSP_MOD_VERSION", GoSyntaxHighlighter.goMod().getTokenHighlights(GoModTokenTypes.NUMBER).single().externalName)
        val all = GoColorSettingsPage().attributeDescriptors.map { it.key.externalName }
        assertTrue(all.toString(), all.all { it.startsWith("GOLSP_") })
    }

    fun testLineComment() {
        myFixture.configureByText("a.go", "package p\n<caret>var x = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("package p\n//var x = 1\n")
    }

    fun testGoModLineComment() {
        myFixture.configureByText("go.mod", "<caret>require x v1.0.0\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("//require x v1.0.0\n")
    }

    fun testBraceAutoClose() {
        myFixture.configureByText("a.go", "package p\nfunc f<caret>\n")
        myFixture.type("(")
        myFixture.checkResult("package p\nfunc f(<caret>)\n")
    }

    fun testQuoteAutoClose() {
        myFixture.configureByText("a.go", "package p\nvar s = <caret>\n")
        myFixture.type("\"")
        myFixture.checkResult("package p\nvar s = \"<caret>\"\n")
    }
}
