package io.github.xefise.golsp.lsp.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class GoplsSettingsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `defaults are nested under the gopls section`() {
        assertEquals(
            json("""{"gopls": {"semanticTokens": true, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false}}}"""),
            GoplsSettings.build(null),
        )
    }

    @Test
    fun `user values override defaults and nested objects merge`() {
        val user = json("""{"gopls": {"semanticTokens": false, "codelenses": {"tidy": false}, "staticcheck": true}}""")
        assertEquals(
            json("""{"gopls": {"semanticTokens": false, "directoryFilters": ["-**/node_modules"], "codelenses": {"test": false, "tidy": false}, "staticcheck": true}}"""),
            GoplsSettings.build(user),
        )
    }

    @Test
    fun `user arrays replace default arrays instead of appending`() {
        val user = json("""{"gopls": {"directoryFilters": ["-vendor"]}}""")
        assertEquals(JsonParser.parseString("""["-vendor"]"""), GoplsSettings.build(user).getAsJsonObject("gopls").get("directoryFilters"))
    }

    @Test
    fun `user scalar replacing an object wins`() {
        val user = json("""{"gopls": {"codelenses": null}}""")
        assertEquals(true, GoplsSettings.build(user).getAsJsonObject("gopls").get("codelenses").isJsonNull)
    }

    @Test
    fun `merge does not mutate its inputs`() {
        val base = json("""{"a": {"b": 1}}""")
        GoplsSettings.deepMerge(base, json("""{"a": {"c": 2}}"""))
        assertEquals(json("""{"a": {"b": 1}}"""), base)
    }
}
