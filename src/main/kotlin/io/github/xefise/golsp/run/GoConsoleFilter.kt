// Modified from go-lang-idea-plugin (go-lang-plugin-org), commit e73dab4, src/com/goide/runconfig/GoConsoleFilter.java, Apache-2.0
package io.github.xefise.golsp.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.LazyFileHyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PathUtil
import io.github.xefise.golsp.run.model.GoConsoleLink
import io.github.xefise.golsp.run.model.GoConsoleLinks
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Links `file.go:line[:col]` in Run output; relative paths resolve against the run's directories, then the project. */
class GoConsoleFilter(
    private val project: Project,
    private val workingDirectory: String?,
    private val packageDirectory: String?,
) : Filter, DumbAware {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = GoConsoleLinks.find(line).mapNotNull { link ->
            hyperlink(link)?.let { Filter.ResultItem(lineStart + link.start, lineStart + link.end, it) }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun hyperlink(link: GoConsoleLink): HyperlinkInfo? {
        val path = link.path
        if (OSAgnosticPathUtil.isAbsolute(path)) return local(path, link)
        // `go build` prints `./x.go` (`.\x.go` on Windows) relative to the package it builds; program output is relative to its cwd.
        val fromPackage = path.startsWith("./") || path.startsWith(".\\")
        val relative = if (fromPackage) path.substring(2) else path
        val bases = if (fromPackage) {
            listOf(packageDirectory, workingDirectory, project.basePath)
        } else {
            listOf(workingDirectory, packageDirectory, project.basePath)
        }
        for (base in bases.filterNotNull().distinct()) {
            local("$base/$relative", link)?.let { return it }
        }
        return singleProjectFile(relative)?.let { OpenFileHyperlinkInfo(project, it, link.line, link.column) }
    }

    // Filters run off EDT for every line: no synchronous VFS refresh here; a file not yet in VFS is refreshed on click.
    private fun local(path: String, link: GoConsoleLink): HyperlinkInfo? {
        val normalized = FileUtil.toSystemIndependentName(path)
        LocalFileSystem.getInstance().findFileByPath(normalized)?.let {
            return if (it.isDirectory) null else OpenFileHyperlinkInfo(project, it, link.line, link.column)
        }
        val exists = try {
            Files.isRegularFile(Path.of(path))
        } catch (_: InvalidPathException) {
            false
        }
        return if (exists) LazyFileHyperlinkInfo(project, normalized, link.line, link.column) else null
    }

    private fun singleProjectFile(name: String): VirtualFile? {
        if (!name.endsWith(".go") || !PathUtil.isValidFileName(name) || DumbService.isDumb(project)) return null
        return try {
            // Non-blocking: a pending write action cancels and restarts the lookup instead of waiting on it.
            ReadAction.nonBlocking<VirtualFile?> {
                FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).singleOrNull()
            }.executeSynchronously()
        } catch (_: IndexNotReadyException) {
            null
        }
    }
}
