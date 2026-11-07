package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class GoModulesTest {
    @Test
    fun `module line is read after comments and blank lines`() {
        assertEquals("example.com/gotests", GoModules.modulePath("// header\n\nmodule example.com/gotests\n\ngo 1.22\n"))
    }

    @Test
    fun `quoted module path and trailing comment are stripped`() {
        assertEquals("example.com/q", GoModules.modulePath("module \"example.com/q\" // why\n"))
        assertEquals("example.com/r", GoModules.modulePath("module `example.com/r`\n"))
    }

    @Test
    fun `commented out module line is ignored`() {
        assertNull(GoModules.modulePath("// module example.com/old\ngo 1.22\n"))
    }

    @Test
    fun `module without a path is no module`() {
        assertNull(GoModules.modulePath("module\n"))
        assertNull(GoModules.modulePath(""))
    }

    @Test
    fun `windows line endings do not leak into the path`() {
        assertEquals("example.com/w", GoModules.modulePath("module example.com/w\r\ngo 1.22\r\n"))
    }

    @Test
    fun `import path maps to a directory inside the module`() {
        assertEquals("", GoModules.relativeDirectory("example.com/m", "example.com/m"))
        assertEquals("a/b", GoModules.relativeDirectory("example.com/m/a/b", "example.com/m"))
    }

    @Test
    fun `module path prefix without a slash is another module`() {
        assertNull(GoModules.relativeDirectory("example.com/mod2/x", "example.com/mod"))
    }

    @Test
    fun `block form declares the module on its first line`() {
        assertEquals("example.com/b", GoModules.modulePath("module (\n\t// comment\n\texample.com/b\n)\n"))
        assertNull(GoModules.modulePath("module (\n)\n"))
    }

    private fun module(root: String, modulePath: String, path: String = "/p/$root", inContent: Boolean = true) =
        GoModules.Module(root, modulePath, path, inContent)

    private fun owners(importPath: String, vararg modules: GoModules.Module<String>) =
        GoModules.owners(importPath, modules.toList()).map { it.first.root to it.second }

    @Test
    fun `nested module shadows its parent`() {
        val modules = arrayOf(module("root", "example.com/m"), module("tools", "example.com/m/tools"))
        assertEquals(listOf("tools" to "lint", "root" to "tools/lint"), owners("example.com/m/tools/lint", *modules))
        assertEquals(listOf("root" to "toolsx"), owners("example.com/m/toolsx", *modules))
    }

    @Test
    fun `copy in project content wins over a library copy with a shorter path`() {
        val modules = arrayOf(module("lib", "example.com/m", "/n", inContent = false), module("src", "example.com/m", "/p/src"))
        assertEquals(listOf("src", "lib"), owners("example.com/m/x", *modules).map { it.first })
    }

    @Test
    fun `duplicate copies in content are ordered by shortest path then lexicographically`() {
        val modules = arrayOf(
            module("deep", "example.com/m", "/p/testdata/copy"),
            module("b", "example.com/m", "/p/b"),
            module("a", "example.com/m", "/p/a"),
        )
        assertEquals(listOf("a", "b", "deep"), owners("example.com/m", *modules).map { it.first })
    }

    @Test
    fun `import path outside every module has no owner`() {
        assertEquals(emptyList<Pair<String, String>>(), owners("fmt", module("root", "example.com/m")))
    }

    @Test
    fun `import path of a directory comes from the nearest go mod above it`() {
        val root = Files.createTempDirectory("gomodules")
        try {
            Files.writeString(root.resolve("go.mod"), "module example.com/outer\n")
            val nested = Files.createDirectories(root.resolve("nested/pkg/deep"))
            Files.writeString(root.resolve("nested/go.mod"), "module example.com/inner\n")
            val plain = Files.createDirectories(root.resolve("a/b"))
            assertEquals("example.com/outer", GoModules.importPath(root))
            assertEquals("example.com/outer/a/b", GoModules.importPath(plain))
            assertEquals("example.com/inner/pkg/deep", GoModules.importPath(nested))
            assertEquals("example.com/outer/a/b", GoModules.importPath(root.resolve("a/./b/../b")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory without a module line has no import path`() {
        val root = Files.createTempDirectory("gomodules")
        try {
            Files.writeString(root.resolve("go.mod"), "go 1.22\n")
            assertNull(GoModules.importPath(root))
            // A go.mod directory is not a go.mod file.
            Files.createDirectories(root.resolve("x/go.mod"))
            assertNull(GoModules.importPath(root.resolve("x")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
