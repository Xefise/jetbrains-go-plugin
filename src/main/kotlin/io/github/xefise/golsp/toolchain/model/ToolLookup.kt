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
    private val UNIX_FALLBACK_DIRS = listOf("/usr/local/go/bin", "/opt/homebrew/bin", "/usr/local/bin")

    fun executableName(tool: GoTool, env: LookupEnvironment): String =
        if (env.isWindows) "${tool.baseName}.exe" else tool.baseName

    // Order: GOBIN for installed tools, then PATH (what the user's shell resolves), then Go-specific install locations.
    fun candidateDirectories(tool: GoTool, env: LookupEnvironment): List<String> {
        val dirs = mutableListOf<String>()
        if (tool != GoTool.GO) dirs += env.goBinDirs.map { it.trim() }.filter { it.isNotEmpty() }
        dirs += splitPath(env.path, env.pathSeparator)
        firstGoPathEntry(env)?.let { dirs += join(it, "bin", env) }
        env.home?.takeIf { it.isNotBlank() }?.let { dirs += join(join(it, "go", env), "bin", env) }
        if (tool == GoTool.GO) {
            env.goRoot?.takeIf { it.isNotBlank() }?.let { dirs += join(it, "bin", env) }
        }
        if (!env.isWindows) dirs += UNIX_FALLBACK_DIRS
        return dirs.map { it.trimEnd(env.fileSeparator).ifEmpty { it } }.distinct()
    }

    fun candidates(tool: GoTool, env: LookupEnvironment): List<String> {
        val name = executableName(tool, env)
        return candidateDirectories(tool, env).map { join(it, name, env) }
    }

    fun find(tool: GoTool, env: LookupEnvironment, isExecutable: (String) -> Boolean): String? =
        candidates(tool, env).firstOrNull(isExecutable)

    /** Null when [path] has no entries: replacing an unknown PATH with just the go directory would hide everything else. */
    fun prependToPath(path: String?, directory: String, separator: Char): String? {
        val entries = splitPath(path, separator)
        if (entries.isEmpty()) return null
        if (directory in entries) return path
        return (listOf(directory) + entries).joinToString(separator.toString())
    }

    private fun splitPath(path: String?, separator: Char): List<String> =
        path.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }

    private fun firstGoPathEntry(env: LookupEnvironment): String? =
        splitPath(env.goPath, env.pathSeparator).firstOrNull()

    private fun join(dir: String, child: String, env: LookupEnvironment): String =
        if (dir.endsWith(env.fileSeparator)) dir + child else "$dir${env.fileSeparator}$child"
}
