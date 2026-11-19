package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoplsAnalyzersTest {
    private val projectDir = File(System.getProperty("golsp.projectDir") ?: ".")

    private fun analyzer(doc: String, url: String = "") = GoplsAnalyzer("x", doc, url, true)

    private fun body(html: String) = html.removePrefix("<html><body>").removeSuffix("</body></html>")

    @Test
    fun `catalog parses analyzers and the gopls version`() {
        val catalog = GoplsAnalyzerCatalog.parse(
            """{"goplsVersion": "v0.24.0", "analyzers": [
                {"name": "SA4006", "doc": "Dead store", "url": "https://staticcheck.dev/docs/checks/#SA4006", "default": false},
                {"name": "printf", "doc": "check printf", "url": "https://pkg.go.dev/x", "default": true}]}""",
        )
        assertEquals("v0.24.0", catalog.goplsVersion)
        assertEquals(
            listOf(
                GoplsAnalyzer("SA4006", "Dead store", "https://staticcheck.dev/docs/checks/#SA4006", false),
                GoplsAnalyzer("printf", "check printf", "https://pkg.go.dev/x", true),
            ),
            catalog.analyzers,
        )
    }

    @Test
    fun `catalog treats missing default url and doc as off and empty`() {
        val catalog = GoplsAnalyzerCatalog.parse("""{"goplsVersion": "v1", "analyzers": [{"name": "a", "url": null}]}""")
        assertEquals(GoplsAnalyzer("a", "", "", false), catalog.analyzers.single())
    }

    @Test
    fun `catalog lookup by diagnostic source is exact and null-safe`() {
        val catalog = GoplsAnalyzerCatalog("v1", listOf(GoplsAnalyzer("SA4006", "", "", false)))
        assertEquals("SA4006", catalog["SA4006"]?.name)
        assertNull(catalog["sa4006"])
        assertNull(catalog["compiler"])
        assertNull(catalog[null])
    }

    @Test
    fun `analyses map has an explicit value for every analyzer`() {
        val catalog = GoplsAnalyzerCatalog("v1", listOf("SA4006", "printf", "shadow").map { GoplsAnalyzer(it, "", "", false) })
        val enabled = setOf("SA4006", "shadow")
        assertEquals(
            mapOf("SA4006" to true, "printf" to false, "shadow" to true),
            GoplsAnalyses.map(catalog) { it.name in enabled },
        )
    }

    @Test
    fun `analyses map is empty for an empty catalog`() {
        assertEquals(emptyMap<String, Boolean>(), GoplsAnalyses.map(GoplsAnalyzerCatalog("", emptyList())) { true })
    }

    @Test
    fun `doc paragraphs are joined lines and end with the url link`() {
        assertEquals(
            "<p>check things</p><p>first line second line</p><p><a href=\"https://u.dev/a\">https://u.dev/a</a></p>",
            body(GoplsAnalyzerDocs.html(analyzer("check things\n\nfirst line\nsecond line", "https://u.dev/a"))),
        )
    }

    @Test
    fun `tab indented code with an inner blank line is one escaped pre block`() {
        assertEquals(
            "<p>For example:</p><pre>func f() {\n\tx := a &lt; b\n\n\treturn\n}</pre><p>after</p>",
            body(GoplsAnalyzerDocs.html(analyzer("For example:\n\n\tfunc f() {\n\t\tx := a < b\n\n\t\treturn\n\t}\n\nafter"))),
        )
    }

    @Test
    fun `four space indented staticcheck code becomes pre`() {
        assertEquals(
            "<p>Before:</p><pre>mu.Lock()\ndefer mu.Lock()</pre>",
            body(GoplsAnalyzerDocs.html(analyzer("Before:\n\n    mu.Lock()\n    defer mu.Lock()\n"))),
        )
    }

    @Test
    fun `indented value right under a label stays in the paragraph`() {
        assertEquals(
            "<p>Available since<br>2019.1, non-default</p>",
            body(GoplsAnalyzerDocs.html(analyzer("Available since\n    2019.1, non-default\n"))),
        )
    }

    @Test
    fun `dash lines become a list with linked urls`() {
        assertEquals(
            "<ul><li><a href=\"https://go.dev/doc/a\">https://go.dev/doc/a</a></li><li>plain</li></ul>",
            body(GoplsAnalyzerDocs.html(analyzer("- https://go.dev/doc/a\n- plain"))),
        )
    }

    @Test
    fun `trailing sentence punctuation is not part of a link`() {
        assertEquals(
            "<p>See <a href=\"https://go.dev/x\">https://go.dev/x</a>.</p>",
            body(GoplsAnalyzerDocs.html(analyzer("See https://go.dev/x."))),
        )
    }

    @Test
    fun `markup characters in text are escaped`() {
        assertEquals("<p>replace interface{} with &lt;any&gt; &amp; &quot;x&quot;</p>", body(GoplsAnalyzerDocs.html(analyzer("replace interface{} with <any> & \"x\""))))
    }

    @Test
    fun `empty doc and url give an empty body`() {
        assertEquals("", body(GoplsAnalyzerDocs.html(analyzer(""))))
    }

    // Catches a hand edit or a partial regeneration: every bundled analyzer needs exactly one registered inspection.
    @Test
    fun `generated inspections match the bundled catalog`() {
        val catalog = GoplsAnalyzerCatalog.parse(File(projectDir, "src/main/resources/golsp/gopls-analyzers.json").readText())
        val xml = File(projectDir, "src/main/resources/META-INF/golsp-gopls-analyzers.xml").readText()
        val registered = Regex("""shortName="Gopls_([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(catalog.analyzers.isNotEmpty())
        assertEquals(catalog.analyzers.map { it.name }, registered)
        assertTrue("generated from another gopls version", xml.contains("gopls ${catalog.goplsVersion} "))
    }

    @Test
    fun `our staticcheck additions are on by default in the generated inspections`() {
        val xml = File(projectDir, "src/main/resources/META-INF/golsp-gopls-analyzers.xml").readText()
        for (name in listOf("SA2003", "SA4006", "SA4017")) {
            assertTrue(name, Regex("""shortName="Gopls_$name"[^/]*enabledByDefault="true"""").containsMatchIn(xml))
        }
    }
}
