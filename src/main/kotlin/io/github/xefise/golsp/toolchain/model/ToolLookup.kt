package io.github.xefise.golsp.toolchain.model

enum class GoTool(val baseName: String) {
    GO("go"),
    DLV("dlv"),
    GOPLS("gopls"),
}

data class LookupEnvironment(
    val path: String?,
    val home: String?,
    val goPath: String?,
    val goRoot: String?,
    val isWindows: Boolean,
    // `go install` targets: $GOBIN from the process env first, then GOBIN reported by `go env`.
    val goBinDirs: List<String> = emptyList(),
    // Entry names of ~/sdk (`golang.org/dl/goX.Y.Z download`); lazy, so the directory is listed only when go is not found earlier.
    val sdkDirNames: Lazy<List<String>> = lazyOf(emptyList()),
) {
    val pathSeparator: Char get() = if (isWindows) ';' else ':'
    val fileSeparator: Char get() = if (isWindows) '\\' else '/'

    /** Fills what the process env lacks from `go env`: GOBIN is appended, GOPATH and GOROOT only when unset. */
    fun withGoEnv(goEnv: GoEnv?): LookupEnvironment {
        if (goEnv == null) return this
        return copy(
            goPath = goPath?.takeIf { it.isNotBlank() } ?: goEnv.goPath,
            goRoot = goRoot?.takeIf { it.isNotBlank() } ?: goEnv.goRoot,
            goBinDirs = (goBinDirs + listOfNotNull(goEnv.goBin)).distinct(),
        )
    }
}

object ToolLookup {
    const val SDK_DIR = "sdk"
    private val UNIX_FALLBACK_DIRS = listOf("/usr/local/go/bin", "/opt/homebrew/bin", "/usr/local/bin")

    fun executableName(tool: GoTool, env: LookupEnvironment): String =
        if (env.isWindows) "${tool.baseName}.exe" else tool.baseName

    // Order: GOBIN for installed tools, then PATH (what the user's shell resolves), then Go-specific install locations.
    fun candidateDirectories(tool: GoTool, env: LookupEnvironment): List<String> =
        (primaryDirectories(tool, env) + sdkDirectories(tool, env)).distinct()

    private fun primaryDirectories(tool: GoTool, env: LookupEnvironment): List<String> {
        val dirs = mutableListOf<String>()
        if (tool != GoTool.GO) dirs += env.goBinDirs.map { it.trim() }.filter { it.isNotEmpty() }
        dirs += splitPath(env.path, env.pathSeparator)
        firstGoPathEntry(env)?.let { dirs += join(it, "bin", env) }
        env.home?.takeIf { it.isNotBlank() }?.let { dirs += join(join(it, "go", env), "bin", env) }
        if (tool == GoTool.GO) {
            env.goRoot?.takeIf { it.isNotBlank() }?.let { dirs += join(it, "bin", env) }
        }
        if (!env.isWindows) dirs += UNIX_FALLBACK_DIRS
        return normalize(dirs, env)
    }

    private fun sdkDirectories(tool: GoTool, env: LookupEnvironment): List<String> {
        if (tool != GoTool.GO) return emptyList()
        val home = env.home?.takeIf { it.isNotBlank() } ?: return emptyList()
        val sdk = join(home, SDK_DIR, env)
        return normalize(newestFirstSdkDirs(env.sdkDirNames.value).map { join(join(sdk, it, env), "bin", env) }, env)
    }

    private fun normalize(dirs: List<String>, env: LookupEnvironment): List<String> =
        dirs.map { it.trimEnd(env.fileSeparator).ifEmpty { it } }.distinct()

    /** `go1.22.3`-style names newest first; release > rc > beta of the same version; anything else (gotip) is dropped. */
    fun newestFirstSdkDirs(names: List<String>): List<String> =
        names.mapNotNull { name -> (GoVersion.parse(name) as? GoVersion.Release)?.takeIf { it.text == name }?.let { name to it } }
            .sortedWith { a, b -> b.second.compareTo(a.second).takeIf { it != 0 } ?: a.first.compareTo(b.first) }
            .map { it.first }

    fun candidates(tool: GoTool, env: LookupEnvironment): List<String> {
        val name = executableName(tool, env)
        return candidateDirectories(tool, env).map { join(it, name, env) }
    }

    fun find(tool: GoTool, env: LookupEnvironment, isExecutable: (String) -> Boolean): String? {
        val name = executableName(tool, env)
        return primaryDirectories(tool, env).map { join(it, name, env) }.firstOrNull(isExecutable)
            ?: sdkDirectories(tool, env).map { join(it, name, env) }.firstOrNull(isExecutable)
    }

    /**
     * [directory] first, later copies of it removed: an entry further down would still lose to an earlier `go`.
     * Null when [path] has no entries: replacing an unknown PATH with just the go directory would hide everything else.
     */
    fun prependToPath(path: String?, directory: String, separator: Char): String? {
        val entries = splitPath(path, separator)
        if (entries.isEmpty()) return null
        val isWindows = separator == ';'
        return (listOf(directory) + entries.filterNot { sameDirectory(it, directory, isWindows) }).joinToString(separator.toString())
    }

    /** Ignores trailing separators; case-insensitive on Windows, where both slashes separate. */
    fun sameDirectory(a: String, b: String, isWindows: Boolean): Boolean {
        val trim = if (isWindows) charArrayOf('/', '\\') else charArrayOf('/')
        fun canonical(dir: String) = dir.trim().let { d -> d.trimEnd(*trim).ifEmpty { d.take(1) } }
            .let { if (isWindows) it.replace('/', '\\') else it }
        return canonical(a).equals(canonical(b), ignoreCase = isWindows)
    }

    private fun splitPath(path: String?, separator: Char): List<String> =
        path.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }

    private fun firstGoPathEntry(env: LookupEnvironment): String? =
        splitPath(env.goPath, env.pathSeparator).firstOrNull()

    private fun join(dir: String, child: String, env: LookupEnvironment): String =
        if (dir.endsWith(env.fileSeparator)) dir + child else "$dir${env.fileSeparator}$child"
}
