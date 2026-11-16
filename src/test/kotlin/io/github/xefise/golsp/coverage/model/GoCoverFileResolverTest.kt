package io.github.xefise.golsp.coverage.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoCoverFileResolverTest {
    private val resolver = GoCoverFileResolver(
        mapOf(
            "example.com/m" to "/work/m",
            "example.com/m/nested" to "/work/m/nested/",
            "example.com/lib" to "/elsewhere/lib",
        ),
    )

    @Test
    fun `file of a package below the module root`() {
        assertEquals("/work/m/calc/calc.go", resolver.resolve("example.com/m/calc/calc.go"))
    }

    @Test
    fun `file of the module root package`() {
        assertEquals("/work/m/main.go", resolver.resolve("example.com/m/main.go"))
    }

    @Test
    fun `nested module wins over its parent`() {
        assertEquals("/work/m/nested/x/x.go", resolver.resolve("example.com/m/nested/x/x.go"))
    }

    @Test
    fun `module path prefix without a slash boundary does not match`() {
        assertNull(resolver.resolve("example.com/mother/a.go"))
    }

    @Test
    fun `package outside every module is unresolved`() {
        assertNull(resolver.resolve("github.com/dep/pkg/a.go"))
        assertNull(resolver.resolve("a.go"))
    }

    @Test
    fun `absolute file names are used as is`() {
        assertEquals("/abs/dir/a.go", resolver.resolve("/abs/dir/a.go"))
        assertEquals("C:/work/a.go", resolver.resolve("C:\\work\\a.go"))
    }

    @Test
    fun `package named by its directory drops the underscore`() {
        assertEquals("/abs/dir/a.go", resolver.resolve("_/abs/dir/a.go"))
    }

    private fun existing(vararg files: String) = GoCoverFileResolver(emptyMap(), listOf("/work/m/", "/work/n")) { it in files }

    @Test
    fun `windows package named by its directory restores the drive colon`() {
        assertEquals("C:/Users/me/x/a.go", existing("C:/Users/me/x/a.go").resolve("_/C_/Users/me/x/a.go"))
    }

    @Test
    fun `unix directory that looks like a mangled drive is kept when it exists`() {
        assertEquals("/C_/x/a.go", existing("/C_/x/a.go").resolve("_/C_/x/a.go"))
    }

    @Test
    fun `package named by a mangled directory that does not exist is unresolved`() {
        // `my project` was written as `my_project`; the name cannot be reversed.
        assertNull(existing().resolve("_/Users/me/my_project/a.go"))
    }

    @Test
    fun `package outside every module resolves under the first vendor that has the file`() {
        assertEquals("/work/n/vendor/github.com/dep/pkg/a.go", existing("/work/n/vendor/github.com/dep/pkg/a.go").resolve("github.com/dep/pkg/a.go"))
        assertEquals("/work/m/vendor/github.com/dep/pkg/a.go", existing("/work/m/vendor/github.com/dep/pkg/a.go").resolve("github.com/dep/pkg/a.go"))
    }

    @Test
    fun `package missing from vendor is unresolved`() {
        assertNull(existing().resolve("github.com/dep/pkg/a.go"))
    }

    @Test
    fun `module package wins over a vendored copy`() {
        val both = GoCoverFileResolver(mapOf("example.com/lib" to "/lib"), listOf("/work/m")) { true }
        assertEquals("/lib/a.go", both.resolve("example.com/lib/a.go"))
    }

    @Test
    fun `go work use directives in a block and on single lines`() {
        val text = """
            go 1.27

            use (
            	./app // the binary
            	"./with space"
            	// ./commented
            )
            use ./lib
            use `../other`
            toolchain go1.27.3
        """.trimIndent()
        assertEquals(listOf("./app", "./with space", "./lib", "../other"), GoCoverFileResolver.goWorkUses(text))
    }

    @Test
    fun `go work without use directives`() {
        assertEquals(emptyList<String>(), GoCoverFileResolver.goWorkUses("go 1.27\n"))
    }
}
