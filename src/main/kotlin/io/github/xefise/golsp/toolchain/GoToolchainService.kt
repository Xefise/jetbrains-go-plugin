package io.github.xefise.golsp.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.xefise.golsp.toolchain.model.ConfiguredToolPaths
import io.github.xefise.golsp.toolchain.model.GoEnv
import io.github.xefise.golsp.toolchain.model.GoEnvParseResult
import io.github.xefise.golsp.toolchain.model.GoEnvParser
import io.github.xefise.golsp.toolchain.model.GoRootPolicy
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolchain
import io.github.xefise.golsp.toolchain.model.GoToolchainSetting
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.LookupEnvironment
import io.github.xefise.golsp.toolchain.model.NotFoundReason
import io.github.xefise.golsp.toolchain.model.ToolLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
    private var resolving: ProgressIndicator? = null

    /** Reading the snapshot starts detection lazily in projects the startup check did not recognise as Go. */
    val state: GoToolchainState get() = current.also { ensureStarted() }

    val toolchain: GoToolchain? get() = (state as? GoToolchainState.Ready)?.toolchain

    /** Re-detects in the background with [configuredGoBinary], by default the effective one from settings. */
    fun refresh(configuredGoBinary: String? = configuredPaths().goBinary) {
        val gen = synchronized(lock) {
            job?.cancel()
            resolving?.cancel()
            if (settled.isDone) settled = CompletableFuture()
            ++generation
        }
        // A shim may now resolve to another Go (`asdf install`, `mise use`) although its own file is unchanged.
        GoToolProcesses.clearCaches()
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
        var detected = safeDetect(configuredGoBinary)
        settle(gen, detected)
        // A cold first `go env` (empty build cache, antivirus) may be slow; retry once, never in a loop.
        if (detected is GoToolchainState.NotFound && detected.reason == NotFoundReason.GO_ENV_TIMEOUT) {
            delay(TIMEOUT_RETRY_DELAY_MS)
            detected = safeDetect(configuredGoBinary)
            settle(gen, detected)
        }
        if (detected is GoToolchainState.Ready) resolveEffective(gen, detected.toolchain)
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

    /** Null in an untrusted project: no path there may come from its workspace file or lead to running a tool. */
    fun findTool(tool: GoTool): String? {
        if (!TrustedProjects.isProjectTrusted(project)) return null
        val detectedGo = if (tool == GoTool.GO) toolchain?.goBinary else null
        return configuredPaths().resolve(tool, detectedGo, GoToolLocator::isExecutableFile) {
            GoToolLocator.find(tool, lookupEnvironment())
        }
    }

    fun configuredPaths(): ConfiguredToolPaths = GoProjectToolchainSettings.configuredPaths(project)

    /**
     * PATH for child processes with the detected go's `GOROOT/bin` first, or null when go is unknown. Not the found
     * binary's directory: that may be `/opt/homebrew/bin` or a shims directory, and would reorder python, node...
     */
    fun pathWithGoFirst(basePath: String?): String? {
        val go = findTool(GoTool.GO) ?: return null
        val real = toolchain?.takeIf { it.goBinary == go }?.let(GoToolProcesses::realGo) ?: go
        val goDir = Path.of(real).parent?.toString() ?: return null
        return ToolLookup.prependToPath(basePath, goDir, lookupEnvironment().pathSeparator)
    }

    /** GOROOTs a child process may inherit; any other inherited GOROOT is dropped (see [GoChildCommandLine]). */
    fun goRoots(): List<String> = toolchain?.goRoots.orEmpty()

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
        return runGoEnv(go)
    }

    /**
     * Ready is published from a `GOTOOLCHAIN=local` run; here the user's GOTOOLCHAIN and the project's go.mod may make
     * go download another toolchain (~70 MB), so this has a long timeout, cancellation on refresh, and shows progress
     * only once it outlives [PROGRESS_DELAY_MS] (a plain `go env` takes ~100 ms).
     */
    private suspend fun resolveEffective(gen: Long, local: GoToolchain) {
        if (GoToolchainSetting.neverSwitches(userGoToolchain(local.env))) return
        val indicator = EmptyProgressIndicator()
        synchronized(lock) {
            if (gen != generation || project.isDisposed) return
            resolving = indicator
        }
        val done = CompletableFuture<Unit>()
        try {
            coroutineScope<Unit> {
                val progress = launch {
                    delay(PROGRESS_DELAY_MS)
                    if (!done.isDone) showResolvingProgress(indicator, done)
                }
                try {
                    val effective = runGoEnv(local.goBinary, emptyMap()) { it.runProcessWithProgressIndicator(indicator, EFFECTIVE_TIMEOUT_MS) }
                    if (indicator.isCanceled) return@coroutineScope
                    if (effective !is GoToolchainState.Ready) {
                        thisLogger().info("Effective Go toolchain not resolved, keeping the local one: $effective")
                        return@coroutineScope
                    }
                    update(gen, GoToolchainState.Ready(GoToolchain(local.goBinary, effective.toolchain.env, local.env.goRoot)))
                } finally {
                    done.complete(Unit)
                    progress.cancel()
                }
            }
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Effective Go toolchain resolution failed", e)
        } finally {
            synchronized(lock) { if (resolving === indicator) resolving = null }
        }
    }

    // Only mirrors the quiet run: waits for it, and cancelling the visible task kills the process through [quiet].
    private fun showResolvingProgress(quiet: ProgressIndicator, done: CompletableFuture<Unit>) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolving the project's Go toolchain", true) {
            override fun run(indicator: ProgressIndicator) {
                while (!done.isDone) {
                    if (indicator.isCanceled) {
                        quiet.cancel()
                        return
                    }
                    try {
                        done.get(PROGRESS_POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                    }
                }
            }
        })
    }

    // Phase 1 forces `local`, so the user's value comes from the environment and go env files, as go itself reads them.
    private fun userGoToolchain(env: GoEnv): String? {
        val userFile = env.value("GOENV")?.takeIf { it != "off" }?.let { file -> readTextOrNull { Path.of(file) } }
        val goRootFile = env.goRoot?.let { root -> readTextOrNull { Path.of(root, "go.env") } }
        return GoToolchainSetting.effective(GoToolLocator.loadShellEnvironment()[GOTOOLCHAIN], userFile, goRootFile)
    }

    private fun readTextOrNull(path: () -> Path): String? = try {
        path().takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
    } catch (_: IOException) {
        null
    } catch (_: InvalidPathException) {
        null
    }

    // GOTOOLCHAIN=local never waits for a toolchain download (resolveEffective does); the forced value is not the user's.
    private fun runGoEnv(go: String): GoToolchainState =
        when (val state = runGoEnv(go, mapOf(GOTOOLCHAIN to "local")) { it.runProcess(GO_ENV_TIMEOUT_MS, true) }) {
            is GoToolchainState.Ready -> GoToolchainState.Ready(GoToolchain(go, GoEnv(state.toolchain.env.values - GOTOOLCHAIN)))
            else -> state
        }

    private fun runGoEnv(go: String, extra: Map<String, String>, run: (CapturingProcessHandler) -> ProcessOutput): GoToolchainState {
        // Inside the project, so a shim reads its .tool-versions and go.mod/go.work `toolchain` lines apply.
        val workDir = project.basePath?.takeIf { Files.isDirectory(Path.of(it)) } ?: System.getProperty("java.io.tmpdir")
        // GOROOT is dropped: go finds its own, and an inherited one from another install would be reported instead.
        val commandLine = GoToolProcesses.commandLine(go, GoEnvParser.ARGUMENTS, extra, setOf(GoRootPolicy.GOROOT), workDir)
        val output = try {
            run(CapturingProcessHandler(commandLine))
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
        private const val GOTOOLCHAIN = GoToolchainSetting.NAME
        private const val GO_ENV_TIMEOUT_MS = 5_000
        private const val EFFECTIVE_TIMEOUT_MS = 10 * 60_000
        private const val STDERR_LIMIT = 500
        private const val TIMEOUT_RETRY_DELAY_MS = 30_000L
        private const val PROGRESS_DELAY_MS = 2_500L
        private const val PROGRESS_POLL_MS = 100L

        fun getInstance(project: Project): GoToolchainService = project.service()
    }
}
