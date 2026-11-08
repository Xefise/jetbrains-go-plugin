package io.github.xefise.golsp.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorNotifications
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolInstall
import io.github.xefise.golsp.toolchain.model.GoToolRegistry
import io.github.xefise.golsp.toolchain.model.GoToolSpec
import io.github.xefise.golsp.toolchain.model.GoToolStatusSnapshot
import io.github.xefise.golsp.toolchain.model.GoToolStatuses
import io.github.xefise.golsp.toolchain.model.GoToolchain
import io.github.xefise.golsp.toolchain.model.GoVersion
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** `go install` of gopls/dlv into the user's GOBIN; application-wide because GOBIN is shared by all projects. */
@Service(Service.Level.APP)
class GoToolInstaller {
    private val installing: MutableSet<GoTool> = ConcurrentHashMap.newKeySet()

    fun isInstalling(tool: GoTool): Boolean = tool in installing

    /**
     * Installs (or updates to) the version pinned for the project's Go in a background task; safe on EDT.
     * False when nothing was started: untrusted project, Go not ready (a notification says so), or already installing.
     */
    fun install(project: Project, tool: GoTool): Boolean {
        val spec = GoToolRegistry.spec(tool) ?: return false
        if (!TrustedProjects.isProjectTrusted(project)) return false
        val toolchain = GoToolchainService.getInstance(project).toolchain
        if (toolchain == null) {
            notify(project, NotificationType.WARNING, "Cannot install ${tool.baseName}", "Go is not found or not detected yet.")
            return false
        }
        if (!installing.add(tool)) return false
        val finished = AtomicBoolean(false)
        val finishOnce = { if (finished.compareAndSet(false, true)) finish(tool) }
        try {
            publishStatus()
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Installing ${tool.baseName}", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        runInstall(project, spec, toolchain, indicator)
                        recheck(project)
                    } finally {
                        finishOnce()
                    }
                }

                // run() may never be called when the task is dropped (project closed); the set must not keep the tool forever.
                override fun onFinished() = finishOnce()
            })
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            thisLogger().warn("Cannot start installing ${tool.baseName}", e)
            finishOnce()
            return false
        }
        return true
    }

    /** Installs every tool that is missing or needs an update according to the project's last status check. */
    fun installAll(project: Project): List<GoTool> =
        GoToolStatusSnapshot.CHECKED_TOOLS
            .filter { GoToolStatusService.getInstance(project).status(it)?.needsInstall == true }
            .filter { install(project, it) }

    fun update(project: Project, tool: GoTool): Boolean = install(project, tool)

    private fun runInstall(project: Project, spec: GoToolSpec, toolchain: GoToolchain, indicator: ProgressIndicator) {
        val name = spec.tool.baseName
        if (!TrustedProjects.isProjectTrusted(project)) return
        indicator.isIndeterminate = true
        indicator.text = "Checking the local Go version"
        val installGo = GoToolProcesses.localGoVersion(toolchain.goBinary)
        val projectGo = GoVersion.parse(toolchain.env.goVersion)
        val workDir = try {
            Files.createTempDirectory("golsp-tools")
        } catch (e: IOException) {
            notify(project, NotificationType.ERROR, "Cannot install $name", "Cannot create a temporary directory: ${e.message}")
            return
        }
        try {
            val command = GoToolInstall.command(spec, toolchain.goBinary, installGo, projectGo, workDir.toString(), toolchain.env)
            indicator.text = "go ${command.arguments.joinToString(" ")}"
            val commandLine = GoToolProcesses.commandLine(
                command.executable, command.arguments, command.environment, command.removedEnvironment, command.workingDirectory,
            )
            val output = try {
                GoToolProcesses.runner.run(commandLine, INSTALL_TIMEOUT_MS, indicator)
            } catch (e: ExecutionException) {
                thisLogger().info("go install failed to start", e)
                notify(project, NotificationType.ERROR, "Cannot install $name", e.message.orEmpty())
                return
            }
            when {
                output.isCancelled || indicator.isCanceled -> Unit
                output.isTimeout -> notify(project, NotificationType.ERROR, "Cannot install $name", "go install did not finish within ${INSTALL_TIMEOUT_MS / 60_000} minutes.")
                output.exitCode != 0 -> notify(
                    project, NotificationType.ERROR, "Cannot install $name",
                    "go install exited with code ${output.exitCode}:\n${GoToolStatuses.outputTail(output.stderr.ifBlank { output.stdout })}",
                )
                else -> succeeded(project, spec.tool)
            }
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun succeeded(project: Project, tool: GoTool) {
        notify(project, NotificationType.INFORMATION, "${tool.baseName} installed", "")
        forEachOpenProject { it.messageBus.syncPublisher(GoToolInstallListener.TOPIC).toolInstalled(tool) }
    }

    // Background thread, still marked Installing: the banner must not fall back to "not found" before the new status is known.
    private fun recheck(project: Project) = forEachOpenProject {
        val statuses = GoToolStatusService.getInstance(it)
        if (it == project) statuses.refreshNow() else statuses.refreshNowIfStarted()
    }

    private fun finish(tool: GoTool) {
        installing.remove(tool)
        forEachOpenProject { EditorNotifications.getInstance(it).updateAllNotifications() }
        publishStatus()
    }

    private fun publishStatus() = forEachOpenProject { GoToolStatusService.getInstance(it).publish() }

    // A project may close at any point; one closing project must not stop the others from being updated.
    private fun forEachOpenProject(action: (Project) -> Unit) {
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (project.isDisposed) return@forEach
            try {
                action(project)
            } catch (e: Exception) {
                if (e is ProcessCanceledException) throw e
                if (!project.isDisposed) thisLogger().warn("Go tool installer update failed for ${project.name}", e)
            }
        }
    }

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        if (project.isDisposed) return
        // Notification content is HTML; tool output is not.
        val html = StringUtil.escapeXmlEntities(content).replace("\n", "<br>")
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(title, html, type).notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Go Tools"
        private const val INSTALL_TIMEOUT_MS = 10 * 60_000

        fun getInstance(): GoToolInstaller = service()
    }
}
