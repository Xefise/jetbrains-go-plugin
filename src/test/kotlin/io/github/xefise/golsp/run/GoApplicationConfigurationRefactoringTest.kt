package io.github.xefise.golsp.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoApplicationConfigurationRefactoringTest : BasePlatformTestCase() {
    private val added = mutableListOf<RunnerAndConfigurationSettings>()

    override fun tearDown() {
        try {
            added.forEach(RunManager.getInstance(project)::removeConfiguration)
        } finally {
            super.tearDown()
        }
    }

    private fun configuration(name: String, setup: GoApplicationConfiguration.() -> Unit): GoApplicationConfiguration {
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration(name, runConfigurationType<GoApplicationConfigurationType>().configurationFactories.single())
        (settings.configuration as GoApplicationConfiguration).setup()
        runManager.addConfiguration(settings)
        added += settings
        return settings.configuration as GoApplicationConfiguration
    }

    private fun mainFile(path: String): PsiFile = myFixture.addFileToProject(path, "package main\n\nfunc main() {}\n")

    fun testRenamedPackageDirectoryUpdatesDirectoryWorkingDirectoryAndGeneratedName() {
        val file = mainFile("cmd/server/main.go")
        val config = configuration("server") {
            packageDirectory = "/src/cmd/server"
            workingDirectory = "/src/cmd/server/data"
        }
        myFixture.renameElement(file.containingDirectory!!, "api")
        assertEquals("/src/cmd/api", config.packageDirectory)
        assertEquals("/src/cmd/api/data", config.workingDirectory)
        assertEquals("api", config.name)
    }

    fun testRenamedParentDirectoryKeepsThePackageName() {
        val file = mainFile("cmd/server/main.go")
        val config = configuration("server") { packageDirectory = "/src/cmd/server" }
        myFixture.renameElement(file.containingDirectory!!.parentDirectory!!, "bin")
        assertEquals("/src/bin/server", config.packageDirectory)
        assertEquals("server", config.name)
    }

    fun testRenamedPackageDirectoryKeepsANameTheUserEdited() {
        val file = mainFile("cmd/server/main.go")
        val config = configuration("Backend") { packageDirectory = "/src/cmd/server" }
        myFixture.renameElement(file.containingDirectory!!, "api")
        assertEquals("/src/cmd/api", config.packageDirectory)
        assertEquals("Backend", config.name)
    }

    fun testRenamedFileOfFileKindUpdatesPathAndGeneratedName() {
        val file = mainFile("tools/gen.go")
        val config = configuration("gen.go") { runKind = GoRunKind.FILE; filePath = "/src/tools/gen.go" }
        myFixture.renameElement(file, "generate.go")
        assertEquals("/src/tools/generate.go", config.filePath)
        assertEquals("generate.go", config.name)
    }

    fun testRenamingAnUnrelatedFileLeavesTheConfigurationAlone() {
        mainFile("cmd/server/main.go")
        val other = mainFile("cmd/client/main.go")
        val config = configuration("server") { packageDirectory = "/src/cmd/server" }
        myFixture.renameElement(other.containingDirectory!!, "cli")
        assertEquals("/src/cmd/server", config.packageDirectory)
        assertEquals("server", config.name)
    }
}
