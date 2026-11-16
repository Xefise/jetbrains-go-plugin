package io.github.xefise.golsp.toolchain.model

import java.io.File

/**
 * [env] is what the project builds with, possibly a toolchain go.mod made go download; [localGoRoot] is where
 * [goBinary] itself lives (the real binary behind a shim or symlink).
 */
data class GoToolchain(val goBinary: String, val env: GoEnv, val localGoRoot: String? = env.goRoot) {
    val goBinDirectory: String? get() = File(goBinary).parent

    /** GOROOTs a child process may legitimately inherit. */
    val goRoots: List<String> get() = listOfNotNull(localGoRoot, env.goRoot).distinct()

    // What tools launched with this toolchain depend on; volatile go env values (GOMOD, GOGCCFLAGS...) are ignored.
    val identity: List<String?>
        get() = listOf(goBinary, localGoRoot) + IDENTITY_KEYS.map(env::value)

    fun sameAs(other: GoToolchain?): Boolean = other != null && identity == other.identity

    companion object {
        // GOTOOLCHAIN itself is left out: its effect is the resolved GOROOT/GOVERSION, and detection forces `local` first.
        val IDENTITY_KEYS: List<String> = listOf("GOROOT", "GOVERSION", "GOPATH", "GOBIN", "GOFLAGS")
    }
}

enum class NotFoundReason {
    PROJECT_NOT_TRUSTED,
    GO_NOT_FOUND,
    GO_ENV_FAILED,
    GO_ENV_TIMEOUT,
}

sealed interface GoToolchainState {
    data object Detecting : GoToolchainState

    data class Ready(val toolchain: GoToolchain) : GoToolchainState

    data class NotFound(val reason: NotFoundReason, val message: String) : GoToolchainState

    fun sameAs(other: GoToolchainState): Boolean =
        if (this is Ready && other is Ready) toolchain.sameAs(other.toolchain) else this == other
}
