package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoRefactoringTargetsTest {
    /** Selection ranges given by their texts from the innermost out; each is the first occurrence in [text]. */
    private fun ranges(text: String, vararg texts: String): List<GoTextSpan> = texts.map { part ->
        val start = text.indexOf(part).takeIf { it >= 0 } ?: error("'$part' not in text")
        GoTextSpan(start, start + part.length)
    }

    private fun candidates(text: String, vararg texts: String): List<String> =
        GoRefactoringTargets.expressions(text, ranges(text, *texts)).map { text.substring(it.start, it.end) }

    @Test
    fun `expressions grow from the caret up to the enclosing statement`() {
        val text = "func f() {\n\tx := 1 + 2*3\n}\n"
        assertEquals(listOf("2", "2*3", "1 + 2*3"), candidates(text, "2", "2*3", "1 + 2*3", "x := 1 + 2*3", "{\n\tx := 1 + 2*3\n}"))
    }

    @Test
    fun `left side of a definition is no expression`() {
        val text = "\tsum := a + b\n"
        assertEquals(emptyList<String>(), candidates(text, "sum", "sum := a + b"))
    }

    @Test
    fun `identifiers of a multi-value definition are no expressions but the right side is`() {
        val text = "\tv, ok := m[k]\n"
        assertEquals(emptyList<String>(), candidates(text, "v", "v, ok := m[k]"))
        assertEquals(listOf("m", "m[k]"), candidates(text, "m", "m[k]", "v, ok := m[k]"))
    }

    @Test
    fun `targets of a multi-value assignment are no expressions but the right side is`() {
        val text = "\ta, b = x, y\n"
        assertEquals(emptyList<String>(), candidates(text, "a"))
        assertEquals(emptyList<String>(), candidates(text, "b"))
        assertEquals(listOf("x"), candidates(text, "x"))
    }

    @Test
    fun `method name after a dot is skipped but the selector and the call stay`() {
        val text = "\tfmt.Println(x)\n"
        assertEquals(listOf("fmt.Println", "fmt.Println(x)"), candidates(text, "Println", "fmt.Println", "fmt.Println(x)"))
    }

    @Test
    fun `return statement ends the candidates`() {
        val text = "\treturn a + b\n"
        assertEquals(listOf("a", "a + b"), candidates(text, "a", "a + b", "return a + b"))
    }

    @Test
    fun `comparison is an expression not an assignment`() {
        val text = "\tok = x == y\n"
        assertEquals(listOf("x", "x == y"), candidates(text, "x", "x == y", "ok = x == y"))
    }

    @Test
    fun `compound assignment and increment end the candidates`() {
        assertEquals(listOf("n * 2"), candidates("\tx += n * 2\n", "n * 2", "x += n * 2"))
        assertEquals(emptyList<String>(), candidates("\ti++\n", "i", "i++"))
    }

    @Test
    fun `channel send is a statement but its value is an expression`() {
        assertEquals(listOf("v"), candidates("\tch <- v\n", "v", "ch <- v"))
    }

    @Test
    fun `receive is an expression`() {
        assertEquals(listOf("<-ch", "use(<-ch)"), candidates("\tuse(<-ch)\n", "<-ch", "use(<-ch)"))
    }

    @Test
    fun `composite literal key is skipped`() {
        val text = "\tp := T{Name: v}\n"
        assertEquals(listOf("T{Name: v}"), candidates(text, "Name", "T{Name: v}", "p := T{Name: v}"))
    }

    @Test
    fun `label is skipped`() {
        assertEquals(emptyList<String>(), candidates("Loop:\n", "Loop"))
    }

    @Test
    fun `map key literal case condition and slice bound before a colon are expressions`() {
        assertEquals(listOf("\"a\""), candidates("\tm := map[string]int{\"a\": 1}\n", "\"a\""))
        assertEquals(listOf("cond"), candidates("\tcase cond:\n", "cond"))
        assertEquals(listOf("lo"), candidates("\t_ = s[lo:hi]\n", "lo"))
    }

    @Test
    fun `multi-line range ends the candidates`() {
        val text = "\tx := f(a,\n\t\tb)\n"
        assertEquals(listOf("b"), candidates(text, "b", "f(a,\n\t\tb)", "x := f(a,\n\t\tb)"))
    }

    @Test
    fun `assignment text inside a string or a func literal does not make a statement`() {
        assertEquals(listOf("\"a := b\"", "fmt.Println(\"a := b\")"), candidates("\tfmt.Println(\"a := b\")\n", "\"a := b\"", "fmt.Println(\"a := b\")"))
        assertEquals(listOf("func() { y := 1; _ = y }"), candidates("\tgo2(func() { y := 1; _ = y })\n", "func() { y := 1; _ = y }"))
    }

    @Test
    fun `out of bounds and empty ranges are ignored`() {
        val text = "\treturn n\n"
        val n = text.indexOf("n\n")
        val spans = listOf(GoTextSpan(n, n), GoTextSpan(-1, 2), GoTextSpan(n, text.length + 5), GoTextSpan(n, n + 1))
        assertEquals(listOf(GoTextSpan(n, n + 1)), GoRefactoringTargets.expressions(text, spans))
    }

    private fun lines(text: String, selected: String): String {
        val start = text.indexOf(selected)
        val span = GoRefactoringTargets.statementLines(text, GoTextSpan(start, start + selected.length))
        return text.substring(span.start, span.end)
    }

    @Test
    fun `lone statement widens to its whole line with the newline`() {
        assertEquals("\tprintln(sum)\n", lines("func f() {\n\tprintln(sum)\n\treturn\n}\n", "println(sum)"))
    }

    @Test
    fun `trailing blanks after the statement are taken along`() {
        assertEquals("\tprintln(sum)  \n", lines("func f() {\n\tprintln(sum)  \n}\n", "println(sum)"))
    }

    @Test
    fun `trailing line comment is taken along`() {
        assertEquals("\tprintln(sum) // why\n", lines("func f() {\n\tprintln(sum) // why\n}\n", "println(sum)"))
    }

    @Test
    fun `trailing code after the selection keeps it as it is`() {
        assertEquals("a()", lines("\ta(); b()\n", "a()"))
    }

    @Test
    fun `selection already ending with a newline only gains the indentation`() {
        assertEquals("\ta()\n\tb()\n", lines("func f() {\n\ta()\n\tb()\n}\n", "a()\n\tb()\n"))
    }

    @Test
    fun `partial line selection stays as it is`() {
        assertEquals("a + b", lines("\tsum := a + b\n", "a + b"))
        assertEquals("sum := a", lines("\tsum := a + b\n", "sum := a"))
    }

    @Test
    fun `statement on the last line without a newline widens to the end of text`() {
        assertEquals("\tx()", lines("\tx()", "x()"))
    }

    @Test
    fun `new variable is found at its declaration`() {
        val before = "func compute(a, b int) int {\n\tsum := a + b\n\treturn sum\n}\n"
        val after = "func compute(a, b int) int {\n\tnewVar := a + b\n\tsum := newVar\n\treturn sum\n}\n"
        // "func compute(a, b int) int {\n\t" is 30 characters.
        assertEquals(GoTextSpan(30, 36), GoRefactoringTargets.newName(before, after))
    }

    @Test
    fun `older identifier with the same name outside the edit is skipped`() {
        val before = "func g() { newVar := 1; _ = newVar }\nfunc f() int {\n\treturn 1 + n\n}\n"
        val after = "func g() { newVar := 1; _ = newVar }\nfunc f() int {\n\tnewVar := 1 + n\n\treturn newVar\n}\n"
        val start = after.indexOf("\tnewVar :=") + 1
        assertEquals(GoTextSpan(start, start + "newVar".length), GoRefactoringTargets.newName(before, after))
    }

    @Test
    fun `numbered fresh name is found`() {
        val before = "\tx := a + b\n"
        val after = "\tnewVar1 := a + b\n\tx := newVar1\n"
        assertEquals(GoTextSpan(1, 8), GoRefactoringTargets.newName(before, after))
    }

    @Test
    fun `extracted function is found at its call site`() {
        val before = "func f(a int) {\n\tprintln(a)\n}\n"
        val after = "func f(a int) {\n\tnewFunction(a)\n}\n\nfunc newFunction(a int) {\n\tprintln(a)\n}\n"
        // "func f(a int) {\n\t" is 17 characters.
        assertEquals(GoTextSpan(17, 28), GoRefactoringTargets.newName(before, after))
    }

    @Test
    fun `constant name is found`() {
        val before = "\treturn n * 1024\n"
        val after = "\tconst newConst = 1024\n\treturn n * newConst\n"
        assertEquals(GoTextSpan(7, 15), GoRefactoringTargets.newName(before, after))
    }

    @Test
    fun `no name when the text did not change or the name is only in a comment or string`() {
        assertNull(GoRefactoringTargets.newName("x := 1\n", "x := 1\n"))
        assertNull(GoRefactoringTargets.newName("x := 1\n", "x := 1 // newVar\n"))
        assertNull(GoRefactoringTargets.newName("x := 1\n", "x := \"newVar\"\n"))
    }

    @Test
    fun `identifier merely starting with a gopls name is not it`() {
        assertNull(GoRefactoringTargets.newName("x := 1\n", "newVariable := 1\nx := newVariable\n"))
    }
}
