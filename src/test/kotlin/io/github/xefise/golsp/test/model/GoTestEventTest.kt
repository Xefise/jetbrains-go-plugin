package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTestEventTest {
    @Test
    fun `test event fields are read`() {
        val line = """{"Time":"2026-11-07T16:32:58.687107+04:00","Action":"pass","Package":"example.com/gotests/calc",""" +
            """"Test":"TestSubtests/with_space","Elapsed":0.25}"""
        assertEquals(
            GoTestEvent(action = "pass", pkg = "example.com/gotests/calc", test = "TestSubtests/with_space", elapsedSeconds = 0.25),
            GoTestEvent.parse(line),
        )
    }

    @Test
    fun `build events carry import path and output`() {
        val line = """{"ImportPath":"p [p.test]","Action":"build-output","Output":"x.go:1:1: bad\n"}"""
        assertEquals(GoTestEvent(action = "build-output", importPath = "p [p.test]", output = "x.go:1:1: bad\n"), GoTestEvent.parse(line))
    }

    @Test
    fun `failed build and output type are read`() {
        val event = GoTestEvent.parse("""{"Action":"fail","Package":"p","Elapsed":0,"FailedBuild":"p [p.test]","OutputType":"frame"}""")!!
        assertEquals("p [p.test]", event.failedBuild)
        assertEquals("frame", event.outputType)
        assertEquals(0.0, event.elapsedSeconds!!, 0.0)
    }

    @Test
    fun `plain text from the go command is not an event`() {
        assertNull(GoTestEvent.parse("go: cannot find main module, but found .git/config"))
    }

    @Test
    fun `json without an action is not an event`() {
        assertNull(GoTestEvent.parse("""{"Package":"p"}"""))
    }

    @Test
    fun `truncated json is not an event`() {
        assertNull(GoTestEvent.parse("""{"Action":"output","Package":"p","Outp"""))
    }

    @Test
    fun `action of the wrong type is not an event`() {
        assertNull(GoTestEvent.parse("""{"Action":1}"""))
    }

    @Test
    fun `surrounding whitespace and line break are tolerated`() {
        assertEquals("start", GoTestEvent.parse("  {\"Action\":\"start\",\"Package\":\"p\"}\r\n")?.action)
    }

    @Test
    fun `blank line is not an event`() {
        assertNull(GoTestEvent.parse(""))
    }
}
