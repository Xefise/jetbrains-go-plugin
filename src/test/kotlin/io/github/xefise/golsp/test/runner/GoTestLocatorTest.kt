package io.github.xefise.golsp.test.runner

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.GoFunctionHeader
import io.github.xefise.golsp.lang.GoSubtestCall

class GoTestLocatorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("go.mod", "module example.com/gotests\n\ngo 1.22\n")
        myFixture.addFileToProject("calc/calc.go", "package calc\n\nfunc TestNotInTestFile() {}\n")
        myFixture.addFileToProject(
            "calc/calc_test.go",
            """
            package calc

            func TestAdd(t *testing.T) {}

            func TestSubtests(t *testing.T) {
                t.Run("with space", func(t *testing.T) {})
                t.Run("a/b", func(t *testing.T) {})
                t.Run("nested", func(t *testing.T) {
                    t.Run("fails", func(t *testing.T) {})
                })
            }

            func TestOther(t *testing.T) {
                t.Run("fails", func(t *testing.T) {})
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("tools/go.mod", "module example.com/gotests/tools\n")
        myFixture.addFileToProject("tools/lint/lint_test.go", "package lint\n\nfunc TestLint(t *testing.T) {}\n")
    }

    private fun locate(url: String, protocol: String = "gotest"): List<PsiElement> =
        GoTestLocator.getLocation(protocol, url.removePrefix("gotest://"), project, GlobalSearchScope.projectScope(project))
            .map { it.psiElement }

    private fun single(url: String): PsiElement = locate(url).single()

    fun testPackageUrlResolvesToItsDirectory() {
        val dir = single("gotest://example.com/gotests/calc") as PsiDirectory
        assertEquals("calc", dir.name)
    }

    fun testModuleRootPackageResolvesToTheModuleDirectory() {
        val dir = single("gotest://example.com/gotests") as PsiDirectory
        assertEquals(myFixture.findFileInTempDir("go.mod").parent, dir.virtualFile)
    }

    fun testTopLevelTestResolvesToItsFunctionInTheTestFile() {
        val header = single("gotest://example.com/gotests/calc#TestAdd") as GoFunctionHeader
        assertEquals("TestAdd", header.functionName)
        assertEquals("calc_test.go", header.containingFile.name)
    }

    fun testSubtestWithSpaceResolvesToItsRunCall() {
        assertEquals("with space", (single("gotest://example.com/gotests/calc#TestSubtests/with_space") as GoSubtestCall).subtestName)
    }

    fun testNestedSubtestResolvesInsideItsOwnFunction() {
        val call = single("gotest://example.com/gotests/calc#TestSubtests/nested/fails") as GoSubtestCall
        val text = VfsUtilCore.loadText(myFixture.findFileInTempDir("calc/calc_test.go"))
        assertEquals(text.indexOf("t.Run(\"fails\""), call.textRange.startOffset)

        val other = single("gotest://example.com/gotests/calc#TestOther/fails") as GoSubtestCall
        assertEquals(text.lastIndexOf("t.Run(\"fails\""), other.textRange.startOffset)
    }

    fun testSubtestNamedWithSlashResolvesToTheSingleLiteral() {
        assertEquals("a/b", (single("gotest://example.com/gotests/calc#TestSubtests/a/b") as GoSubtestCall).subtestName)
    }

    fun testDynamicSubtestFallsBackToItsFunction() {
        val header = single("gotest://example.com/gotests/calc#TestSubtests/generated#01") as GoFunctionHeader
        assertEquals("TestSubtests", header.functionName)
    }

    fun testUnknownTestFallsBackToThePackageDirectory() {
        assertTrue(single("gotest://example.com/gotests/calc#TestMissing") is PsiDirectory)
    }

    fun testFunctionOutsideTestFilesIsNotATest() {
        assertTrue(single("gotest://example.com/gotests/calc#TestNotInTestFile") is PsiDirectory)
    }

    fun testNestedModuleOwnsItsPackages() {
        val header = single("gotest://example.com/gotests/tools/lint#TestLint") as GoFunctionHeader
        assertEquals("lint_test.go", header.containingFile.name)
    }

    fun testImportPathOutsideTheProjectResolvesToNothing() {
        assertEmpty(locate("gotest://fmt#TestPrint"))
        assertEmpty(locate("gotest://example.com/gotests/nosuchdir"))
    }

    fun testCopyOfTheModuleDeeperInTheProjectLosesToTheShorterPath() {
        myFixture.addFileToProject("testdata/copy/go.mod", "module example.com/gotests\n")
        myFixture.addFileToProject("testdata/copy/calc/calc_test.go", "package calc\n\nfunc TestAdd(t *testing.T) {}\n")
        val header = single("gotest://example.com/gotests/calc#TestAdd")
        assertEquals("calc", header.containingFile.containingDirectory.name)
        assertEquals(myFixture.findFileInTempDir("calc/calc_test.go"), header.containingFile.virtualFile)
    }

    fun testEditedModuleLineIsSeenByTheNextLookup() {
        assertTrue(single("gotest://example.com/gotests/calc") is PsiDirectory)
        val goMod = myFixture.findFileInTempDir("go.mod")
        WriteAction.runAndWait<Throwable> { VfsUtil.saveText(goMod, "module example.com/renamed\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("calc", (single("gotest://example.com/renamed/calc") as PsiDirectory).name)
        assertEmpty(locate("gotest://example.com/gotests/calc"))
    }

    fun testForeignProtocolIsIgnored() {
        assertEmpty(locate("gotest://example.com/gotests/calc", protocol = "java:test"))
    }

    fun testDumbModeResolvesToNothing() {
        var result: List<PsiElement>? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.isDumb(project))
            result = locate("gotest://example.com/gotests/calc#TestAdd")
        }
        assertEmpty(result!!)
    }
}
