package io.github.xefise.golsp.toolchain

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.xefise.golsp.toolchain.model.GoProjectMarkers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Detects eagerly only in projects that look like Go; elsewhere the first snapshot read starts detection. */
class GoToolchainStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        val root = project.basePath?.let { Path.of(it) } ?: return
        if (withContext(Dispatchers.IO) { looksLikeGoProject(root) }) GoToolchainService.getInstance(project).ensureStarted()
    }

    private fun looksLikeGoProject(root: Path): Boolean {
        val rootNames = list(root) ?: return false
        if (GoProjectMarkers.hasGoMarker(rootNames.map { it.fileName.toString() })) return true
        return rootNames.asSequence()
            .filter { GoProjectMarkers.isScannedSubdirectory(it.fileName.toString()) && Files.isDirectory(it) }
            .take(GoProjectMarkers.MAX_SUBDIRECTORIES)
            .any { dir -> list(dir)?.let { children -> GoProjectMarkers.hasGoMarker(children.map { it.fileName.toString() }) } == true }
    }

    private fun list(dir: Path): List<Path>? = try {
        Files.list(dir).use { it.toList() }
    } catch (_: IOException) {
        null
    }
}

class GoToolchainTrustListener : TrustedProjectsListener {
    override fun onProjectTrusted(project: Project) {
        if (!project.isDisposed) GoToolchainService.getInstance(project).refreshIfStarted()
    }

    override fun onProjectUntrusted(project: Project) {
        if (!project.isDisposed) GoToolchainService.getInstance(project).refreshIfStarted()
    }
}
