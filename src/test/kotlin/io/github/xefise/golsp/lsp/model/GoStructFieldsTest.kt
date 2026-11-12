package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoStructFieldsTest {
    // Captured from gopls v0.24.0 hover on `OrderLine` in `_ = OrderLine{}` (methods section shortened).
    private val orderLineHover = """
        |```go
        |type OrderLine struct {
        |	Base
        |	*http.Client
        |	SKU, Name string `json:"sku"` // the sku
        |	Qty       int
        |	Meta      struct {
        |		A int
        |	}
        |	f func(a, b int) (int, error)
        |}
        |```
        |
        |---
        |
        |OrderLine is a line.
        |
        |
        |```go
        |// Embedded fields:
        |ID            int                                                // through Base
        |Timeout       time.Duration                                      // through Client
        |```
        |
        |```go
        |func (c *http.Client) Do(req *http.Request) (*http.Response, error)
        |```
        |""".trimMargin()

    @Test
    fun `fields of a struct in declaration order`() {
        assertEquals(
            listOf(
                GoStructField("Base", "Base", embedded = true),
                GoStructField("Client", "*http.Client", embedded = true),
                GoStructField("SKU", "string", embedded = false),
                GoStructField("Name", "string", embedded = false),
                GoStructField("Qty", "int", embedded = false),
                GoStructField("Meta", "struct{…}", embedded = false),
                GoStructField("f", "func(a, b int) (int, error)", embedded = false),
            ),
            GoStructFields.parse(orderLineHover),
        )
    }

    @Test
    fun `labels show embedded fields as their type`() {
        assertEquals(
            listOf("Base", "*http.Client", "SKU string", "Name string", "Qty int", "Meta struct{…}", "f func(a, b int) (int, error)"),
            GoStructFields.parse(orderLineHover)!!.map { it.label },
        )
    }

    @Test
    fun `generic struct, comments, blank lines and inline struct types`() {
        val hover = "```go\ntype Pair[K comparable, V any] struct {\n\t// Key is the key.\n\tKey K\n\n" +
            "\tseen map[*K]struct{} // set\n\tPair[string, int]\n}\n```"
        assertEquals(
            listOf(
                GoStructField("Key", "K", embedded = false),
                GoStructField("seen", "map[*K]struct{}", embedded = false),
                GoStructField("Pair", "Pair[string, int]", embedded = true),
            ),
            GoStructFields.parse(hover),
        )
    }

    @Test
    fun `empty struct has no fields`() {
        assertEquals(emptyList<GoStructField>(), GoStructFields.parse("```go\ntype Empty struct{}\n```"))
    }

    @Test
    fun `hovers that are not struct declarations`() {
        listOf(
            "```go\ntype Lines []OrderLine\n```",
            "```go\ntype Shape interface {\n\tArea() float64\n}\n```",
            "```go\nvar ok bool\n```",
            "```go\ntype int int\n```",
            "no code block",
            "```go\ntype OrderLine struct {\n\tSKU string",
        ).forEach { assertNull(it, GoStructFields.parse(it)) }
    }

    @Test
    fun `exported fields`() {
        assertEquals(
            listOf("Base", "Client", "SKU", "Name", "Qty", "Meta"),
            GoStructFields.parse(orderLineHover)!!.filter { it.exported }.map { it.name },
        )
    }

    private fun site(element: Int, key: String?) = GoCompositeLiteralSite(0, "OrderLine", 0, qualified = false, element, key)

    @Test
    fun `active field by key, else by position`() {
        val fields = GoStructFields.parse(orderLineHover)!!
        assertEquals(4, GoStructFields.activeIndex(fields, site(0, "Qty")))
        assertEquals(1, GoStructFields.activeIndex(fields, site(0, "Client")))
        assertNull(GoStructFields.activeIndex(fields, site(0, "Missing")))
        assertEquals(6, GoStructFields.activeIndex(fields, site(6, null)))
        assertNull(GoStructFields.activeIndex(fields, site(7, null)))
    }
}
