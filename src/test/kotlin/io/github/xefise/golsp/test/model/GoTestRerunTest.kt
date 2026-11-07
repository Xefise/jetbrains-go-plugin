package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.test.model.GoTestRerun.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTestRerunTest {
    private fun pkg(importPath: String) = GoTestLocation.Target(importPath, null)

    private fun test(importPath: String, name: String) = GoTestLocation.Target(importPath, name)

    @Test
    fun `nothing failed selects nothing`() {
        assertNull(GoTestRerun.select(emptyList(), listOf(test("m/a", "TestA"))))
    }

    @Test
    fun `failed subtest covers its failed parents and package`() {
        val failed = listOf(test("m/a", "TestT/x/y"), test("m/a", "TestT/x"), test("m/a", "TestT"), pkg("m/a"))
        assertEquals(Selection(listOf("m/a"), listOf("TestT/x/y")), GoTestRerun.select(failed, failed))
    }

    @Test
    fun `parent that failed by itself is selected`() {
        val failed = listOf(test("m/a", "TestParent"), pkg("m/a"))
        assertEquals(Selection(listOf("m/a"), listOf("TestParent")), GoTestRerun.select(failed, failed))
    }

    @Test
    fun `name prefix without a level boundary is not a parent`() {
        val failed = listOf(test("m/a", "TestA"), test("m/a", "TestAB/x"))
        assertEquals(Selection(listOf("m/a"), listOf("TestA", "TestAB/x")), GoTestRerun.select(failed, failed))
    }

    @Test
    fun `duplicate subtest names stay distinct and repeats collapse`() {
        val failed = listOf(test("m/a", "TestT/same#01"), test("m/a", "TestT/same#01"), test("m/a", "TestT/same"))
        assertEquals(Selection(listOf("m/a"), listOf("TestT/same#01", "TestT/same")), GoTestRerun.select(failed, failed))
    }

    @Test
    fun `package failed outside its tests reruns in full`() {
        val seen = listOf(pkg("m/a"), test("m/a", "TestA"))
        assertEquals(Selection(listOf("m/a"), emptyList()), GoTestRerun.select(listOf(pkg("m/a"), pkg("m/a")), seen))
    }

    @Test
    fun `failed packages keep the order of the tree`() {
        val failed = listOf(test("m/z", "TestZ"), pkg("m/z"), test("m/a", "TestA"), pkg("m/a"))
        assertEquals(Selection(listOf("m/z", "m/a"), listOf("TestZ", "TestA")), GoTestRerun.select(failed, failed))
    }

    @Test
    fun `whole package next to failed tests contributes the top-level tests it ran`() {
        // m/a passed its tests and then failed (TestMain exit code); one shared -run must still select them.
        val seen = listOf(pkg("m/a"), test("m/a", "TestA"), test("m/a", "TestA/sub"), test("m/a", "BenchmarkA"), pkg("m/b"), test("m/b", "TestB"))
        val failed = listOf(pkg("m/a"), test("m/b", "TestB"), pkg("m/b"))
        assertEquals(Selection(listOf("m/a", "m/b"), listOf("TestA", "BenchmarkA", "TestB")), GoTestRerun.select(failed, seen))
    }

    @Test
    fun `package failed before any test next to failed tests adds no names but keeps the package`() {
        // Build, init and TestMain failures reproduce under any -run; a failure that needs its unstarted tests does not.
        val failed = listOf(pkg("m/broken"), test("m/b", "TestB"), pkg("m/b"))
        assertEquals(Selection(listOf("m/broken", "m/b"), listOf("TestB")), GoTestRerun.select(failed, failed))
    }
}
