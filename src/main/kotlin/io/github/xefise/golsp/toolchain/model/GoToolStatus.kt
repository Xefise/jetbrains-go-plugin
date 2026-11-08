package io.github.xefise.golsp.toolchain.model

sealed interface GoToolState {
    data object Missing : GoToolState

    data object Installing : GoToolState

    data object Ok : GoToolState

    /** Installing the pinned version fixes it. */
    data class Outdated(val reason: String) : GoToolState

    /** Delve newer than the project's Go supports; installing the pinned (older) version fixes it. */
    data class TooNew(val reason: String) : GoToolState

    /** Found, but its version could not be judged (local build, replaced module, unreadable): never offer an update. */
    data object Unknown : GoToolState
}

/** [path] is the resolved executable, [version] the module version as `go version -m` printed it. */
data class GoToolStatus(val tool: GoTool, val state: GoToolState, val path: String?, val version: String?) {
    val canUpdate: Boolean get() = state is GoToolState.Outdated || state is GoToolState.TooNew

    val needsInstall: Boolean get() = state == GoToolState.Missing || canUpdate
}

/** Status of gopls and dlv for a project; a tool without an entry has not been checked (Go not ready, untrusted). */
data class GoToolStatusSnapshot(val statuses: Map<GoTool, GoToolStatus>) {
    fun status(tool: GoTool): GoToolStatus? = statuses[tool]

    /** [installing] tools are shown as Installing whatever their last checked state was. */
    fun withInstalling(installing: (GoTool) -> Boolean): GoToolStatusSnapshot {
        if (statuses.keys.none(installing)) return this
        return GoToolStatusSnapshot(statuses.mapValues { (tool, status) -> if (installing(tool)) status.copy(state = GoToolState.Installing) else status })
    }

    companion object {
        val NOT_CHECKED = GoToolStatusSnapshot(emptyMap())
        val CHECKED_TOOLS: List<GoTool> = listOf(GoTool.GOPLS, GoTool.DLV)
    }
}

object GoToolStatuses {
    /** [info] is null when `go version -m` could not read [path]. */
    fun evaluate(spec: GoToolSpec, path: String?, info: GoBinaryInfo?, projectGo: GoVersion, installGo: GoVersion): GoToolStatus {
        if (path == null) return GoToolStatus(spec.tool, GoToolState.Missing, null, null)
        if (info == null) return GoToolStatus(spec.tool, GoToolState.Unknown, path, null)
        val state = when (val check = GoToolVersionCheck.check(spec, info, projectGo, installGo)) {
            GoToolVersionStatus.Ok -> GoToolState.Ok
            is GoToolVersionStatus.Outdated -> GoToolState.Outdated(check.reason)
            is GoToolVersionStatus.TooNew -> GoToolState.TooNew(check.reason)
            GoToolVersionStatus.Unknown -> GoToolState.Unknown
        }
        return GoToolStatus(spec.tool, state, path, info.moduleVersion)
    }

    /** The last lines of a failed `go install`, enough to show the error without flooding a notification. */
    fun outputTail(output: String, maxLines: Int = 15, maxChars: Int = 1500): String =
        output.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList().takeLast(maxLines).joinToString("\n").takeLast(maxChars)
}
