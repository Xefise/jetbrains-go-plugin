package io.github.xefise.golsp.lang

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/** Go Code Style page: gofmt-compatible indents, and Go in Actions on Save "Reformat code" (gopls formats). */
class GoLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = GoLanguage

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.TAB_SIZE = 4
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = IndentOptionsEditor()

    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE

    override fun createConfigurable(
        baseSettings: CodeStyleSettings,
        modelSettings: CodeStyleSettings,
    ): CodeStyleConfigurable = object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, configurableDisplayName) {
        override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
            object : TabbedLanguageCodeStylePanel(GoLanguage, currentSettings, settings) {
                // Spaces, wrapping and blank lines are fixed by gofmt.
                override fun initTabs(settings: CodeStyleSettings) = addIndentOptionsTab(settings)
            }
    }

    private companion object {
        val SAMPLE = """
            package main

            import "fmt"

            func main() {
            	for i := 0; i < 3; i++ {
            		fmt.Println(i,
            			"gopher")
            	}
            }
        """.trimIndent()
    }
}
