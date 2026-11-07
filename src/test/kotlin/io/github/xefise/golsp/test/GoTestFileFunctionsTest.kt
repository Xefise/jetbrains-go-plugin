package io.github.xefise.golsp.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTestFileFunctionsTest {
    @Test
    fun `finds top level functions and generic ones`() {
        assertEquals(
            listOf("TestA", "TestGeneric"),
            GoTestFileFunctions.topLevel("package p\n\nfunc TestA(t *testing.T) {}\n\nfunc TestGeneric[T any](t *testing.T) {}\n"),
        )
    }

    @Test
    fun `skips methods function literals and nested funcs`() {
        val text = """
            package p
            var f = func() {}
            func (s *S) TestMethod(t *testing.T) {}
            func TestOuter(t *testing.T) {
                inner := func() {}
                _ = inner
            }
        """.trimIndent()
        assertEquals(listOf("TestOuter"), GoTestFileFunctions.topLevel(text))
    }

    @Test
    fun `ignores func text inside strings and comments`() {
        val text = "package p\n// func TestInComment(t *testing.T)\nvar s = `\nfunc TestInRaw(t *testing.T) {}\n`\nfunc TestReal(t *testing.T) {}\n"
        assertEquals(listOf("TestReal"), GoTestFileFunctions.topLevel(text))
    }

    @Test
    fun `empty or half typed text does not throw`() {
        assertEquals(emptyList<String>(), GoTestFileFunctions.topLevel(""))
        assertEquals(listOf("TestHalf"), GoTestFileFunctions.topLevel("package p\nfunc TestHalf(t *testing.T) {\n\tif x {"))
    }

    @Test
    fun `decode strips a leading BOM and replaces malformed UTF-8`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("package p\n", GoTestFileFunctions.decode(bom + "package p\n".toByteArray()))
        assertEquals("a\uFFFDb", GoTestFileFunctions.decode(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())))
    }

    @Test
    fun `ignore build constraint in the header excludes the file`() {
        assertTrue(GoTestFileFunctions.isIgnoredByBuildConstraint("//go:build ignore\n\npackage main\n"))
        assertTrue(GoTestFileFunctions.isIgnoredByBuildConstraint("// Copyright\n\n// +build ignore\r\n\npackage main\n"))
    }

    @Test
    fun `other constraints and ignore after the header do not exclude the file`() {
        assertFalse(GoTestFileFunctions.isIgnoredByBuildConstraint("//go:build integration\n\npackage p\n"))
        assertFalse(GoTestFileFunctions.isIgnoredByBuildConstraint("//go:build !ignore\n\npackage p\n"))
        assertFalse(GoTestFileFunctions.isIgnoredByBuildConstraint("package p\n\n//go:build ignore\n"))
        assertFalse(GoTestFileFunctions.isIgnoredByBuildConstraint(""))
    }
}
