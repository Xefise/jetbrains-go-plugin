package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.execution.model.DelveOutput
import io.github.xefise.golsp.execution.model.ProcessSpec
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The `dlv dap` the plugin starts on loopback to attach to a local process, and how to read what it prints. */
object DelveAttachServer {
    const val READY_TIMEOUT_MS: Long = 30_000L

    // Port 0: dlv binds a free port itself and reports it in the ready line, so there is no port race.
    fun command(dlvExecutable: String, workingDirectory: String, environment: Map<String, String>): ProcessSpec =
        ProcessSpec(
            executable = dlvExecutable,
            arguments = listOf("dap", "--listen=${DelveAttachTarget.LOOPBACK}:0"),
            workingDirectory = workingDirectory,
            environment = environment,
        )

    /** The port from the first complete ready line in [stdout]; a line still being written is ignored. */
    fun readyPort(stdout: CharSequence): Int? {
        val complete = stdout.substring(0, stdout.lastIndexOf('\n') + 1)
        return complete.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith(DelveOutput.READY_PREFIX) }
            .firstNotNullOfOrNull { line -> line.substringAfterLast(':').trim().toIntOrNull()?.takeIf { it in 1..65535 } }
    }

    /** Only absolute paths: on macOS the process list can report a bare or relative executable name. */
    fun isCandidatePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            Path.of(path).isAbsolute
        } catch (_: InvalidPathException) {
            false
        }
    }

    fun sessionName(executableName: String, processId: Int): String = "Attach to $executableName ($processId)"

    fun startFailure(exitCode: Int?, stderr: String): String {
        val details = stderr.trim()
        val exit = exitCode?.let { " (exit code $it)" }.orEmpty()
        return if (details.isEmpty()) "dlv dap exited before it started listening$exit" else "dlv dap exited before it started listening$exit: $details"
    }

    /** A readable message when dlv's stderr reports a failed attach, or null when it reports nothing alarming. */
    fun attachFailure(stderr: String, macOS: Boolean): String? {
        val details = stderr.trim()
        val lower = details.lowercase()
        if (ATTACH_FAILURE_MARKERS.none { it in lower }) return null
        return if (macOS && isMacAuthorizationFailure(lower)) "$details\n$MACOS_HINT" else details
    }

    /**
     * What to tell the user when the plugin's dlv exits, or null for a normal end: dlv we stopped ourselves is never
     * reported, a clean exit after the session started only when stderr shows a failed attach.
     */
    fun exitReport(stderr: String, exitCode: Int?, sessionStarted: Boolean, destroyedByUs: Boolean, macOS: Boolean): String? {
        if (destroyedByUs) return null
        attachFailure(stderr, macOS)?.let { return it }
        val details = stderr.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
        val exit = exitCode?.let { " (exit code $it)" }.orEmpty()
        return when {
            !sessionStarted -> "dlv dap exited before the debug session started$exit$details"
            exitCode != null && exitCode != 0 -> "dlv dap exited$exit$details"
            else -> null
        }
    }

    const val MACOS_HINT: String =
        "macOS refused to let debugserver attach: run `sudo DevToolsSecurity -enable` and allow the developer-tools authorization prompt."

    private val ATTACH_FAILURE_MARKERS =
        listOf("could not attach", "failed to attach", "attach failed", "unable to attach", "operation not permitted", "task_for_pid")

    private val MACOS_REFUSAL_MARKERS = listOf("denied", "not allowed", "authoriz", "permission", "not permitted")

    private fun isMacAuthorizationFailure(lower: String): Boolean =
        "task_for_pid" in lower || ("debugserver" in lower && MACOS_REFUSAL_MARKERS.any { it in lower })
}
