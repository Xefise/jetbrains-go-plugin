package io.github.xefise.golsp.run

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoApplicationRunConfigurationProducerTest : BasePlatformTestCase() {
    private val producer = GoApplicationRunConfigurationProducer()
    private lateinit var rootModule: PsiFile

    override fun setUp() {
        super.setUp()
        rootModule = add("go.mod", "module example.com/app\n\ngo 1.27\n")
    }

    private fun withoutRootModule() {
        WriteAction.runAndWait<Throwable> { rootModule.virtualFile.delete(this) }
    }

    private fun context(element: PsiElement): ConfigurationContext =
        ConfigurationContext.createEmptyContextForLocation(PsiLocation(element) as Location<*>)

    private fun produced(element: PsiElement): GoApplicationConfiguration? =
        producer.createConfigurationFromContext(context(element))?.configuration as GoApplicationConfiguration?

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

    private fun mainLeaf(file: PsiFile): PsiElement = file.findElementAt(file.text.indexOf("main()"))!!

    private fun dirPath(file: PsiFile): String = file.virtualFile.parent.path

    fun testMainIdentifierProducesPackageConfigurationForItsDirectory() {
        val file = add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        val config = produced(mainLeaf(file))!!
        assertEquals(GoRunKind.PACKAGE, config.runKind)
        assertEquals(dirPath(file), config.packageDirectory)
        assertEquals("app", config.name)
    }

    fun testFileItselfProducesTheSamePackageConfiguration() {
        val file = add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        assertEquals(dirPath(file), produced(file)!!.packageDirectory)
    }

    fun testSiblingFileWithoutMainProducesConfigurationForThePackage() {
        add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        val helper = add("cmd/app/util.go", "package main\n\nfunc util() {}\n")
        assertEquals(dirPath(helper), produced(helper)!!.packageDirectory)
    }

    fun testMainPackageWithoutEntryPointProducesNothing() {
        assertNull(produced(add("lib/util.go", "package main\n\nfunc util() {}\n")))
    }

    fun testNonMainPackageProducesNothing() {
        val file = add("pkg/lib.go", "package lib\n\nfunc main() {}\n")
        assertNull(produced(mainLeaf(file)))
    }

    fun testTestFileInMainPackageProducesNothing() {
        add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        assertNull(produced(add("cmd/app/main_test.go", "package main\n\nfunc TestX(t *testing.T) {}\n")))
    }

    fun testDirectoryWithMainPackageProducesConfiguration() {
        val file = add("cmd/tool/tool.go", "package main\n\nfunc main() {}\n")
        val config = produced(file.containingDirectory)!!
        assertEquals(GoRunKind.PACKAGE, config.runKind)
        assertEquals(dirPath(file), config.packageDirectory)
        assertEquals("tool", config.name)
    }

    fun testDirectoryGainingAMainFileProducesConfigurationAfterwards() {
        val util = add("cmd/late/util.go", "package main\n\nfunc util() {}\n")
        assertNull(produced(util.containingDirectory))
        add("cmd/late/main.go", "package main\n\nfunc main() {}\n")
        assertEquals(dirPath(util), produced(util.containingDirectory)!!.packageDirectory)
    }

    fun testExcludedDirectoryProducesNothing() {
        val file = add("build/gen/main.go", "package main\n\nfunc main() {}\n")
        withExcluded(file.virtualFile.parent) {
            assertNull(produced(mainLeaf(file)))
            assertNull(produced(file.containingDirectory))
        }
    }

    fun testDirectoryWhoseOnlyMainIsInATestFileProducesNothing() {
        val file = add("cmd/odd/odd_test.go", "package main\n\nfunc main() {}\n")
        assertNull(produced(file.containingDirectory))
    }

    fun testDirectoryWithLibraryPackageProducesNothing() {
        val file = add("pkg/lib/lib.go", "package lib\n\nfunc Lib() {}\n")
        assertNull(produced(file.containingDirectory))
    }

    fun testExistingPackageConfigurationIsReusedForAnyContextOfThatPackage() {
        val file = add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        val existing = configWith { packageDirectory = dirPath(file) }
        assertTrue(producer.isConfigurationFromContext(existing, context(mainLeaf(file))))
        assertTrue(producer.isConfigurationFromContext(existing, context(file.containingDirectory)))
    }

    fun testExistingConfigurationIsFoundThroughRunManager() {
        val file = add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        val runManager = RunManager.getInstance(project)
        val settings = runManager.createConfiguration("app", producer.configurationFactory)
        (settings.configuration as GoApplicationConfiguration).packageDirectory = dirPath(file)
        runManager.addConfiguration(settings)
        try {
            assertSame(settings, context(mainLeaf(file)).findExisting())
        } finally {
            runManager.removeConfiguration(settings)
        }
    }

    fun testPackageConfigurationOfAnotherDirectoryIsNotReused() {
        val file = add("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        val other = add("cmd/other/main.go", "package main\n\nfunc main() {}\n")
        val existing = configWith { packageDirectory = dirPath(other) }
        assertFalse(producer.isConfigurationFromContext(existing, context(mainLeaf(file))))
    }

    fun testFileConfigurationIsReusedOnlyForItsOwnFile() {
        val file = add("scripts/gen.go", "package main\n\nfunc main() {}\n")
        val sibling = add("scripts/other.go", "package main\n\nfunc other() {}\n")
        val existing = configWith {
            runKind = GoRunKind.FILE
            filePath = file.virtualFile.path
        }
        assertTrue(producer.isConfigurationFromContext(existing, context(mainLeaf(file))))
        assertFalse(producer.isConfigurationFromContext(existing, context(sibling)))
        assertFalse(producer.isConfigurationFromContext(existing, context(file.containingDirectory)))
    }

    fun testBlankPackageDirectoryIsNotReused() {
        val file = add("main.go", "package main\n\nfunc main() {}\n")
        assertFalse(producer.isConfigurationFromContext(configWith { packageDirectory = "" }, context(mainLeaf(file))))
    }

    fun testMainOutsideAnyModuleProducesFileConfiguration() {
        withoutRootModule()
        val file = add("scratch/hello.go", "package main\n\nfunc main() {}\n")
        val config = produced(mainLeaf(file))!!
        assertEquals(GoRunKind.FILE, config.runKind)
        assertEquals(file.virtualFile.path, config.filePath)
        assertEquals("hello.go", config.name)
        assertTrue(producer.isConfigurationFromContext(config, context(file)))
    }

    fun testNestedGoModAboveProducesPackageConfiguration() {
        withoutRootModule()
        add("svc/go.mod", "module svc\n")
        val file = add("svc/cmd/app/main.go", "package main\n\nfunc main() {}\n")
        assertEquals(GoRunKind.PACKAGE, produced(mainLeaf(file))!!.runKind)
    }

    fun testGoWorkAloneAboveProducesPackageConfiguration() {
        withoutRootModule()
        add("ws/go.work", "go 1.27\n")
        val file = add("ws/app/main.go", "package main\n\nfunc main() {}\n")
        assertEquals(GoRunKind.PACKAGE, produced(mainLeaf(file))!!.runKind)
    }

    fun testSiblingModuleDoesNotCountForAnotherDirectory() {
        withoutRootModule()
        add("other/go.mod", "module other\n")
        val file = add("loose/main.go", "package main\n\nfunc main() {}\n")
        assertEquals(GoRunKind.FILE, produced(mainLeaf(file))!!.runKind)
    }

    fun testOutsideModuleSiblingWithoutMainProducesNothing() {
        withoutRootModule()
        add("scratch/main.go", "package main\n\nfunc main() {}\n")
        assertNull(produced(add("scratch/util.go", "package main\n\nfunc util() {}\n")))
    }

    fun testOutsideModuleDirectoryProducesNothing() {
        withoutRootModule()
        val file = add("scratch/main.go", "package main\n\nfunc main() {}\n")
        assertNull(produced(file.containingDirectory))
    }

    fun testOutsideModuleExistingPackageConfigurationIsNotReused() {
        withoutRootModule()
        val file = add("scratch/main.go", "package main\n\nfunc main() {}\n")
        val stale = configWith { packageDirectory = dirPath(file) }
        assertFalse(producer.isConfigurationFromContext(stale, context(mainLeaf(file))))
    }

    private fun withExcluded(dir: VirtualFile, block: () -> Unit) {
        PsiTestUtil.addExcludedRoot(module, dir)
        try {
            block()
        } finally {
            PsiTestUtil.removeExcludedRoot(module, dir)
        }
    }

    private fun configWith(setup: GoApplicationConfiguration.() -> Unit): GoApplicationConfiguration =
        (producer.configurationFactory.createTemplateConfiguration(project) as GoApplicationConfiguration).apply(setup)
}
