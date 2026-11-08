package io.github.xefise.golsp.coverage.integration

import com.intellij.coverage.SuccessCoverageLoadingResult
import io.github.xefise.golsp.coverage.GoCoverageEngine
import io.github.xefise.golsp.coverage.GoCoverageRunner
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunTarget
import io.github.xefise.golsp.execution.model.GoTestCommandBuilder
import io.github.xefise.golsp.execution.model.GoTestSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The command of "Run with Coverage" against a real `go`; skipped without `go` on PATH. */
class GoCoverageIntegrationTest {
    private val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))

    @Test
    fun `failing and broken packages still leave a profile whose files resolve on disk`() {
        val module = File(projectDir, "testdata/gotests")
        val profile = Files.createTempFile("go-cover", ".out")
        try {
            Files.delete(profile)
            val request = GoExecutionRequest(
                GoRunTarget.Package(module.path),
                buildFlags = listOf("-count=1"),
                test = GoTestSpec(recursive = true, coverProfile = profile.toString()),
            )
            val spec = GoTestCommandBuilder.testCommand("go", request)
            val process = try {
                ProcessBuilder(listOf(spec.executable) + spec.arguments)
                    .directory(File(spec.workingDirectory))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                Assume.assumeNoException("go is not on PATH", e)
                throw e
            }
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                fail("go test timed out")
            }
            // calc fails and broken does not compile, yet go writes the profile of what ran.
            assertEquals(1, process.exitValue())
            val result = GoCoverageRunner.load(profile, module.toPath()) as SuccessCoverageLoadingResult
            val calc = GoCoverageEngine.pathKey(File(module, "calc/calc.go").path)
            assertTrue(result.projectData!!.classes.keys.toString(), calc in result.projectData!!.classes.keys)
            result.projectData!!.classes.keys.forEach { assertTrue(it, File(it).isFile) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }
}
