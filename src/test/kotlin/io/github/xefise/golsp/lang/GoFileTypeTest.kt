package io.github.xefise.golsp.lang

import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoFileTypeTest : BasePlatformTestCase() {
    fun testGoFileIsParsedAsGoFile() {
        val text = "package main\n\nfunc main() {}\n"
        val file = myFixture.configureByText("main.go", text)
        assertInstanceOf(file, GoFile::class.java)
        assertEquals(GoFileType.INSTANCE, file.fileType)
        assertEquals(GoLanguage, file.language)
        assertEquals(text, file.text)
    }

    fun testEmptyGoFile() {
        val file = myFixture.configureByText("empty.go", "")
        assertInstanceOf(file, GoFile::class.java)
        assertEquals("", file.text)
    }

    fun testGoModAndGoWorkByFileName() {
        val mod = myFixture.configureByText("go.mod", "module example.com/m\n\ngo 1.22\n")
        assertEquals(GoModFileType.INSTANCE, mod.fileType)
        assertEquals(GoModLanguage, mod.language)
        assertInstanceOf(mod, GoModFile::class.java)

        val work = myFixture.configureByText("go.work", "go 1.22\n\nuse ./a\n")
        assertEquals(GoWorkFileType.INSTANCE, work.fileType)
        assertEquals(GoWorkLanguage, work.language)
    }

    fun testOtherModFilesAreNotGoMod() {
        assertNotSame(GoModFileType.INSTANCE, myFixture.configureByText("other.mod", "x").fileType)
    }

    fun testGoSumIsPlainText() {
        for (name in listOf("go.sum", "go.work.sum")) {
            val sum = myFixture.configureByText(name, "golang.org/x/mod v0.20.0 h1:abc=\n")
            assertEquals(GoSumFileType.INSTANCE, sum.fileType)
            assertEquals(PlainTextLanguage.INSTANCE, sum.language)
        }
    }

    fun testPersistedIds() {
        assertEquals("golsp.go", GoLanguage.id)
        assertEquals("golsp.gomod", GoModLanguage.id)
        assertEquals("golsp.gowork", GoWorkLanguage.id)
        assertEquals("golsp.go", GoFileType.INSTANCE.name)
        assertEquals("golsp.gomod", GoModFileType.INSTANCE.name)
        assertEquals("golsp.gowork", GoWorkFileType.INSTANCE.name)
        assertEquals("golsp.gosum", GoSumFileType.INSTANCE.name)
    }
}
