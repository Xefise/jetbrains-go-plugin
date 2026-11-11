package io.github.xefise.golsp.lang.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoPackageNamesTest {
    @Test
    fun takesPackageOfNonTestSources() {
        val siblings = mapOf("a_test.go" to "foo_test", "b.go" to "foo", "c.go" to null)
        assertEquals("foo", GoPackageNames.forNewFile("dir", siblings))
    }

    @Test
    fun keepsMainPackageRegardlessOfDirectoryName() {
        assertEquals("main", GoPackageNames.forNewFile("my-tool", mapOf("main.go" to "main")))
    }

    @Test
    fun picksFirstSourceByNameWhenPackagesDisagree() {
        assertEquals("aaa", GoPackageNames.forNewFile("dir", mapOf("z.go" to "zzz", "a.go" to "aaa")))
    }

    @Test
    fun onlyTestFilesStripExternalTestSuffix() {
        assertEquals("foo", GoPackageNames.forNewFile("dir", mapOf("a_test.go" to "foo_test")))
        assertEquals("foo", GoPackageNames.forNewFile("dir", mapOf("a_test.go" to "foo")))
        assertEquals("dir", GoPackageNames.forNewFile("dir", mapOf("a_test.go" to "_test")))
    }

    @Test
    fun fallsBackToDirectoryWhenNoPackageDeclared() {
        assertEquals("util", GoPackageNames.forNewFile("util", emptyMap()))
        assertEquals("util", GoPackageNames.forNewFile("util", mapOf("a.go" to null, "b.go" to "")))
    }

    @Test
    fun directoryNameBecomesIdentifier() {
        assertEquals("my_pkg", GoPackageNames.fromDirectoryName("my-pkg"))
        assertEquals("v1_2", GoPackageNames.fromDirectoryName("v1.2"))
        assertEquals("mypkg", GoPackageNames.fromDirectoryName("MyPkg"))
        assertEquals("привет", GoPackageNames.fromDirectoryName("Привет"))
        assertEquals("_2fa", GoPackageNames.fromDirectoryName("2fa"))
        assertEquals("go_", GoPackageNames.fromDirectoryName("go"))
        assertEquals("type_", GoPackageNames.fromDirectoryName("type"))
        assertEquals("main", GoPackageNames.fromDirectoryName("-"))
        assertEquals("main", GoPackageNames.fromDirectoryName(""))
    }
}
