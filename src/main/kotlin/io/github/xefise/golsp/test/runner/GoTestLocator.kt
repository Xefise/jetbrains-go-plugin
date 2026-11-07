package io.github.xefise.golsp.test.runner

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.test.model.GoModules
import io.github.xefise.golsp.test.model.GoTestLocation
import java.io.IOException

/**
 * Resolves `gotest://` URLs: the import path through the go.mod files of the project, then the test function and its
 * literal `t.Run` calls in the package's `_test.go` files; an unresolved test falls back to the package directory.
 */
object GoTestLocator : SMTestLocator {
    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        if (protocol != GoTestLocation.PROTOCOL) return emptyList()
        val target = GoTestLocation.parse(path) ?: return emptyList()
        // The go.mod lookup needs the file name index.
        if (DumbService.isDumb(project)) return emptyList()
        val directory = packageDirectory(project, scope, target.importPath) ?: return emptyList()
        val psiManager = PsiManager.getInstance(project)
        val element: PsiElement = target.testName?.let { findTest(psiManager, directory, it) }
            ?: psiManager.findDirectory(directory)
            ?: return emptyList()
        return listOf(PsiLocation(element))
    }

    /** Needs a read action and smart mode; modules are cached until go.mod files or the VFS change. */
    internal fun packageDirectory(project: Project, scope: GlobalSearchScope, importPath: String): VirtualFile? =
        GoModules.owners(importPath, modules(project)).firstNotNullOfOrNull { (module, relative) ->
            val directory = if (relative.isEmpty()) module.root else module.root.findFileByRelativePath(relative)
            directory?.takeIf { it.isDirectory && scope.contains(it) }
        }

    private fun modules(project: Project): List<GoModules.Module<VirtualFile>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val modules = FilenameIndex.getVirtualFilesByName("go.mod", GlobalSearchScope.allScope(project)).mapNotNull { file ->
                val root = file.parent ?: return@mapNotNull null
                val modulePath = modulePath(file) ?: return@mapNotNull null
                GoModules.Module(root, modulePath, root.path, fileIndex.isInContent(root) && !fileIndex.isInLibrary(root))
            }
            CachedValueProvider.Result.create(
                modules,
                PsiModificationTracker.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootManager.getInstance(project),
            )
        }

    private fun modulePath(goMod: VirtualFile): String? = try {
        GoModules.modulePath(VfsUtilCore.loadText(goMod))
    } catch (_: IOException) {
        null
    }

    private fun findTest(psiManager: PsiManager, directory: VirtualFile, fullName: String): PsiElement? {
        val levels = fullName.split('/')
        for (file in directory.children.sortedBy { it.name }) {
            if (file.isDirectory || !file.name.endsWith("_test.go")) continue
            val goFile = psiManager.findFile(file) as? GoFile ?: continue
            val headers = goFile.functionHeaders
            val index = headers.indexOfFirst { !it.isMethod && it.functionName == levels[0] }
            if (index < 0) continue
            val header = headers[index]
            if (levels.size == 1) return header
            val bodyEnd = headers.getOrNull(index + 1)?.textRange?.startOffset ?: goFile.textLength
            val calls = goFile.subtestCalls.filter { it.textRange.startOffset in header.textRange.endOffset until bodyEnd }
            val match = GoTestLocation.matchSubtest(levels.drop(1), calls.map { it.subtestName })
            return match?.let { calls[it] } ?: header
        }
        return null
    }
}
