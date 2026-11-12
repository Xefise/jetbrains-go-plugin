package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTypeCompletionTest {
    private fun insert(slot: GoTypeSlot, type: String, isStruct: Boolean, pointerTyped: Boolean = false) =
        GoTypeCompletion.insertText(GoTypeSlotSite(slot, 0, pointerTyped), type, isStruct)

    @Test
    fun `parameter gets an editable name with the full one first`() {
        assertEquals("\${1|warehouse,w|} *Warehouse\$0", insert(GoTypeSlot.PARAMETER, "Warehouse", isStruct = true))
        assertEquals("\${1|store,s|} Store\$0", insert(GoTypeSlot.PARAMETER, "Store", isStruct = false))
        assertEquals("\${1|client,c|} *http.Client\$0", insert(GoTypeSlot.PARAMETER, "http.Client", isStruct = true))
    }

    @Test
    fun `receiver gets the short name first`() {
        assertEquals("\${1|w,warehouse|} *Warehouse\$0", insert(GoTypeSlot.RECEIVER, "Warehouse", isStruct = true))
    }

    @Test
    fun `named parameters and results get the type only`() {
        assertEquals("*Warehouse", insert(GoTypeSlot.RESULT, "Warehouse", isStruct = true))
        assertEquals("*Order", insert(GoTypeSlot.NAMED, "Order", isStruct = true))
        assertEquals("io.Reader", insert(GoTypeSlot.RESULT, "io.Reader", isStruct = false))
    }

    @Test
    fun `typed star is kept for any type`() {
        assertEquals("*Store", insert(GoTypeSlot.NAMED, "Store", isStruct = false, pointerTyped = true))
    }

    @Test
    fun `value structs and idiomatic names`() {
        assertEquals("\${1:t} time.Time\$0", insert(GoTypeSlot.PARAMETER, "time.Time", isStruct = true))
        assertEquals("\${1:ctx} context.Context\$0", insert(GoTypeSlot.PARAMETER, "context.Context", isStruct = false))
    }

    @Test
    fun `predeclared types are left alone`() {
        listOf("string", "error", "any", "int").forEach { assertNull(it, insert(GoTypeSlot.PARAMETER, it, isStruct = false)) }
    }

    @Test
    fun `name candidates`() {
        assertEquals(listOf("warehouse", "w"), GoTypeCompletion.names("Warehouse"))
        assertEquals(listOf("httpClient", "h"), GoTypeCompletion.names("HTTPClient"))
        assertEquals(listOf("url", "u"), GoTypeCompletion.names("URL"))
        assertEquals(listOf("t"), GoTypeCompletion.names("T"))
        // `type` is a keyword, `order` would shadow the type, `time` the package.
        assertEquals(listOf("t"), GoTypeCompletion.names("Type"))
        assertEquals(listOf("o"), GoTypeCompletion.names("order"))
        assertEquals(listOf("t"), GoTypeCompletion.names("time.Time"))
    }
}
