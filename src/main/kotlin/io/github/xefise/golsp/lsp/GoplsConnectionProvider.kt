package io.github.xefise.golsp.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import io.github.xefise.golsp.toolchain.GoChildCommandLine
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolchain
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.NotFoundReason
import java.nio.file.Files
import java.nio.file.Path

/** Resolves gopls in [start], which LSP4IJ calls on a background thread, so the toolchain can be awaited. */
class GoplsConnectionProvider(private val project: Project) : OSProcessStreamConnectionProvider() {
    override fun start() {
        project.putUserData(LAUNCHED_WITH, null)
        project.putUserData(CONFIGURED_GOPLS, GoToolchainService.getInstance(project).configuredPaths().goplsPath.orEmpty())
        val toolchain = GoToolchainService.getInstance(project).awaitDetected(TOOLCHAIN_WAIT_MS)
        commandLine = createCommandLine(toolchain)
        super.start()
        project.putUserData(LAUNCHED_WITH, (toolchain as? GoToolchainState.Ready)?.toolchain)
    }

    private fun createCommandLine(state: GoToolchainState): GeneralCommandLine {
        if (!TrustedProjects.isProjectTrusted(project)) {
            throw CannotStartProcessException("gopls is not started in an untrusted project; trust the project to enable Go language features")
        }
        val toolchain = GoToolchainService.getInstance(project)
        blockingReason(state)?.let { throw CannotStartProcessException("gopls is not started: $it") }
        val gopls = toolchain.findTool(GoTool.GOPLS) ?: throw CannotStartProcessException(
            "gopls not found in GOBIN, PATH or ~/go/bin; click Install in the Go editor banner or set its path in the Go settings",
        )
        val commandLine = GoChildCommandLine(listOf(gopls), toolchain.goRoots())
        // gopls shells out to `go`; the detected one must win over whatever comes first in PATH.
        toolchain.pathWithGoFirst(toolchain.lookupEnvironment().path)?.let { commandLine.withEnvironment("PATH", it) }
        project.basePath?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }?.let { commandLine.withWorkingDirectory(it) }
        return commandLine
    }

    companion object {
        private const val TOOLCHAIN_WAIT_MS = 10_000L

        private val LAUNCHED_WITH: Key<GoToolchain> = Key.create("golsp.gopls.launchedWith")

        // The configured gopls path the last start saw ("" = autodetect); null when it never started.
        private val CONFIGURED_GOPLS: Key<String> = Key.create("golsp.gopls.configuredPath")

        fun launchedWith(project: Project): GoToolchain? = project.getUserData(LAUNCHED_WITH)

        fun configuredGoplsAtLaunch(project: Project): String? = project.getUserData(CONFIGURED_GOPLS)

        fun forgetLaunch(project: Project) = project.putUserData(LAUNCHED_WITH, null)

        /** Non-null when gopls must not run for [state]; a slow or failing `go env` alone does not block it. */
        fun blockingReason(state: GoToolchainState): String? = when (state) {
            is GoToolchainState.NotFound -> when (state.reason) {
                NotFoundReason.PROJECT_NOT_TRUSTED, NotFoundReason.GO_NOT_FOUND -> state.message
                NotFoundReason.GO_ENV_FAILED, NotFoundReason.GO_ENV_TIMEOUT -> null
            }
            GoToolchainState.Detecting, is GoToolchainState.Ready -> null
        }
    }
}
