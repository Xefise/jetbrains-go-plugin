package io.github.xefise.golsp.debug.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelveAttachServerTest {
    @Test
    fun `server listens on an ephemeral loopback port`() {
        val spec = DelveAttachServer.command("/go/bin/dlv", "/work", mapOf("PATH" to "/go/bin"))
        assertEquals("/go/bin/dlv", spec.executable)
        assertEquals(listOf("dap", "--listen=127.0.0.1:0"), spec.arguments)
        assertEquals("/work", spec.workingDirectory)
        assertEquals(mapOf("PATH" to "/go/bin"), spec.environment)
    }

    @Test
    fun `ready port is read from the line dlv prints`() {
        // Captured from `dlv dap --listen=127.0.0.1:0`, Delve 1.27.2.
        assertEquals(63144, DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:63144\n"))
    }

    @Test
    fun `ready port is found after other dlv output and with crlf`() {
        assertEquals(5000, DelveAttachServer.readyPort("Warning: something\r\nDAP server listening at: 127.0.0.1:5000\r\n"))
    }

    @Test
    fun `ready line still being written gives no port`() {
        // "...:63" could be the start of 63144.
        assertNull(DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:63"))
    }

    @Test
    fun `no ready line gives no port`() {
        assertNull(DelveAttachServer.readyPort(""))
        assertNull(DelveAttachServer.readyPort("Warning: build flags ignored with dap\n"))
    }

    @Test
    fun `ready line with an impossible port gives no port`() {
        assertNull(DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:0\n"))
        assertNull(DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:65536\n"))
        assertNull(DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:abc\n"))
        assertEquals(65535, DelveAttachServer.readyPort("DAP server listening at: 127.0.0.1:65535\n"))
    }

    @Test
    fun `only absolute executable paths are candidates`() {
        assertTrue(DelveAttachServer.isCandidatePath("/Users/me/bin/server"))
        assertFalse(DelveAttachServer.isCandidatePath(null))
        assertFalse(DelveAttachServer.isCandidatePath(""))
        assertFalse(DelveAttachServer.isCandidatePath("  "))
        assertFalse(DelveAttachServer.isCandidatePath("server"))
        assertFalse(DelveAttachServer.isCandidatePath("./bin/server"))
    }

    @Test
    fun `session name carries executable and pid`() {
        assertEquals("Attach to server (4242)", DelveAttachServer.sessionName("server", 4242))
    }

    @Test
    fun `start failure carries exit code and stderr`() {
        assertEquals(
            "dlv dap exited before it started listening (exit code 1): listen tcp: address in use",
            DelveAttachServer.startFailure(1, "listen tcp: address in use\n"),
        )
        assertEquals("dlv dap exited before it started listening", DelveAttachServer.startFailure(null, " "))
    }

    @Test
    fun `quiet or benign stderr is no attach failure`() {
        assertNull(DelveAttachServer.attachFailure("", macOS = true))
        assertNull(DelveAttachServer.attachFailure("Detaching and continuing\n", macOS = true))
        assertNull(DelveAttachServer.attachFailure("2026/11/08 error layer=dap: client closed\n", macOS = true))
    }

    @Test
    fun `macos debugserver refusal gets the DevToolsSecurity hint`() {
        val stderr = "error: debugserver: attach failed: not allowed to attach to process\n"
        assertEquals(
            "error: debugserver: attach failed: not allowed to attach to process\n${DelveAttachServer.MACOS_HINT}",
            DelveAttachServer.attachFailure(stderr, macOS = true),
        )
    }

    @Test
    fun `macos task_for_pid failure gets the DevToolsSecurity hint`() {
        assertEquals(
            "could not attach to pid 42: task_for_pid failed\n${DelveAttachServer.MACOS_HINT}",
            DelveAttachServer.attachFailure("could not attach to pid 42: task_for_pid failed", macOS = true),
        )
    }

    @Test
    fun `permission wording without debugserver gets no hint`() {
        assertEquals(
            "could not attach to pid 42: permission denied",
            DelveAttachServer.attachFailure("could not attach to pid 42: permission denied", macOS = true),
        )
    }

    @Test
    fun `attach failure off macos has no DevToolsSecurity hint`() {
        assertEquals(
            "error: debugserver: attach failed: not allowed",
            DelveAttachServer.attachFailure("error: debugserver: attach failed: not allowed", macOS = false),
        )
    }

    @Test
    fun `clean exit after a started session is not reported`() {
        assertNull(DelveAttachServer.exitReport("", 0, sessionStarted = true, destroyedByUs = false, macOS = true))
    }

    @Test
    fun `dlv stopped by the plugin is never reported`() {
        assertNull(DelveAttachServer.exitReport("could not attach to pid 1", 1, sessionStarted = false, destroyedByUs = true, macOS = true))
    }

    @Test
    fun `exit before the session started is reported`() {
        assertEquals(
            "dlv dap exited before the debug session started (exit code 2): boom",
            DelveAttachServer.exitReport("boom\n", 2, sessionStarted = false, destroyedByUs = false, macOS = false),
        )
    }

    @Test
    fun `non zero exit after the session started is reported`() {
        assertEquals("dlv dap exited (exit code 1)", DelveAttachServer.exitReport("", 1, sessionStarted = true, destroyedByUs = false, macOS = false))
    }

    @Test
    fun `failed attach in stderr is reported even after a clean exit`() {
        assertEquals(
            "could not attach to pid 42: no such process",
            DelveAttachServer.exitReport("could not attach to pid 42: no such process", 0, sessionStarted = true, destroyedByUs = false, macOS = true),
        )
    }
}
