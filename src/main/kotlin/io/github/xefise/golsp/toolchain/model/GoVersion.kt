// Modified from vscode-go (golang/vscode-go), commit 1608cb5, extension/src/util.ts (GoVersion), MIT
package io.github.xefise.golsp.toolchain.model

/** A Go toolchain version as `go env GOVERSION` reports it (`go1.27.1`, `go1.21rc2`, `devel go1.28-abc...`). */
sealed interface GoVersion {
    /** [patch] is null when absent: `go1.20` (a pre-1.21 `.0` release) or a prerelease like `go1.21rc2`. */
    data class Release(
        val major: Int,
        val minor: Int,
        val patch: Int?,
        val preRelease: String?,
        val text: String,
    ) : GoVersion, Comparable<Release> {
        fun isBefore(major: Int, minor: Int): Boolean =
            this.major < major || (this.major == major && this.minor < minor)

        override fun compareTo(other: Release): Int = COMPARATOR.compare(this, other)

        // Consistent with compareTo: `go1.20` and `go1.20.0` are the same release spelled differently.
        override fun equals(other: Any?): Boolean = other is Release && compareTo(other) == 0

        override fun hashCode(): Int = listOf(major, minor, patch ?: 0, preRelease).hashCode()

        private companion object {
            val PRE_RELEASE_KINDS = listOf("alpha", "beta", "rc")

            // A prerelease sorts before the release it precedes: go1.21rc2 < go1.21.0.
            fun preReleaseRank(preRelease: String?): Pair<Int, Int> {
                if (preRelease == null) return PRE_RELEASE_KINDS.size to 0
                val kind = PRE_RELEASE_KINDS.indexOfFirst { preRelease.startsWith(it) }
                return kind to preRelease.substring(PRE_RELEASE_KINDS[kind].length).toInt()
            }

            val COMPARATOR: Comparator<Release> = compareBy<Release>({ it.major }, { it.minor }, { it.patch ?: 0 })
                .thenBy { preReleaseRank(it.preRelease).first }
                .thenBy { preReleaseRank(it.preRelease).second }
        }
    }

    /** Built from source or a vendor fork; like vscode-go, assumed newer than any release. */
    data class Devel(val text: String) : GoVersion

    data object Unknown : GoVersion

    companion object {
        private val RELEASE = Regex("""^go(\d{1,4})\.(\d{1,4})(?:\.(\d{1,4}))?((?:alpha|beta|rc)\d{1,4})?$""")
        private val GO_LIKE = Regex("""^go\d+\.\d+\S*$""")

        /** Accepts GOVERSION or a whole `go version` line; trailing experiment / platform words are ignored. */
        fun parse(text: String?): GoVersion {
            val trimmed = text?.trim()?.removePrefix("go version ")?.trim().orEmpty()
            val token = trimmed.substringBefore(' ')
            if (token == "devel") return Devel(trimmed)
            val match = RELEASE.matchEntire(token)
            if (match != null) {
                val (major, minor, patch, pre) = match.destructured
                return Release(major.toInt(), minor.toInt(), patch.toIntOrNull(), pre.ifEmpty { null }, token)
            }
            // e.g. Google-internal `go1.23-20240317-RC00`: vscode-go also treats these as devel builds.
            return if (GO_LIKE.matches(token)) Devel(trimmed) else Unknown
        }
    }
}
