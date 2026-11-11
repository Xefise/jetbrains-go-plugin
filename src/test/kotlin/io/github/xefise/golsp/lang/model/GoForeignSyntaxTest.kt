package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import org.junit.Assert.assertEquals
import org.junit.Test

// Code snippets live inside a function body unless the test says otherwise; results are the texts after each fix.
class GoForeignSyntaxTest {
    @Test
    fun `slice type before var name is swapped`() = fixes("var []float64 arr", "var arr []float64")

    @Test
    fun `builtin type before var name is swapped`() = fixes("var int a", "var a int")

    @Test
    fun `builtin type before var name keeps the initializer`() = fixes("var int a = 0", "var a int = 0")

    @Test
    fun `var with type first and define offers short and typed forms`() = fixes("var int a := 0", "a := 0", "var a int = 0")

    @Test
    fun `var with named type first and define is reported`() = fixes("var Point p := Point{}", "p := Point{}", "var p Point = Point{}")

    @Test
    fun `const with type first and define keeps const`() = fixes("const int n := 1", "const n int = 1", body = false)

    @Test
    fun `statement with builtin type first and define offers short and typed forms`() = fixes("int a := 0", "a := 0", "var a int = 0")

    @Test
    fun `statement with slice type first and assignment becomes var`() = fixes("[]int xs = nil", "var xs []int = nil")

    @Test
    fun `statement with map type first and nothing else becomes var`() = fixes("map[string]int m", "var m map[string]int")

    @Test
    fun `statement with qualified type first and define is reported`() = fixes("time.Duration d := 5", "d := 5", "var d time.Duration = 5")

    @Test
    fun `statement with pointer type first is reported`() = fixes("*Node n := nil", "n := nil", "var n *Node = nil")

    @Test
    fun `statement with generic type first is reported`() = fixes("List[int] l := nil", "l := nil", "var l List[int] = nil")

    @Test
    fun `statement after case label is reported`() = fixes("switch {\ncase true:\n\tint a := 1\n}", "switch {\ncase true:\n\ta := 1\n}", "switch {\ncase true:\n\tvar a int = 1\n}")

    @Test
    fun `statement after semicolon on one line is reported`() = fixes("x := 1; int a := x", "x := 1; a := x", "x := 1; var a int = x")

    @Test
    fun `param with slice type first is swapped`() =
        fixes("func f([]float64 arr, int n) {}", "func f(arr []float64, int n) {}", "func f([]float64 arr, n int) {}", body = false)

    @Test
    fun `method receiver with pointer type first is swapped`() = fixes("func (*T r) M() {}", "func (r *T) M() {}", body = false)

    @Test
    fun `result with builtin type first is swapped`() = fixes("func f() (int n) { return }", "func f() (n int) { return }", body = false)

    @Test
    fun `func literal param with type first is swapped`() = fixes("f := func(float64 x) {}", "f := func(x float64) {}")

    @Test
    fun `statement with string type first and define is reported`() = fixes("string s := \"\"", "s := \"\"", "var s string = \"\"")

    @Test
    fun `var with string type first and define is reported`() = fixes("var error err := nil", "err := nil", "var err error = nil")

    @Test
    fun `struct field with slice type first is swapped`() = fixes("type T struct {\n\t[]int xs\n}", "type T struct {\n\txs []int\n}", body = false)

    @Test
    fun `struct field with builtin type first is swapped`() = fixes("type T struct{ int a; b string }", "type T struct{ a int; b string }", body = false)

    @Test
    fun `var group line with builtin type first is swapped`() = fixes("var (\n\tint a = 1\n)", "var (\n\ta int = 1\n)", body = false)

    @Test
    fun `spread before the last call argument moves after it`() = fixes("f(...xs)", "f(xs...)")

    @Test
    fun `spread after other arguments moves after its argument`() = fixes("append(a, ...b)", "append(a, b...)")

    @Test
    fun `spread of a call result moves after the call`() = fixes("f(...g(x, y))", "f(g(x, y)...)")

    @Test
    fun `spread with a trailing comma moves after the argument`() = fixes("f(\n\t...xs,\n)", "f(\n\txs...,\n)")

    @Test
    fun `problem range covers the type and the name`() {
        val text = wrap("[]int xs := nil")
        val problem = GoForeignSyntax.find(text, tokens(text)).single()
        assertEquals("[]int xs", text.substring(problem.start, problem.end))
    }

    @Test
    fun `valid declarations are not reported`() = clean(
        "var x []int", "var x int", "var x, y []int", "var x = []int{1, 2}", "var x T", "var x T = v", "var _ int",
        "var arr [3]Point", "var m map[string][]int", "var ch <-chan int", "var fn func(int) error", "var p *T",
        "x := []int{1, 2}", "x, y := 1, 2", "b := []byte(s)", "var (\n\ta int = 1\n\tb, c string\n)", "const (\n\tA = iota\n\tB\n)",
    )

    @Test
    fun `valid statements are not reported`() = clean(
        "*p = 5", "x[i] = 1", "[]int{1}[0] = 2", "<-ch", "ch <- v", "x++", "L:\n\tfor {\n\t\tbreak L\n\t}", "go f()", "defer g(x)",
        "return", "x.y = z", "fmt.Println(a, b)", "a = b", "if x := f(); x > 0 {\n}", "switch v := x.(type) {\ncase int:\n}",
        "for i := range xs {\n}", "y := x +\n\tz", "type local struct{ a int }",
    )

    @Test
    fun `valid signatures are not reported`() = clean(
        "func f(a, b []int) {}", "func f(xs ...int) {}", "func f(...int) {}", "func f(int, string) {}", "func f() (int, error) { return 0, nil }",
        "func f() (n int, err error) { return }", "func (r *T) M(a [2]Item) {}", "func (T) M() {}", "func (r *List[K]) M() {}",
        "func F[T any, S ~[]T](s S) T { var z T; return z }", "func f(fn func(int) error) {}", "func f(fn func(...int)) {}",
        "func f(m map[string]int, ch chan<- int) {}", "func f(p *pkg.T) {}", "type F func(...any) error",
        "type I interface {\n\tPrintf(format string, args ...any)\n\tM() (int, error)\n\tfmt.Stringer\n}", "type C interface{ ~int | ~string }",
        body = false,
    )

    @Test
    fun `valid struct fields are not reported`() = clean(
        "type T struct {\n\ta int\n\tb, c []string\n\tsync.Mutex\n\t*Node\n\tItems [4]Item\n\tName string `json:\"name\"`\n\tfn func(int) error\n}",
        "type T[K comparable, V any] struct{ m map[K]V }",
        body = false,
    )

    @Test
    fun `valid ellipses are not reported`() = clean("f(xs...)", "append(a, b...)", "x := [...]int{1, 2}", "f(a, xs...)")

    @Test
    fun `spread that is not the last argument is not reported`() = clean("f(...xs, 1)")

    @Test
    fun `names that are builtin types are not reported`() = clean("var int string", "func f(error error) {}", body = false)

    @Test
    fun `fields and params named string or error are not reported`() = clean(
        "type machine struct {\n\tstring inputString\n\terror yaml_error_type_t // comment\n}",
        "func cgoFormatErr(error cgoErrorMsg, kind abi.Kind) {}", "var string s", "var any T = v",
        body = false,
    )

    @Test
    fun `type first with a plain named type and no assignment is not reported`() = clean("foo bar")

    @Test
    fun `java array syntax is not reported`() = clean("int[] xs := nil", "func f(int[] xs) {}", body = false)

    @Test
    fun `type and name split across lines are not reported`() = clean("var []int\nxs")

    @Test
    fun `code in strings and comments is not reported`() = clean("s := \"int a := 0\"", "// int a := 0\n", "/* f(...xs) */")

    @Test
    fun `top level type first is not reported`() = clean("int a := 0", body = false)

    @Test
    fun `half typed code does not throw`() = clean("func f(", "var", "f(...", "int a :", "var []", "struct {", body = false)

    private fun fixes(code: String, vararg expected: String, body: Boolean = true) {
        val text = if (body) wrap(code) else code
        val results = GoForeignSyntax.find(text, tokens(text)).flatMap { it.fixes }.map { text.replaceRange(it.start, it.end, it.replacement) }
        assertEquals(expected.map { if (body) wrap(it) else it }, results)
    }

    private fun clean(vararg snippets: String, body: Boolean = true) {
        for (code in snippets) {
            val text = if (body) wrap(code) else code
            assertEquals(code, emptyList<GoSyntaxProblem>(), GoForeignSyntax.find(text, tokens(text)))
        }
    }

    private fun wrap(code: String) = "package p\n\nfunc main() {\n$code\n}\n"

    private fun tokens(text: String): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null) {
            result += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }
}
