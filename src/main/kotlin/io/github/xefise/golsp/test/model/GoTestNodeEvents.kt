package io.github.xefise.golsp.test.model

/** Test-runner events with string node ids; a `null` output target or parent is the root. */
sealed interface GoTestNodeEvent {
    data class SuiteStarted(val id: String, val name: String, val locationUrl: String) : GoTestNodeEvent
    data class TestStarted(val id: String, val parentId: String?, val name: String, val locationUrl: String?) : GoTestNodeEvent
    data class Output(val id: String?, val text: String, val error: Boolean) : GoTestNodeEvent
    data class Finished(val id: String, val durationMs: Long?) : GoTestNodeEvent

    /** [expected] and [actual] come together and enable the diff viewer. */
    data class Failed(
        val id: String,
        val message: String,
        val durationMs: Long?,
        val details: String = "",
        val expected: String? = null,
        val actual: String? = null,
    ) : GoTestNodeEvent

    data class Ignored(val id: String, val message: String = "") : GoTestNodeEvent
}

/**
 * Maps [GoTestTreeOp]s to runner events. Packages are suites whose state comes from their children: a failed suite
 * would count as one more failed test. A package that fails with no failed test (build error, `TestMain`, exit code)
 * gets a failed child [PACKAGE_FAILURE] that takes the package output of the same batch, i.e. the build output, and
 * has the package's output outside of tests as its failure details.
 * One instance per run, fed in order.
 */
class GoTestNodeEventMapper {
    private val packages = HashMap<Int, String>()
    private val packageOf = HashMap<Int, Int>()
    private val failedPackages = HashSet<Int>()

    fun map(ops: List<GoTestTreeOp>): List<GoTestNodeEvent> {
        val needsFailureNode = scan(ops)
        val events = mutableListOf<GoTestNodeEvent>()
        val started = HashSet<Int>()
        fun failureNode(packageId: Int): String {
            val id = "$packageId$FAILURE_SUFFIX"
            if (started.add(packageId)) {
                events += GoTestNodeEvent.TestStarted(id, packageId.toString(), PACKAGE_FAILURE, GoTestLocation.packageUrl(packages.getValue(packageId)))
            }
            return id
        }
        for (op in ops) {
            events += when (op) {
                is GoTestTreeOp.PackageStarted ->
                    GoTestNodeEvent.SuiteStarted(op.id.toString(), op.importPath, GoTestLocation.packageUrl(op.importPath))
                is GoTestTreeOp.TestStarted -> GoTestNodeEvent.TestStarted(
                    op.id.toString(), op.parentId.toString(), op.name, GoTestLocation.testUrl(op.importPath, op.fullName),
                )
                is GoTestTreeOp.Output -> {
                    val target = op.id?.let { if (it in needsFailureNode) failureNode(it) else it.toString() }
                    GoTestNodeEvent.Output(target, op.text, op.error)
                }
                is GoTestTreeOp.Passed -> GoTestNodeEvent.Finished(op.id.toString(), op.durationMs)
                is GoTestTreeOp.Failed -> when {
                    op.id in needsFailureNode -> {
                        events += GoTestNodeEvent.Failed(failureNode(op.id), op.message.ifEmpty { PACKAGE_FAILURE_MESSAGE }, null, op.details)
                        GoTestNodeEvent.Finished(op.id.toString(), op.durationMs)
                    }
                    op.id in packages -> GoTestNodeEvent.Finished(op.id.toString(), op.durationMs)
                    else -> GoTestNodeEvent.Failed(op.id.toString(), op.message, op.durationMs, op.details, op.expected, op.actual)
                }
                is GoTestTreeOp.Skipped ->
                    if (op.id in packages) GoTestNodeEvent.Finished(op.id.toString(), null) else GoTestNodeEvent.Ignored(op.id.toString(), op.reason)
            }
        }
        return events
    }

    /**
     * Ops of a process that exited before reporting its packages: nodes still open stay open, so the test runner marks
     * them terminated, as it does after Stop. Real panics and timeouts are reported by `go test` and never get here.
     */
    fun mapAfterExit(ops: List<GoTestTreeOp>): List<GoTestNodeEvent> =
        map(ops.filter { it is GoTestTreeOp.PackageStarted || it is GoTestTreeOp.TestStarted || it is GoTestTreeOp.Output })

    // Updates the tree state in op order and returns the packages that fail in this batch with no failed test.
    private fun scan(ops: List<GoTestTreeOp>): Set<Int> {
        val result = HashSet<Int>()
        for (op in ops) {
            when (op) {
                is GoTestTreeOp.PackageStarted -> packages[op.id] = op.importPath
                is GoTestTreeOp.TestStarted -> packageOf[op.id] = packageOf[op.parentId] ?: op.parentId
                is GoTestTreeOp.Failed -> {
                    val pkg = packageOf[op.id]
                    if (pkg != null) failedPackages += pkg
                    else if (op.id in packages && op.id !in failedPackages) result += op.id
                }
                else -> Unit
            }
        }
        return result
    }

    companion object {
        const val PACKAGE_FAILURE: String = "[package failed]"
        const val RUN_FAILURE: String = "[go test failed]"
        private const val RUN_FAILURE_ID = "go-test-failed"

        /** A `go` command that failed before reporting anything (no main module, bad flag): one failed node with its stderr. */
        fun runFailure(stderr: String, exitCode: Int): List<GoTestNodeEvent> {
            val text = stderr.trim()
            val message = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "go test exited with code $exitCode"
            return listOf(
                GoTestNodeEvent.TestStarted(RUN_FAILURE_ID, null, RUN_FAILURE, null),
                GoTestNodeEvent.Failed(RUN_FAILURE_ID, message, null, text),
            )
        }

        const val PACKAGE_FAILURE_MESSAGE: String =
            "The package failed outside of its tests: build error, TestMain or the test binary's exit code"
        private const val FAILURE_SUFFIX = ":package"
    }
}
