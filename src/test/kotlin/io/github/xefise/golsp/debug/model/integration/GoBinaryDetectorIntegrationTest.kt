package io.github.xefise.golsp.debug.model.integration

import io.github.xefise.golsp.debug.model.GoBinaryDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Builds testdata/hello with the real toolchain; skipped without `go` on PATH. */
class GoBinaryDetectorIntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `binary built by go is detected with its version`() {
        val binary = File(temp.root, "hello")
        val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        val process = try {
            ProcessBuilder("go", "build", "-o", binary.absolutePath, ".")
                .directory(File(projectDir, "testdata/hello"))
                .redirectErrorStream(true)
                .redirectOutput(File(temp.root, "build.log"))
                .start()
        } catch (e: IOException) {
            Assume.assumeNoException("go is not on PATH", e)
            throw e
        }
        assertTrue("go build timed out", process.waitFor(2, TimeUnit.MINUTES))
        assertEquals(File(temp.root, "build.log").readText(), 0, process.exitValue())

        assertTrue(GoBinaryDetector.isGoBinary(binary.toPath()))
        assertTrue(GoBinaryDetector.goVersion(binary.toPath())!!.startsWith("go1."))
    }
}
