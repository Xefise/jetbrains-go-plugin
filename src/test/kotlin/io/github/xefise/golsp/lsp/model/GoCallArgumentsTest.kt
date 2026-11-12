package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCallArgumentsTest {
    // `|` marks the caret; a coarse tokenizer is enough: brackets, spaces, `//` comments, strings, the rest is code.
    private fun inside(source: String): Boolean {
        val caret = source.indexOf('|')
        val text = source.removeRange(caret, caret + 1)
        val tokens = mutableListOf<GoIndentToken>()
        var i = 0
        while (i < caret) {
            val c = text[i]
            val start = i
            val kind = when {
                c in "([{" -> { i++; GoIndentTokenKind.OPEN }
                c in ")]}" -> { i++; GoIndentTokenKind.CLOSE }
                c.isWhitespace() -> { while (i < caret && text[i].isWhitespace()) i++; GoIndentTokenKind.SPACE }
                text.startsWith("//", i) -> { while (i < caret && text[i] != '\n') i++; GoIndentTokenKind.COMMENT }
                c == '"' -> { i++; while (i < caret && text[i] != '"') i++; if (i < caret) i++; GoIndentTokenKind.STRING }
                else -> { while (i < caret && text[i] !in "()[]{}\" \t\n" && !text.startsWith("//", i)) i++; GoIndentTokenKind.CODE }
            }
            tokens += GoIndentToken(kind, start, i)
        }
        return GoCallArguments.isInside(tokens.asReversed().asSequence(), text)
    }

    @Test
    fun `argument lists of calls`() {
        listOf(
            "NewWarehouse(|)",
            "w.Reserve(\"apple\", |)",
            "w.Reserve(sku|)",
            "f()(|)",
            "Map[int](xs, |)",
            "fmt.Println(\"a (b\", |)",
            "outer(inner(1), |)",
            "f(\n\ta,\n\t|",
            "f /* c */ (|)",
            "t.Run(tt.name, func(t *testing.T) {}|)",
        ).forEach { assertTrue(it, inside(it)) }
    }

    @Test
    fun `everything else`() {
        listOf(
            "x := 1|",
            "func main() {\n\tx|",
            "f(a)|",
            "m := map[string]int{\"a\": |}",
            "xs[|]",
            "(|",
            "|",
            "f(func() {\n\t|",
        ).forEach { assertFalse(it, inside(it)) }
    }
}
