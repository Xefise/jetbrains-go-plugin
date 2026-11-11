package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.codeContext
import io.github.xefise.golsp.lang.templatePlace
import io.github.xefise.golsp.lang.model.GoCodeContext.LITERAL
import io.github.xefise.golsp.lang.model.GoCodeContext.OTHER
import io.github.xefise.golsp.lang.model.GoCodeContext.STATEMENT
import io.github.xefise.golsp.lang.model.GoCodeContext.TOP_LEVEL
import org.junit.Assert.assertEquals
import org.junit.Test

class GoTemplateContextsTest {
    @Test
    fun `empty file is top level`() = check(TOP_LEVEL, "<caret>")

    @Test
    fun `after a closed func is top level`() = check(TOP_LEVEL, "package main\n\nfunc f() {\n}\n<caret>")

    @Test
    fun `func body is statement`() = check(STATEMENT, "func f() {\n\t<caret>\n}")

    @Test
    fun `unclosed func body is statement`() = check(STATEMENT, "func f() {\n\t<caret>")

    @Test
    fun `method with result types is statement`() = check(STATEMENT, "func (s *S) M(x int) (int, error) {\n\t<caret>")

    @Test
    fun `generic func is statement`() = check(STATEMENT, "func F[T any](x T) []T {\n\t<caret>")

    @Test
    fun `func returning a slice type is statement`() = check(STATEMENT, "func f() []int {\n\t<caret>")

    @Test
    fun `func returning a map type is statement`() = check(STATEMENT, "func f() map[string]int {\n\t<caret>")

    @Test
    fun `struct type body is other`() = check(OTHER, "type S struct {\n\t<caret>")

    @Test
    fun `interface body is other`() = check(OTHER, "type I interface {\n\t<caret>")

    @Test
    fun `struct type declared inside a func is other`() = check(OTHER, "func f() {\n\ttype S struct {\n\t\t<caret>")

    @Test
    fun `after a local struct type closes the func body is statement again`() =
        check(STATEMENT, "func f() {\n\ttype S struct {\n\t\ta int\n\t}\n\t<caret>")

    @Test
    fun `composite literal in a func is other`() = check(OTHER, "func f() {\n\tx := T{\n\t\t<caret>")

    @Test
    fun `slice literal is other`() = check(OTHER, "func f() {\n\tx := []int{<caret>")

    @Test
    fun `map literal is other`() = check(OTHER, "func f() {\n\tm := map[string]int{<caret>")

    @Test
    fun `nested elided literal is other`() = check(OTHER, "func f() {\n\tx := [][]int{\n\t\t{<caret>")

    @Test
    fun `anonymous struct literal is other`() = check(OTHER, "func f() {\n\tx := struct{ a int }{<caret>")

    @Test
    fun `return of a literal is other`() = check(OTHER, "func f() T {\n\treturn T{<caret>")

    @Test
    fun `literal as call argument is other`() = check(OTHER, "func f() {\n\tg(T{<caret>")

    @Test
    fun `if body is statement`() = check(STATEMENT, "func f() {\n\tif x == y {\n\t\t<caret>")

    @Test
    fun `if with init statement is statement`() = check(STATEMENT, "func f() {\n\tif v, ok := m[k]; ok {\n\t\t<caret>")

    @Test
    fun `if with indexed condition is statement`() = check(STATEMENT, "func f() {\n\tif a[i] {\n\t\t<caret>")

    @Test
    fun `if condition split over lines is statement`() = check(STATEMENT, "func f() {\n\tif a &&\n\t\tb {\n\t\t<caret>")

    @Test
    fun `else body is statement`() = check(STATEMENT, "func f() {\n\tif x {\n\t} else {\n\t\t<caret>")

    @Test
    fun `for range over a slice literal body is statement`() =
        check(STATEMENT, "func f() {\n\tfor _, v := range []int{1, 2} {\n\t\t<caret>")

    @Test
    fun `slice literal inside a for range header is other`() = check(OTHER, "func f() {\n\tfor _, v := range []int{<caret>")

    @Test
    fun `three clause for body is statement`() = check(STATEMENT, "func f() {\n\tfor i := 0; i < n; i++ {\n\t\t<caret>")

    @Test
    fun `bare for body is statement`() = check(STATEMENT, "func f() {\n\tfor {\n\t\t<caret>")

    @Test
    fun `case body of a switch is statement`() = check(STATEMENT, "func f() {\n\tswitch x {\n\tcase 1:\n\t\t<caret>")

    @Test
    fun `type switch case body is statement`() = check(STATEMENT, "func f() {\n\tswitch v := x.(type) {\n\tcase int:\n\t\t<caret>")

    @Test
    fun `select case body is statement`() = check(STATEMENT, "func f() {\n\tselect {\n\tcase <-ch:\n\t\t<caret>")

    @Test
    fun `func literal assigned in a body is statement`() = check(STATEMENT, "func f() {\n\tg := func(x int) error {\n\t\t<caret>")

    @Test
    fun `func literal passed as argument is statement`() = check(STATEMENT, "func f() {\n\tt.Run(\"x\", func(t *testing.T) {\n\t\t<caret>")

    @Test
    fun `func literal as a literal field value is statement`() = check(STATEMENT, "var x = T{\n\tf: func() {\n\t\t<caret>")

    @Test
    fun `map of func type literal is other`() = check(OTHER, "func f() {\n\tm := map[string]func(){\n\t\t<caret>")

    @Test
    fun `go func body is statement`() = check(STATEMENT, "func f() {\n\tgo func() {\n\t\t<caret>")

    @Test
    fun `bare block in a body is statement`() = check(STATEMENT, "func f() {\n\tx := 1\n\t{\n\t\t<caret>")

    @Test
    fun `literal inside a func literal inside a literal is other`() =
        check(OTHER, "var x = T{\n\tf: func() {\n\t\ty := []int{<caret>")

    @Test
    fun `top level var literal is other`() = check(OTHER, "var x = T{\n\t<caret>")

    @Test
    fun `import group is other`() = check(OTHER, "import (\n\t<caret>")

    @Test
    fun `parameter list is other`() = check(OTHER, "func f(<caret>")

    @Test
    fun `call arguments inside a body are other`() = check(OTHER, "func f() {\n\tg(<caret>")

    @Test
    fun `unclosed paren is closed by the block brace`() = check(STATEMENT, "func f() {\n\tif x {\n\t\tg(\n\t}\n\t<caret>")

    @Test
    fun `inside a string is literal`() = check(LITERAL, "func f() {\n\ts := \"ab<caret>c\"")

    @Test
    fun `right after a closed string is not literal`() = check(OTHER, "func f() {\n\tg(\"abc\"<caret>")

    @Test
    fun `at the end of an unterminated string is literal`() = check(LITERAL, "func f() {\n\ts := \"abc<caret>")

    @Test
    fun `after an escaped quote at the end of an unterminated string is literal`() =
        check(LITERAL, "func f() {\n\ts := \"a\\\"<caret>")

    @Test
    fun `at the end of a line comment is literal`() = check(LITERAL, "func f() {\n\t// note<caret>\n}")

    @Test
    fun `on the line after a comment is statement`() = check(STATEMENT, "func f() {\n\t// note\n\t<caret>\n}")

    @Test
    fun `inside a raw string spanning lines is literal`() = check(LITERAL, "func f() {\n\ts := `a\n<caret>b`")

    @Test
    fun `inside a block comment is literal`() = check(LITERAL, "/* a <caret> */")

    @Test
    fun `braces inside strings and comments are ignored`() =
        check(STATEMENT, "func f() {\n\ts := \"{\" // {\n\t/* { */\n\t<caret>")

    @Test
    fun `stray closing brace at top level is top level`() = check(TOP_LEVEL, "}\n<caret>")

    @Test
    fun `func returning interface is statement`() = check(STATEMENT, "func f() interface{} {\n\t<caret>")

    @Test
    fun `func returning a slice of interface is statement`() = check(STATEMENT, "func f() []interface{} {\n\t<caret>")

    @Test
    fun `func literal returning a map of any is statement`() = check(STATEMENT, "func f() {\n\tg := func() map[string]struct{} {\n\t\t<caret>")

    @Test
    fun `slice of interface literal is other`() = check(OTHER, "func f() {\n\tx := []interface{}{<caret>")

    @Test
    fun `key at the start of a line starts a statement`() = checkStart(true, "func f() {\n\tg()\n\terr<caret>")

    @Test
    fun `key right after the opening brace starts a statement`() = checkStart(true, "func f() {\n\terr<caret>")

    @Test
    fun `key after a comment line starts a statement`() = checkStart(true, "func f() {\n\tg() // x\n\t// y\n\terr<caret>")

    @Test
    fun `key after a case colon starts a statement`() = checkStart(true, "func f() {\n\tswitch {\n\tcase a:\n\t\terr<caret>")

    @Test
    fun `key after a case colon on the same line starts a statement`() =
        checkStart(true, "func f() {\n\tswitch {\n\tcase a: err<caret>")

    @Test
    fun `key after a label starts a statement`() = checkStart(true, "func f() {\nL:\n\tfor<caret>")

    @Test
    fun `key right after else brace starts a statement`() = checkStart(true, "func f() {\n\tif x {\n\t} else { err<caret>")

    @Test
    fun `selection starting at a statement after an opening brace on the same line starts a statement`() =
        checkStart(true, "func f() { <caret>g() }")

    @Test
    fun `key after a semicolon starts a statement`() = checkStart(true, "func f() {\n\tg(); err<caret>")

    @Test
    fun `key at the very beginning starts a statement`() = checkStart(true, "main<caret>")

    @Test
    fun `key after a selector dot does not start a statement`() = checkStart(false, "func f() {\n\t!f().go<caret>")

    @Test
    fun `key after a dot on the next line does not start a statement`() = checkStart(false, "func f() {\n\tx.\n\tgo<caret>")

    @Test
    fun `key on the right side of a define does not start a statement`() = checkStart(false, "func f() {\n\tx := err<caret>")

    @Test
    fun `key after return does not start a statement`() = checkStart(false, "func f() {\n\treturn err<caret>")

    @Test
    fun `key on a continuation line does not start a statement`() = checkStart(false, "func f() {\n\tx := a +\n\t\terr<caret>")

    @Test
    fun `selection start at a line start after a statement starts a statement`() =
        checkStart(true, "func f() {\n\tg()\n<caret>\th()\n}")

    @Test
    fun `selection start after an assignment does not start a statement`() = checkStart(false, "func f() {\n\tx := <caret>g()\n}")

    private fun checkStart(expected: Boolean, source: String) {
        val offset = source.indexOf("<caret>")
        assertEquals(expected, templatePlace(source.replace("<caret>", ""), offset).startsStatement)
    }

    private fun check(expected: GoCodeContext, source: String) {
        val offset = source.indexOf("<caret>")
        assertEquals(expected, codeContext(source.replace("<caret>", ""), offset))
    }
}
