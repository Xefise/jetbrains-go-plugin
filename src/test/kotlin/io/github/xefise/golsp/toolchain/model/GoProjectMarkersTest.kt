package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoProjectMarkersTest {
    @Test
    fun `go mod, go work or a go file mark a Go directory`() {
        assertTrue(GoProjectMarkers.hasGoMarker(listOf("README.md", "go.mod")))
        assertTrue(GoProjectMarkers.hasGoMarker(listOf("go.work")))
        assertTrue(GoProjectMarkers.hasGoMarker(listOf("main.go")))
    }

    @Test
    fun `JS project files and look-alikes are not markers`() {
        assertFalse(GoProjectMarkers.hasGoMarker(listOf("package.json", "go.sum", "go.mod.bak", "cargo.toml", "golang")))
        assertFalse(GoProjectMarkers.hasGoMarker(emptyList()))
    }

    @Test
    fun `hidden and dependency directories are not scanned`() {
        listOf(".git", ".idea", "node_modules", "vendor").forEach { assertFalse(it, GoProjectMarkers.isScannedSubdirectory(it)) }
        assertTrue(GoProjectMarkers.isScannedSubdirectory("backend"))
    }
}
