package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.execution.model.GoTestSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class GoTestSelectionTest {
    @Test
    fun `no names selects the whole target without -run`() {
        assertEquals(GoTestSpec(recursive = true), GoTestSelection.spec(emptyList(), recursive = true))
    }

    @Test
    fun `blank names are ignored instead of producing an empty pattern`() {
        assertEquals(GoTestSpec(), GoTestSelection.spec(listOf("", "  ")))
    }

    @Test
    fun `tests become one exact run alternation and no benchmarks`() {
        assertEquals(
            GoTestSpec(runPattern = "^TestA$|^TestB$/^sub_case$"),
            GoTestSelection.spec(listOf("TestA", "TestB/sub case")),
        )
    }

    @Test
    fun `benchmarks alone go to -bench and skip every test`() {
        assertEquals(
            GoTestSpec(runPattern = "^$", benchPattern = "^BenchmarkAdd$|^BenchmarkMul$/^n=10$"),
            GoTestSelection.spec(listOf("BenchmarkAdd", "BenchmarkMul/n=10")),
        )
    }

    @Test
    fun `mixed selection splits tests and benchmarks`() {
        assertEquals(
            GoTestSpec(runPattern = "^TestA$|^FuzzParse$", benchPattern = "^BenchmarkAdd$"),
            GoTestSelection.spec(listOf("TestA", "BenchmarkAdd", "FuzzParse")),
        )
    }

    @Test
    fun `lowercase after the Benchmark prefix is not a benchmark`() {
        assertEquals(GoTestSpec(runPattern = "^Benchmarkfoo$"), GoTestSelection.spec(listOf("Benchmarkfoo")))
    }

    @Test
    fun `file tests keep tests fuzz and examples once and drop benchmarks and helpers`() {
        assertEquals(
            listOf("TestA", "FuzzB", "ExampleC"),
            GoTestSelection.fileTests(listOf("TestA", "helper", "BenchmarkX", "TestMain", "FuzzB", "TestA", "Testlower", "ExampleC")),
        )
    }
}
