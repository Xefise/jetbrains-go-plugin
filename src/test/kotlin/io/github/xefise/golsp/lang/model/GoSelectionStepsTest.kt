package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoSelectionStepsTest {
    @Test
    fun `call chain grows from argument to statement, block, declaration and file`() {
        val file = inFunc("\tfmt.Println(a.b(<caret>x)[i])")
        assertEquals(
            listOf(
                "x", "(x)", "a.b(x)", "a.b(x)[i]", "(a.b(x)[i])", "fmt.Println(a.b(x)[i])",
                "{\n\tfmt.Println(a.b(x)[i])\n}", "func main() {\n\tfmt.Println(a.b(x)[i])\n}", file.text,
            ),
            steps(file),
        )
    }

    @Test
    fun `caret right after a word before a closer or comma belongs to that word`() {
        assertEquals(listOf("y", "x.y", "x.y, z"), steps(inFunc("\tf(x.y<caret>, z)")).take(3))
        assertEquals(listOf("y", "x.y", "(x.y)"), steps(inFunc("\tf(x.y<caret>)")).take(3))
    }

    @Test
    fun `selector chain grows by prefixes from the qualifier`() {
        assertEquals(listOf("a", "a.b", "a.b.c", "a.b.c()"), steps(inFunc("\t<caret>a.b.c()")).take(4))
    }

    @Test
    fun `selector chain from a middle name starts at the chain head`() {
        assertEquals(listOf("b", "a.b", "a.b.c"), steps(inFunc("\tx := a.<caret>b.c")).take(3))
    }

    @Test
    fun `argument grows to its list item before the whole argument list`() {
        assertEquals(
            listOf("a", "a+b", "a+b, c", "(a+b, c)", "f(a+b, c)"),
            steps(inFunc("\tf(<caret>a+b, c)")).take(5),
        )
    }

    @Test
    fun `brackets inside string and rune literals do not pair`() {
        assertEquals(
            listOf("x", "\"(\", ')', x", "(\"(\", ')', x)", "f(\"(\", ')', x)"),
            steps(inFunc("\tf(\"(\", ')', <caret>x)")).take(4),
        )
    }

    @Test
    fun `brackets inside comments do not pair`() {
        assertEquals(
            listOf("x", "x /* ) */", "(x /* ) */)", "f(x /* ) */)"),
            steps(inFunc("\tf(<caret>x /* ) */)")).take(4),
        )
    }

    @Test
    fun `statement step leaves out a trailing line comment`() {
        val file = inFunc("\ty := <caret>x // see (f)\n\tz := 2")
        assertEquals(listOf("x", "y := x", "y := x // see (f)\n\tz := 2"), steps(file).take(3))
    }

    @Test
    fun `caret in a line comment grows to the comment then the block contents`() {
        val file = inFunc("\tx := 1 // so<caret>me (text\n\ty := 2")
        assertEquals(listOf("// some (text", "x := 1 // some (text\n\ty := 2"), steps(file).take(2))
    }

    @Test
    fun `multi-line call is one statement`() {
        val file = inFunc("\tfoo(a,\n\t\t<caret>b)\n\tbar()")
        assertEquals(
            listOf("b", "a,\n\t\tb", "(a,\n\t\tb)", "foo(a,\n\t\tb)", "foo(a,\n\t\tb)\n\tbar()"),
            steps(file).take(5),
        )
    }

    @Test
    fun `multi-line raw string with brackets is one statement`() {
        val file = inFunc("\ts := `a {\n(b`\n\t<caret>t := 1")
        assertEquals(listOf("t", "t := 1", "s := `a {\n(b`\n\tt := 1"), steps(file).take(3))
        val inString = inFunc("\ts := `a {\n(<caret>b`\n\tt := 1")
        assertEquals(listOf("`a {\n(b`", "s := `a {\n(b`"), steps(inString).take(2))
    }

    @Test
    fun `block body grows to block then if-else statement with its header`() {
        val file = inFunc("\tif ok {\n\t\tx()\n\t} else {\n\t\t<caret>y()\n\t}")
        assertEquals(
            listOf("y", "y()", "{\n\t\ty()\n\t}", "if ok {\n\t\tx()\n\t} else {\n\t\ty()\n\t}"),
            steps(file).take(4),
        )
    }

    @Test
    fun `header line grows to the whole statement up to its closing brace`() {
        assertEquals(listOf("ok", "if ok {\n\t\tx()\n\t}"), steps(inFunc("\tif <caret>ok {\n\t\tx()\n\t}")).take(2))
    }

    @Test
    fun `statement in a block grows to the block contents before the braces`() {
        val file = inFunc("\ta := 1\n\t<caret>b := 2")
        assertEquals(listOf("b", "b := 2", "a := 1\n\tb := 2", "{\n\ta := 1\n\tb := 2\n}"), steps(file).take(4))
    }

    @Test
    fun `caret on an opening bracket selects the bracket then its group`() {
        assertEquals(listOf("(", "(x)", "f(x)"), steps(inFunc("\tf<caret>(x)")).take(3))
    }

    @Test
    fun `composite literal type joins its braces`() {
        assertEquals(
            listOf("int", "map[string]int", "map[string]int{}"),
            steps(inFunc("\tm := map[string]<caret>int{}")).take(3),
        )
    }

    @Test
    fun `keyword is not chained to a parenthesised operand`() {
        assertEquals(listOf("x", "(x)", "return(x) + 1"), steps(inFunc("\treturn(<caret>x) + 1")).take(3))
    }

    @Test
    fun `unbalanced brackets fall back to lines and file`() {
        val file = Snippet("package main\n\nfunc f(<caret>x {\n")
        val result = steps(file)
        assertEquals("x", result.first())
        assertEquals(file.text, result.last())
        assertStrictlyGrowing(file)
    }

    @Test
    fun `caret in indentation starts from the line`() {
        val file = inFunc("<caret>\tb := 2")
        assertEquals(listOf("b := 2", "{\n\tb := 2\n}"), steps(file).take(2))
    }

    @Test
    fun `empty file yields nothing`() {
        assertEquals(emptyList<String>(), steps(Snippet("<caret>")))
    }

    @Test
    fun `steps strictly grow on a larger file`() {
        val body = "\tfor i, v := range xs {\n\t\tif v.ok(i) && m[k] {\n\t\t\tgo func() {\n\t\t\t\tdone <- s.f(\"}\")<caret>[0]\n" +
            "\t\t\t}()\n\t\t}\n\t}"
        assertStrictlyGrowing(inFunc(body))
    }

    private class Snippet(withCaret: String) {
        val offset = withCaret.indexOf("<caret>")
        val text = withCaret.replace("<caret>", "")
    }

    private fun inFunc(body: String) = Snippet("package main\n\nfunc main() {\n$body\n}\n")

    private fun raw(file: Snippet): List<GoSpan> {
        val lexer = GoLexer()
        lexer.start(file.text)
        val tokens = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null) {
            tokens += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return GoSelectionSteps.compute(file.text, tokens, file.offset)
    }

    private fun steps(file: Snippet): List<String> = raw(file).map { file.text.substring(it.start, it.end) }

    private fun assertStrictlyGrowing(file: Snippet) {
        raw(file).zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b.contains(a) && a != b) }
    }
}
