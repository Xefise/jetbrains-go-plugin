package io.github.xefise.golsp.toolchain.model

/** Build info of a Go binary from `go version -m`; only the main module is kept. */
data class GoBinaryInfo(
    /** The toolchain that built the binary, e.g. `go1.27.3`. */
    val builtWith: GoVersion,
    val packagePath: String?,
    val modulePath: String?,
    /** As printed: `v0.24.0`, a pseudo-version, `(devel)` for a local build. */
    val moduleVersion: String?,
    /** Set when the main module line is followed by `=>`. */
    val replacement: String? = null,
) {
    /** Null for local builds (`(devel)`, VCS-stamped `+dirty`), replaced modules and unparseable versions. */
    val releasedVersion: ModuleVersion?
        get() = if (replacement != null || moduleVersion.orEmpty().endsWith("+dirty")) null else ModuleVersion.parse(moduleVersion)
}

object GoBinaryInfoParser {
    fun arguments(binaryPath: String): List<String> = listOf("version", "-m", binaryPath)

    /** Null when stdout is not `go version -m` output (not a Go binary, empty, garbage). */
    fun parse(stdout: String): GoBinaryInfo? {
        val lines = stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()
        val headerIndex = lines.indexOfFirst { !it.startsWith('\t') }
        if (headerIndex < 0) return null
        // `<file>: go1.27.3`; the file path may itself contain ": ", the version never does.
        val header = lines[headerIndex]
        if (!header.contains(": ")) return null
        val builtWith = GoVersion.parse(header.substringAfterLast(": "))
        if (builtWith !is GoVersion.Release && builtWith !is GoVersion.Devel) return null

        var packagePath: String? = null
        var modulePath: String? = null
        var moduleVersion: String? = null
        var replacement: String? = null
        var previousKey: String? = null
        for (line in lines.drop(headerIndex + 1)) {
            if (!line.startsWith('\t')) break
            val fields = line.substring(1).split('\t')
            val key = fields[0]
            when (key) {
                "path" -> packagePath = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
                "mod" -> {
                    modulePath = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
                    moduleVersion = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
                }
                "=>" -> if (previousKey == "mod") {
                    replacement = fields.drop(1).filter { it.isNotBlank() && !it.startsWith("h1:") }.joinToString(" ")
                }
            }
            previousKey = key
        }
        return GoBinaryInfo(builtWith, packagePath, modulePath, moduleVersion, replacement)
    }
}
