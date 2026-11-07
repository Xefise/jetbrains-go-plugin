package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoTestRunPatternTest {
    @Test
    fun `top level test is anchored on both ends`() {
        assertEquals("^TestAdd$", GoTestRunPattern.exactly(listOf("TestAdd")))
    }

    @Test
    fun `subtest gets one anchored pattern per level with spaces rewritten`() {
        assertEquals("^TestSubtests$/^with_space$", GoTestRunPattern.exactly(listOf("TestSubtests/with space")))
    }

    @Test
    fun `slash inside a subtest name splits into levels like go test does`() {
        assertEquals("^TestSubtests$/^a$/^b$", GoTestRunPattern.exactly(listOf("TestSubtests/a/b")))
    }

    @Test
    fun `regexp metacharacters in a subtest name are escaped`() {
        assertEquals("^TestX$/^регэксп_\\[x\\]\\+\\$$", GoTestRunPattern.exactly(listOf("TestX/регэксп [x]+$")))
    }

    @Test
    fun `alternation bar in a name is escaped so it does not split paths`() {
        assertEquals("^TestX$/^a\\|b$", GoTestRunPattern.exactly(listOf("TestX/a|b")))
    }

    @Test
    fun `several tests become alternatives of whole paths`() {
        assertEquals(
            "^TestAdd$|^TestSubtests$/^nested$/^ok$",
            GoTestRunPattern.exactly(listOf("TestAdd", "TestSubtests/nested/ok")),
        )
    }

    @Test
    fun `duplicate names appear once`() {
        assertEquals("^TestAdd$", GoTestRunPattern.exactly(listOf("TestAdd", "TestAdd")))
    }

    @Test
    fun `name already reported by go test is left unchanged`() {
        assertEquals("with_space", GoTestRunPattern.rewrite("with_space"))
    }

    @Test
    fun `tab and unicode spaces become underscores`() {
        // U+00A0 no-break space, U+2003 em space, U+3000 ideographic space
        assertEquals("a_b_c_d_e", GoTestRunPattern.rewrite("a\tb\u00A0c\u2003d\u3000e"))
    }

    @Test
    fun `non printable runes are quoted like strconv does`() {
        // U+200B zero-width space is a format character, U+0001 a control character
        assertEquals("a\\u200bb\\x01", GoTestRunPattern.rewrite("a\u200Bb\u0001"))
    }

    @Test
    fun `non ascii letters are kept`() {
        assertEquals("тест_ü", GoTestRunPattern.rewrite("тест ü"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty selection is rejected because an empty pattern runs every test`() {
        GoTestRunPattern.exactly(emptyList())
    }
}
