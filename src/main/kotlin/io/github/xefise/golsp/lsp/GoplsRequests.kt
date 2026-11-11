package io.github.xefise.golsp.lsp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Blocking helpers for our own requests to an already running gopls; call them off EDT. */
internal object GoplsRequests {
    private const val POLL_MS = 20L

    fun isStarted(project: Project): Boolean =
        LanguageServerManager.getInstance(project).getServerStatus(GoplsLanguageServerFactory.SERVER_ID) == ServerStatus.started

    // Never starts gopls: starting it is the toolchain's job, and callers run under a read action or a progress.
    fun startedGopls(project: Project, deadline: Long): LanguageServerItem? {
        if (!isStarted(project)) {
            logger<GoplsRequests>().debug("gopls is not started")
            return null
        }
        return await(LanguageServerManager.getInstance(project).getLanguageServer(GoplsLanguageServerFactory.SERVER_ID), deadline)
    }

    fun canResolve(server: LanguageServerItem): Boolean {
        val provider = server.serverCapabilities?.codeActionProvider ?: return false
        return provider.isRight && provider.right.resolveProvider == true
    }

    fun deadline(seconds: Long): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)

    /** Waits for [future] until [deadline]; cancels it on timeout or when the current progress is cancelled. */
    fun <T> await(future: CompletableFuture<T>, deadline: Long): T {
        while (true) {
            try {
                ProgressManager.checkCanceled()
            } catch (e: ProcessCanceledException) {
                future.cancel(true)
                throw e
            }
            if (System.nanoTime() >= deadline) {
                future.cancel(true)
                throw TimeoutException()
            }
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Short polls let a pending write action cancel a read action.
            }
        }
    }
}
