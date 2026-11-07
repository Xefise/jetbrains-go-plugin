package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoDelveTestStreamTest {
    private val events = mutableListOf<String>()
    private val dlv = StringBuilder()
    private var asked = 0
    private val stream = GoDelveTestStream({ asked++; "example.com/p" }, { events += it }, { text, _ -> dlv.append(text) })

    @Test
    fun `import path is resolved only when the binary starts printing`() {
        stream.write("DAP server listening at: 127.0.0.1:1\n", error = false)
        assertEquals(0, asked)
        stream.write("\u0016=== RUN   TestA\n", error = false)
        assertEquals(1, asked)
        stream.write("\u0016--- PASS: TestA (0.00s)\n", error = false)
        assertEquals(1, asked)
        assertEquals("DAP server listening at: 127.0.0.1:1\n", dlv.toString())
    }

    @Test
    fun `ready line split anywhere still ends dlv output`() {
        "DAP server listening at: 127.0.0.1:1\n".forEach { stream.write(it.toString(), error = false) }
        stream.write("hello from init\n", error = false)
        stream.finish()
        assertEquals("DAP server listening at: 127.0.0.1:1\n", dlv.toString())
        assertTrue(events.toString(), events.any { it.contains("hello from init") })
    }

    @Test
    fun `a marker in the middle of a long line is not a line start`() {
        stream.write("x".repeat(5000), error = false)
        stream.write("DAP server listening at: 127.0.0.1:1\n", error = false)
        stream.write("after\n", error = false)
        stream.finish()
        assertEquals(0, asked)
        assertTrue(dlv.endsWith("after\n"))
    }

    @Test
    fun `binary panic on stderr without a final line fails the package`() {
        stream.write("DAP server listening at: 127.0.0.1:1\n", error = false)
        stream.write("\u0016=== RUN   TestA\n", error = false)
        stream.write("pan", error = true)
        stream.write("ic: boom\n", error = true)
        stream.finish()
        assertTrue(events.toString(), events.last().contains("\"Action\":\"fail\""))
    }
}
