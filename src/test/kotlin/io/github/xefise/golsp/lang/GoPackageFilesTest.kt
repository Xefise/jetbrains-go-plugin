package io.github.xefise.golsp.lang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoPackageFilesTest {
    @Test
    fun `ignore build constraint in the header excludes the file`() {
        assertTrue(GoPackageFiles.isIgnoredByBuildConstraint("//go:build ignore\n\npackage main\n"))
        assertTrue(GoPackageFiles.isIgnoredByBuildConstraint("// Copyright\n\n// +build ignore\r\n\npackage main\n"))
        assertTrue(GoPackageFiles.isIgnoredByBuildConstraint("//go:build ignore  \npackage main\n"))
    }

    @Test
    fun `other constraints and ignore after the header do not exclude the file`() {
        assertFalse(GoPackageFiles.isIgnoredByBuildConstraint("//go:build integration\n\npackage p\n"))
        assertFalse(GoPackageFiles.isIgnoredByBuildConstraint("//go:build !ignore\n\npackage p\n"))
        assertFalse(GoPackageFiles.isIgnoredByBuildConstraint("package p\n\n//go:build ignore\n"))
        assertFalse(GoPackageFiles.isIgnoredByBuildConstraint(""))
    }

    @Test
    fun `underscore and dot prefixed names are ignored by go`() {
        assertTrue(GoPackageFiles.isIgnoredByName("_gen.go"))
        assertTrue(GoPackageFiles.isIgnoredByName(".hidden_test.go"))
        assertFalse(GoPackageFiles.isIgnoredByName("gen_.go"))
        assertFalse(GoPackageFiles.isIgnoredByName("main.go"))
    }
}
