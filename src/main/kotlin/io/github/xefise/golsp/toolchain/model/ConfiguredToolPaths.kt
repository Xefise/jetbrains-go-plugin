package io.github.xefise.golsp.toolchain.model

/** Paths from settings; null means "not configured, look the tool up". */
data class ConfiguredToolPaths(val goBinary: String?, val goplsPath: String?, val dlvPath: String?) {
    fun pathFor(tool: GoTool): String? = when (tool) {
        GoTool.GO -> goBinary
        GoTool.GOPLS -> goplsPath
        GoTool.DLV -> dlvPath
    }

    /**
     * A configured go is authoritative: the detected one, else the configured file itself, never a lookup fallback
     * that would silently swap the toolchain. A configured gopls/dlv that is not executable falls back to lookup.
     */
    fun resolve(tool: GoTool, detectedGo: String?, isExecutable: (String) -> Boolean, lookup: () -> String?): String? {
        if (tool == GoTool.GO) {
            detectedGo?.let { return it }
            goBinary?.let { return it.takeIf(isExecutable) }
            return lookup()
        }
        return pathFor(tool)?.takeIf(isExecutable) ?: lookup()
    }

    companion object {
        val NONE = ConfiguredToolPaths(null, null, null)

        /** The project's go override wins over the application one; blank values mean "not configured". */
        fun of(appGoBinary: String?, projectGoBinary: String?, goplsPath: String?, dlvPath: String?) = ConfiguredToolPaths(
            goBinary = normalize(projectGoBinary) ?: normalize(appGoBinary),
            goplsPath = normalize(goplsPath),
            dlvPath = normalize(dlvPath),
        )

        fun normalize(path: String?): String? = path?.trim()?.takeIf { it.isNotEmpty() }
    }
}
