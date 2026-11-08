package io.github.xefise.golsp.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.server.LanguageServerException
import io.github.xefise.golsp.toolchain.GoToolInstallListener
import io.github.xefise.golsp.toolchain.GoToolchainListener
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.GoToolchainSettingsListener
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolchainState

/** Restarts an already-created gopls when the detected toolchain differs from the one it was launched with. */
class GoplsToolchainListener(private val project: Project) : GoToolchainListener {
    // The event is only a trigger: events of different generations may arrive out of order, the service state is current.
    override fun toolchainChanged(state: GoToolchainState) {
        if (project.isDisposed) return
        val current = GoToolchainService.getInstance(project).state
        val manager = LanguageServerManager.getInstance(project)
        val status = manager.getServerStatus(GoplsLanguageServerFactory.SERVER_ID)
        GoplsRestart.guarded {
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
                    if (!current.toolchain.sameAs(GoplsConnectionProvider.launchedWith(project))) GoplsRestart.restart(manager)
                // Stopped or failed earlier (untrusted, go missing): bring it back now that the toolchain is ready.
                else -> GoplsRestart.restart(manager)
            }
        }
    }
}

/** A freshly installed or updated gopls replaces the running (or failed) one. */
class GoplsToolInstallListener(private val project: Project) : GoToolInstallListener {
    override fun toolInstalled(tool: GoTool) {
        if (tool == GoTool.GOPLS) GoplsRestart.restartIfCreated(project)
    }
}

/** A changed gopls path in settings takes effect without the user restarting the server. */
class GoplsSettingsListener(private val project: Project) : GoToolchainSettingsListener {
    override fun settingsChanged() {
        if (project.isDisposed) return
        val launched = GoplsConnectionProvider.configuredGoplsAtLaunch(project) ?: return
        val configured = GoToolchainService.getInstance(project).configuredPaths().goplsPath.orEmpty()
        // Settings are applied on EDT; LSP4IJ's restart is kept off it.
        if (configured != launched) ApplicationManager.getApplication().executeOnPooledThread { GoplsRestart.restartIfCreated(project) }
    }
}

internal object GoplsRestart {
    /** Restarts gopls unless it was never created, is starting anyway, or must not run for the current toolchain. */
    fun restartIfCreated(project: Project) {
        if (project.isDisposed) return
        val manager = LanguageServerManager.getInstance(project)
        val status = manager.getServerStatus(GoplsLanguageServerFactory.SERVER_ID)
        if (status == null || status == ServerStatus.none || status == ServerStatus.starting) return
        if (GoplsConnectionProvider.blockingReason(GoToolchainService.getInstance(project).state) != null) return
        guarded { restart(manager) }
    }

    fun restart(manager: LanguageServerManager) =
        manager.start(GoplsLanguageServerFactory.SERVER_ID, LanguageServerManager.StartOptions().setForceRestart(true))

    fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: LanguageServerException) {
            // Thrown when the user disabled gopls in LSP4IJ settings; that choice wins.
            logger<GoplsRestart>().info("gopls restart skipped: ${e.message}")
        }
    }
}
