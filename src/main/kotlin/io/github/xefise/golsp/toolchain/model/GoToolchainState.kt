package io.github.xefise.golsp.toolchain.model

import java.io.File

data class GoToolchain(val goBinary: String, val env: GoEnv) {
    val goBinDirectory: String? get() = File(goBinary).parent

    // What tools launched with this toolchain depend on; volatile go env values (GOMOD, GOGCCFLAGS...) are ignored.
    val identity: List<String?>
        get() = listOf(goBinary) + IDENTITY_KEYS.map(env::value)

    fun sameAs(other: GoToolchain?): Boolean = other != null && identity == other.identity

    companion object {
        val IDENTITY_KEYS: List<String> = listOf("GOROOT", "GOVERSION", "GOPATH", "GOBIN", "GOFLAGS", "GOTOOLCHAIN")
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
