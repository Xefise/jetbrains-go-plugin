package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
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

    const val READY_PATTERN: String = "DAP server listening at: \${address}:\${port}"

    fun launchParameters(
        request: GoExecutionRequest,
        outputMode: DelveOutputMode = DEFAULT_OUTPUT_MODE,
    ): Map<String, Any> {
        val params = linkedMapOf<String, Any>(
            "type" to "go",
            "request" to "launch",
            "mode" to "debug",
            "program" to request.target.path,
            "args" to request.programArguments,
            "cwd" to request.workingDirectory,
            "env" to request.environment,
        )
        // The array form is passed to `go build` verbatim, without dlv re-splitting quoted flags.
        if (request.buildFlags.isNotEmpty()) params["buildFlags"] = request.buildFlags
        outputMode.dapValue?.let { params["outputMode"] = it }
        return params
    }

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
