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
