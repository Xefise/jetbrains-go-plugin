package io.github.xefise.golsp.lang.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.postfixKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Inputs are the document with the postfix key already removed; <caret> is where the key's dot was.
class GoPostfixExpressionTest {
    @Test
    fun `identifier at statement start is the whole operand`() {
        assertEquals("err" to true, find(inFunc("err<caret>")))
    }

    @Test
    fun `selector chain with calls is one operand`() {
        assertEquals("a.b.c().d" to true, find(inFunc("a.b.c().d<caret>")))
    }

    @Test
    fun `method call is one operand`() {
        assertEquals("a.b()" to true, find(inFunc("a.b()<caret>")))
    }

    @Test
    fun `index expression is one operand`() {
        assertEquals("m[k]" to true, find(inFunc("m[k]<caret>")))
    }

    @Test
    fun `slice expression with colon inside brackets is one operand`() {
        assertEquals("s[1:n]" to true, find(inFunc("s[1:n]<caret>")))
    }

    @Test
    fun `chained calls are one operand`() {
        assertEquals("f(x)(y)" to true, find(inFunc("f(x)(y)<caret>")))
    }

    @Test
    fun `call arguments with nested parens and strings are skipped`() {
        assertEquals("fmt.Sprintf(\"(%d)\", g(1, 2))" to true, find(inFunc("fmt.Sprintf(\"(%d)\", g(1, 2))<caret>")))
    }

    @Test
    fun `composite literal includes its type`() {
        assertEquals("T{1, 2}" to true, find(inFunc("T{1, 2}<caret>")))
    }

    @Test
    fun `composite literal with qualified slice map and generic types`() {
        assertEquals("[]*pkg.T{}" to false, find(inFunc("x = []*pkg.T{}<caret>")))
        assertEquals("map[string]int{\"a\": 1}" to false, find(inFunc("x = map[string]int{\"a\": 1}<caret>")))
        assertEquals("List[int]{}" to false, find(inFunc("x = List[int]{}<caret>")))
    }

    @Test
    fun `address of composite literal keeps the ampersand`() {
        assertEquals("&T{a: 1}" to true, find(inFunc("&T{a: 1}<caret>")))
    }

    @Test
    fun `interpreted string literal is an operand`() {
        assertEquals("\"s\"" to true, find(inFunc("\"s\"<caret>")))
    }

    @Test
    fun `raw string containing dots is one operand`() {
        assertEquals("`a.b.c`" to true, find(inFunc("`a.b.c`<caret>")))
    }

    @Test
    fun `number literal with a decimal point is one operand`() {
        assertEquals("1.5" to true, find(inFunc("1.5<caret>")))
    }

    @Test
    fun `unary not belongs to the operand`() {
        assertEquals("!ok" to true, find(inFunc("!ok<caret>")))
    }

    @Test
    fun `stacked unary operators belong to the operand`() {
        assertEquals("!*p" to true, find(inFunc("!*p<caret>")))
    }

    @Test
    fun `receive operator belongs to the operand`() {
        assertEquals("<-ch" to true, find(inFunc("<-ch<caret>")))
    }

    @Test
    fun `binary operator stops the operand`() {
        assertEquals("y" to false, find(inFunc("x + y<caret>")))
    }

    @Test
    fun `binary minus is not taken as unary`() {
        assertEquals("y" to false, find(inFunc("x - y<caret>")))
    }

    @Test
    fun `binary star is not taken as dereference`() {
        assertEquals("b" to false, find(inFunc("a * b<caret>")))
    }

    @Test
    fun `short variable declaration stops at the right side`() {
        assertEquals("b" to false, find(inFunc("a := b<caret>")))
    }

    @Test
    fun `assignment stops at the right side`() {
        assertEquals("f()" to false, find(inFunc("a = f()<caret>")))
    }

    @Test
    fun `parenthesized expression with method call is one operand`() {
        assertEquals("(a + b).String()" to true, find(inFunc("(a + b).String()<caret>")))
    }

    @Test
    fun `parenthesized expression is one operand`() {
        assertEquals("(a + b)" to true, find(inFunc("(a + b)<caret>")))
    }

    @Test
    fun `type assertion continues the operand`() {
        assertEquals("x.(error)" to true, find(inFunc("x.(error)<caret>")))
    }

    @Test
    fun `immediately called function literal is one operand`() {
        assertEquals("func() {}()" to true, find(inFunc("func() {}()<caret>")))
    }

    @Test
    fun `called function literal with a result type is one operand`() {
        assertEquals("func() int { return 1 }()" to true, find(inFunc("func() int { return 1 }()<caret>")))
    }

    @Test
    fun `composite literal on a line after an index expression does not take its brackets`() {
        assertEquals("T{}" to true, find(inFunc("x := a[0]\n\tT{}<caret>")))
    }

    @Test
    fun `conversion to a slice map or array type includes the type prefix`() {
        assertEquals("[]byte(s)" to true, find(inFunc("[]byte(s)<caret>")))
        assertEquals("[]*pkg.T(x)" to false, find(inFunc("y = []*pkg.T(x)<caret>")))
        assertEquals("map[string]int(m)" to false, find(inFunc("y = map[string]int(m)<caret>")))
        assertEquals("[4]int(a)" to false, find(inFunc("y = [4]int(a)<caret>")))
    }

    @Test
    fun `name on a line after an index expression does not take its brackets`() {
        assertEquals("b" to true, find(inFunc("x := a[0]\n\tb<caret>")))
    }

    @Test
    fun `call with a unary prefix is not a plain call`() {
        assertFalse(operand(inFunc("!f()<caret>"))!!.isCall)
        assertFalse(operand(inFunc("<-f()<caret>"))!!.isCall)
    }

    @Test
    fun `selector continued after a dot on the previous line is one operand`() {
        assertEquals("a.\n\t\tb" to true, find(inFunc("a.\n\t\tb<caret>")))
    }

    @Test
    fun `parenthesized operand on a new line is not a call of the previous line`() {
        assertEquals("(x)" to true, find(inFunc("f()\n\t(x)<caret>")))
    }

    @Test
    fun `call argument is an operand but not a statement`() {
        assertEquals("x" to false, find(inFunc("f(a, x<caret>)")))
    }

    @Test
    fun `argument on its own line inside a call is not a statement`() {
        assertEquals("x" to false, find(inFunc("f(\n\t\tx<caret>\n\t)")))
    }

    @Test
    fun `operand inside a composite literal is not a statement`() {
        assertEquals("x" to false, find(inFunc("v := []int{\n\t\tx<caret>\n\t}")))
    }

    @Test
    fun `operand after label colon or semicolon is a statement`() {
        assertEquals("x" to true, find(inFunc("a(); x<caret>")))
        assertEquals("x" to true, find(inFunc("switch v {\n\tcase 1:\n\t\tx<caret>\n\t}")))
    }

    @Test
    fun `operand on a continuation line after a binary operator is not a statement`() {
        assertEquals("b" to false, find(inFunc("x := a +\n\t\tb<caret>")))
    }

    @Test
    fun `operand on the line after a return keyword starts a new statement`() {
        assertEquals("x" to true, find(inFunc("return\n\tx<caret>")))
    }

    @Test
    fun `operand in if else for and closure blocks is a statement`() {
        assertEquals("x" to true, find(inFunc("if a {\n\t\tx<caret>\n\t}")))
        assertEquals("x" to true, find(inFunc("if a {\n\t} else {\n\t\tx<caret>\n\t}")))
        assertEquals("x" to true, find(inFunc("for _, v := range []int{1} {\n\t\tx<caret>\n\t}")))
        assertEquals("x" to true, find(inFunc("go func() {\n\t\tx<caret>\n\t}()")))
        assertEquals("x" to true, find(inFunc("h := func(n int) error {\n\t\tx<caret>\n\t}")))
    }

    @Test
    fun `operand in a bare block nested in a function is a statement`() {
        assertEquals("x" to true, find(inFunc("{\n\t\tx<caret>\n\t}")))
    }

    @Test
    fun `method with receiver and multi-line signature has a statement body`() {
        val text = "package main\n\nfunc (s *S) Do(\n\ta int,\n) (int, error) {\n\tx<caret>\n}\n"
        assertEquals("x" to true, find(text))
    }

    @Test
    fun `operand at top level is rejected`() {
        assertNull(find("package main\n\nvar v = x<caret>\n"))
    }

    @Test
    fun `operand in a struct type is rejected`() {
        assertNull(find("package main\n\ntype T struct {\n\tx<caret>\n}\n"))
    }

    @Test
    fun `operand after a closed function is rejected`() {
        assertNull(find("package main\n\nfunc f() {\n}\n\nx<caret>\n"))
    }

    @Test
    fun `caret inside a string is rejected`() {
        assertNull(find(inFunc("s := \"a<caret>b\"")))
    }

    @Test
    fun `caret inside a raw string is rejected`() {
        assertNull(find(inFunc("s := `a<caret>b`")))
    }

    @Test
    fun `caret after a comment is rejected`() {
        assertNull(find(inFunc("/* c */<caret>")))
        assertNull(find(inFunc("x // c<caret>")))
    }

    @Test
    fun `caret after whitespace is rejected`() {
        assertNull(find(inFunc("x <caret>")))
    }

    @Test
    fun `keyword as operand is rejected`() {
        assertNull(find(inFunc("return<caret>")))
    }

    @Test
    fun `unbalanced closer is rejected`() {
        assertNull(find(inFunc("x)<caret>")))
        assertNull(find(inFunc("x[1)<caret>")))
    }

    @Test
    fun `call of a selector is a call while paren expression and conversion-like assertion are not`() {
        assertTrue(operand(inFunc("a.b(1)<caret>"))!!.isCall)
        assertFalse(operand(inFunc("(a)<caret>"))!!.isCall)
        assertFalse(operand(inFunc("x.(T)<caret>"))!!.isCall)
        assertFalse(operand(inFunc("x<caret>"))!!.isCall)
    }

    @Test
    fun `offset at the start of the text is rejected`() {
        assertNull(GoPostfixExpression.find("x", 0, emptySequence()))
    }

    private fun inFunc(body: String) = "package main\n\nfunc f() {\n\t$body\n}\n"

    private fun find(textWithCaret: String): Pair<String, Boolean>? {
        val text = textWithCaret.replace("<caret>", "")
        val operand = operand(textWithCaret) ?: return null
        return text.substring(operand.start, operand.end) to operand.isStatement
    }

    private fun operand(textWithCaret: String): GoPostfixOperand? {
        val offset = textWithCaret.indexOf("<caret>")
        val text = textWithCaret.replace("<caret>", "")
        val tokens = lex(text)
        return GoPostfixExpression.find(text, offset, tokens.asReversed().asSequence())
    }

    private fun lex(text: String): List<GoPostfixToken> {
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        val result = ArrayList<GoPostfixToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += GoPostfixToken(postfixKind(type), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }
}
