package io.github.xefise.golsp.toolchain.model

/** A Go module version: `v`-prefixed semver; pseudo-versions are ordinary semver prereleases. */
data class ModuleVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String>,
    val text: String,
) : Comparable<ModuleVersion> {
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    /** Semver precedence; build metadata such as `+incompatible` is ignored. */
    override fun compareTo(other: ModuleVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        when {
            preRelease.isEmpty() && other.preRelease.isEmpty() -> return 0
            preRelease.isEmpty() -> return 1
            other.preRelease.isEmpty() -> return -1
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifiers(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = text

    companion object {
        private const val NUMBER = """0|[1-9]\d*"""
        private const val IDENTIFIER = """[0-9A-Za-z-]+"""
        private val SEMVER = Regex(
            """^v($NUMBER)\.($NUMBER)\.($NUMBER)(?:-($IDENTIFIER(?:\.$IDENTIFIER)*))?(?:\+$IDENTIFIER(?:\.$IDENTIFIER)*)?$""",
        )
        private val DIGITS = Regex("""\d+""")

        /** Null for anything that is not a full semver: `(devel)`, `v1.2`, `latest`, a branch name. */
        fun parse(text: String?): ModuleVersion? {
            val match = text?.trim()?.let { SEMVER.matchEntire(it) } ?: return null
            val (major, minor, patch, pre) = match.destructured
            val preRelease = if (pre.isEmpty()) emptyList() else pre.split('.')
            if (preRelease.any { DIGITS.matches(it) && it.length > 1 && it.startsWith('0') }) return null
            val numbers = listOf(major, minor, patch).map { it.toLongOrNull() ?: return null }
            return ModuleVersion(numbers[0], numbers[1], numbers[2], preRelease, text.trim())
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val aNumeric = DIGITS.matches(a)
            val bNumeric = DIGITS.matches(b)
            return when {
                aNumeric && bNumeric -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
