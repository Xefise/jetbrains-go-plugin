package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoParameterLabelTest {
    @Test
    fun `named parameters split into name and type`() {
        assertEquals(GoParameterLabel("sku", "string"), GoParameterLabel.parse("sku string"))
        assertEquals(GoParameterLabel("invs", "map[string]int"), GoParameterLabel.parse("invs map[string]int"))
        assertEquals(GoParameterLabel("f", "func(t *testing.T)"), GoParameterLabel.parse("f func(t *testing.T)"))
        assertEquals(GoParameterLabel("a", "...any"), GoParameterLabel.parse("a ...any"))
        assertEquals(GoParameterLabel("_", "int"), GoParameterLabel.parse("_ int"))
        assertEquals(GoParameterLabel("größe", "int"), GoParameterLabel.parse("größe int"))
        assertEquals(GoParameterLabel("s", "struct{ a int }"), GoParameterLabel.parse("s struct{ a int }"))
    }

    @Test
    fun `unnamed parameters are all type`() {
        listOf("int", "[]string", "chan int", "<-chan int", "func(int) error", "map[string]int", "struct{}", "*testing.T", "").forEach {
            assertEquals(it, GoParameterLabel(null, it), GoParameterLabel.parse(it))
        }
    }
}
