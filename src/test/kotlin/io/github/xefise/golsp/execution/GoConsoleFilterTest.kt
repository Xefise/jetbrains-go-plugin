package io.github.xefise.golsp.execution

import com.intellij.execution.filters.FileHyperlinkInfo
import com.intellij.execution.filters.Filter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class GoConsoleFilterTest : BasePlatformTestCase() {
    private lateinit var root: File

    override fun setUp() {
        super.setUp()
        root = FileUtil.createTempDirectory("go-console-filter", null)
        VfsRootAccess.allowRootAccess(testRootDisposable, root.path, root.canonicalPath)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(root)
        } finally {
            super.tearDown()
        }
    }

    private fun diskFile(relative: String): File =
        File(root, relative).also { it.parentFile.mkdirs(); it.writeText("package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(x)\n}\n") }

    private fun dir(relative: String): String = FileUtil.toSystemIndependentName(File(root, relative).also { it.mkdirs() }.path)

    private fun filter(workingDirectory: String? = null, packageDirectory: String? = null) =
        GoConsoleFilter(project, workingDirectory, packageDirectory)

    private data class Target(val path: String, val line: Int, val column: Int, val start: Int, val end: Int)

    // Simulates a line appended after 100 chars of earlier output.
    private fun apply(filter: GoConsoleFilter, line: String): Target? {
        val result = filter.applyFilter(line, 100 + line.length) ?: return null
        val item: Filter.ResultItem = result.resultItems.single()
        val descriptor = (item.hyperlinkInfo as FileHyperlinkInfo).descriptor!!
        // The descriptor carries an offset once the document is loaded; convert it back to line and column.
        val document = FileDocumentManager.getInstance().getDocument(descriptor.file)!!
        val line = document.getLineNumber(descriptor.offset)
        val column = descriptor.offset - document.getLineStartOffset(line)
        return Target(descriptor.file.path, line, column, item.highlightStartOffset, item.highlightEndOffset)
    }

    private fun path(file: File) = FileUtil.toSystemIndependentName(file.canonicalPath)

    private fun canonical(target: Target?) = target?.copy(path = path(File(target.path)))

    fun testRaceReportFrameLinksFileAndLine() {
        val file = diskFile("race/main.go")
        val line = "      ${path(file)}:6 +0x3c\n"
        val expectedStart = 100 + 6
        assertEquals(Target(path(file), 5, 0, expectedStart, expectedStart + path(file).length + 2), canonical(apply(filter(), line)))
    }

    fun testRaceReportHeaderIsHighlightedWithoutALink() {
        val line = "WARNING: DATA RACE\n"
        val item = filter().applyFilter(line, 100 + line.length)!!.resultItems.single()
        assertNull(item.hyperlinkInfo)
        assertEquals(100, item.highlightStartOffset)
        assertEquals(118, item.highlightEndOffset)
        assertNotNull(item.highlightAttributes)
    }

    fun testAbsolutePanicFrameLinksFileAndLine() {
        val file = diskFile("abs/main.go")
        val line = "\t${path(file)}:3 +0x1d\n"
        val expectedEnd = 100 + 1 + path(file).length + 2
        assertEquals(Target(path(file), 2, 0, 101, expectedEnd), canonical(apply(filter(), line)))
    }

    fun testAbsolutePanicFrameWithSpacesLinksTheWholePathOnce() {
        val file = diskFile("My Projects/app/main.go")
        val line = "\t${path(file)}:3 +0x1d\n"
        val expectedEnd = 100 + 1 + path(file).length + 2
        // The tail "Projects/app/main.go" also resolves from cwd, yet must not become a second, overlapping link.
        diskFile("cwd/Projects/app/main.go")
        assertEquals(Target(path(file), 2, 0, 101, expectedEnd), canonical(apply(filter(dir("cwd")), line)))
    }

    fun testUnresolvedSpacedPathFallsBackToItsTail() {
        val file = diskFile("pkg/sub/x.go")
        // "/nowhere/a " precedes "sub/x.go:3", which starts at 11 and is 10 chars long
        val target = canonical(apply(filter(dir("pkg"), dir("pkg")), "/nowhere/a sub/x.go:3\n"))
        assertEquals(Target(path(file), 2, 0, 111, 121), target)
    }

    fun testRelativePathResolvesAgainstWorkingDirectory() {
        val file = diskFile("wd/data/x.go")
        assertEquals(path(file), canonical(apply(filter(dir("wd"), dir("pkg")), "data/x.go:2: msg\n"))!!.path)
    }

    fun testRelativePathFallsBackToPackageDirectory() {
        val file = diskFile("pkg/util.go")
        assertEquals(path(file), canonical(apply(filter(dir("wd"), dir("pkg")), "util.go:4: msg\n"))!!.path)
    }

    fun testCompilerErrorPrefersPackageDirectoryOverWorkingDirectory() {
        diskFile("wd/main.go")
        val inPackage = diskFile("pkg/main.go")
        val target = canonical(apply(filter(dir("wd"), dir("pkg")), "./main.go:5:2: undefined: x\n"))
        // "./main.go:5:2" is 13 chars, line 5 col 2 -> 0-based 4, 1
        assertEquals(Target(path(inPackage), 4, 1, 100, 113), target)
    }

    fun testLineWithoutColumnOpensAtColumnZero() {
        diskFile("pkg/a_test.go")
        val target = apply(filter(dir("pkg"), dir("pkg")), "    a_test.go:3: got 1\n")!!
        assertEquals(2 to 0, target.line to target.column)
    }

    fun testUnknownRelativePathGivesNoLink() {
        assertNull(apply(filter(dir("wd"), dir("pkg")), "missing.go:3: msg\n"))
    }

    fun testDirectoryNamedLikeAFileGivesNoLink() {
        dir("pkg/odd.go")
        assertNull(apply(filter(dir("pkg"), dir("pkg")), "odd.go:1: msg\n"))
    }

    fun testHugeLineNumberGivesNoLink() {
        diskFile("pkg/main.go")
        assertNull(apply(filter(dir("pkg"), dir("pkg")), "main.go:99999999999: msg\n"))
    }

    fun testWindowsPathDoesNotFail() {
        assertNull(apply(filter(dir("wd"), dir("pkg")), "C:\\x\\y.go:3: msg\n"))
    }

    fun testUrlGivesNoLinkEvenWhenFileWithThatNameExists() {
        myFixture.addFileToProject("x.go", "package main\n")
        assertNull(apply(filter(), "GET http://host:8080/x.go:12 200\n"))
    }

    fun testSingleProjectFileWithThatNameIsTheLastResort() {
        val file = myFixture.addFileToProject("cmd/only.go", "package main\n\nfunc main() {}\n")
        assertEquals(Target(file.virtualFile.path, 2, 0, 100, 109), apply(filter(dir("wd"), dir("pkg")), "only.go:3 msg\n"))
    }

    fun testSeveralProjectFilesWithThatNameGiveNoLink() {
        myFixture.addFileToProject("a/dup.go", "package a\n")
        myFixture.addFileToProject("b/dup.go", "package b\n")
        assertNull(apply(filter(), "dup.go:1: msg\n"))
    }

    fun testIndexFallbackIsSkippedInDumbMode() {
        myFixture.addFileToProject("cmd/only.go", "package main\n")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNull(apply(filter(), "only.go:1: msg\n"))
        }
    }

    fun testParenthesisedFileNameLinksSingleProjectFile() {
        val file = myFixture.addFileToProject("cmd/main.go", "package main\n")
        // "(main.go)" begins at 7 in "failed (main.go)"; the name spans 8..15
        assertEquals(Target(file.virtualFile.path, 0, 0, 108, 115), apply(filter(), "failed (main.go)\n"))
    }

    fun testLaterReferenceLinksWhenAnEarlierOneDoesNotResolve() {
        val file = diskFile("pkg/main.go")
        val line = "listening on foo.local:8080 see main.go:3\n"
        // "main.go:3" starts at 32 and is 9 chars long
        assertEquals(Target(path(file), 2, 0, 132, 141), canonical(apply(filter(dir("pkg"), dir("pkg")), line)))
    }

    fun testReferenceInsideParenthesesLinks() {
        val file = diskFile("pkg/main.go")
        // "(main.go:3)": "main.go:3" spans 1..10
        assertEquals(Target(path(file), 2, 0, 101, 110), canonical(apply(filter(dir("pkg"), dir("pkg")), "(main.go:3)\n")))
    }

    fun testWindowsDotBackslashCompilerErrorPrefersPackageDirectory() {
        diskFile("wd/main.go")
        val inPackage = diskFile("pkg/main.go")
        assertEquals(path(inPackage), canonical(apply(filter(dir("wd"), dir("pkg")), ".\\main.go:5:2: undefined: x\n"))!!.path)
    }

    fun testIndexFallbackIgnoresNonGoNames() {
        myFixture.addFileToProject("10.0.0.1", "not go\n")
        assertNull(apply(filter(), "dial tcp 10.0.0.1:8080: refused\n"))
    }
}
