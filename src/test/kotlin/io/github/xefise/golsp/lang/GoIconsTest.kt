package io.github.xefise.golsp.lang

import com.intellij.ide.FileIconProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoIconsTest : BasePlatformTestCase() {
    private val provider get() = FileIconProvider.EP_NAME.findExtensionOrFail(GoTestFileIconProvider::class.java)

    fun testTestFileGetsTestIcon() {
        val file = myFixture.addFileToProject("calc/calc_test.go", "package calc").virtualFile
        assertSame(GoIcons.TEST_FILE, provider.getIcon(file, 0, project))
    }

    fun testRegularGoFileKeepsFileTypeIcon() {
        val file = myFixture.addFileToProject("calc/calc.go", "package calc").virtualFile
        assertNull(provider.getIcon(file, 0, project))
        assertSame(GoIcons.FILE, file.fileType.icon)
    }

    fun testDirectoryNamedLikeTestFileIsIgnored() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("x_test.go")
        assertNull(provider.getIcon(dir, 0, project))
    }

    fun testModuleFilesShareModuleIcon() {
        for (name in listOf("go.mod", "go.work", "go.sum")) {
            val file = myFixture.addFileToProject(name, "").virtualFile
            assertSame(name, GoIcons.MODULE_FILE, file.fileType.icon)
        }
    }
}
