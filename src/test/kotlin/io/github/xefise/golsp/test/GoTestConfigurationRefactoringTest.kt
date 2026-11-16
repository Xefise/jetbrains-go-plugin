package io.github.xefise.golsp.test

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoTestConfigurationRefactoringTest : BasePlatformTestCase() {
    private val added = mutableListOf<RunnerAndConfigurationSettings>()

    override fun tearDown() {
        try {
            added.forEach(RunManager.getInstance(project)::removeConfiguration)
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(name: String, setup: GoTestConfiguration.() -> Unit): GoTestConfiguration {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(name, runConfigurationType<GoTestConfigurationType>().configurationFactories.single())
        (settings.configuration as GoTestConfiguration).setup()
        runManager.addConfiguration(settings)
        added += settings
        return settings.configuration as GoTestConfiguration
    }

    private fun testFile(path: String): PsiFile =
        myFixture.addFileToProject(path, "package calc\n\nimport \"testing\"\n\nfunc TestAdd(t *testing.T) {}\n")

    private fun directory(file: PsiFile): PsiDirectory = file.containingDirectory!!

    private fun move(element: com.intellij.psi.PsiElement, target: PsiDirectory) =
        MoveFilesOrDirectoriesProcessor(project, arrayOf(element), target, false, false, null, null).run()

    fun testRenamedTestFileUpdatesPathAndGeneratedName() {
        val file = testFile("calc/calc_test.go")
        val config = configuration("calc_test.go") { scope = GoTestScope.FILE; filePath = file.virtualFile.path }
        myFixture.renameElement(file, "sum_test.go")
        assertEquals("/src/calc/sum_test.go", config.filePath)
        assertEquals("sum_test.go", config.name)
    }

    fun testRenamedTestFileKeepsANameTheUserEdited() {
        val file = testFile("calc/calc_test.go")
        val config = configuration("my calc tests") { scope = GoTestScope.FILE; filePath = file.virtualFile.path }
        myFixture.renameElement(file, "sum_test.go")
        assertEquals("/src/calc/sum_test.go", config.filePath)
        assertEquals("my calc tests", config.name)
    }

    fun testRenamedPackageDirectoryUpdatesDirectoryAndGeneratedName() {
        val file = testFile("pkg/calc/calc_test.go")
        val config = configuration("go test ./pkg/calc") { scope = GoTestScope.PACKAGE; directory = file.virtualFile.parent.path }
        myFixture.renameElement(directory(file), "math")
        assertEquals("/src/pkg/math", config.directory)
        assertEquals("go test ./pkg/math", config.name)
    }

    fun testRenamedParentDirectoryUpdatesRecursiveConfiguration() {
        val file = testFile("pkg/calc/calc_test.go")
        val config = configuration("go test ./pkg/...") { scope = GoTestScope.DIRECTORY; directory = "/src/pkg" }
        myFixture.renameElement(directory(file).parentDirectory!!, "lib")
        assertEquals("/src/lib", config.directory)
        assertEquals("go test ./lib/...", config.name)
    }

    fun testRenamedParentDirectoryUpdatesFilePathAndEnvFile() {
        val file = testFile("calc/calc_test.go")
        myFixture.addFileToProject("calc/.env", "A=1\n")
        val config = configuration("calc_test.go") {
            scope = GoTestScope.FILE
            filePath = file.virtualFile.path
            envFile = "/src/calc/.env"
        }
        myFixture.renameElement(directory(file), "math")
        assertEquals("/src/math/calc_test.go", config.filePath)
        assertEquals("/src/math/.env", config.envFile)
        assertEquals("calc_test.go", config.name)
    }

    fun testSingleTestConfigurationFollowsItsDirectoryAndKeepsTheTestName() {
        val file = testFile("calc/calc_test.go")
        val config = configuration("TestAdd") {
            scope = GoTestScope.PACKAGE
            directory = file.virtualFile.parent.path
            testNames = listOf("TestAdd")
        }
        myFixture.renameElement(directory(file), "math")
        assertEquals("/src/math", config.directory)
        assertEquals("TestAdd", config.name)
    }

    fun testMovedTestFileUpdatesPath() {
        val file = testFile("calc/calc_test.go")
        val target = testFile("other/other_test.go").containingDirectory!!
        val config = configuration("calc_test.go") { scope = GoTestScope.FILE; filePath = file.virtualFile.path }
        move(file, target)
        assertEquals("/src/other/calc_test.go", config.filePath)
        assertEquals("calc_test.go", config.name)
    }

    // "/src/calc2" starts with "/src/calc" as a string, not as a path.
    fun testRenamingADirectoryWithACommonNamePrefixLeavesTheConfigurationAlone() {
        val calc = testFile("calc/calc_test.go")
        val calc2 = testFile("calc2/calc_test.go")
        val config = configuration("go test ./calc2") { scope = GoTestScope.PACKAGE; directory = calc2.virtualFile.parent.path }
        myFixture.renameElement(directory(calc), "math")
        assertEquals("/src/calc2", config.directory)
        assertEquals("go test ./calc2", config.name)
    }
}
