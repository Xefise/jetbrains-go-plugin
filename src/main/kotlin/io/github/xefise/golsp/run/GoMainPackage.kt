package io.github.xefise.golsp.run

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoFunctionHeader
import io.github.xefise.golsp.lang.GoPackageFiles
import io.github.xefise.golsp.lang.GoTokenTypes

// Program entry detection on the flat PSI only: no indexes, no `go` processes.
object GoMainPackage {
    private const val MAIN = "main"

    // `func main()`: not a method, no type parameters, empty parameter list.
    fun isEntryPoint(header: GoFunctionHeader): Boolean {
        val name = header.nameIdentifier ?: return false
        if (name.text != MAIN || header.isMethod) return false
        val open = nextSignificant(name) ?: return false
        if (open.node.elementType != GoTokenTypes.LPAREN) return false
        return nextSignificant(open)?.node?.elementType == GoTokenTypes.RPAREN
    }

    fun isMainPackageFile(file: GoFile): Boolean = !file.isTestFile() && file.packageName == MAIN

    fun hasEntryPoint(file: GoFile): Boolean = isMainPackageFile(file) && file.functionHeaders.any(::isEntryPoint)

    // A `//go:build ignore` generator: never part of its directory's package, run only as `go run gen.go`.
    fun isBuiltAlone(file: GoFile): Boolean = GoPackageFiles.isIgnoredByBuildConstraint(file.viewProvider.contents)

    // `_x.go` / `.x.go` cannot be run at all, not even when named on the command line.
    fun isIgnoredByGo(file: GoFile): Boolean = GoPackageFiles.isIgnoredByName(file.name)

    // Excluded and library directories get neither the gutter nor a producer.
    fun isInContent(item: PsiFileSystemItem): Boolean {
        val file = item.virtualFile ?: return false
        return ProjectFileIndex.getInstance(item.project).isInContent(file)
    }

    // A project directory whose package build holds a non-test `package main` file with `func main()`.
    fun isMainPackageDirectory(directory: PsiDirectory): Boolean =
        CachedValuesManager.getCachedValue(directory) {
            CachedValueProvider.Result.create(
                isInContent(directory) && scan(directory),
                PsiModificationTracker.MODIFICATION_COUNT,
                ProjectRootManager.getInstance(directory.project),
            )
        }

    private fun scan(directory: PsiDirectory): Boolean {
        val psiManager = PsiManager.getInstance(directory.project)
        return directory.virtualFile.children.asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".go") && !GoPackageFiles.isIgnoredByName(it.name) }
            .mapNotNull { psiManager.findFile(it) as? GoFile }
            .any { hasEntryPoint(it) && !isBuiltAlone(it) }
    }

    private fun nextSignificant(element: PsiElement): PsiElement? =
        generateSequence(element.nextSibling) { it.nextSibling }
            .firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }
}
