package io.github.xefise.golsp.test.model

/** What "Rerun Failed Tests" runs, from the location URLs of the nodes of the previous run's tree. */
object GoTestRerun {
    /**
     * [packages] are import paths in tree order; [testNames] are full names as `go test` reported them, for one shared
     * `-run`/`-bench`. Empty [testNames] runs every test of [packages].
     */
    data class Selection(val packages: List<String>, val testNames: List<String>)

    /**
     * [failed] are the failed nodes, suites included; [seen] are all nodes of the tree. A failed test with failed
     * subtests is covered by them, so only the deepest failures are kept. A package that failed with no failed test
     * (build error, TestMain, exit code) reruns in full: alone without a filter, next to other packages through the
     * top-level tests it ran, since one `-run` is shared by every package. Null when nothing failed.
     */
    fun select(failed: List<GoTestLocation.Target>, seen: List<GoTestLocation.Target>): Selection? {
        val failedByPackage = LinkedHashMap<String, MutableList<String>>()
        for (target in failed) {
            val names = failedByPackage.getOrPut(target.importPath) { mutableListOf() }
            target.testName?.let { names += it }
        }
        if (failedByPackage.isEmpty()) return null
        val deepest = failedByPackage.mapValues { (_, names) ->
            names.distinct().filter { name -> names.none { it.startsWith("$name/") } }
        }
        val whole = deepest.filterValues { it.isEmpty() }.keys
        if (whole.size == deepest.size) return Selection(deepest.keys.toList(), emptyList())
        val names = deepest.flatMap { (importPath, names) ->
            if (importPath in whole) topLevelNames(importPath, seen) else names
        }
        return Selection(deepest.keys.toList(), names.distinct())
    }

    private fun topLevelNames(importPath: String, seen: List<GoTestLocation.Target>): List<String> =
        seen.mapNotNull { target -> target.testName?.takeIf { target.importPath == importPath }?.substringBefore('/') }
}
