package io.github.xefise.golsp.test.model.integration

import io.github.xefise.golsp.test.model.GoTestEvent
import io.github.xefise.golsp.test.model.GoTestLocation
import io.github.xefise.golsp.test.model.GoTestRerun
import io.github.xefise.golsp.test.model.GoTestSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Feeds names reported by a real failing run back into `go test`; skipped without `go` on PATH. */
class GoTestRerunIntegrationTest {
    @Test
    fun `rerun of reported failures runs exactly the failed tests again`() {
        val first = goTestJson("-count=1", "./rerun")
        val selection = GoTestRerun.select(targets(first, "fail"), targets(first, "run"))!!
        val spec = GoTestSelection.spec(selection.testNames)
        val again = goTestJson("-count=1", "-run", spec.runPattern!!, "./rerun")
        // "same" and "with_space#01" passed: their siblings with the same source name must not run.
        assertEquals(
            listOf(
                "TestTable",
                "TestTable/same#01",
                "TestTable/with_space",
                "TestTable/a/b",
                "TestParentFails",
                "TestParentFails/child",
            ),
            again.filter { it.action == "run" }.map { it.test },
        )
        assertEquals(targets(first, "fail"), targets(again, "fail"))
    }

    private fun targets(events: List<GoTestEvent>, action: String): List<GoTestLocation.Target> =
        events.filter { it.action == action }.map { GoTestLocation.Target(it.pkg!!, it.test) }

    private fun goTestJson(vararg args: String): List<GoTestEvent> {
        val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        val stdout = File.createTempFile("go-test-json", ".jsonl").apply { deleteOnExit() }
        val process = try {
            ProcessBuilder(listOf("go", "test", "-json") + args)
                .directory(File(projectDir, "testdata/gotests"))
                .redirectOutput(stdout)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (e: IOException) {
            Assume.assumeNoException("go is not on PATH", e)
            throw e
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("go test timed out")
        }
        val events = stdout.readLines().mapNotNull(GoTestEvent::parse)
        stdout.delete()
        // The selected tests fail again, so go test exits 1 both times.
        assertEquals(1, process.exitValue())
        return events
    }
}
