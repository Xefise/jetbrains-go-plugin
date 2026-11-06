package io.github.xefise.golsp.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.xefise.golsp.toolchain.model.GoEnvParseResult
import io.github.xefise.golsp.toolchain.model.GoEnvParser
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolchain
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.LookupEnvironment
import io.github.xefise.golsp.toolchain.model.NotFoundReason
import io.github.xefise.golsp.toolchain.model.ToolLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The single owner of `go` discovery for a project; everything else reads [state] and never runs `go` itself. */
@Service(Service.Level.PROJECT)
class GoToolchainService(private val project: Project, private val scope: CoroutineScope) {
    private val lock = Any()

    @Volatile
    private var current: GoToolchainState = GoToolchainState.Detecting
    private var generation = 0L
    private var job: Job? = null
    private var settled = CompletableFuture<GoToolchainState>()

    /** Reading the snapshot starts detection lazily in projects the startup check did not recognise as Go. */
    val state: GoToolchainState get() = current.also { ensureStarted() }

    val toolchain: GoToolchain? get() = (state as? GoToolchainState.Ready)?.toolchain

    /** Re-detects in the background; [configuredGoBinary] is the explicit path from settings (none yet). */
    fun refresh(configuredGoBinary: String? = null) {
        val gen = synchronized(lock) {
            job?.cancel()
            if (settled.isDone) settled = CompletableFuture()
            ++generation
        }
        // Detecting is published before the job starts, so it can never overwrite the job's result.
        update(gen, GoToolchainState.Detecting)
        synchronized(lock) {
            if (gen == generation) job = scope.launch(Dispatchers.IO) { detectWithRetry(gen, configuredGoBinary) }
        }
    }

    fun ensureStarted() {
        if (synchronized(lock) { generation == 0L }) refresh()
    }

    /** Re-detects after a trust change, but never makes a non-Go project run `go`. */
    fun refreshIfStarted() {
        if (synchronized(lock) { generation != 0L }) refresh()
    }

    private suspend fun detectWithRetry(gen: Long, configuredGoBinary: String?) {
        val first = safeDetect(configuredGoBinary)
        settle(gen, first)
        // A first `go env` may be slow while GOTOOLCHAIN downloads a toolchain; retry once, never in a loop.
        if (first is GoToolchainState.NotFound && first.reason == NotFoundReason.GO_ENV_TIMEOUT) {
            delay(TIMEOUT_RETRY_DELAY_MS)
            settle(gen, safeDetect(configuredGoBinary))
        }
    }

    private fun safeDetect(configuredGoBinary: String?): GoToolchainState = try {
        detect(configuredGoBinary)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        thisLogger().warn("Go toolchain detection failed", e)
        GoToolchainState.NotFound(NotFoundReason.GO_ENV_FAILED, "Go toolchain detection failed: ${e.message}")
    }

    /** Blocks a background thread until detection settles; on EDT returns the current state immediately. */
    fun awaitDetected(timeoutMs: Long): GoToolchainState {
        val snapshot = state
        if (snapshot !is GoToolchainState.Detecting || ApplicationManager.getApplication().isDispatchThread) return snapshot
        val future = synchronized(lock) { settled }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            current
        }
    }

    /** Lookup env enriched with the detected `go env` (GOBIN, GOPATH); never waits for the shell env. */
    fun lookupEnvironment(): LookupEnvironment = GoToolLocator.environment().withGoEnv(toolchain?.env)

    fun findTool(tool: GoTool): String? {
        if (tool == GoTool.GO) toolchain?.goBinary?.let { return it }
        return GoToolLocator.find(tool, lookupEnvironment())
    }

    /** PATH for child processes with the detected go directory first, or null when go is unknown. */
    fun pathWithGoFirst(basePath: String?): String? {
        val goDir = findTool(GoTool.GO)?.let { Path.of(it).parent?.toString() } ?: return null
        return ToolLookup.prependToPath(basePath, goDir, lookupEnvironment().pathSeparator)
    }

    private fun settle(gen: Long, state: GoToolchainState) {
        if (!update(gen, state)) return
        synchronized(lock) { if (gen == generation) settled.complete(state) }
    }

    private fun update(gen: Long, state: GoToolchainState): Boolean {
        synchronized(lock) {
            if (gen != generation) return false
            if (current.sameAs(state)) return true
            current = state
        }
        if (project.isDisposed) return true
        try {
            project.messageBus.syncPublisher(GoToolchainListener.TOPIC).toolchainChanged(state)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            thisLogger().error("Go toolchain listener failed", e)
        }
        return true
    }

    private fun detect(configuredGoBinary: String?): GoToolchainState {
        if (!TrustedProjects.isProjectTrusted(project)) {
            return GoToolchainState.NotFound(NotFoundReason.PROJECT_NOT_TRUSTED, "The project is not trusted, so Go tools are not started")
        }
        // Background thread: waiting for the login-shell env here is what keeps every other caller from waiting.
        val shellEnv = GoToolLocator.loadShellEnvironment()
        val go = if (configuredGoBinary != null) {
            configuredGoBinary.takeIf(GoToolLocator::isExecutableFile)
                ?: return GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "Configured Go executable '$configuredGoBinary' is not executable")
        } else {
            GoToolLocator.find(GoTool.GO, GoToolLocator.lookupEnvironment(shellEnv))
                ?: return GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "Go executable not found in PATH, ~/go/bin, GOROOT or standard locations")
        }
        return runGoEnv(go, shellEnv)
    }

    private fun runGoEnv(go: String, shellEnv: Map<String, String>): GoToolchainState {
        val lookup = GoToolLocator.lookupEnvironment(shellEnv)
        val commandLine = GeneralCommandLine(listOf(go) + GoEnvParser.ARGUMENTS)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
        Path.of(go).parent?.let { ToolLookup.prependToPath(lookup.path, it.toString(), lookup.pathSeparator) }
            ?.let { commandLine.withEnvironment("PATH", it) }
        // Run inside the project so go.mod/go.work `toolchain` lines (GOTOOLCHAIN=auto) pick the effective version.
        project.basePath?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }?.let { commandLine.withWorkingDirectory(it) }
        val output = try {
            CapturingProcessHandler(commandLine).runProcess(GO_ENV_TIMEOUT_MS, true)
        } catch (e: ExecutionException) {
            thisLogger().info("go env failed to start", e)
            return GoToolchainState.NotFound(NotFoundReason.GO_ENV_FAILED, "Cannot run '$go env -json': ${e.message}")
        }
        if (output.isTimeout) {
            return GoToolchainState.NotFound(NotFoundReason.GO_ENV_TIMEOUT, "'$go env -json' did not finish within ${GO_ENV_TIMEOUT_MS / 1000} s")
        }
        if (output.exitCode != 0) {
            val stderr = output.stderr.trim().take(STDERR_LIMIT)
            return GoToolchainState.NotFound(NotFoundReason.GO_ENV_FAILED, "'$go env -json' exited with code ${output.exitCode}: $stderr")
        }
        return when (val parsed = GoEnvParser.parse(output.stdout)) {
            is GoEnvParseResult.Success -> GoToolchainState.Ready(GoToolchain(go, parsed.env))
            is GoEnvParseResult.Failure -> GoToolchainState.NotFound(NotFoundReason.GO_ENV_FAILED, parsed.message)
        }
    }

    companion object {
        private const val GO_ENV_TIMEOUT_MS = 5_000
        private const val STDERR_LIMIT = 500
        private const val TIMEOUT_RETRY_DELAY_MS = 30_000L

        fun getInstance(project: Project): GoToolchainService = project.service()
    }
}
