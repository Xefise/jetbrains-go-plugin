package io.github.xefise.golsp.lsp.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class GoplsSettingsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private val DEFAULT_HINTS = """{"assignVariableTypes": true, "rangeVariableTypes": true, "compositeLiteralTypes": false, "functionTypeParameters": false, "parameterNames": false, "compositeLiteralFields": false, "constantValues": false, "ignoredError": false}"""

    @Test
    fun `defaults are nested under the gopls section`() {
        assertEquals(
            json("""{"gopls": {"semanticTokens": true, "usePlaceholders": true, "hints": $DEFAULT_HINTS, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false}, "newGoFileHeader": false}}"""),
            GoplsSettings.build(null, usePlaceholders = true),
        )
    }

    @Test
    fun `user values override defaults and nested objects merge`() {
        val user = json("""{"gopls": {"semanticTokens": false, "codelenses": {"tidy": false}, "staticcheck": true}}""")
        assertEquals(
            json("""{"gopls": {"semanticTokens": false, "usePlaceholders": true, "hints": $DEFAULT_HINTS, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false, "tidy": false}, "newGoFileHeader": false, "staticcheck": true}}"""),
            GoplsSettings.build(user, usePlaceholders = true),
        )
    }

    @Test
    fun `user arrays replace default arrays instead of appending`() {
        val user = json("""{"gopls": {"directoryFilters": ["-vendor"]}}""")
        assertEquals(JsonParser.parseString("""["-vendor"]"""), GoplsSettings.build(user, usePlaceholders = true).getAsJsonObject("gopls").get("directoryFilters"))
    }

    @Test
    fun `user scalar replacing an object wins`() {
        val user = json("""{"gopls": {"codelenses": null}}""")
        assertEquals(true, GoplsSettings.build(user, usePlaceholders = true).getAsJsonObject("gopls").get("codelenses").isJsonNull)
    }

    @Test
    fun `analyses are sent as given including explicit false`() {
        val analyses = linkedMapOf("SA4006" to true, "printf" to false)
        assertEquals(
            json("""{"SA4006": true, "printf": false}"""),
            GoplsSettings.build(null, usePlaceholders = false, analyses = analyses).getAsJsonObject("gopls").get("analyses"),
        )
    }

    @Test
    fun `no analyses key when the map is empty`() {
        assertEquals(false, GoplsSettings.build(null, usePlaceholders = false).getAsJsonObject("gopls").has("analyses"))
    }

    @Test
    fun `staticcheck key is never sent by default`() {
        val settings = GoplsSettings.build(null, usePlaceholders = false, analyses = mapOf("SA4006" to true))
        assertEquals(false, settings.getAsJsonObject("gopls").has("staticcheck"))
    }

    @Test
    fun `user analyses override profile analyses per key`() {
        val user = json("""{"gopls": {"analyses": {"SA2003": false, "unusedparams": true}}}""")
        val profile = mapOf("SA2003" to true, "SA4006" to true, "unusedparams" to false, "printf" to false)
        assertEquals(
            json("""{"SA2003": false, "SA4006": true, "unusedparams": true, "printf": false}"""),
            GoplsSettings.build(user, usePlaceholders = false, analyses = profile).getAsJsonObject("gopls").get("analyses"),
        )
    }

    @Test
    fun `hints are sent as given and user hints override them per key`() {
        val user = json("""{"gopls": {"hints": {"parameterNames": true, "assignVariableTypes": false}}}""")
        val hints = GoplsInlayHints.DEFAULTS + ("constantValues" to true)
        assertEquals(
            json("""{"assignVariableTypes": false, "rangeVariableTypes": true, "compositeLiteralTypes": false, "functionTypeParameters": false, "parameterNames": true, "compositeLiteralFields": false, "constantValues": true, "ignoredError": false}"""),
            GoplsSettings.build(user, usePlaceholders = false, hints = hints).getAsJsonObject("gopls").get("hints"),
        )
    }

    @Test
    fun `placeholders option off is sent as false`() {
        assertEquals(false, GoplsSettings.build(null, usePlaceholders = false).getAsJsonObject("gopls").get("usePlaceholders").asBoolean)
    }

    @Test
    fun `user json placeholders win over the option`() {
        val user = json("""{"gopls": {"usePlaceholders": false}}""")
        assertEquals(false, GoplsSettings.build(user, usePlaceholders = true).getAsJsonObject("gopls").get("usePlaceholders").asBoolean)
    }

    @Test
    fun `merge does not mutate its inputs`() {
        val base = json("""{"a": {"b": 1}}""")
        GoplsSettings.deepMerge(base, json("""{"a": {"c": 2}}"""))
        assertEquals(json("""{"a": {"b": 1}}"""), base)
    }
}
