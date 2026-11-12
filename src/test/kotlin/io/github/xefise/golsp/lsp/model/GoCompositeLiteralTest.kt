package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoCompositeLiteralTest {
    // `|` marks the caret.
    private fun site(source: String): GoCompositeLiteralSite? {
        val caret = source.indexOf('|')
        val text = source.removeRange(caret, caret + 1)
        return GoCompositeLiteral.find(goTokensBefore(text, caret), text)
    }

    @Test
    fun `empty literal of a local type`() {
        val text = "x := OrderLine{}"
        assertEquals(GoCompositeLiteralSite(14, "OrderLine", 5, qualified = false, element = 0, key = null), site("x := OrderLine{|}"))
        assertEquals('{', text[14])
    }

    @Test
    fun `type name position and qualifier`() {
        val site = site("_ = http.Server{|}")!!
        assertEquals("Server", site.typeName)
        assertEquals(9, site.typeNameStart)
        assertEquals(true, site.qualified)
    }

    @Test
    fun `generic type arguments are skipped to the type name`() {
        assertEquals("Pair", site("_ = Pair[string, int]{|}")?.typeName)
        assertEquals("Pair", site("_ = maps.Pair[K, V]{|}")?.typeName)
    }

    @Test
    fun `literals in expression positions`() {
        listOf(
            "return OrderLine{|}",
            "lines = append(lines, &OrderLine{|})",
            "w.Reserve(OrderLine{|})",
            "w.Reserve(\n\t\tctx,\n\t\tOrderLine{|})",
            "ch <- OrderLine{|}",
            "var line = OrderLine{|}",
            "if ok {\n\tline := OrderLine{|",
            "if ok {\n}\nline := OrderLine{|",
            "func f() OrderLine { return OrderLine{|} }",
            "x := Order{Line: OrderLine{|}}",
        ).forEach { assertEquals(it, "OrderLine", site(it)?.typeName) }
    }

    @Test
    fun `element index counts top-level commas only`() {
        assertEquals(1, site("OrderLine{SKU: \"a\", |}")?.element)
        assertEquals(2, site("OrderLine{\n\tSKU: f(a, b),\n\tQty: 1,\n\t|\n}")?.element)
        assertEquals(1, site("OrderLine{\"a, b\", |}")?.element)
    }

    @Test
    fun `key of the current element`() {
        assertEquals("Qty", site("OrderLine{SKU: \"a\", Qty: 1|}")?.key)
        assertEquals("Qty", site("OrderLine{SKU: \"a\", Qty: |}")?.key)
        assertEquals("SKU", site("OrderLine{SKU: items[1:2]|}")?.key)
        assertNull(site("OrderLine{SKU: \"a\", Q|}")!!.key)
        assertNull(site("Index{\"a\": |}")!!.key)
    }

    @Test
    fun `blocks and non-struct literals are not sites`() {
        listOf(
            "if ok {|",
            "if v, ok := m[k]; ok {|",
            "} else if ok {|",
            "} else {|",
            "for _, line := range lines {|",
            "for i := 0; i < n; i++ {|",
            "for {|",
            "switch kind {|",
            "switch v := x.(type) {|",
            "select {|",
            "func main() {|",
            "func (w *Warehouse) Lines() []OrderLine {|",
            "func load() Result {|",
            "go func() {|",
            "type OrderLine struct {|",
            "_ = []OrderLine{|",
            "_ = [3]OrderLine{|",
            "_ = map[string]OrderLine{|",
            "_ = []Pair[string, int]{|",
            "_ = map[string]int{|",
            "x := OrderLine{SKU: f(|)}",
            "x := OrderLine{SKU: items[|]}",
            "lines := []OrderLine{{|}}",
            "OrderLine\n{|",
            "x := OrderLine{}|",
        ).forEach { assertNull(it, site(it)) }
    }
}
