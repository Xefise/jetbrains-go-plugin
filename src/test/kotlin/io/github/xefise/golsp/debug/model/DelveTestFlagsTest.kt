package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.debug.model.DelveTestFlags.Split
import org.junit.Assert.assertEquals
import org.junit.Test

class DelveTestFlagsTest {
    private fun split(vararg flags: String): Split = DelveTestFlags.split(flags.toList())

    @Test
    fun `build flags stay build flags`() {
        val flags = arrayOf("-race", "-tags", "integration", "-ldflags=-X main.v=a b", "-gcflags=all=-N", "-mod=vendor", "-trimpath")
        assertEquals(Split(flags.toList(), emptyList()), split(*flags))
    }

    @Test
    fun `coverage, vet and fuzz shape the build and stay build flags`() {
        assertEquals(Split(listOf("-cover", "-covermode=atomic", "-vet=off", "-fuzz=FuzzX"), emptyList()), split("-cover", "-covermode=atomic", "-vet=off", "-fuzz=FuzzX"))
    }

    @Test
    fun `value test flags with a separate value become one test binary argument`() {
        assertEquals(
            Split(emptyList(), listOf("-test.count=1", "-test.timeout=30s", "-test.cpu=1,2")),
            split("-count", "1", "-timeout", "30s", "-cpu", "1,2"),
        )
    }

    @Test
    fun `value test flags with an equals sign keep their value verbatim`() {
        assertEquals(Split(emptyList(), listOf("-test.run=^A$|^B$", "-test.skip=a=b")), split("-run=^A$|^B$", "-skip=a=b"))
    }

    @Test
    fun `boolean test flags take no separate value`() {
        assertEquals(Split(listOf("./x"), listOf("-test.short", "-test.failfast")), split("-short", "./x", "-failfast"))
    }

    @Test
    fun `double dash and test dot prefixes are the same flag`() {
        assertEquals(Split(emptyList(), listOf("-test.count=2", "-test.benchmem")), split("--count=2", "-test.benchmem"))
    }

    @Test
    fun `verbose flag is dropped in every spelling`() {
        assertEquals(Split(listOf("-race"), emptyList()), split("-v", "-race", "-v=true", "-test.v=test2json", "--v"))
    }

    @Test
    fun `split keeps the user order inside each group`() {
        assertEquals(
            Split(listOf("-race", "-tags=x"), listOf("-test.count=1", "-test.short")),
            split("-race", "-count=1", "-tags=x", "-short"),
        )
    }

    @Test
    fun `trailing value flag without a value is left to go test to report`() {
        assertEquals(Split(listOf("-timeout"), emptyList()), split("-timeout"))
    }

    @Test
    fun `flag-like names that are not test flags stay build flags`() {
        assertEquals(Split(listOf("-runner", "-", "--", "-x"), emptyList()), split("-runner", "-", "--", "-x"))
    }

    @Test
    fun `no flags split into nothing`() {
        assertEquals(Split(emptyList(), emptyList()), split())
    }
}
