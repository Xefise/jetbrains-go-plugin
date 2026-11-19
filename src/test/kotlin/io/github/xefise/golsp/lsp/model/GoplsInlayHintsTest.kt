package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoplsInlayHintsTest {
    private val allOff = GoplsInlayHints.ALL.associate { it.name to false }

    @Test
    fun `untouched settings give the defaults with an explicit value for every hint`() {
        assertEquals(
            allOff + mapOf("assignVariableTypes" to true, "rangeVariableTypes" to true),
            GoplsInlayHints.map({ null }) { _, _ -> null },
        )
        assertEquals(GoplsInlayHints.map({ null }) { _, _ -> null }, GoplsInlayHints.DEFAULTS)
    }

    @Test
    fun `hint names are the eight gopls hints and unique`() {
        assertEquals(
            setOf(
                "assignVariableTypes", "rangeVariableTypes", "compositeLiteralTypes", "functionTypeParameters",
                "parameterNames", "compositeLiteralFields", "constantValues", "ignoredError",
            ),
            GoplsInlayHints.ALL.map { it.name }.toSet(),
        )
        assertEquals(GoplsInlayHints.ALL.size, GoplsInlayHints.ALL.map { it.name }.distinct().size)
    }

    @Test
    fun `disabled provider turns off all its options even when they are enabled`() {
        val hints = GoplsInlayHints.map({ if (it == GoplsInlayHints.TYPES_PROVIDER) false else null }) { _, _ -> true }
        GoplsInlayHints.ALL.forEach { hint ->
            assertEquals(hint.name, hint.providerId != GoplsInlayHints.TYPES_PROVIDER, hints.getValue(hint.name))
        }
    }

    @Test
    fun `option values override defaults per provider and option`() {
        val options = mapOf(
            (GoplsInlayHints.TYPES_PROVIDER to "assignVariableTypes") to false,
            (GoplsInlayHints.PARAMETERS_PROVIDER to "parameterNames") to true,
            // Same option id under a foreign provider must not leak.
            (GoplsInlayHints.VALUES_PROVIDER to "compositeLiteralFields") to true,
        )
        val hints = GoplsInlayHints.map({ true }) { hint, provider -> options[provider to hint] }
        assertEquals(allOff + mapOf("rangeVariableTypes" to true, "parameterNames" to true), hints)
    }

    @Test
    fun `enabled provider with an untouched option keeps the option default`() {
        val hints = GoplsInlayHints.map({ true }) { _, _ -> null }
        assertEquals(GoplsInlayHints.DEFAULTS, hints)
    }
}
