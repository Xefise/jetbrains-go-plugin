package io.github.xefise.golsp.lang.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoStringLiteralsTest {
    @Test
    fun interpreted() {
        assertEquals("plain", GoStringLiterals.unquote("\"plain\""))
        assertEquals("a b/c", GoStringLiterals.unquote("\"a b/c\""))
        assertEquals("tab\there\n", GoStringLiterals.unquote("\"tab\\there\\n\""))
        assertEquals("q\"\\", GoStringLiterals.unquote("\"q\\\"\\\\\""))
        assertEquals("AéA€", GoStringLiterals.unquote("\"\\x41\\u00e9\\101\\U000020AC\""))
        assertEquals("", GoStringLiterals.unquote("\"\""))
    }

    @Test
    fun raw() {
        assertEquals("a\\nb", GoStringLiterals.unquote("`a\\nb`"))
        assertEquals("line1\nline2", GoStringLiterals.unquote("`line1\r\nline2`"))
    }

    @Test
    fun malformed() {
        assertNull(GoStringLiterals.unquote("\"open"))
        assertNull(GoStringLiterals.unquote("\""))
        assertNull(GoStringLiterals.unquote("'x'"))
        assertNull(GoStringLiterals.unquote("\"bad\\q\""))
        assertNull(GoStringLiterals.unquote("\"\\'\""))
        assertNull(GoStringLiterals.unquote("\"\\xZZ\""))
        assertNull(GoStringLiterals.unquote("\"\\uD800\""))
        assertNull(GoStringLiterals.unquote("\"\\xff\""))
        assertNull(GoStringLiterals.unquote("\"trailing\\\""))
    }
}
