package io.github.xefise.golsp.lsp.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoplsModuleCommandsTest {
    @Test
    fun `go get and go mod tidy rewrite module files`() {
        assertTrue(GoplsModuleCommands.rewritesModuleFiles("Running go get"))
        assertTrue(GoplsModuleCommands.rewritesModuleFiles("Running go mod tidy"))
    }

    @Test
    fun `workspace loading and test runs do not`() {
        assertFalse(GoplsModuleCommands.rewritesModuleFiles("Setting up workspace"))
        assertFalse(GoplsModuleCommands.rewritesModuleFiles("Running go test"))
        assertFalse(GoplsModuleCommands.rewritesModuleFiles(null))
    }

    @Test
    fun `module file uris are recognised by file name`() {
        assertTrue(GoplsModuleCommands.isModuleFile("file:///home/u/app/go.mod"))
        assertTrue(GoplsModuleCommands.isModuleFile("file:///home/u/app/go.sum"))
        assertTrue(GoplsModuleCommands.isModuleFile("file:///C:/work/app/go.work"))
    }

    @Test
    fun `go sources and look-alike names are not module files`() {
        assertFalse(GoplsModuleCommands.isModuleFile("file:///home/u/app/main.go"))
        assertFalse(GoplsModuleCommands.isModuleFile("file:///home/u/app/old.go.mod"))
        assertFalse(GoplsModuleCommands.isModuleFile("file:///home/u/go.mod/main.go"))
    }
}
