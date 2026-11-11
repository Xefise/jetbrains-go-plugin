package io.github.xefise.golsp.lang

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoCreateFileActionTest : BasePlatformTestCase() {
    fun testActionIsRegistered() {
        assertInstanceOf(ActionManager.getInstance().getAction("golsp.NewGoFile"), GoCreateFileAction::class.java)
    }

    fun testPackageFromSiblingSources() {
        myFixture.addFileToProject("my-tool/main.go", "package main\n\nfunc main() {}\n")
        val file = myFixture.addFileToProject("my-tool/main_test.go", "package main_test\n")
        val dir = file.containingDirectory
        assertEquals("main", GoCreateFileAction.packageFor(dir))
    }

    fun testPackageFromDirectoryName() {
        val file = myFixture.addFileToProject("my-pkg/readme.txt", "")
        val dir = file.containingDirectory
        assertEquals("my_pkg", GoCreateFileAction.packageFor(dir))
    }

    fun testTemplateRendersPackageClause() {
        val dir = myFixture.addFileToProject("pkg/a.go", "package foo\n").containingDirectory
        val created = create(GoCreateFileAction.TEMPLATE_NAME, dir)
        assertEquals("b.go", created.name)
        assertEquals("foo", created.packageName)
    }

    fun testApplicationTemplateDeclaresMain() {
        val dir = myFixture.addFileToProject("pkg/a.go", "package foo\n").containingDirectory
        val created = create(GoCreateFileAction.APPLICATION_TEMPLATE_NAME, dir)
        assertEquals("main", created.packageName)
        assertEquals(listOf("main"), created.functionHeaders.map { it.functionName })
    }

    private fun create(templateName: String, dir: PsiDirectory): GoFile {
        val template = FileTemplateManager.getInstance(project).getInternalTemplate(templateName)
        return WriteCommandAction.writeCommandAction(project).compute<GoFile?, RuntimeException> {
            CreateFileFromTemplateAction.createFileFromTemplate(
                "b", template, dir, null, false, emptyMap(),
                mapOf(GoCreateFileAction.PACKAGE_PROPERTY to GoCreateFileAction.packageFor(dir)),
            ) as GoFile?
        }!!
    }
}
