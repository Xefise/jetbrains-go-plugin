package io.github.xefise.golsp.lsp

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.server.LanguageServerException
import io.github.xefise.golsp.toolchain.GoToolchainListener
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoToolchainState

/** Restarts an already-created gopls when the detected toolchain differs from the one it was launched with. */
class GoplsToolchainListener(private val project: Project) : GoToolchainListener {
    // The event is only a trigger: events of different generations may arrive out of order, the service state is current.
    override fun toolchainChanged(state: GoToolchainState) {
        if (project.isDisposed) return
        val current = GoToolchainService.getInstance(project).state
        val manager = LanguageServerManager.getInstance(project)
        val status = manager.getServerStatus(GoplsLanguageServerFactory.SERVER_ID)
        try {
            when {
                GoplsConnectionProvider.blockingReason(current) != null -> {
                    GoplsConnectionProvider.forgetLaunch(project)
                    if (status != null && status != ServerStatus.none) manager.stop(GoplsLanguageServerFactory.SERVER_ID)
                }
                current !is GoToolchainState.Ready -> Unit
                // Never created yet (no Go file opened): LSP4IJ starts it on demand with the right toolchain.
                status == null || status == ServerStatus.none -> Unit
                // A pending start is still awaiting detection and will read the state it settles to.
                status == ServerStatus.starting -> Unit
                status == ServerStatus.started ->
                    if (!current.toolchain.sameAs(GoplsConnectionProvider.launchedWith(project))) restart(manager)
                // Stopped or failed earlier (untrusted, go missing): bring it back now that the toolchain is ready.
                else -> restart(manager)
            }
        } catch (e: LanguageServerException) {
            // Thrown when the user disabled gopls in LSP4IJ settings; that choice wins.
            thisLogger().info("gopls restart skipped: ${e.message}")
        }
    }

    private fun restart(manager: LanguageServerManager) =
        manager.start(GoplsLanguageServerFactory.SERVER_ID, LanguageServerManager.StartOptions().setForceRestart(true))
}
