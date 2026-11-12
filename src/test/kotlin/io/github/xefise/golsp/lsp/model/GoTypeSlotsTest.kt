package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTypeSlotsTest {
    // `|` marks the start of gopls' edit range: the identifier being completed.
    private fun site(source: String): GoTypeSlotSite? {
        val rangeStart = source.indexOf('|')
        val text = source.removeRange(rangeStart, rangeStart + 1)
        return GoTypeSlots.find(goTokensBefore(text, rangeStart), text, rangeStart)
    }

    private fun slot(source: String) = site(source)?.slot

    @Test
    fun `unnamed parameters`() {
        listOf(
            "func NewX(|Ware) {}",
            "func NewX(ctx context.Context, |Ware",
            "func NewX(\n\tctx context.Context,\n\t|Ware",
            "func (w *Warehouse) Update(|Ord",
            "func Map[T any](xs []T, |Ware",
            "x := func(|Ware",
            "type Handler func(|Ware",
            "func main() {\n\tfunc(|Ware",
        ).forEach { assertEquals(it, GoTypeSlot.PARAMETER, slot(it)) }
    }

    @Test
    fun `receiver without a name`() {
        assertEquals(GoTypeSlot.RECEIVER, slot("func (|Ware) Reserve() {}"))
        assertEquals(GoTypeSlot.RECEIVER, slot("package main\n\nfunc (|Ware"))
    }

    @Test
    fun `parameter or receiver that already has a name`() {
        listOf("func NewX(w |Ware", "func NewX(a, b |Ware", "func (w |Ware) M()", "func NewX(ctx context.Context, w |Ware")
            .forEach { assertEquals(it, GoTypeSlot.NAMED, slot(it)) }
    }

    @Test
    fun `results`() {
        listOf(
            "func NewX() |Ware",
            "func NewX(sku string) (|Ware",
            "func NewX() (int, |Ware",
            "func (w *Warehouse) Find(sku string) |Ord",
            "func Map[T any](xs []T) |Ware",
            "x := func() |Ware",
        ).forEach { assertEquals(it, GoTypeSlot.RESULT, slot(it)) }
    }

    @Test
    fun `qualifier and typed star move the start back`() {
        assertEquals(GoTypeSlotSite(GoTypeSlot.PARAMETER, 10, pointerTyped = false), site("func NewX(http.|Cli"))
        assertEquals(GoTypeSlotSite(GoTypeSlot.PARAMETER, 10, pointerTyped = true), site("func NewX(*http.|Cli"))
        assertEquals(GoTypeSlotSite(GoTypeSlot.NAMED, 12, pointerTyped = true), site("func NewX(o *|Ord"))
    }

    @Test
    fun `not a signature type slot`() {
        listOf(
            "foo(|Ware",
            "x.Reserve(|Ware",
            "w := NewX(ctx, |Ware",
            "func (w *Warehouse) |Res",
            "func NewX(xs []|Ware",
            "func NewX(m map[string]|Ware",
            "func NewX(xs ...|Ware",
            "var x |Ware",
            "type Store interface {\n\tGet(|Ware",
            "func NewX() []|Ware",
        ).forEach { assertNull(it, site(it)) }
    }
}
