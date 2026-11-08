package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessInfo
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.ThreeState
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebugger
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import com.intellij.xdebugger.attach.XAttachHost
import com.intellij.xdebugger.attach.XLocalAttachDebugger
import io.github.xefise.golsp.debug.model.DelveAttachServer
import io.github.xefise.golsp.debug.model.GoBinaryDetector
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Offers "Go (Delve)" in Attach to Process for local processes whose executable is a Go binary. */
class GoAttachDebuggerProvider : XAttachDebuggerProvider, DumbAware {
    override fun isAttachHostApplicable(attachHost: XAttachHost): Boolean = attachHost is LocalAttachHost

    // Called for every process on a background thread; the holder lives for one action invocation.
    override fun getAvailableDebuggers(
        project: Project,
        attachHost: XAttachHost,
        processInfo: ProcessInfo,
        contextHolder: UserDataHolder,
    ): List<XAttachDebugger> = if (isGoProcess(processInfo, contextHolder)) listOf(GoAttachDebugger) else emptyList()

    companion object {
        private val GO_BINARY_CACHE = Key.create<MutableMap<String, Boolean>>("golsp.attach.goBinaries")

        fun isGoProcess(processInfo: ProcessInfo, contextHolder: UserDataHolder): Boolean {
            if (processInfo.isOwnedByCurrentUser == ThreeState.NO) return false
            val path = processInfo.executableCannonicalPath.orElse(null)?.takeIf(DelveAttachServer::isCandidatePath) ?: return false
            // A lost race only costs one extra detection.
            val cache = contextHolder.getUserData(GO_BINARY_CACHE)
                ?: ConcurrentHashMap<String, Boolean>().also { contextHolder.putUserData(GO_BINARY_CACHE, it) }
            return cache.getOrPut(path) { GoBinaryDetector.isGoBinary(Path.of(path)) }
        }
    }
}

object GoAttachDebugger : XLocalAttachDebugger {
    override fun getDebuggerDisplayName(): String = "Go (Delve)"

    @Throws(ExecutionException::class)
    override fun attachDebugSession(project: Project, processInfo: ProcessInfo) {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw ExecutionException("Go debugging is disabled in untrusted projects; trust the project to attach")
        }
        GoAttachLauncher(project, processInfo).start()
    }
}
