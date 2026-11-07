package io.github.xefise.golsp.test

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoTestRunConfigurationProducerTest : BasePlatformTestCase() {
    private val producer = GoTestRunConfigurationProducer()

    private fun context(element: PsiElement): ConfigurationContext =
        ConfigurationContext.createEmptyContextForLocation(PsiLocation(element) as Location<*>)

    private fun produced(element: PsiElement): GoTestConfiguration? =
        producer.createConfigurationFromContext(context(element))?.configuration as GoTestConfiguration?

    private fun add(path: String, body: String): PsiFile =
        myFixture.addFileToProject(path, "package calc\n\nimport \"testing\"\n\n$body\n")

    private fun leaf(file: PsiFile, anchor: String): PsiElement = file.findElementAt(file.text.indexOf(anchor))!!

    private fun dirPath(file: PsiFile): String = file.virtualFile.parent.path

    private val subtests = "func TestAdd(t *testing.T) {\n\tt.Run(\"sub case\", func(t *testing.T) {\n\t\tx := 1\n\t\t_ = x\n\t})\n}\n\nfunc helper() {}"

    fun testTestNameProducesPackageConfigurationFilteredToIt() {
        val file = add("calc/calc_test.go", subtests)
        val config = produced(leaf(file, "TestAdd"))!!
        assertEquals(GoTestScope.PACKAGE, config.scope)
        assertEquals(dirPath(file), config.directory)
        assertEquals(listOf("TestAdd"), config.testNames)
        assertEquals("TestAdd", config.name)
    }

    fun testSubtestLiteralProducesItsFullPath() {
        val file = add("calc/calc_test.go", subtests)
        val config = produced(leaf(file, "\"sub case\""))!!
        assertEquals(GoTestScope.PACKAGE, config.scope)
        assertEquals(listOf("TestAdd/sub case"), config.testNames)
        assertEquals("TestAdd/sub case", config.name)
    }

    fun testCaretInsideSubtestBodyPicksTheSubtest() {
        val file = add("calc/calc_test.go", subtests)
        assertEquals(listOf("TestAdd/sub case"), produced(leaf(file, "x := 1"))!!.testNames)
    }

    fun testBenchmarkNameProducesBenchmarkSelection() {
        val file = add("calc/bench_test.go", "func BenchmarkAdd(b *testing.B) {}")
        assertEquals(listOf("BenchmarkAdd"), produced(leaf(file, "BenchmarkAdd"))!!.testNames)
    }

    fun testCaretOutsideTestsProducesFileConfiguration() {
        val file = add("calc/calc_test.go", subtests)
        val config = produced(leaf(file, "helper"))!!
        assertEquals(GoTestScope.FILE, config.scope)
        assertEquals(file.virtualFile.path, config.filePath)
        assertEquals(emptyList<String>(), config.testNames)
        assertEquals("calc_test.go", config.name)
    }

    fun testFileItselfProducesFileConfiguration() {
        val file = add("calc/calc_test.go", subtests)
        assertEquals(GoTestScope.FILE, produced(file)!!.scope)
    }

    // A FILE run of benchmarks alone has nothing to run without names.
    fun testFileWithOnlyBenchmarksProducesNothing() {
        assertNull(produced(add("calc/bench_test.go", "func BenchmarkAdd(b *testing.B) {}")))
    }

    fun testNonTestFileProducesNothing() {
        assertNull(produced(add("calc/calc.go", "func TestAdd(t *testing.T) {}")))
    }

    fun testPackageDirectoryProducesPackageConfiguration() {
        val file = add("calc/calc_test.go", subtests)
        val config = produced(file.containingDirectory)!!
        assertEquals(GoTestScope.PACKAGE, config.scope)
        assertEquals(dirPath(file), config.directory)
        assertEquals(emptyList<String>(), config.testNames)
        assertEquals("go test ./calc", config.name)
    }

    fun testRootDirectoryWithNestedTestsProducesRecursiveConfiguration() {
        val file = add("calc/calc_test.go", subtests)
        val root = file.containingDirectory.parentDirectory!!
        val config = produced(root)!!
        assertEquals(GoTestScope.DIRECTORY, config.scope)
        assertEquals(root.virtualFile.path, config.directory)
        assertEquals("go test ./...", config.name)
    }

    fun testDirectoryWithTestsDeeperThanOneLevelIsRecursive() {
        val file = add("pkg/deep/calc/calc_test.go", subtests)
        val pkg = file.containingDirectory.parentDirectory!!.parentDirectory!!
        val config = produced(pkg)!!
        assertEquals(GoTestScope.DIRECTORY, config.scope)
        assertEquals("go test ./pkg/...", config.name)
    }

    fun testDirectoryWithoutTestsProducesNothing() {
        assertNull(produced(add("lib/lib.go", "func Lib() {}").containingDirectory))
    }

    // `go test ./...` skips testdata, vendor and nested modules.
    fun testTestsOnlyInIgnoredDirectoriesOrNestedModulesProduceNothing() {
        add("app/testdata/x_test.go", subtests)
        add("app/vendor/v/v_test.go", subtests)
        add("app/_skip/s_test.go", subtests)
        myFixture.addFileToProject("app/tools/go.mod", "module tools\n")
        val file = add("app/tools/t_test.go", subtests)
        val app = file.containingDirectory.parentDirectory!!
        assertNull(produced(app))
    }

    fun testDirectoryGainingNestedTestsBecomesRecursive() {
        val own = add("grow/grow_test.go", subtests)
        assertEquals(GoTestScope.PACKAGE, produced(own.containingDirectory)!!.scope)
        add("grow/sub/sub_test.go", subtests)
        assertEquals(GoTestScope.DIRECTORY, produced(own.containingDirectory)!!.scope)
    }

    fun testExcludedTestFileProducesNothing() {
        val file = add("gen/gen_test.go", subtests)
        val dir = file.virtualFile.parent
        PsiTestUtil.addExcludedRoot(module, dir)
        try {
            assertNull(produced(leaf(file, "TestAdd")))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, dir)
        }
    }

    fun testProducedConfigurationIsRecognisedForItsOwnContextOnly() {
        val file = add("calc/calc_test.go", subtests)
        val testContext = leaf(file, "TestAdd")
        val config = produced(testContext)!!
        assertTrue(producer.isConfigurationFromContext(config, context(testContext)))
        assertFalse(producer.isConfigurationFromContext(config, context(leaf(file, "\"sub case\""))))
        assertFalse(producer.isConfigurationFromContext(config, context(leaf(file, "helper"))))
        assertFalse(producer.isConfigurationFromContext(config, context(file.containingDirectory)))
    }

    fun testFileAndDirectoryConfigurationsAreRecognisedOnlyForTheirTargets() {
        val file = add("calc/calc_test.go", subtests)
        val other = add("other/other_test.go", subtests)
        val fileConfig = produced(file)!!
        assertTrue(producer.isConfigurationFromContext(fileConfig, context(leaf(file, "helper"))))
        assertFalse(producer.isConfigurationFromContext(fileConfig, context(other)))
        val dirConfig = produced(file.containingDirectory)!!
        assertTrue(producer.isConfigurationFromContext(dirConfig, context(file.containingDirectory)))
        assertFalse(producer.isConfigurationFromContext(dirConfig, context(other.containingDirectory)))
        assertFalse(producer.isConfigurationFromContext(dirConfig, context(file)))
    }

    fun testRecursiveConfigurationIsNotMistakenForPackageOfTheSameDirectory() {
        val file = add("r/r_test.go", subtests)
        add("r/sub/sub_test.go", subtests)
        val packageConfig = (producer.configurationFactory.createTemplateConfiguration(project) as GoTestConfiguration).apply {
            scope = GoTestScope.PACKAGE
            directory = dirPath(file)
        }
        assertFalse(producer.isConfigurationFromContext(packageConfig, context(file.containingDirectory)))
    }

    fun testTestFilesGoIgnoresDoNotMakeADirectoryTestable() {
        val file = add("ign/_skip_test.go", subtests)
        add("ign/.hidden_test.go", subtests)
        assertNull(produced(file.containingDirectory))
    }

    // Past the scan bound the walk stops and assumes nested tests rather than costing seconds on a huge tree.
    fun testHugeTreeIsAssumedRecursiveWithoutAFullScan() {
        val file = add("big/lib.go", "func Lib() {}")
        val big = file.virtualFile.parent
        WriteAction.runAndWait<Throwable> {
            repeat(501) { big.createChildDirectory(this, "d$it") }
        }
        assertEquals(GoTestScope.DIRECTORY, produced(file.containingDirectory)!!.scope)
    }
}
