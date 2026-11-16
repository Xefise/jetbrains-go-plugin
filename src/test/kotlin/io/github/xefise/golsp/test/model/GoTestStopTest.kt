package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.test.model.GoTestNodeEventMapper.Companion.PACKAGE_FAILURE
import io.github.xefise.golsp.test.model.GoTestNodeEventMapper.Companion.PACKAGE_FAILURE_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Stop sends SIGINT; go then fails the interrupted package and reports nothing for its running tests
 * (captures in testData/gotest-json/extra/stopped*.jsonl). Runs reducer and mapper the way the converter does.
 */
class GoTestStopTest {

    /** "name: status" per node in start order, children indented; an open node is "terminated" in the runner. */
    private fun replay(fixture: String, stopBefore: (String) -> Boolean): String {
        val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        val lines = File(projectDir, "src/test/testData/gotest-json/$fixture.jsonl").readLines()
        val reducer = GoTestTreeReducer()
        val mapper = GoTestNodeEventMapper()
        val events = mutableListOf<GoTestNodeEvent>()
        var stopped = false
        for (line in lines) {
            if (!stopped && stopBefore(line)) {
                reducer.stop()
                stopped = true
            }
            events += mapper.map(reducer.accept(line))
        }
        events += mapper.mapAfterExit(reducer.finish())
        return render(events)
    }

    private fun render(events: List<GoTestNodeEvent>): String {
        val names = LinkedHashMap<String, String>()
        val parents = HashMap<String, String?>()
        val status = HashMap<String, String>()
        for (event in events) {
            when (event) {
                is GoTestNodeEvent.SuiteStarted -> { names[event.id] = event.name; parents[event.id] = null }
                is GoTestNodeEvent.TestStarted -> { names[event.id] = event.name; parents[event.id] = event.parentId }
                is GoTestNodeEvent.Finished -> status.putIfAbsent(event.id, "finished")
                is GoTestNodeEvent.Failed -> status[event.id] = "failed: ${event.message}"
                is GoTestNodeEvent.Ignored -> status[event.id] = "ignored"
                is GoTestNodeEvent.Output -> Unit
            }
        }
        fun depth(id: String): Int = parents[id]?.let { depth(it) + 1 } ?: 0
        return names.entries.joinToString("") { (id, name) ->
            "  ".repeat(depth(id)) + "$name: ${status[id] ?: "terminated"}\n"
        }
    }

    private val all: (String) -> Boolean = { true }
    private val atSignal: (String) -> Boolean = { "signal: interrupt" in it }

    @Test
    fun `running test stays terminated when the user stopped the run`() {
        assertEquals(
            """
            example.com/gotests/slow: terminated
              TestFast: finished
              TestSlow: terminated
            """.trimIndent() + "\n",
            replay("extra/stopped", all),
        )
    }

    @Test
    fun `running test fails as unfinished when go fails the package without a stop`() {
        val tree = replay("extra/stopped") { false }
        assertEquals(
            "  TestSlow: failed: The test did not finish: the test binary panicked, timed out or exited",
            tree.lines()[2],
        )
    }

    @Test
    fun `failure reported before the stop stays failed and open subtest with its parent stay terminated`() {
        val expected = """
            example.com/cap/stop: terminated
              TestPasses: finished
              TestFails: failed: failed before the stop
              TestSlowParent: terminated
                child: terminated
            """.trimIndent() + "\n"
        assertEquals(expected, replay("extra/stopped-after-failure", all))
        assertEquals(expected, replay("extra/stopped-after-failure", atSignal))
    }

    @Test
    fun `package failing with no running test after the stop still fails`() {
        assertEquals(
            """
            example.com/gotests/broken: finished
              $PACKAGE_FAILURE: failed: $PACKAGE_FAILURE_MESSAGE
            """.trimIndent() + "\n",
            replay("build-failure", all),
        )
    }
}
