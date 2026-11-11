package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoCodeActionCatalogTest {
    @Test
    fun `extract variable needs a selection and belongs to extract`() {
        assertEquals(
            GoCodeActionKind("refactor.extract.variable", GoCodeActionGroup.EXTRACT, "Introduce Variable", needsSelection = true),
            GoCodeActionCatalog.find("refactor.extract.variable"),
        )
    }

    @Test
    fun `inline call works from the caret`() {
        assertEquals(false, GoCodeActionCatalog.find("refactor.inline.call")?.needsSelection)
    }

    @Test
    fun `every catalog entry is found by its own kind`() {
        GoCodeActionCatalog.KINDS.forEach { assertEquals(it.kind, it, GoCodeActionCatalog.find(it.kind)) }
    }

    @Test
    fun `source test and organize imports are excluded`() {
        assertNull(GoCodeActionCatalog.find("source.test"))
        assertNull(GoCodeActionCatalog.find("source.organizeImports"))
    }

    @Test
    fun `move kinds are excluded through their parent`() {
        assertNull(GoCodeActionCatalog.find("refactor.move.moveType"))
        assertNull(GoCodeActionCatalog.find("refactor.move.moveDeclaration"))
    }

    @Test
    fun `implement interface is excluded`() {
        assertNull(GoCodeActionCatalog.find("refactor.rewrite.implementInterface"))
    }

    @Test
    fun `unknown rewrite kind gets a generic entry titled by its last segment`() {
        assertEquals(
            GoCodeActionKind("refactor.rewrite.somethingNew", GoCodeActionGroup.REWRITE, "somethingNew", needsSelection = false),
            GoCodeActionCatalog.find("refactor.rewrite.somethingNew"),
        )
    }

    @Test
    fun `unknown extract kind lands in extract`() {
        assertEquals(GoCodeActionGroup.EXTRACT, GoCodeActionCatalog.find("refactor.extract.somethingNew")?.group)
    }

    @Test
    fun `quickfix, gopls private kinds and missing kinds are not catalog actions`() {
        assertNull(GoCodeActionCatalog.find("quickfix"))
        assertNull(GoCodeActionCatalog.find("gopls.doc.features"))
        assertNull(GoCodeActionCatalog.find(""))
        assertNull(GoCodeActionCatalog.find(null))
    }

    @Test
    fun `a kind covers itself and dotted children only`() {
        assertTrue(GoCodeActionCatalog.covers("refactor.extract", "refactor.extract"))
        assertTrue(GoCodeActionCatalog.covers("refactor.extract", "refactor.extract.function"))
        assertFalse(GoCodeActionCatalog.covers("refactor.extract", "refactor.extractX"))
        assertFalse(GoCodeActionCatalog.covers("refactor.extract.function", "refactor.extract"))
    }

    @Test
    fun `source test is not requested even when source is`() {
        assertFalse(GoCodeActionCatalog.isRequested("source.test", listOf("source")))
        assertTrue(GoCodeActionCatalog.isRequested("source.addTest", listOf("source")))
    }

    @Test
    fun `an action of another kind than requested is dropped`() {
        assertFalse(GoCodeActionCatalog.isRequested("refactor.extract.function", listOf("refactor.extract.variable")))
        assertTrue(GoCodeActionCatalog.isRequested("refactor.extract.variable", listOf("refactor")))
        assertFalse(GoCodeActionCatalog.isRequested(null, listOf("refactor")))
    }

    @Test
    fun `order follows the catalog and puts unknown kinds last`() {
        val kinds = listOf("refactor.rewrite.somethingNew", "source.addTest", "refactor.inline.call", "refactor.extract.function")
        assertEquals(
            listOf("refactor.extract.function", "refactor.inline.call", "source.addTest", "refactor.rewrite.somethingNew"),
            kinds.sortedBy(GoCodeActionCatalog::order),
        )
    }
}
