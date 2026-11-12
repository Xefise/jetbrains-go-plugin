package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoplsSnippetsTest {
    private fun names(snippet: String) = GoplsSnippets.argumentNamesOnly(snippet)

    @Test
    fun `named parameters keep only the name`() {
        assertEquals("Reserve(\${1:sku}, \${2:quantity})", names("Reserve(\${1:sku string}, \${2:quantity int})"))
    }

    @Test
    fun `variadic, pointer, func and qualified types are dropped`() {
        assertEquals("Printf(\${1:format}, \${2:a})", names("Printf(\${1:format string}, \${2:a ...any})"))
        assertEquals("Run(\${1:name}, \${2:f})", names("Run(\${1:name string}, \${2:f func(t *testing.T)})"))
        assertEquals("Do(\${1:w}, \${2:r})", names("Do(\${1:w http.ResponseWriter}, \${2:r *http.Request})"))
        assertEquals("append(\${1:slice}, \${2:elems})", names("append(\${1:slice []Type}, \${2:elems ...Type})"))
        assertEquals("New[\${1:T}]()", names("New[\${1:T interface\\{~int | ~string\\}}]()"))
    }

    @Test
    fun `escaped braces inside the type do not end the placeholder`() {
        assertEquals("Set(\${1:m})\$0", names("Set(\${1:m map[string]struct\\{\\}})\$0"))
    }

    @Test
    fun `unicode parameter names survive`() {
        assertEquals("F(\${1:größe})", names("F(\${1:größe int})"))
    }

    @Test
    fun `type-only placeholders are left alone`() {
        listOf(
            "F(\${1:int}, \${2:[]string})",
            "F(\${1:chan int})",
            "F(\${1:func(int) error})",
            "F(\${1:<-chan int})",
            "F(\${1:_ int})",
            "new(\${1:TypeOrExpr})",
            "Name: \${1:string}",
            "return \${1:0}, \${2:\"\"}, \${3:struct\\{ a int \\}\\{\\}}",
            "for \${1:i}, \${2:v} := range x {\n\t\$0\n}",
            "\${3:\${2:err}} := f()",
            "F(\${1:struct\\{\\}})",
            "func(\${1:t} *testing.T) {\$0}",
        ).forEach { assertEquals(it, names(it)) }
    }

    @Test
    fun `tab stops, empty placeholders and plain text are untouched`() {
        listOf("Close()", "Close(\${1:})", "if err != nil {\n\treturn \$1\n}\$0", "cost \\\$5 \${1}", "").forEach {
            assertEquals(it, names(it))
        }
    }

    @Test
    fun `nested placeholders and unterminated input are kept verbatim`() {
        listOf("F(\${1:a \${2:int}})", "F(\${1:a int", "F(\${x:a int})").forEach { assertEquals(it, names(it)) }
    }
}
