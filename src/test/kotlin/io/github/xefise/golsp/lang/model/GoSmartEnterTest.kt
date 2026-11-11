package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import org.junit.Assert.assertEquals
import org.junit.Test

// Results mirror the editor shell: "<body>" is the new body line, "<plain>" means the platform adds a plain new line.
class GoSmartEnterTest {
    @Test
    fun `if header gets a body at the line indent`() = check("\tif x<caret>", "\tif x {\n<body>\n\t}")

    @Test
    fun `trailing spaces are dropped before the body`() = check("if x   <caret>", "if x {\n<body>\n}")

    @Test
    fun `bare for gets a body`() = check("for<caret>", "for {\n<body>\n}")

    @Test
    fun `three clause for gets a body`() = check("for i := 0; i < n; i++<caret>", "for i := 0; i < n; i++ {\n<body>\n}")

    @Test
    fun `header that already opens a block only moves into it`() = check("for {<caret>", "for {\n<plain>")

    @Test
    fun `unclosed call in if condition is closed before the brace`() = check("if f(g(x<caret>", "if f(g(x)) {\n<body>\n}")

    @Test
    fun `unclosed index in if condition is closed`() = check("if a[i<caret>", "if a[i] {\n<body>\n}")

    @Test
    fun `else after closing brace gets a body`() = check("} else<caret>", "} else {\n<body>\n}")

    @Test
    fun `else if after closing brace gets a body`() = check("\t} else if x<caret>", "\t} else if x {\n<body>\n\t}")

    @Test
    fun `empty braces on the header line are split`() = check("if x{}<caret>", "if x {\n<body>\n}")

    @Test
    fun `one line body is left alone`() = check("if x { return }<caret>", "if x { return }\n<plain>")

    @Test
    fun `switch with init statement gets a body`() = check("switch x := f(); x<caret>", "switch x := f(); x {\n<body>\n}")

    @Test
    fun `select gets a body`() = check("select<caret>", "select {\n<body>\n}")

    @Test
    fun `generic func header gets a body`() = check("func F[T any](x T)<caret>", "func F[T any](x T) {\n<body>\n}")

    @Test
    fun `method header with unclosed params is closed`() = check("func (r *T) M(<caret>", "func (r *T) M() {\n<body>\n}")

    @Test
    fun `struct type gets a body`() = check("type T struct<caret>", "type T struct {\n<body>\n}")

    @Test
    fun `interface type gets a body`() = check("type T interface<caret>", "type T interface {\n<body>\n}")

    @Test
    fun `func type declaration is not a header`() = check("type H func(int)<caret>", "type H func(int)\n<plain>")

    @Test
    fun `case without colon gets one`() = check("case 1<caret>", "case 1:\n<plain>")

    @Test
    fun `default without colon gets one`() = check("default<caret>", "default:\n<plain>")

    @Test
    fun `case with colon is left alone`() = check("case 1:<caret>", "case 1:\n<plain>")

    @Test
    fun `case with receive assignment gets a colon`() = check("case v := <-ch<caret>", "case v := <-ch:\n<plain>")

    @Test
    fun `colon inside case string does not count`() = check("case \"a:b\"<caret>", "case \"a:b\":\n<plain>")

    @Test
    fun `colon inside case slice does not count`() = check("case s[1:2<caret>", "case s[1:2]:\n<plain>")

    @Test
    fun `go func literal is called after its body`() = check("go func()<caret>", "go func() {\n<body>\n}()")

    @Test
    fun `defer func literal with unclosed params is closed and called`() =
        check("defer func(x int<caret>", "defer func(x int) {\n<body>\n}()")

    @Test
    fun `func literal argument closes the call after the body`() = check("foo(func()<caret>", "foo(func() {\n<body>\n})")

    @Test
    fun `assigned func literal gets a body`() = check("x := func(a int) error<caret>", "x := func(a int) error {\n<body>\n}")

    @Test
    fun `returned func literal gets a body`() = check("return func()<caret>", "return func() {\n<body>\n}")

    @Test
    fun `func type of a variable is not a literal`() = check("var f func()<caret>", "var f func()\n<plain>")

    @Test
    fun `composite literal brace only moves into it`() = check("x := T{<caret>", "x := T{\n<plain>")

    @Test
    fun `brackets inside strings are ignored`() = check("if s == \"([\"<caret>", "if s == \"([\" {\n<body>\n}")

    @Test
    fun `paren rune is ignored`() = check("if r == '('<caret>", "if r == '(' {\n<body>\n}")

    @Test
    fun `escaped quote rune is terminated`() = check("if r == '\\''<caret>", "if r == '\\'' {\n<body>\n}")

    @Test
    fun `string ending in an escaped backslash is terminated`() = check("if s == \"a\\\\\"<caret>", "if s == \"a\\\\\" {\n<body>\n}")

    @Test
    fun `brace inside raw string is ignored`() = check("if s == `{`<caret>", "if s == `{` {\n<body>\n}")

    @Test
    fun `line comment stays on the header line`() = check("if x // why<caret>", "if x { // why\n<body>\n}")

    @Test
    fun `block comment at line end stays on the header line`() = check("if x /* c */<caret>", "if x { /* c */\n<body>\n}")

    @Test
    fun `plain call is closed`() = check("fmt.Println(\"x\"<caret>", "fmt.Println(\"x\")\n<plain>")

    @Test
    fun `nested unclosed brackets close innermost first`() = check("foo(a[b<caret>", "foo(a[b])\n<plain>")

    @Test
    fun `unterminated string is left alone`() = check("if s == \"abc<caret>", null)

    @Test
    fun `string ending in an escaped quote is unterminated`() = check("if s == \"a\\\"<caret>", null)

    @Test
    fun `raw string running to the next line is left alone`() = check("x := `abc<caret>\nmore`", null)

    @Test
    fun `line inside a multi-line raw string is left alone`() = check("x := `a\nif x<caret>`", null)

    @Test
    fun `tail of a multi-line header only closes brackets`() =
        check("func f(\n\ta int,\n\tb int) (int<caret>", "\tb int) (int)\n<plain>")

    @Test
    fun `tail of a multi-line header with brace only moves into the body`() =
        check("func f(\n\ta int,\n) error {<caret>", ") error {\n<plain>")

    @Test
    fun `mismatched brackets are left alone`() = check("foo(]<caret>", null)

    @Test
    fun `blank line is left alone`() = check("\t<caret>", null)

    @Test
    fun `comment only line is left alone`() = check("// note<caret>", null)

    @Test
    fun `import group opener gets its closing paren`() = check("import (<caret>", "import (\n<body>\n)")

    @Test
    fun `call ending in an open paren is closed`() = check("foo(<caret>", "foo()\n<plain>")

    @Test
    fun `labeled for gets a body`() = check("loop: for x<caret>", "loop: for x {\n<body>\n}")

    @Test
    fun `labeled bare for gets a body`() = check("outer:\tfor<caret>", "outer:\tfor {\n<body>\n}")

    @Test
    fun `range over slice literal is not a body`() =
        check("for _, v := range []int{1, 2}<caret>", "for _, v := range []int{1, 2} {\n<body>\n}")

    @Test
    fun `range over map literal of pointers is not a body`() =
        check("for k := range map[string]*pkg.T{}<caret>", "for k := range map[string]*pkg.T{} {\n<body>\n}")

    @Test
    fun `parenthesized composite literal in condition is not a body`() =
        check("if x == (T{})<caret>", "if x == (T{}) {\n<body>\n}")

    @Test
    fun `brace after an index expression is a body`() = check("if a[i] { return }<caret>", "if a[i] { return }\n<plain>")

    @Test
    fun `empty braces after an index expression are split`() = check("if a[i] {}<caret>", "if a[i] {\n<body>\n}")

    @Test
    fun `func literal inside an open call in a header gets its own body`() =
        check("if foo(func() bool<caret>", "if foo(func() bool {\n<body>\n})")

    @Test
    fun `func literal field value gets a body`() = check("Run: func()<caret>", "Run: func() {\n<body>\n}")

    private fun check(source: String, expected: String?) {
        val caret = source.indexOf("<caret>")
        val text = source.replace("<caret>", "")
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)

        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null) {
            if (lexer.tokenEnd > lineStart && lexer.tokenStart < lineEnd) {
                tokens += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart - lineStart, lexer.tokenEnd - lineStart)
            }
            lexer.advance()
        }

        val plan = GoSmartEnter.plan(line, tokens)
        val actual = plan?.let {
            val close = it.blockClose
            if (close == null) {
                line.replaceRange(it.replaceStart, it.replaceEnd, it.text) + "\n<plain>"
            } else {
                val indent = line.takeWhile { c -> c == ' ' || c == '\t' }
                line.replaceRange(it.tailAt, line.length, "\n<body>\n$indent$close").replaceRange(it.replaceStart, it.replaceEnd, it.text)
            }
        }
        assertEquals(expected, actual)
    }
}
