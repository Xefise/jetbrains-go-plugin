package io.github.xefise.golsp.lang

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lang.model.GoPackageNames

class GoCreateFileAction : CreateFileFromTemplateAction(TITLE, "Create a new Go file", GoIcons.FILE), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Go File")
            .addKind("Go file", GoIcons.FILE, TEMPLATE_NAME)
            .addKind("Go application", GoIcons.FILE, APPLICATION_TEMPLATE_NAME)
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String = TITLE

    override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? {
        val packageName = ReadAction.computeBlocking<String, RuntimeException> { packageFor(dir) }
        return createFileFromTemplate(
            name, template, dir, defaultTemplateProperty, true, emptyMap(), mapOf(PACKAGE_PROPERTY to packageName),
        )
    }

    companion object {
        const val TEMPLATE_NAME = "Go File"
        const val APPLICATION_TEMPLATE_NAME = "Go Application"
        const val PACKAGE_PROPERTY = "GO_PACKAGE_NAME"
        private const val TITLE = "Go File"

        internal fun packageFor(dir: PsiDirectory): String {
            val siblings = dir.files.filterIsInstance<GoFile>().associate { it.name to it.packageName }
            return GoPackageNames.forNewFile(dir.name, siblings)
        }
    }
}
