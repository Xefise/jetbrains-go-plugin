package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GoTestSourceSignatureTest(private val parameters: String, private val type: String?, private val expected: Boolean) {
    @Test
    fun `signature follows cmd go isTestFunc`() {
        assertEquals("$parameters / $type", expected, GoTestSource.hasSignature(parameters, type))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} -> {2}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("(t *testing.T)", "T", true),
            arrayOf("(t * testing . T)", "T", true),
            arrayOf("(*testing.T)", "T", true),
            arrayOf("(t *T)", "T", true),
            arrayOf("(t *testing.T,)", "T", true),
            arrayOf("(t *testing.B)", "T", false),
            arrayOf("(b *testing.B)", "B", true),
            arrayOf("(f *testing.F)", "F", true),
            arrayOf("()", "T", false),
            arrayOf("(t testing.T)", "T", false),
            arrayOf("(a, b *testing.T)", "T", false),
            arrayOf("(t *testing.T, x int)", "T", false),
            arrayOf("(t *testing.TB)", "T", false),
            arrayOf("()", null, true),
            arrayOf("( )", null, true),
            arrayOf("(x int)", null, false),
        )
    }
}

class GoTestSourceTest {
    @Test
    fun `parameter name is the declared name of the single parameter`() {
        assertEquals("tt", GoTestSource.parameterName("(tt *testing.T)", "T"))
    }

    @Test
    fun `unnamed parameter has no name`() {
        assertEquals(null, GoTestSource.parameterName("(*testing.T)", "T"))
    }

    @Test
    fun `output group is recognised after directives and blank lines in any case`() {
        assertEquals(true, GoTestSource.isOutputGroup(listOf("//go:noinline", "//", "// OUTPUT: 1")))
    }

    @Test
    fun `unordered output counts`() {
        assertEquals(true, GoTestSource.isOutputGroup(listOf("// Unordered output:", "// 1")))
    }

    @Test
    fun `block comment output counts`() {
        assertEquals(true, GoTestSource.isOutputGroup(listOf("/*\n Output: 1\n*/")))
    }

    // An uppercase letter before the colon is not directive-shaped, so the text stays.
    @Test
    fun `output without a space after the slashes counts`() {
        assertEquals(true, GoTestSource.isOutputGroup(listOf("//Output: 1")))
    }

    // "//output:1" looks like a directive (lowercase word, colon, alphanumeric) and go/doc drops it.
    @Test
    fun `directive-shaped output comment does not count`() {
        assertEquals(false, GoTestSource.isOutputGroup(listOf("//output:1")))
    }

    @Test
    fun `group starting with other text does not count`() {
        assertEquals(false, GoTestSource.isOutputGroup(listOf("// prints:", "// Output: 1")))
    }

    @Test
    fun `empty group does not count`() {
        assertEquals(false, GoTestSource.isOutputGroup(emptyList()))
    }
}
