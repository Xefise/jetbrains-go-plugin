package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoplsSemanticTokensTest {
    @Test
    fun `declared modifiers keep their order and gopls ones are appended once`() {
        val merged = GoplsSemanticTokens.withExtraModifiers(listOf("declaration", "definition", "readonly", "static"))
        assertEquals(listOf("declaration", "definition", "readonly", "static"), merged.take(4))
        assertEquals(merged.distinct(), merged)
        assertTrue(merged.containsAll(listOf("defaultLibrary", "signature", "shadowing")))
    }

    @Test
    fun `missing declared list yields gopls modifiers`() {
        assertEquals(GoplsSemanticTokens.EXTRA_MODIFIERS, GoplsSemanticTokens.withExtraModifiers(null))
    }

    @Test
    fun `builtins are recognised by defaultLibrary`() {
        assertEquals(GoTokenStyle.BUILTIN, GoTokenStyles.styleOf("function", listOf("defaultLibrary")))
        assertEquals(GoTokenStyle.BUILTIN, GoTokenStyles.styleOf("type", listOf("defaultLibrary", "number")))
        assertEquals(GoTokenStyle.BUILTIN, GoTokenStyles.styleOf("variable", listOf("readonly", "defaultLibrary")))
    }

    @Test
    fun `readonly variable is a constant`() {
        assertEquals(GoTokenStyle.CONSTANT, GoTokenStyles.styleOf("variable", listOf("readonly", "number")))
        assertEquals(GoTokenStyle.VARIABLE, GoTokenStyles.styleOf("variable", listOf("definition")))
    }

    @Test
    fun `chan modifier selects channel styles`() {
        assertEquals(GoTokenStyle.CHANNEL_VARIABLE, GoTokenStyles.styleOf("variable", listOf("definition", "chan")))
        assertEquals(GoTokenStyle.CHANNEL_PARAMETER, GoTokenStyles.styleOf("parameter", listOf("chan")))
        assertEquals(GoTokenStyle.CHANNEL_FIELD, GoTokenStyles.styleOf("property", listOf("chan")))
    }

    @Test
    fun `non-chan identifiers keep plain styles`() {
        assertEquals(GoTokenStyle.VARIABLE, GoTokenStyles.styleOf("variable", listOf("slice")))
        assertEquals(GoTokenStyle.PARAMETER, GoTokenStyles.styleOf("parameter", listOf("pointer")))
        assertEquals(GoTokenStyle.FIELD, GoTokenStyles.styleOf("property", listOf("map")))
        assertEquals(GoTokenStyle.TYPE, GoTokenStyles.styleOf("type", listOf("chan")))
    }

    @Test
    fun `defaultLibrary and readonly win over chan`() {
        assertEquals(GoTokenStyle.BUILTIN, GoTokenStyles.styleOf("variable", listOf("defaultLibrary", "chan")))
        assertEquals(GoTokenStyle.CONSTANT, GoTokenStyles.styleOf("variable", listOf("readonly", "chan")))
    }

    @Test
    fun `declarations and calls differ`() {
        assertEquals(GoTokenStyle.FUNCTION_DECLARATION, GoTokenStyles.styleOf("function", listOf("definition", "signature")))
        assertEquals(GoTokenStyle.FUNCTION_CALL, GoTokenStyles.styleOf("function", listOf("signature")))
        assertEquals(GoTokenStyle.METHOD_DECLARATION, GoTokenStyles.styleOf("method", listOf("definition")))
        assertEquals(GoTokenStyle.METHOD_CALL, GoTokenStyles.styleOf("method", emptyList()))
    }

    @Test
    fun `lexical tokens stay with the lexer`() {
        listOf("keyword", "comment", "string", "number", "operator", "unknownFutureType").forEach {
            assertEquals(it, GoTokenStyle.LEXER, GoTokenStyles.styleOf(it, emptyList()))
        }
    }
}
