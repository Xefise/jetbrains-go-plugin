package io.github.xefise.golsp.lsp.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class GoplsSettingsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `defaults are nested under the gopls section`() {
        assertEquals(
            json("""{"gopls": {"semanticTokens": true, "usePlaceholders": true, "hints": {"assignVariableTypes": true, "rangeVariableTypes": true}, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false}, "newGoFileHeader": false, "analyses": {"SA2003": true}}}"""),
            GoplsSettings.build(null, usePlaceholders = true),
        )
    }

    @Test
    fun `user values override defaults and nested objects merge`() {
        val user = json("""{"gopls": {"semanticTokens": false, "codelenses": {"tidy": false}, "staticcheck": true}}""")
        assertEquals(
            json("""{"gopls": {"semanticTokens": false, "usePlaceholders": true, "hints": {"assignVariableTypes": true, "rangeVariableTypes": true}, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false, "tidy": false}, "newGoFileHeader": false, "analyses": {"SA2003": true}, "staticcheck": true}}"""),
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
    fun `staticcheck key is absent unless all checks are enabled`() {
        assertEquals(false, GoplsSettings.build(null, usePlaceholders = false).getAsJsonObject("gopls").has("staticcheck"))
        assertEquals(true, GoplsSettings.build(null, usePlaceholders = false, allStaticcheck = true).getAsJsonObject("gopls").get("staticcheck").asBoolean)
    }

    @Test
    fun `user analyses merge with the default and can turn it off`() {
        val user = json("""{"gopls": {"analyses": {"SA2003": false, "unusedparams": true}}}""")
        assertEquals(
            json("""{"SA2003": false, "unusedparams": true}"""),
            GoplsSettings.build(user, usePlaceholders = false).getAsJsonObject("gopls").get("analyses"),
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
