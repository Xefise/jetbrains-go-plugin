package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GoTestNamesTest(private val name: String, private val expected: GoTestKind?) {
    @Test
    fun `kind follows the go test naming rule`() {
        assertEquals(name, expected, GoTestNames.kindOf(name))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("TestAdd", GoTestKind.TEST),
            arrayOf("Test", GoTestKind.TEST),
            arrayOf("Test_add", GoTestKind.TEST),
            arrayOf("Test1", GoTestKind.TEST),
            arrayOf("TestÉcole", GoTestKind.TEST),
            arrayOf("TestMainMenu", GoTestKind.TEST),
            arrayOf("Testadd", null),
            arrayOf("Testécole", null),
            // U+00AA is Lo with the Other_Lowercase property: unicode.IsLower is false, so go test runs it.
            arrayOf("Testª", GoTestKind.TEST),
            arrayOf("TestMain", null),
            arrayOf("BenchmarkAdd", GoTestKind.BENCHMARK),
            arrayOf("Benchmark", GoTestKind.BENCHMARK),
            arrayOf("Benchmarkadd", null),
            arrayOf("FuzzReverse", GoTestKind.FUZZ),
            arrayOf("Fuzzy", null),
            arrayOf("ExampleAdd", GoTestKind.EXAMPLE),
            arrayOf("Example", GoTestKind.EXAMPLE),
            arrayOf("Example_suffix", GoTestKind.EXAMPLE),
            arrayOf("Examples", null),
            arrayOf("Tes", null),
            arrayOf("", null),
            arrayOf("main", null),
            arrayOf("testAdd", null),
        )
    }
}
