// Modified from vscode-go (golang/vscode-go), commit 1608cb5, extension/src/goTools.ts (getImportPathWithVersion)
// and extension/src/goToolsInformation.ts, MIT
package io.github.xefise.golsp.toolchain.model

/** A tool installed with `go install <importPath>@<version>`. */
data class GoToolSpec(val tool: GoTool, val importPath: String, val modulePath: String)

object GoToolRegistry {
    const val LATEST = "latest"
    private const val GOPLS_FOR_GO_1_18 = "v0.14.2"
    private const val GOPLS_FOR_GO_1_20 = "v0.15.3"

    val GOPLS = GoToolSpec(GoTool.GOPLS, "golang.org/x/tools/gopls", "golang.org/x/tools/gopls")
    val DLV = GoToolSpec(GoTool.DLV, "github.com/go-delve/delve/cmd/dlv", "github.com/go-delve/delve")

    fun spec(tool: GoTool): GoToolSpec? = when (tool) {
        GoTool.GOPLS -> GOPLS
        GoTool.DLV -> DLV
        GoTool.GO -> null
    }

    /**
     * The version to install for a project on [goVersion]: a pin when `latest` no longer supports it.
     * Devel and unknown Go versions get `latest`.
     */
    fun installVersion(spec: GoToolSpec, goVersion: GoVersion): String {
        val release = goVersion as? GoVersion.Release ?: return LATEST
        return when (spec.tool) {
            GoTool.GOPLS -> goplsPin(release) ?: LATEST
            // Delve refuses binaries built by a Go outside its supported range, so the pin follows the project's Go.
            GoTool.DLV -> DelveSupport.installVersion(release)
            GoTool.GO -> LATEST
        }
    }

    fun installTarget(spec: GoToolSpec, goVersion: GoVersion): String = "${spec.importPath}@${installVersion(spec, goVersion)}"

    /** The oldest installed version still acceptable for [goVersion]; null when there is no version floor. */
    fun minimumVersion(spec: GoToolSpec, goVersion: GoVersion): ModuleVersion? {
        val release = goVersion as? GoVersion.Release ?: return null
        return when (spec.tool) {
            GoTool.GOPLS -> goplsPin(release)?.let(ModuleVersion::parse)
            GoTool.DLV -> DelveSupport.minimumVersion(release)
            GoTool.GO -> null
        }
    }

    private fun goplsPin(go: GoVersion.Release): String? = when {
        go.isBefore(1, 19) -> GOPLS_FOR_GO_1_18
        go.isBefore(1, 21) -> GOPLS_FOR_GO_1_20
        else -> null
    }
}

/** Go minor versions accepted by Delve releases, from `pkg/goversion/compat.go` at each tag. */
internal object DelveSupport {
    private data class Line(val since: ModuleVersion, val minGoMinor: Int, val maxGoMinor: Int)

    // The first release of each support range; a range holds until the next line.
    private val LINES = listOf(
        line("v1.20.0", 18, 20),
        line("v1.21.0", 18, 21),
        line("v1.22.0", 19, 22),
        line("v1.23.0", 21, 23),
        line("v1.24.0", 21, 24),
        line("v1.25.1", 22, 25),
        line("v1.26.0", 24, 26),
        line("v1.27.0", 25, 27),
    )

    private val NEWEST_KNOWN = LINES.last().since
    val OLDEST_KNOWN_GO_MINOR = LINES.first().minGoMinor

    fun installVersion(go: GoVersion.Release): String {
        if (go.major != 1) return GoToolRegistry.LATEST
        return when {
            go.minor < 19 -> "v1.20.2"
            go.minor < 21 -> "v1.22.1"
            go.minor < 22 -> "v1.25.0"
            go.minor < 24 -> "v1.25.2"
            go.minor < 25 -> "v1.26.3"
            else -> GoToolRegistry.LATEST
        }
    }

    /** First release whose maximum covers [go]; for a Go newer than every known line, the newest known line. */
    fun minimumVersion(go: GoVersion.Release): ModuleVersion? {
        if (go.major != 1) return null
        return LINES.firstOrNull { it.maxGoMinor >= go.minor }?.since ?: NEWEST_KNOWN
    }

    /** Null when [dlv] is newer than every known line or older than the oldest one. */
    fun supportedGoMinors(dlv: ModuleVersion): IntRange? {
        if (dlv.major == NEWEST_KNOWN.major && dlv.minor > NEWEST_KNOWN.minor || dlv.major > NEWEST_KNOWN.major) return null
        return LINES.lastOrNull { it.since <= dlv }?.let { it.minGoMinor..it.maxGoMinor }
    }

    private fun line(since: String, min: Int, max: Int) = Line(ModuleVersion.parse(since)!!, min, max)
}
