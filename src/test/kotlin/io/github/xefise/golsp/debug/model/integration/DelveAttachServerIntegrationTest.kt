package io.github.xefise.golsp.debug.model.integration

import io.github.xefise.golsp.debug.model.DelveAttachServer
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Starts the real `dlv dap` the attach flow uses; skipped when dlv is neither on PATH nor in ~/go/bin. */
class DelveAttachServerIntegrationTest {
    private fun dlv(): String? {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "dlv.exe" else "dlv"
        val candidates = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) } +
            File(System.getProperty("user.home"), "go/bin/$name")
        return candidates.firstOrNull { it.canExecute() }?.absolutePath
    }

    @Test
    fun `dlv dap reports a loopback port that accepts connections`() {
        val dlv = dlv()
        Assume.assumeTrue("dlv is not installed", dlv != null)
        val spec = DelveAttachServer.command(dlv!!, System.getProperty("java.io.tmpdir"), emptyMap())
        val process = ProcessBuilder(listOf(spec.executable) + spec.arguments).directory(File(spec.workingDirectory)).start()
        try {
            val stdout = StringBuilder()
            var port: Int? = null
            val reader = process.inputStream.bufferedReader()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (port == null && System.nanoTime() < deadline) {
                val line = reader.readLine() ?: break
                stdout.append(line).append('\n')
                port = DelveAttachServer.readyPort(stdout)
            }
            assertNotNull("no ready line in: $stdout", port)
            Socket("127.0.0.1", port!!).close()
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
