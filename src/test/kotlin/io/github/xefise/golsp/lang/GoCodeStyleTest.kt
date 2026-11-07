package io.github.xefise.golsp.lang

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.application.options.CodeStyle
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoCodeStyleTest : BasePlatformTestCase() {
    // Actions on Save lists a language's file types for "Reformat code" only if it has a Code Style page.
    fun testGoHasCodeStylePage() {
        assertTrue(LanguageCodeStyleSettingsProvider.getLanguagesWithCodeStyleSettings().contains(GoLanguage))
    }

    fun testTabKeyInsertsTabCharacterInGoFile() {
        myFixture.configureByText("a.go", "package p\n<caret>")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_TAB)
        myFixture.checkResult("package p\n\t<caret>")
    }

    fun testGoIndentDefaultsMatchGofmt() {
        val options = CodeStyle.getSettings(project).getIndentOptions(GoFileType.INSTANCE)
        assertTrue(options.USE_TAB_CHARACTER)
        assertEquals(4, options.TAB_SIZE)
        assertEquals(4, options.INDENT_SIZE)
    }

    fun testCodeStylePageBuildsUnderGoName() {
        val settings = CodeStyle.getSettings(project)
        val provider = LanguageCodeStyleSettingsProvider.forLanguage(GoLanguage)!!
        val configurable = provider.createConfigurable(settings, settings.clone())
        try {
            assertEquals("Go", configurable.displayName)
            assertNotNull(configurable.createComponent())
        } finally {
            configurable.disposeUIResources()
        }
    }
}
