// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class GoColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = AllIcons.FileTypes.Text

    override fun getHighlighter(): SyntaxHighlighter = GoSyntaxHighlighter.go()

    override fun getDemoText(): String = DEMO

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Go"

    private companion object {
        val DEMO = """
            // Package main is a demo.
            package main

            import "fmt"

            /* Block comment */
            const answer = 0x2A

            type Greeter interface {
                Greet(name string) (string, error)
            }

            func main() {
                names := []string{"world", `gopher`}
                for i, name := range names {
                    if i > 0 && name != "" {
                        fmt.Printf("%d: hello, %s%c", i, name, '!')
                    }
                }
                var ok bool = true
                _, _ = ok, nil;
                @
            }
        """.trimIndent()

        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Comments//Line comment", GoColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", GoColors.BLOCK_COMMENT),
            AttributesDescriptor("Keyword", GoColors.KEYWORD),
            AttributesDescriptor("Predeclared identifier", GoColors.BUILTIN),
            AttributesDescriptor("Predeclared constant", GoColors.CONSTANT),
            AttributesDescriptor("Identifier", GoColors.IDENTIFIER),
            AttributesDescriptor("String//Interpreted string", GoColors.STRING),
            AttributesDescriptor("String//Raw string", GoColors.RAW_STRING),
            AttributesDescriptor("String//Rune", GoColors.RUNE),
            AttributesDescriptor("Number", GoColors.NUMBER),
            AttributesDescriptor("Operator", GoColors.OPERATOR),
            AttributesDescriptor("Braces and operators//Parentheses", GoColors.PARENTHESES),
            AttributesDescriptor("Braces and operators//Braces", GoColors.BRACES),
            AttributesDescriptor("Braces and operators//Brackets", GoColors.BRACKETS),
            AttributesDescriptor("Braces and operators//Comma", GoColors.COMMA),
            AttributesDescriptor("Braces and operators//Semicolon", GoColors.SEMICOLON),
            AttributesDescriptor("Braces and operators//Dot", GoColors.DOT),
            AttributesDescriptor("go.mod//Version", GoColors.MOD_VERSION),
            AttributesDescriptor("Bad character", GoColors.BAD_CHARACTER),
        )
    }
}
