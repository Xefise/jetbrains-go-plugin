package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Inputs are the document right after Enter: the newline is already inserted, <caret> starts the new line.
class GoLineIndentTest {
    @Test
    fun `after opening brace indents one level deeper than the brace line`() {
        assertEquals("\t", indent("func main() {\n<caret>"))
    }

    @Test
    fun `nested opening brace indents relative to its own line`() {
        assertEquals("\t\t", indent("func f() {\n\tif x {\n<caret>"))
    }

    @Test
    fun `after opening paren of a grouped declaration indents one level`() {
        assertEquals("\t", indent("import (\n<caret>"))
    }

    @Test
    fun `after opening bracket indents one level`() {
        assertEquals("\t\t", indent("\tx := [\n<caret>"))
    }

    @Test
    fun `comment after opening brace is skipped`() {
        assertEquals("\t", indent("func f() { // start\n<caret>"))
    }

    @Test
    fun `closing brace after caret aligns with the opener line`() {
        assertEquals("\t", indent("func f() {\n\tif x {\n\t\ty()\n<caret>}"))
    }

    @Test
    fun `closing brace after caret at top level gets no indent`() {
        assertEquals("", indent("func f() {\n<caret>}"))
    }

    @Test
    fun `empty line between split braces indents one level`() {
        assertEquals("\t", indent("func f() {\n<caret>\n}"))
    }

    @Test
    fun `closing paren after caret aligns with the call line`() {
        assertEquals("\t", indent("\tfoo(\n\t\ta,\n<caret>)"))
    }

    @Test
    fun `code after caret that is not a closer keeps block indent`() {
        assertEquals("\t", indent("func f() {\n<caret>x()\n}"))
    }

    @Test
    fun `case clause body indents one level deeper than case`() {
        assertEquals("\t\t", indent("\tswitch x {\n\tcase 1, 2:\n<caret>"))
    }

    @Test
    fun `default clause body indents one level deeper than default`() {
        assertEquals("\t\t", indent("\tselect {\n\tdefault:\n<caret>"))
    }

    @Test
    fun `statement inside case body keeps its indent`() {
        assertEquals("\t\t", indent("\tswitch x {\n\tcase 1:\n\t\ty()\n<caret>"))
    }

    @Test
    fun `label colon does not indent`() {
        assertEquals("\t", indent("\tLoop:\n<caret>"))
    }

    @Test
    fun `slice colon does not indent`() {
        assertEquals("\t", indent("\ty := a[1:\n<caret>"))
    }

    @Test
    fun `map key string case is not a case clause`() {
        assertEquals("\t", indent("\t\"case\":\n<caret>"))
    }

    @Test
    fun `after multi-line call closing paren returns to statement indent`() {
        assertEquals("\t", indent("\tfoo(\n\t\ta,\n\t)\n<caret>"))
    }

    @Test
    fun `after closing paren on continuation line returns to statement indent`() {
        assertEquals("\t", indent("\tx := foo(a,\n\t\tb)\n<caret>"))
    }

    @Test
    fun `after closing brace of nested block returns to outer block indent`() {
        assertEquals("\t", indent("func f() {\n\tif x {\n\t\ty()\n\t}\n<caret>"))
    }

    @Test
    fun `after closing brace of top-level func gets no indent`() {
        assertEquals("", indent("func f() {\n\ty()\n}\n<caret>"))
    }

    @Test
    fun `after go func literal call returns to statement indent`() {
        assertEquals("\t", indent("\tgo func() {\n\t\ty()\n\t}()\n<caret>"))
    }

    @Test
    fun `plain statement keeps previous line indent`() {
        assertEquals("\t", indent("func f() {\n\tx := 1\n<caret>"))
    }

    @Test
    fun `blank lines between are skipped`() {
        assertEquals("\t", indent("func f() {\n\tx := 1\n\n\n<caret>"))
    }

    @Test
    fun `brace inside interpreted string is ignored`() {
        assertEquals("\t", indent("\tx := \"{\"\n<caret>"))
    }

    @Test
    fun `brace inside rune is ignored`() {
        assertEquals("\t", indent("\tx := '{'\n<caret>"))
    }

    @Test
    fun `brace inside line comment is ignored`() {
        assertEquals("\t", indent("\tx := 1 // {\n<caret>"))
    }

    @Test
    fun `paren inside block comment is ignored`() {
        assertEquals("\t", indent("\tx := 1 /* ( */\n<caret>"))
    }

    @Test
    fun `multi-line raw string with brace keeps indent of the line where it starts`() {
        assertEquals("\t", indent("\tx := `{\n{\n`\n<caret>"))
    }

    @Test
    fun `closing brace inside string does not count for matching`() {
        assertEquals("", indent("func f() {\n\ts := \"}\"\n<caret>}"))
    }

    @Test
    fun `caret inside raw string returns null`() {
        assertNull(indent("\tx := `a\n<caret>b`"))
    }

    @Test
    fun `caret inside block comment returns null`() {
        assertNull(indent("\t/* a\n<caret>b */"))
    }

    @Test
    fun `mismatched brackets return null`() {
        assertNull(indent("func f() {\n\tfoo(\n<caret>}"))
    }

    @Test
    fun `closer without opener returns null`() {
        assertNull(indent("x\n<caret>)"))
    }

    @Test
    fun `start of file gets no indent`() {
        assertEquals("", indent("<caret>package main"))
    }

    @Test
    fun `empty file gets no indent`() {
        assertEquals("", indent("<caret>"))
    }

    @Test
    fun `spaces style adds indent size spaces`() {
        assertEquals("      ", indent("  if x {\n<caret>", GoIndentStyle(useTabs = false, tabSize = 4, indentSize = 4)))
    }

    @Test
    fun `tab style with smaller indent size pads with spaces`() {
        // tab (4 columns) + 2 = 6 columns = one tab + 2 spaces
        assertEquals("\t  ", indent("\tif x {\n<caret>", GoIndentStyle(useTabs = true, tabSize = 4, indentSize = 2)))
    }

    @Test
    fun `copied indent is kept verbatim`() {
        assertEquals("  ", indent("  x := 1\n<caret>"))
    }

    private fun indent(textWithCaret: String, style: GoIndentStyle = TABS): String? {
        val offset = textWithCaret.indexOf(CARET)
        val text = textWithCaret.replace(CARET, "")
        val tokens = lex(text)
        val backward = tokens.filter { it.start < offset }.asReversed().asSequence()
        val forward = tokens.filter { it.end > offset }.asSequence()
        return GoLineIndent.compute(text, offset, backward, forward, style)
    }

    private fun lex(text: String): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        val result = mutableListOf<GoIndentToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += GoIndentToken(goIndentKind(type), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }

    private companion object {
        const val CARET = "<caret>"
        val TABS = GoIndentStyle(useTabs = true, tabSize = 4, indentSize = 4)
    }
}
