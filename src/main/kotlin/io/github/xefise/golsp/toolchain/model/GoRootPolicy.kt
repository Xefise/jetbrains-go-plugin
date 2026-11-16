package io.github.xefise.golsp.toolchain.model

/**
 * An inherited GOROOT (e.g. exported in `.zshrc`) makes the selected go use another installation's standard library:
 * "version mismatch". Go finds its own GOROOT from its executable, so dropping a foreign one is always safe.
 */
object GoRootPolicy {
    const val GOROOT = "GOROOT"

    /** `<goRoot>/bin`, where the real `go` of a toolchain lives, unlike a shim or a symlink in a shared directory. */
    fun binDirectory(goRoot: String, isWindows: Boolean): String {
        val separator = if (isWindows) '\\' else '/'
        return if (goRoot.endsWith(separator) || (isWindows && goRoot.endsWith('/'))) "${goRoot}bin" else "$goRoot${separator}bin"
    }

    fun goExecutable(goRoot: String, isWindows: Boolean): String {
        val separator = if (isWindows) '\\' else '/'
        return binDirectory(goRoot, isWindows) + separator + if (isWindows) "go.exe" else "go"
    }

    /**
     * [parent] without its GOROOT unless that names one of [selectedRoots]; with no known root any inherited one is
     * dropped. A GOROOT in [explicit] (the run configuration) replaces the inherited one anyway, so it is kept as is.
     */
    fun parentEnvironment(
        parent: Map<String, String>,
        explicit: Map<String, String>,
        selectedRoots: List<String>,
        isWindows: Boolean,
    ): Map<String, String> {
        if (keyOf(explicit, isWindows) != null) return parent
        val key = keyOf(parent, isWindows) ?: return parent
        val inherited = parent.getValue(key)
        if (inherited.isNotBlank() && selectedRoots.any { ToolLookup.sameDirectory(it, inherited, isWindows) }) return parent
        return parent - key
    }

    // Windows variable names are case-insensitive, so `GoRoot` is the same variable.
    private fun keyOf(env: Map<String, String>, isWindows: Boolean): String? =
        if (GOROOT in env) GOROOT else if (isWindows) env.keys.firstOrNull { it.equals(GOROOT, ignoreCase = true) } else null
}
