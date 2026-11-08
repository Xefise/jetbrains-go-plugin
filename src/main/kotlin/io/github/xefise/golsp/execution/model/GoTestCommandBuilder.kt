package io.github.xefise.golsp.execution.model

/** Which tests of the target package (or every package under it when [recursive]) run. */
data class GoTestSpec(
    val recursive: Boolean = false,
    /** `-run`; null runs every test. Never empty: an empty pattern also selects everything. */
    val runPattern: String? = null,
    /** `-bench`; null runs no benchmarks. */
    val benchPattern: String? = null,
    /** Import paths to test instead of the target (`.` or `./...`), resolved from the target directory. */
    val packages: List<String> = emptyList(),
    /** `-coverprofile` written by the IDE; replaces one from the user's flags. Plain runs only, Delve ignores it. */
    val coverProfile: String? = null,
)

object GoTestCommandBuilder {
    const val RECURSIVE_PATTERN: String = "./..."

    // -o and -c redirect or skip the run, -C moves go away from our package dir, -json is ours (=false would starve the tree),
    // -args would swallow the package. Case-sensitive like go's flags.
    private val RESERVED_FLAGS = listOf("o", "c", "C", "json", "args")
    private const val COVER_PROFILE = "coverprofile"

    fun testFlagsError(flags: List<String>): String? {
        val flag = flags.firstOrNull { arg ->
            val name = arg.removePrefix("--").removePrefix("-").substringBefore('=')
            arg.startsWith("-") && name in RESERVED_FLAGS
        } ?: return null
        return "Go test flags must not contain '$flag'; the IDE sets the output, directory, -json and -args itself (use Test arguments for the test binary)"
    }

    /** `go test -json` from the target directory; user flags come first so a selection from the IDE wins. */
    fun testCommand(goExecutable: String, request: GoExecutionRequest): ProcessSpec {
        val test = requireNotNull(request.test) { "not a go test request" }
        val arguments = mutableListOf("test", "-json")
        arguments += if (test.coverProfile == null) request.buildFlags else withoutCoverProfile(request.buildFlags)
        test.coverProfile?.let { arguments += "-$COVER_PROFILE=$it" }
        test.runPattern?.takeIf { it.isNotEmpty() }?.let { arguments += listOf("-run", it) }
        test.benchPattern?.takeIf { it.isNotEmpty() }?.let { arguments += listOf("-bench", it) }
        arguments += when {
            test.packages.isNotEmpty() -> test.packages
            test.recursive -> listOf(RECURSIVE_PATTERN)
            else -> listOf(".")
        }
        if (request.programArguments.isNotEmpty()) arguments += listOf("-args") + request.programArguments
        return ProcessSpec(
            executable = goExecutable,
            arguments = arguments,
            workingDirectory = request.target.directory,
            environment = request.environment,
        )
    }

    // go accepts both `-coverprofile=f` and `-coverprofile f`; a second one would point the profile elsewhere.
    private fun withoutCoverProfile(flags: List<String>): List<String> {
        val result = mutableListOf<String>()
        var skipValue = false
        for (arg in flags) {
            if (skipValue) {
                skipValue = false
                continue
            }
            val name = arg.removePrefix("--").removePrefix("-")
            when {
                !arg.startsWith("-") -> result += arg
                name == COVER_PROFILE -> skipValue = true
                name.startsWith("$COVER_PROFILE=") -> Unit
                else -> result += arg
            }
        }
        return result
    }
}
