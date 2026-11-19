package io.github.xefise.golsp.execution.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoRaceDetectorTest {
    @Test
    fun `adds race only when enabled`() {
        assertEquals(listOf("-v", "-race"), GoRaceDetector.withRaceFlag(listOf("-v"), enabled = true))
        assertEquals(listOf("-v"), GoRaceDetector.withRaceFlag(listOf("-v"), enabled = false))
    }

    @Test
    fun `keeps a race flag the user already typed`() {
        for (flag in listOf("-race", "--race", "-race=true", "-race=false", "--race=1")) {
            assertEquals(flag, listOf("-count=1", flag), GoRaceDetector.withRaceFlag(listOf("-count=1", flag), enabled = true))
        }
    }

    @Test
    fun `similar flags and values are not the race flag`() {
        assertEquals(listOf("-racex", "race", "-race"), GoRaceDetector.withRaceFlag(listOf("-racex", "race"), enabled = true))
        assertEquals(listOf("---race", "-race"), GoRaceDetector.withRaceFlag(listOf("---race"), enabled = true))
    }

    @Test
    fun `finds the report header at the line start`() {
        assertEquals(0 until 18, GoRaceDetector.reportHeader("WARNING: DATA RACE\n"))
        assertEquals(2 until 20, GoRaceDetector.reportHeader("  WARNING: DATA RACE"))
    }

    @Test
    fun `ignores the header inside other text`() {
        assertNull(GoRaceDetector.reportHeader("log: WARNING: DATA RACE"))
        assertNull(GoRaceDetector.reportHeader("WARNING: DATA"))
        assertNull(GoRaceDetector.reportHeader("=================="))
    }
}
