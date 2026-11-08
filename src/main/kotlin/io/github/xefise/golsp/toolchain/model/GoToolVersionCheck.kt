package io.github.xefise.golsp.toolchain.model

sealed interface GoToolVersionStatus {
    data object Ok : GoToolVersionStatus

    /** Installing [GoToolRegistry.installVersion] fixes it. */
    data class Outdated(val reason: String) : GoToolVersionStatus

    /** Delve newer than the project's Go: it refuses to debug, the fix is installing the older pin. */
    data class TooNew(val reason: String) : GoToolVersionStatus

    /** A local or replaced build, another module, or an unparseable Go version: never suggest replacing it. */
    data object Unknown : GoToolVersionStatus
}

/** Offline check of an installed gopls/dlv against the project's Go; no network lookups for newer releases. */
object GoToolVersionCheck {
    /** [installGo] is the Go that `go install` would run as (`GOTOOLCHAIN=local go env GOVERSION`); Unknown if not known. */
    fun check(spec: GoToolSpec, info: GoBinaryInfo, projectGo: GoVersion, installGo: GoVersion = GoVersion.Unknown): GoToolVersionStatus {
        if (info.modulePath != spec.modulePath) return GoToolVersionStatus.Unknown
        val installed = info.releasedVersion ?: return GoToolVersionStatus.Unknown
        val go = projectGo as? GoVersion.Release ?: return GoToolVersionStatus.Unknown
        // No Delve we know of supports such a Go, so no installable version would satisfy the check.
        if (spec.tool == GoTool.DLV && (go.major != 1 || go.minor < DelveSupport.OLDEST_KNOWN_GO_MINOR)) return GoToolVersionStatus.Unknown
        GoToolRegistry.minimumVersion(spec, go)?.let { minimum ->
            if (installed < minimum) {
                return GoToolVersionStatus.Outdated("${spec.tool.baseName} $installed is older than $minimum, the oldest supporting ${go.text}")
            }
        }
        return when (spec.tool) {
            GoTool.GOPLS -> checkGoplsToolchain(info, go, installGo)
            GoTool.DLV -> checkDelveRange(installed, go)
            GoTool.GO -> GoToolVersionStatus.Unknown
        }
    }

    // gopls type-checks with the go/types it was compiled with, so it cannot handle language features of a newer Go.
    private fun checkGoplsToolchain(info: GoBinaryInfo, go: GoVersion.Release, installGo: GoVersion): GoToolVersionStatus {
        val builtWith = info.builtWith as? GoVersion.Release ?: return GoToolVersionStatus.Ok
        if (!builtWith.isBefore(go.major, go.minor)) return GoToolVersionStatus.Ok
        // Before go1.21 there is no toolchain switching: a reinstall would be built by the same old Go again.
        if (installGo is GoVersion.Release && installGo.isBefore(1, 21) && installGo.isBefore(go.major, go.minor)) {
            return GoToolVersionStatus.Unknown
        }
        return GoToolVersionStatus.Outdated("gopls was built with ${builtWith.text}, older than the project's ${go.text}")
    }

    private fun checkDelveRange(installed: ModuleVersion, go: GoVersion.Release): GoToolVersionStatus {
        val range = DelveSupport.supportedGoMinors(installed) ?: return GoToolVersionStatus.Ok
        if (go.minor >= range.first) return GoToolVersionStatus.Ok
        return GoToolVersionStatus.TooNew("dlv $installed supports Go 1.${range.first} and newer, the project uses ${go.text}")
    }
}
