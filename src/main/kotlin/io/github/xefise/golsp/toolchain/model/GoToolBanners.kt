package io.github.xefise.golsp.toolchain.model

/** What the editor banner of a Go file suggests; [tool] is the kind its "don't show again" hides. */
sealed interface GoToolBanner {
    val tool: GoTool

    data class GoNotFound(val message: String) : GoToolBanner {
        override val tool: GoTool get() = GoTool.GO
    }

    data class Install(override val tool: GoTool) : GoToolBanner

    data class Update(override val tool: GoTool, val reason: String) : GoToolBanner

    data class Installing(override val tool: GoTool) : GoToolBanner
}

object GoToolBanners {
    private val GO_FILE_NAMES = setOf("go.mod", "go.work")

    fun isGoFileName(name: String): Boolean = name.endsWith(".go") || name in GO_FILE_NAMES

    /** At most one banner: Go first, then gopls, then dlv. Nothing while detecting or in an untrusted project. */
    fun decide(toolchain: GoToolchainState, tools: GoToolStatusSnapshot, hidden: (GoTool) -> Boolean): GoToolBanner? = when (toolchain) {
        GoToolchainState.Detecting -> null
        is GoToolchainState.NotFound ->
            if (toolchain.reason == NotFoundReason.PROJECT_NOT_TRUSTED || hidden(GoTool.GO)) null
            else GoToolBanner.GoNotFound(toolchain.message)
        is GoToolchainState.Ready -> GoToolStatusSnapshot.CHECKED_TOOLS
            .filterNot(hidden)
            .firstNotNullOfOrNull { tool -> tools.status(tool)?.let(::bannerFor) }
    }

    private fun bannerFor(status: GoToolStatus): GoToolBanner? = when (val state = status.state) {
        GoToolState.Missing -> GoToolBanner.Install(status.tool)
        GoToolState.Installing -> GoToolBanner.Installing(status.tool)
        is GoToolState.Outdated -> GoToolBanner.Update(status.tool, state.reason)
        is GoToolState.TooNew -> GoToolBanner.Update(status.tool, state.reason)
        GoToolState.Ok, GoToolState.Unknown -> null
    }
}

object GoToolTexts {
    fun toolchain(state: GoToolchainState): String = when (state) {
        GoToolchainState.Detecting -> "Detecting…"
        is GoToolchainState.Ready -> listOfNotNull(state.toolchain.env.goVersion, state.toolchain.goBinary).joinToString(" — ")
        is GoToolchainState.NotFound -> state.message
    }

    fun status(status: GoToolStatus?): String {
        if (status == null) return "Not checked: Go is not available"
        val where = listOfNotNull(status.version, status.path).joinToString(" — ")
        return when (val state = status.state) {
            GoToolState.Missing -> "Not installed"
            GoToolState.Installing -> "Installing…"
            GoToolState.Ok -> where
            GoToolState.Unknown -> "$where (version not recognised)"
            is GoToolState.Outdated -> "$where (update recommended: ${state.reason})"
            is GoToolState.TooNew -> "$where (update recommended: ${state.reason})"
        }
    }

    /** Manual update is allowed for any recognised install; an unrecognised one may be a local build. */
    fun canInstallManually(status: GoToolStatus?): Boolean =
        status != null && status.state != GoToolState.Installing && status.state != GoToolState.Unknown

    fun buttonText(status: GoToolStatus?): String = if (status?.state == GoToolState.Missing || status == null) "Install" else "Update"
}
