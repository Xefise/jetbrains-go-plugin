package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTestLocationTest {
    @Test
    fun `package url is the import path`() {
        assertEquals("gotest://example.com/gotests/calc", GoTestLocation.packageUrl("example.com/gotests/calc"))
    }

    @Test
    fun `test url keeps the reported full name verbatim`() {
        assertEquals(
            "gotest://example.com/m#TestSubtests/a/b",
            GoTestLocation.testUrl("example.com/m", "TestSubtests/a/b"),
        )
    }

    @Test
    fun `package path parses without a test`() {
        assertEquals(GoTestLocation.Target("example.com/m/pkg", null), GoTestLocation.parse("example.com/m/pkg"))
    }

    @Test
    fun `hash inside a test name stays in the name`() {
        // Fuzz seeds and duplicate subtests are reported as "seed#0", "x#01"; import paths cannot contain '#'.
        assertEquals(GoTestLocation.Target("example.com/m", "FuzzReverse/seed#0"), GoTestLocation.parse("example.com/m#FuzzReverse/seed#0"))
    }

    @Test
    fun `trailing hash means the package`() {
        assertEquals(GoTestLocation.Target("example.com/m", null), GoTestLocation.parse("example.com/m#"))
    }

    @Test
    fun `empty import path is not a location`() {
        assertNull(GoTestLocation.parse("#TestX"))
        assertNull(GoTestLocation.parse(""))
    }

    private val subtests = listOf("plain", "with space", "a/b", "nested", "ok", "fails", "регэксп [x]+$")

    @Test
    fun `subtest name with a space matches its rewritten form`() {
        assertEquals(1, GoTestLocation.matchSubtest(listOf("with_space"), subtests))
    }

    @Test
    fun `literal with a slash spans two levels`() {
        assertEquals(2, GoTestLocation.matchSubtest(listOf("a", "b"), subtests))
    }

    @Test
    fun `nested subtest is matched after its parent`() {
        assertEquals(5, GoTestLocation.matchSubtest(listOf("nested", "fails"), subtests))
    }

    @Test
    fun `unknown deeper level stops at the deepest match`() {
        assertEquals(3, GoTestLocation.matchSubtest(listOf("nested", "dynamic"), subtests))
    }

    @Test
    fun `child literal before its parent is not matched`() {
        // "ok" appears before "nested" here, so "nested/ok" can only resolve to "nested".
        assertEquals(1, GoTestLocation.matchSubtest(listOf("nested", "ok"), listOf("ok", "nested")))
    }

    @Test
    fun `dynamic or duplicate names do not match`() {
        assertNull(GoTestLocation.matchSubtest(listOf("first"), listOf(null, "plain")))
        assertNull(GoTestLocation.matchSubtest(listOf("plain#01"), subtests))
    }

    @Test
    fun `regexp metacharacters in a literal are matched literally`() {
        assertEquals(6, GoTestLocation.matchSubtest(listOf("регэксп_[x]+$"), subtests))
    }

    @Test
    fun `whole url parses and other protocols are not ours`() {
        assertEquals(GoTestLocation.Target("example.com/m", "TestT/x#01"), GoTestLocation.parseUrl("gotest://example.com/m#TestT/x#01"))
        assertNull(GoTestLocation.parseUrl("file:///work/m"))
        assertNull(GoTestLocation.parseUrl("example.com/m"))
    }
}
