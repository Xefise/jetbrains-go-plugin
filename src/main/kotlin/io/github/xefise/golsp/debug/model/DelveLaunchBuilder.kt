package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.execution.model.DelveOutput
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoTestSpec
import io.github.xefise.golsp.execution.model.ProcessSpec

enum class DelveOutputMode(val dapValue: String?) {
    /** Program output goes to dlv's own stdout/stderr, i.e. straight into the process console. */
    LOCAL(null),

    /** Program output arrives as DAP `output` events. */
    REMOTE("remote"),
}

object DelveLaunchBuilder {
    // Local keeps raw program output: LSP4IJ 0.21.0 drops blank DAP output events and forces a newline per event.
    val DEFAULT_OUTPUT_MODE: DelveOutputMode = DelveOutputMode.LOCAL

    const val READY_PATTERN: String = "${DelveOutput.READY_PREFIX}\${address}:\${port}"

    fun launchParameters(
        request: GoExecutionRequest,
        outputMode: DelveOutputMode = DEFAULT_OUTPUT_MODE,
    ): Map<String, Any> {
        val test = request.test
        val flags = if (test == null) DelveTestFlags.Split(request.buildFlags, emptyList()) else DelveTestFlags.split(request.buildFlags)
        val params = linkedMapOf<String, Any>(
            "type" to "go",
            "request" to "launch",
            "mode" to if (test == null) "debug" else "test",
            "program" to if (test == null) request.target.path else request.target.directory,
            "args" to if (test == null) request.programArguments else flags.binaryArguments + testBinaryArguments(test) + request.programArguments.filterNot(::isVerboseFlag),
            "cwd" to request.workingDirectory,
            "env" to request.environment,
        )
        // The array form is passed to `go build` verbatim, without dlv re-splitting quoted flags.
        if (flags.buildFlags.isNotEmpty()) params["buildFlags"] = flags.buildFlags
        outputMode.dapValue?.let { params["outputMode"] = it }
        return params
    }

    // The user's test flags come first, so the IDE selection wins, as in Run. Several packages are rejected earlier.
    private fun testBinaryArguments(test: GoTestSpec): List<String> {
        check(!test.recursive && test.packages.isEmpty()) { "dlv debugs one package; several packages cannot be debugged" }
        val arguments = mutableListOf<String>()
        test.runPattern?.takeIf { it.isNotEmpty() }?.let { arguments += listOf("-test.run", it) }
        test.benchPattern?.takeIf { it.isNotEmpty() }?.let { arguments += listOf("-test.bench", it) }
        // The test tree parses this framing; a user -test.v among the test arguments would switch it off.
        arguments += TEST2JSON_VERBOSE
        return arguments
    }

    private fun isVerboseFlag(argument: String): Boolean = VERBOSE_FLAG.matches(argument)

    const val TEST2JSON_VERBOSE: String = "-test.v=test2json"
    private val VERBOSE_FLAG = Regex("""--?test\.v(=.*)?""")

    // dlv builds the target itself: it runs in the target directory with `go` first on PATH and with the
    // user's env from the start, the same environment as Run's `go build`.
    fun serverCommand(dlvExecutable: String, request: GoExecutionRequest, path: String?): ProcessSpec =
        ProcessSpec(
            executable = dlvExecutable,
            arguments = listOf("dap", "--listen=127.0.0.1:0"),
            workingDirectory = request.target.directory,
            environment = GoEnvironmentLayers.withPath(request.environment, path),
        )
}
