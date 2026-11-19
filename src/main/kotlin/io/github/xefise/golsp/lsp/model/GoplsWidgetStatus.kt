package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.toolchain.model.GoToolState
import io.github.xefise.golsp.toolchain.model.GoToolStatus
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.NotFoundReason

data class GoplsWidgetStatus(val forCurrentFile: Boolean, val text: String, val isError: Boolean)

object GoplsWidgetStatuses {
    /** Null when the project shows no sign of Go: no Go file in the editor and gopls not running. */
    fun status(goFile: Boolean, started: Boolean, toolchain: () -> GoToolchainState, gopls: () -> GoToolStatus?): GoplsWidgetStatus? {
        if (!goFile && !started) return null
        val state = toolchain()
        val tool = gopls()
        fun of(text: String, isError: Boolean = false) = GoplsWidgetStatus(goFile, text, isError)
        return when {
            state is GoToolchainState.NotFound && state.reason == NotFoundReason.PROJECT_NOT_TRUSTED -> of("gopls (project not trusted)")
            state is GoToolchainState.NotFound && state.reason == NotFoundReason.GO_NOT_FOUND -> of("gopls (Go not found)", isError = true)
            tool?.state == GoToolState.Missing -> of("gopls not found", isError = true)
            tool?.state == GoToolState.Installing -> of("gopls (installing)")
            !tool?.version.isNullOrBlank() -> of("gopls ${tool!!.version}")
            else -> of("gopls")
        }
    }
}
