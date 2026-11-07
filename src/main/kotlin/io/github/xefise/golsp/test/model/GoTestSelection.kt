package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.execution.model.GoTestSpec

object GoTestSelection {
    /**
     * Full test paths ("TestX", "TestX/sub case") to `-run` / `-bench`. Benchmarks are selected only by `-bench`,
     * so a benchmark-only selection sets `-run` to [GoTestRunPattern.NONE] to skip every test.
     */
    fun spec(testNames: Collection<String>, recursive: Boolean = false): GoTestSpec {
        val names = testNames.filter { it.isNotBlank() }
        if (names.isEmpty()) return GoTestSpec(recursive)
        val (benchmarks, tests) = names.partition { GoTestNames.kindOf(it.substringBefore('/')) == GoTestKind.BENCHMARK }
        return GoTestSpec(
            recursive = recursive,
            runPattern = if (tests.isEmpty()) GoTestRunPattern.NONE else GoTestRunPattern.exactly(tests),
            benchPattern = if (benchmarks.isEmpty()) null else GoTestRunPattern.exactly(benchmarks),
        )
    }

    /** What running a file means: its tests, fuzz seeds and examples; benchmarks only when asked for by name. */
    fun fileTests(topLevelFunctionNames: Collection<String>): List<String> =
        topLevelFunctionNames.distinct().filter { GoTestNames.kindOf(it).let { kind -> kind != null && kind != GoTestKind.BENCHMARK } }
}
