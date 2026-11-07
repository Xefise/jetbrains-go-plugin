package io.github.xefise.golsp.test.model.integration

import io.github.xefise.golsp.test.model.GoTestRunPattern
import io.github.xefise.golsp.test.model.GoTestTreeReducer
import io.github.xefise.golsp.test.model.TestTreeRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs the patterns through the real `go test` matcher on testdata/gotests; skipped without `go` on PATH. */
class GoTestRunPatternIntegrationTest {
    @Test
    fun `pattern built from source names runs exactly the selected tests`() {
        val pattern = GoTestRunPattern.exactly(
            listOf(
                "TestAdd",
                "TestSubtests/with space",
                "TestSubtests/a/b",
                "TestSubtests/регэксп [x]+$",
                "TestSubtests/nested/ok",
            ),
        )
        val recorder = TestTreeRecorder()
        val reducer = GoTestTreeReducer()
        goTestJson("-count=1", "-run", pattern, "./calc").forEach { recorder.apply(reducer.accept(it)) }
        recorder.apply(reducer.finish())
        recorder.assertAllFinished()
        TestTreeRecorder.assertTree(
            """
            example.com/gotests/calc: passed
              TestAdd: passed
              TestSubtests: passed
                with_space: passed
                a/b: passed
                nested: passed
                  ok: passed
                регэксп_[x]+$: passed
            """,
            recorder,
        )
    }

    private fun goTestJson(vararg args: String): List<String> {
        val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        // Output goes to a file so a hung go cannot block the read and the timeout below applies.
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
        val lines = stdout.readLines()
        stdout.delete()
        assertEquals(lines.joinToString("\n"), 0, process.exitValue())
        return lines
    }
}
