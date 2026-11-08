package io.github.xefise.golsp.toolchain

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolRegistry
import io.github.xefise.golsp.toolchain.model.GoToolStatus
import io.github.xefise.golsp.toolchain.model.GoToolStatusSnapshot
import io.github.xefise.golsp.toolchain.model.GoToolStatuses
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.GoVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** gopls/dlv status for the project; [snapshot] is EDT-safe, the check itself runs `go version -m` in the background. */
@Service(Service.Level.PROJECT)
class GoToolStatusService(private val project: Project, private val scope: CoroutineScope) {
    private val lock = Any()

    @Volatile
    private var checked: GoToolStatusSnapshot = GoToolStatusSnapshot.NOT_CHECKED
    private var generation = 0L
    private var job: Job? = null

    /** Never starts anything; empty until the toolchain is ready in a trusted project. */
    val snapshot: GoToolStatusSnapshot get() = checked.withInstalling(GoToolInstaller.getInstance()::isInstalling)

    fun status(tool: GoTool): GoToolStatus? = snapshot.status(tool)

    /** Re-checks in the background; reading the toolchain here starts its detection, so call it only on user demand or events. */
    fun refresh() {
        val gen = synchronized(lock) {
            job?.cancel()
            ++generation
        }
        synchronized(lock) {
            if (gen == generation) job = scope.launch(Dispatchers.IO) { safeCheck(gen) }
        }
    }

    /** Re-checks only where a check already ran, so events never make a non-Go project run `go`. */
    fun refreshIfStarted() {
        if (synchronized(lock) { generation != 0L }) refresh()
    }

    /** Re-checks on the calling background thread, superseding any running check. */
    fun refreshNow() {
        val gen = synchronized(lock) {
            job?.cancel()
            ++generation
        }
        safeCheck(gen)
    }

    fun refreshNowIfStarted() {
        if (synchronized(lock) { generation != 0L }) refreshNow()
    }

    /** Re-publishes the snapshot, e.g. when an install starts or ends. */
    fun publish() {
        if (!project.isDisposed) project.messageBus.syncPublisher(GoToolStatusListener.TOPIC).toolStatusChanged(snapshot)
    }

    private fun safeCheck(gen: Long) {
        val result = try {
            check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ProcessCanceledException) throw e
            thisLogger().warn("Go tool status check failed", e)
            GoToolStatusSnapshot.NOT_CHECKED
        }
        synchronized(lock) {
            if (gen != generation) return
            checked = result
        }
        publish()
    }

    private fun check(): GoToolStatusSnapshot {
        if (!TrustedProjects.isProjectTrusted(project)) return GoToolStatusSnapshot.NOT_CHECKED
        val toolchainService = GoToolchainService.getInstance(project)
        val toolchain = (toolchainService.state as? GoToolchainState.Ready)?.toolchain ?: return GoToolStatusSnapshot.NOT_CHECKED
        val projectGo = GoVersion.parse(toolchain.env.goVersion)
        val installGo = GoToolProcesses.localGoVersion(toolchain.goBinary)
        val statuses = GoToolStatusSnapshot.CHECKED_TOOLS.associateWith { tool ->
            val path = toolchainService.findTool(tool)
            val info = path?.let { GoToolProcesses.binaryInfo(toolchain.goBinary, it) }
            GoToolStatuses.evaluate(GoToolRegistry.spec(tool)!!, path, info, projectGo, installGo)
        }
        return GoToolStatusSnapshot(statuses)
    }

    companion object {
        fun getInstance(project: Project): GoToolStatusService = project.service()
    }
}

/** Published on the project bus from a background thread or EDT whenever the snapshot or an install state changes. */
fun interface GoToolStatusListener {
    fun toolStatusChanged(snapshot: GoToolStatusSnapshot)

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<GoToolStatusListener> = Topic.create("Go tool status changed", GoToolStatusListener::class.java)
    }
}

/** Published on every open project's bus from a background thread after `go install` of [tool] succeeded. */
fun interface GoToolInstallListener {
    fun toolInstalled(tool: GoTool)

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<GoToolInstallListener> = Topic.create("Go tool installed", GoToolInstallListener::class.java)
    }
}

class GoToolStatusRefresher(private val project: Project) : GoToolchainListener {
    // Detecting is followed by a settled state, which is the one worth checking against.
    override fun toolchainChanged(state: GoToolchainState) {
        if (state !is GoToolchainState.Detecting && !project.isDisposed) GoToolStatusService.getInstance(project).refresh()
    }
}
