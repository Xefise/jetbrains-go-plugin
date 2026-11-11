package io.github.xefise.golsp.lsp.integration

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.psi.PsiDocumentManager
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import io.github.xefise.golsp.lang.GoCreateFileAction
import io.github.xefise.golsp.lsp.GoplsLanguageServerFactory
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoNewFileIntegrationTest : GoIntegrationTestCase() {
    override val requiredTools: List<GoTool> = listOf(GoTool.GO, GoTool.GOPLS)

    fun testNewGoFileNextToTestFileHasOnePackageClause() {
        fixture.addFileToProject("app/go.mod", "module app\n\ngo 1.27\n")
        val sibling = fixture.addFileToProject(
            "app/internal/order/order_test.go",
            "package order\n\nimport \"testing\"\n\nfunc TestOrder(t *testing.T) {}\n",
        )
        fixture.configureFromExistingVirtualFile(sibling.virtualFile)
        waitUntil("gopls to start") {
            LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started
        }
        awaitPackageLoaded(sibling.text.indexOf("testing.T") + "testing.".length)

        val dir = sibling.containingDirectory
        val template = FileTemplateManager.getInstance(project).getInternalTemplate(GoCreateFileAction.TEMPLATE_NAME)
        // Same path as the action: on EDT outside a write action, opening the new file in an editor.
        val created = CreateFileFromTemplateAction.createFileFromTemplate(
            "service", template, dir, null, true, emptyMap(),
            mapOf(GoCreateFileAction.PACKAGE_PROPERTY to GoCreateFileAction.packageFor(dir)),
        )!!
        val document = PsiDocumentManager.getInstance(project).getDocument(created)!!

        // No positive signal exists for "gopls decided not to edit"; its insert used to arrive well within this window.
        poll(SETTLE_SECONDS) { PACKAGE_CLAUSE.findAll(document.text).count() > 1 }
        assertEquals(document.text, 1, PACKAGE_CLAUSE.findAll(document.text).count())
        assertTrue(document.text, document.text.startsWith("package order\n"))
    }

    // A resolved definition proves gopls has loaded the package the new file joins.
    private fun awaitPackageLoaded(offset: Int) {
        val file = fixture.file
        val editor = fixture.editor
        waitUntil("gopls definition at $offset") {
            inBackgroundRead("one definition request") {
                val source = file.findElementAt(offset)
                GotoDeclarationHandler.EP_NAME.extensionList.any { it.getGotoDeclarationTargets(source, offset, editor)?.isNotEmpty() == true }
            }
        }
    }

    companion object {
        private const val SETTLE_SECONDS = 5
        private val PACKAGE_CLAUSE = Regex("(?m)^package ")
    }
}
