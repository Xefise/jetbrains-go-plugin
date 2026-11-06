package io.github.xefise.golsp.execution.model

import org.junit.Assert.assertEquals
import org.junit.Test

// Expectations follow vscode-go's parseEnvFile, which the parser is ported from.
class DotEnvParserTest {
    private fun parse(text: String, inherited: Map<String, String> = emptyMap()) = DotEnvParser.parse(text, inherited)

    @Test
    fun `plain key value pairs are read in order`() {
        assertEquals(listOf("A" to "1", "B" to "two"), parse("A=1\nB=two\n").toList())
    }

    @Test
    fun `export prefix and spaces around the equals sign are ignored`() {
        assertEquals(mapOf("A" to "1", "B" to "2"), parse("export A=1\n  export   B =  2"))
    }

    @Test
    fun `trailing spaces of an unquoted value are kept`() {
        assertEquals(mapOf("A" to "x  "), parse("A=x  "))
    }

    @Test
    fun `key may contain dots and dashes`() {
        assertEquals(mapOf("app.name-v2" to "x"), parse("app.name-v2=x"))
    }

    @Test
    fun `empty value is an empty string`() {
        assertEquals(mapOf("A" to "", "B" to ""), parse("A=\nB=\"\""))
    }

    @Test
    fun `lines without a key are ignored`() {
        assertEquals(mapOf("A" to "1"), parse("# comment\n\n=nokey\njust words\nbad key=1\nA=1"))
    }

    @Test
    fun `a commented-out assignment is ignored`() {
        assertEquals(emptyMap<String, String>(), parse("#A=1"))
    }

    @Test
    fun `later definition overwrites an earlier one`() {
        assertEquals(mapOf("A" to "2"), parse("A=1\nA=2"))
    }

    @Test
    fun `surrounding quotes are stripped`() {
        assertEquals(mapOf("A" to "x y", "B" to "x y"), parse("A=\"x y\"\nB='x y'"))
    }

    @Test
    fun `escaped newline becomes a newline only in double quotes`() {
        assertEquals(mapOf("D" to "a\nb", "S" to "a\\nb", "U" to "a\\nb"), parse("D=\"a\\nb\"\nS='a\\nb'\nU=a\\nb"))
    }

    @Test
    fun `equals signs inside the value are kept`() {
        assertEquals(mapOf("URL" to "a=b=c"), parse("URL=a=b=c"))
    }

    @Test
    fun `CRLF line endings do not leak into values`() {
        assertEquals(mapOf("A" to "1", "B" to "2"), parse("A=1\r\nB=2\r\n"))
    }

    @Test
    fun `leading BOM is dropped`() {
        assertEquals(mapOf("A" to "1"), parse("﻿A=1"))
    }

    @Test
    fun `substitution uses a variable defined above`() {
        assertEquals("/home/x/bin", parse("ROOT=/home/x\nBIN=\${ROOT}/bin")["BIN"])
    }

    @Test
    fun `substitution falls back to the inherited environment`() {
        assertEquals("/usr/bin:/extra", parse("PATH=\${PATH}:/extra", mapOf("PATH" to "/usr/bin"))["PATH"])
    }

    @Test
    fun `file variable wins over the inherited one in substitution`() {
        assertEquals("file", parse("HOME=file\nX=\${HOME}", mapOf("HOME" to "env"))["X"])
    }

    @Test
    fun `a variable defined below is not visible yet`() {
        assertEquals("", parse("X=\${LATER}\nLATER=1")["X"])
    }

    @Test
    fun `unknown variable substitutes to empty`() {
        assertEquals("a--b", parse("X=a-\${NOPE}-b")["X"])
    }

    @Test
    fun `escaped dollar is not substituted and loses its backslash`() {
        assertEquals("\${HOME}", parse("X=\\\${HOME}", mapOf("HOME" to "/h"))["X"])
    }

    @Test
    fun `malformed substitution leaves the whole value untouched`() {
        assertEquals("\${B} \${A-x}", parse("X=\${B} \${A-x}", mapOf("B" to "b"))["X"])
    }

    @Test
    fun `empty substitution is malformed`() {
        assertEquals("\${}", parse("X=\${}")["X"])
    }

    @Test
    fun `dollar without braces is literal`() {
        assertEquals("\$HOME", parse("X=\$HOME", mapOf("HOME" to "/h"))["X"])
    }

    @Test
    fun `substitution also applies inside single quotes`() {
        assertEquals("/h", parse("X='\${HOME}'", mapOf("HOME" to "/h"))["X"])
    }

    @Test
    fun `empty text gives no variables`() {
        assertEquals(emptyMap<String, String>(), parse(""))
    }
}
