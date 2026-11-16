// Modified from vscode-go (golang/vscode-go), commit 1608cb5, extension/src/goEnv.ts (toolInstallationEnvironment)
// and extension/src/goInstallTools.ts (installTools, tmpDirForToolInstallation), MIT
package io.github.xefise.golsp.toolchain.model

/** `go install -v <pkg>@<version>`; [environment] is set and [removedEnvironment] unset on top of the IDE environment. */
data class GoToolInstallCommand(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val removedEnvironment: Set<String>,
    val workingDirectory: String,
)

object GoToolInstall {
    /** Tools are built for the host with the default setup, whatever the project cross-compiles to. */
    val REMOVED_ENVIRONMENT: Set<String> = setOf("GOOS", "GOARCH", "GOROOT", "GOFLAGS", "GOENV", "GO111MODULE")

    /**
     * [goExecutable] reports [installGo] when run with `GOTOOLCHAIN=local`; [projectGo] is the version the project
     * builds with. [workingDirectory] must be outside any module or workspace (e.g. a fresh temp directory), so
     * the project's go.mod, go.work and toolchain line cannot interfere.
     */
    fun command(
        spec: GoToolSpec,
        goExecutable: String,
        installGo: GoVersion,
        projectGo: GoVersion,
        workingDirectory: String,
        goEnv: GoEnv = GoEnv(emptyMap()),
        inheritedEnvironment: Map<String, String> = emptyMap(),
    ): GoToolInstallCommand = GoToolInstallCommand(
        executable = goExecutable,
        arguments = listOf("install", "-v", GoToolRegistry.installTarget(spec, projectGo)),
        environment = mapOf("GOTOOLCHAIN" to toolchain(installGo, projectGo)) + installTarget(goEnv) +
            downloadSettings(goEnv, inheritedEnvironment),
        removedEnvironment = REMOVED_ENVIRONMENT,
        workingDirectory = workingDirectory,
    )

    /** `go env -w` settings that make downloads work behind a corporate proxy or for private modules. */
    val DOWNLOAD_SETTINGS: List<String> = listOf("GOPROXY", "GOSUMDB", "GONOSUMDB", "GOPRIVATE", "GONOPROXY", "GOINSECURE", "GOMODCACHE")

    // GOENV is unset, so GOBIN/GOPATH from the user's GOENV file are passed explicitly: install where lookup looks.
    private fun installTarget(goEnv: GoEnv): Map<String, String> = fromGoEnv(listOf("GOBIN", "GOPATH"), goEnv)

    // Variables the environment already sets are inherited unchanged; only what the GOENV file adds is passed on.
    private fun downloadSettings(goEnv: GoEnv, inherited: Map<String, String>): Map<String, String> =
        fromGoEnv(DOWNLOAD_SETTINGS.filter { it !in inherited }, goEnv)

    private fun fromGoEnv(keys: List<String>, goEnv: GoEnv): Map<String, String> =
        keys.mapNotNull { key -> goEnv.value(key)?.let { key to it } }.toMap()

    /**
     * `auto` lets tools that need a newer Go download it; set unconditionally because GOENV or go.env may say `local`.
     * A project on a newer Go than the local one gets its tools built by at least that Go.
     */
    fun toolchain(installGo: GoVersion, projectGo: GoVersion): String {
        // Devel builds are assumed new enough, and their names are not valid GOTOOLCHAIN values.
        if (installGo !is GoVersion.Release || projectGo !is GoVersion.Release) return "auto"
        return if (projectGo > installGo) "${toolchainName(projectGo)}+auto" else "auto"
    }

    // Since go1.21 the first release is `go1.N.0`; `go1.N` names no downloadable toolchain.
    private fun toolchainName(go: GoVersion.Release): String =
        if (go.patch == null && go.preRelease == null && !go.isBefore(1, 21)) "${go.text}.0" else go.text
}
