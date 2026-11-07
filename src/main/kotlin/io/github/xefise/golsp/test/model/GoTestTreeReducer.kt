package io.github.xefise.golsp.test.model

import kotlin.math.roundToLong

/** Test-tree changes in the order the test runner UI must apply them; a `null` parent or output target is the root. */
sealed interface GoTestTreeOp {
    data class PackageStarted(val id: Int, val importPath: String) : GoTestTreeOp

    /** [name] is the part of [fullName] below the parent node: "with_space", or "a/b" for a subtest named so. */
    data class TestStarted(
        val id: Int,
        val parentId: Int,
        val name: String,
        val importPath: String,
        val fullName: String,
    ) : GoTestTreeOp

    data class Output(val id: Int?, val text: String, val error: Boolean = false) : GoTestTreeOp
    data class Passed(val id: Int, val durationMs: Long?) : GoTestTreeOp
    data class Failed(val id: Int, val message: String, val durationMs: Long?) : GoTestTreeOp
    data class Skipped(val id: Int) : GoTestTreeOp
}

/**
 * Turns a `go test -json` stream into tree operations. One instance per run; feed lines in order, then [finish].
 *
 * Packages interleave under `./...` and parallel tests interleave within a package, so all state is keyed by
 * package and full test name. A package node appears only once something in it runs or fails: packages without
 * test files stay out of the tree.
 */
class GoTestTreeReducer {
    private var nextId = 1
    private val packages = LinkedHashMap<String, PackageState>()
    private val buildOutput = LinkedHashMap<String, StringBuilder>()

    /** Build output shown under a failed package; a shared dependency can fail many packages. */
    private val claimedBuildOutput = HashSet<String>()

    private class PackageState(val importPath: String) {
        var id: Int? = null
        val pendingOutput = StringBuilder()

        /** Tests that have started and not finished, in start order. */
        val openTests = LinkedHashMap<String, Int>()

        /** Benchmarks that printed a result line: they ran to completion. */
        val benchmarksWithResult = HashSet<String>()

        /** Tests closed as unfinished: a benchmark parent with such a descendant did not finish either. */
        val cutOff = HashSet<String>()

        /** Unterminated output of benchmarks: test2json can split a result line across several events. */
        val partialLines = HashMap<String, StringBuilder>()
    }

    fun accept(line: String): List<GoTestTreeOp> {
        val event = GoTestEvent.parse(line)
            ?: return if (line.isBlank()) emptyList() else listOf(GoTestTreeOp.Output(null, line.withNewline()))
        val ops = mutableListOf<GoTestTreeOp>()
        when (event.action) {
            "build-output" -> {
                val path = event.importPath
                val text = event.output
                if (path != null && !text.isNullOrEmpty()) buildOutput.getOrPut(path, ::StringBuilder).append(text)
            }
            "build-fail" -> Unit
            else -> event.pkg?.let { onPackageEvent(packages.getOrPut(it) { PackageState(it) }, event, ops) }
        }
        return ops
    }

    /** The process has exited: whatever is still open never got a result. */
    fun finish(): List<GoTestTreeOp> {
        val ops = mutableListOf<GoTestTreeOp>()
        for (state in packages.values) {
            if (state.id == null && state.openTests.isEmpty() && state.pendingOutput.isEmpty()) continue
            closeOpenTests(state, passed = false, ops)
            val id = packageNode(state, ops)
            ops += GoTestTreeOp.Failed(id, "go test exited before reporting a result for this package", null)
        }
        packages.clear()
        buildOutput.filter { it.key !in claimedBuildOutput && it.value.isNotEmpty() }
            .forEach { ops += GoTestTreeOp.Output(null, it.value.toString(), error = true) }
        buildOutput.clear()
        claimedBuildOutput.clear()
        return ops
    }

    private fun onPackageEvent(state: PackageState, event: GoTestEvent, ops: MutableList<GoTestTreeOp>) {
        val test = event.test
        if (test == null) {
            onPackageLevel(state, event, ops)
            return
        }
        when (event.action) {
            "run" -> startTest(state, test, ops)
            "output", "bench" -> testOutput(state, test, event, ops)
            "pass" -> finishTest(state, test, ops) { GoTestTreeOp.Passed(it, event.durationMs()) }
            "fail" -> finishTest(state, test, ops) { GoTestTreeOp.Failed(it, "", event.durationMs()) }
            "skip" -> finishTest(state, test, ops) { GoTestTreeOp.Skipped(it) }
        }
    }

    private fun onPackageLevel(state: PackageState, event: GoTestEvent, ops: MutableList<GoTestTreeOp>) {
        when (event.action) {
            "output" -> if (!event.isFrame()) {
                val text = event.output.orEmpty()
                val id = state.id
                if (id == null) state.pendingOutput.append(text) else ops += GoTestTreeOp.Output(id, text)
            }
            "pass" -> {
                closeOpenTests(state, passed = true, ops)
                val id = state.id
                if (id == null) {
                    // Nothing ran here ("[no tests to run]" under a -run filter): keep the package out of the tree.
                    if (state.pendingOutput.isNotEmpty()) ops += GoTestTreeOp.Output(null, state.pendingOutput.toString())
                } else {
                    ops += GoTestTreeOp.Passed(id, event.durationMs())
                }
                packages.remove(state.importPath)
            }
            "fail" -> {
                closeOpenTests(state, passed = false, ops)
                val id = packageNode(state, ops)
                event.failedBuild?.let { failedBuild ->
                    buildOutput[failedBuild]?.let {
                        claimedBuildOutput += failedBuild
                        ops += GoTestTreeOp.Output(id, it.toString(), error = true)
                    }
                }
                ops += GoTestTreeOp.Failed(id, "", event.durationMs())
                packages.remove(state.importPath)
            }
            "skip" -> {
                val id = state.id
                if (id == null) {
                    // "[no test files]"
                    if (state.pendingOutput.isNotEmpty()) ops += GoTestTreeOp.Output(null, state.pendingOutput.toString())
                } else {
                    ops += GoTestTreeOp.Skipped(id)
                }
                packages.remove(state.importPath)
            }
        }
    }

    private fun packageNode(state: PackageState, ops: MutableList<GoTestTreeOp>): Int {
        state.id?.let { return it }
        val id = nextId++
        state.id = id
        ops += GoTestTreeOp.PackageStarted(id, state.importPath)
        if (state.pendingOutput.isNotEmpty()) {
            ops += GoTestTreeOp.Output(id, state.pendingOutput.toString())
            state.pendingOutput.setLength(0)
        }
        return id
    }

    private fun startTest(state: PackageState, fullName: String, ops: MutableList<GoTestTreeOp>): Int {
        val packageId = packageNode(state, ops)
        // A subtest named "a/b" reports as "Parent/a/b": the parent is the longest running prefix, not the last segment.
        // A paused parallel subtest "a" next to one literally named "a/b" is ambiguous and resolves to "a".
        var parentId = packageId
        var name = fullName
        var cut = fullName.lastIndexOf('/')
        while (cut > 0) {
            val parent = state.openTests[fullName.substring(0, cut)]
            if (parent != null) {
                parentId = parent
                name = fullName.substring(cut + 1)
                break
            }
            cut = fullName.lastIndexOf('/', cut - 1)
        }
        // A repeated run of a name still open (benchmarks get no pass) settles the previous run, then starts clean.
        state.openTests[fullName]?.let { closeRun(state, fullName, it, ops) }
        state.benchmarksWithResult.removeIf { it == fullName || it.startsWith("$fullName/") }
        state.cutOff.removeIf { it == fullName || it.startsWith("$fullName/") }
        val id = nextId++
        state.openTests[fullName] = id
        ops += GoTestTreeOp.TestStarted(id, parentId, name, state.importPath, fullName)
        return id
    }

    private fun testOutput(state: PackageState, test: String, event: GoTestEvent, ops: MutableList<GoTestTreeOp>) {
        val text = event.output ?: return
        if (event.isFrame()) return
        if (GoTestNames.kindOf(test.substringBefore('/')) == GoTestKind.BENCHMARK) state.trackBenchmarkResult(test, text)
        val target = state.openTests[test] ?: packageNode(state, ops)
        ops += GoTestTreeOp.Output(target, text, error = event.outputType == "error")
    }

    private fun PackageState.trackBenchmarkResult(test: String, text: String) {
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val line = partialLines.getOrPut(test, ::StringBuilder)
            // Past the cap the line is only marked overlong, so a dropped head can never pass for a result line.
            line.append(text, start, minOf(end, start + MAX_RESULT_LINE + 1 - line.length))
            if (newline < 0) return
            settlePartialLine(test)
            start = newline + 1
        }
    }

    private fun PackageState.settlePartialLine(test: String) {
        val line = partialLines.remove(test) ?: return
        if (line.length <= MAX_RESULT_LINE && isBenchmarkResult(test, line)) benchmarksWithResult += test
    }

    // "BenchmarkAdd-8   \t     100\t  1.250 ns/op\t  0 B/op"; the -N suffix is absent with GOMAXPROCS 1.
    private fun isBenchmarkResult(test: String, line: CharSequence): Boolean =
        line.startsWith(test) && BENCHMARK_RESULT.matches(line.subSequence(test.length, line.length))

    private fun finishTest(state: PackageState, test: String, ops: MutableList<GoTestTreeOp>, result: (Int) -> GoTestTreeOp) {
        val id = state.openTests[test] ?: startTest(state, test, ops)
        closeOpenChildren(state, test, ops)
        state.openTests.remove(test)
        state.partialLines.remove(test)
        ops += result(id)
    }

    // Children can still be open when the binary reports the parent (e.g. a panicking subtest): close them first.
    private fun closeOpenChildren(state: PackageState, test: String, ops: MutableList<GoTestTreeOp>) {
        state.openTests.entries.filter { it.key.startsWith("$test/") }.reversed().forEach {
            ops += state.closeOpen(it.key, it.value, passed = false)
            state.openTests.remove(it.key)
        }
    }

    private fun closeRun(state: PackageState, test: String, id: Int, ops: MutableList<GoTestTreeOp>) {
        closeOpenChildren(state, test, ops)
        state.openTests.remove(test)
        ops += state.closeOpen(test, id, passed = false)
    }

    private fun closeOpenTests(state: PackageState, passed: Boolean, ops: MutableList<GoTestTreeOp>) {
        state.openTests.entries.reversed().forEach { ops += state.closeOpen(it.key, it.value, passed) }
        state.openTests.clear()
    }

    /**
     * Benchmarks never get their own `pass`: one finished if it printed a result line, or if a sub-benchmark did and
     * no descendant was cut off. Open tests close children first, so descendants are settled by then.
     */
    private fun PackageState.closeOpen(name: String, id: Int, passed: Boolean): GoTestTreeOp {
        settlePartialLine(name)
        val prefix = "$name/"
        val finished = name in benchmarksWithResult ||
            (benchmarksWithResult.any { it.startsWith(prefix) } && cutOff.none { it.startsWith(prefix) })
        if (passed || finished) return GoTestTreeOp.Passed(id, null)
        cutOff += name
        return GoTestTreeOp.Failed(id, DID_NOT_FINISH, null)
    }

    private fun GoTestEvent.isFrame(): Boolean =
        outputType == "frame" || (outputType == null && FRAME_LINE.containsMatchIn(output.orEmpty()))

    private fun GoTestEvent.durationMs(): Long? = elapsedSeconds?.let { (it * 1000).roundToLong() }

    private fun String.withNewline(): String = if (endsWith("\n")) this else "$this\n"

    private companion object {
        const val MAX_RESULT_LINE = 4096
        const val DID_NOT_FINISH = "The test did not finish: the test binary panicked, timed out or exited"

        // After the name: optional -GOMAXPROCS, iterations, then value-unit metric pairs; ns/op can be suppressed.
        val BENCHMARK_RESULT = Regex("""(-\d+)?\s+\d+(\s+[\d.]+\s+\S+)+\s*""")

        // Before Go 1.24 frame lines carry no OutputType; PASS/FAIL/ok lines of the package are kept.
        val FRAME_LINE = Regex("""^\s*(=== (RUN|PAUSE|CONT|NAME)\s|--- (PASS|FAIL|SKIP|BENCH): )""")
    }
}
