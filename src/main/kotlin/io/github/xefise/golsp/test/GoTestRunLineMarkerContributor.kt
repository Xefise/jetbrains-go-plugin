package io.github.xefise.golsp.test

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoFunctionHeader
import io.github.xefise.golsp.lang.GoSubtestCall
import io.github.xefise.golsp.test.model.GoModules
import io.github.xefise.golsp.test.model.GoTestLocation
import io.github.xefise.golsp.test.model.GoTestRunPattern
import java.io.IOException
import javax.swing.Icon

class GoTestRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || !isAnchorCandidate(element)) return null
        val file = element.containingFile as? GoFile ?: return null
        if (!GoTestDeclarations.isTestFileInContent(file)) return null
        val test = GoTestDeclarations.at(element) ?: return null
        return Info(icon(file, test), ExecutorAction.getActions(0), null)
    }

    // Cheap structural pre-check so that only name leaves reach the cached per-file scan.
    private fun isAnchorCandidate(element: PsiElement): Boolean = when (val parent = element.parent) {
        is GoFunctionHeader -> parent.nameIdentifier == element
        is GoSubtestCall -> parent.nameLiteral == element
        else -> false
    }

    // Last-run state from TestStateStorage; needs no index, so it also works in dumb mode.
    private fun icon(file: GoFile, test: GoTestDeclaration): Icon {
        val url = testUrl(file, test) ?: return AllIcons.RunConfigurations.TestState.Run
        return getTestStateIcon(url, file.project, false)
    }

    companion object {
        /** The URL the test tree gives this test: the go.mod import path and the name as `go test` prints it. */
        fun testUrl(file: GoFile, test: GoTestDeclaration): String? {
            val importPath = importPath(file) ?: return null
            return GoTestLocation.testUrl(importPath, GoTestRunPattern.rewrite(test.fullName))
        }

        // Nearest go.mod above the file, as `go` resolves it; go.work and GOPATH mode are not considered.
        private fun importPath(file: GoFile): String? = CachedValuesManager.getCachedValue(file) {
            val directory = file.virtualFile?.parent
            val goMod = generateSequence(directory) { it.parent }.firstNotNullOfOrNull { it.findChild("go.mod") }
            val path = goMod?.let { mod -> modulePath(mod)?.let { join(it, VfsUtilCore.getRelativePath(directory!!, mod.parent)) } }
            val dependencies = listOfNotNull(goMod, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
            CachedValueProvider.Result.create(path, *dependencies.toTypedArray())
        }

        private fun join(modulePath: String, relative: String?): String? = when {
            relative == null -> null
            relative.isEmpty() -> modulePath
            else -> "$modulePath/$relative"
        }

        private fun modulePath(goMod: VirtualFile): String? = try {
            GoModules.modulePath(VfsUtilCore.loadText(goMod))
        } catch (_: IOException) {
            null
        }
    }
}
