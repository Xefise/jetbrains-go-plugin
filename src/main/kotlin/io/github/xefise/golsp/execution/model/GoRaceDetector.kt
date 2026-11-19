package io.github.xefise.golsp.execution.model

object GoRaceDetector {
    const val FLAG: String = "-race"
    private const val REPORT_HEADER = "WARNING: DATA RACE"

    /** Appends `-race` unless the user already set it in the flags, with or without a value. */
    fun withRaceFlag(buildFlags: List<String>, enabled: Boolean): List<String> {
        if (!enabled || buildFlags.any(::isRaceFlag)) return buildFlags
        return buildFlags + FLAG
    }

    private fun isRaceFlag(arg: String): Boolean {
        val name = arg.removePrefix("-").removePrefix("-").substringBefore('=')
        return arg.startsWith("-") && name == "race"
    }

    /** The span of the race report header in one console line, or null. */
    fun reportHeader(line: String): IntRange? {
        val start = line.indexOf(REPORT_HEADER)
        if (start < 0 || line.substring(0, start).isNotBlank()) return null
        return start until start + REPORT_HEADER.length
    }
}
