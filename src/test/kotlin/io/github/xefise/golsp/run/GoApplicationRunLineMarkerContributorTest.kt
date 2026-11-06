package io.github.xefise.golsp.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.GoLanguage

class GoApplicationRunLineMarkerContributorTest : BasePlatformTestCase() {
    private val contributor = GoApplicationRunLineMarkerContributor()

    // Text offsets of the leaves that get a marker.
    private fun markedOffsets(text: String, name: String = "main.go"): List<Int> {
        val file = myFixture.configureByText(name, text)
        return PsiTreeUtil.collectElements(file) { it.firstChild == null }
            .filter { contributor.getInfo(it) != null }
            .map(PsiElement::getTextOffset)
    }

    private fun assertMarkedOnlyAt(text: String, anchor: String) {
        assertEquals(listOf(text.indexOf(anchor)), markedOffsets(text))
    }

    fun testMainFunctionInPackageMainIsMarkedOnItsName() {
        assertMarkedOnlyAt("package main\n\nfunc main() {\n}\n", "main()")
    }

    fun testMarkerOffersExecutorActions() {
        myFixture.configureByText("main.go", "package main\n\nfunc ma<caret>in() {}\n")
        val info = contributor.getInfo(myFixture.file.findElementAt(myFixture.caretOffset)!!)!!
        assertTrue(info.actions.isNotEmpty())
    }

    // Daemon highlighting (findGuttersAtCaret) would start LSP4IJ features and hang the headless test.
    fun testContributorIsRegisteredForGoLanguage() {
        assertTrue(RunLineMarkerContributor.EXTENSION.allForLanguage(GoLanguage).any { it is GoApplicationRunLineMarkerContributor })
    }

    fun testWhitespaceAndCommentsInsideEmptyParametersStillMark() {
        assertMarkedOnlyAt("package main\n\nfunc main /* c */ ( /* none */ ) {}\n", "main /*")
    }

    fun testMainAfterUnclosedBraceAtColumnZeroIsMarked() {
        val text = "package main\n\nfunc helper() {\n\tif true {\n\nfunc main() {}\n"
        assertMarkedOnlyAt(text, "main() {}")
    }

    fun testFileInExcludedDirectoryIsNotMarked() {
        val file = myFixture.addFileToProject("vendor/tool/main.go", "package main\n\nfunc main() {}\n")
        val leaf = file.findElementAt(file.text.indexOf("main()"))!!
        assertNotNull(contributor.getInfo(leaf))
        val dir = file.virtualFile.parent
        PsiTestUtil.addExcludedRoot(module, dir)
        try {
            assertNull(contributor.getInfo(leaf))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, dir)
        }
    }

    fun testOtherPackageIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package mainx\n\nfunc main() {}\n"))
    }

    fun testFileWithoutPackageClauseIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("func main() {}\n"))
    }

    fun testTestFileIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package main\n\nfunc main() {}\n", "main_test.go"))
    }

    fun testMethodNamedMainIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package main\n\ntype T struct{}\n\nfunc (T) main() {}\n"))
    }

    fun testMainWithParametersIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package main\n\nfunc main(x int) {}\n"))
    }

    fun testGenericMainIsNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package main\n\nfunc main[T any]() {}\n"))
    }

    fun testOtherFunctionNamesAreNotMarked() {
        assertEquals(emptyList<Int>(), markedOffsets("package main\n\nfunc Main() {}\nfunc init() {}\nfunc mainly() {}\n"))
    }

    fun testMainInCommentStringOrLiteralIsNotMarked() {
        val text = """
            package main

            // func main() {}
            var s = "func main() {}"
            var f = func() {}

            func run() {
            	g := func() {}
            	_ = g
            }
        """.trimIndent()
        assertEquals(emptyList<Int>(), markedOffsets(text))
    }
}
