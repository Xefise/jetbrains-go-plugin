package io.github.xefise.golsp.debug.model

/**
 * Splits "go test flags" for dlv test mode: dlv builds with `go test -c`, which accepts test-binary flags but never
 * passes them on, so they must reach the binary as `-test.<name>` arguments instead.
 */
object DelveTestFlags {
    data class Split(val buildFlags: List<String>, val binaryArguments: List<String>)

    // Test-binary flags of `go help testflag` (Go 1.27); cover*, -vet and -fuzz shape the build and stay build flags.
    private val BOOLEAN_FLAGS = setOf("artifacts", "benchmem", "failfast", "fullpath", "short", "v")
    private val VALUE_FLAGS = setOf(
        "bench", "benchtime", "count", "cpu", "fuzzminimizetime", "fuzztime", "list", "outputdir", "parallel", "run",
        "shuffle", "skip", "timeout", "blockprofile", "blockprofilerate", "cpuprofile", "memprofile", "memprofilerate",
        "mutexprofile", "mutexprofilefraction", "trace",
    )

    // The debug console sets the verbosity itself (test2json output for the test tree).
    private const val VERBOSE = "v"

    fun split(goTestFlags: List<String>): Split {
        val build = mutableListOf<String>()
        val binary = mutableListOf<String>()
        var i = 0
        while (i < goTestFlags.size) {
            val arg = goTestFlags[i++]
            val flag = parse(arg)
            when {
                flag == null -> build += arg
                flag.name in BOOLEAN_FLAGS -> if (flag.name != VERBOSE) binary += flag.render(flag.value)
                flag.value != null -> binary += flag.render(flag.value)
                i < goTestFlags.size -> binary += flag.render(goTestFlags[i++])
                // A trailing value flag without its value: let `go test -c` report it.
                else -> build += arg
            }
        }
        return Split(build, binary)
    }

    private class Flag(val name: String, val value: String?) {
        fun render(value: String?): String = if (value == null) "-test.$name" else "-test.$name=$value"
    }

    private fun parse(arg: String): Flag? {
        if (!arg.startsWith("-") || arg == "-" || arg == "--") return null
        val body = arg.removePrefix("--").removePrefix("-")
        val name = body.substringBefore('=').removePrefix("test.")
        if (name !in BOOLEAN_FLAGS && name !in VALUE_FLAGS) return null
        return Flag(name, if ('=' in body) body.substringAfter('=') else null)
    }
}
