package io.github.xefise.golsp.test.model

import io.github.xefise.golsp.execution.model.DelveOutput

/**
 * The output of a `dlv dap` process debugging one package's tests (Debug, outputMode local): dlv's own lines up to its
 * ready line go to [dlvOutput]; the test binary's `-test.v=test2json` output after it becomes `go test -json` lines for
 * [events] through [GoTest2Json]. [importPath] is asked once, when the binary's first line arrives.
 *
 * Chunks may split lines anywhere; stdout and stderr are assembled separately. Not thread-safe.
 */
class GoDelveTestStream(
    private val importPath: () -> String,
    private val events: (String) -> Unit,
    private val dlvOutput: (text: String, error: Boolean) -> Unit,
) {
    private class Lines {
        val partial = StringBuilder()
        var midLine = false
    }

    private val stdout = Lines()
    private val stderr = Lines()
    private var ready = false
    private var binary: GoTest2Json? = null
    private var crashed = false

    fun write(text: String, error: Boolean) {
        val lines = if (error) stderr else stdout
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            lines.partial.append(text, start, end)
            start = end
            if (newline >= 0 || lines.partial.length >= MAX_LINE) emit(lines, error)
        }
    }

    /** The dlv process exited; its exit code is not the binary's and is not asked for. */
    fun finish() {
        if (stdout.partial.isNotEmpty()) emit(stdout, error = false)
        if (stderr.partial.isNotEmpty()) emit(stderr, error = true)
        val converter = binary ?: return
        // Without a final framed PASS/FAIL or a crash trace the binary was stopped: no summary, so open nodes stay
        // open and the runner marks them terminated, as after Stop in Run.
        (converter.reportedExitCode() ?: CRASH_EXIT_CODE.takeIf { crashed })?.let(converter::exited)
        converter.close()
    }

    private fun emit(lines: Lines, error: Boolean) {
        val text = lines.partial.toString()
        val lineStart = !lines.midLine
        lines.midLine = !text.endsWith('\n')
        lines.partial.setLength(0)
        line(text, error, lineStart)
    }

    private fun line(text: String, error: Boolean, lineStart: Boolean) {
        var converter = binary
        if (converter == null) {
            // dlv cannot launch the binary before it listens; a framed line means the binary runs all the same.
            val framed = lineStart && text.startsWith(FRAMING_MARK)
            if (!ready && !framed) {
                dlvOutput(text, error)
                if (lineStart && !error && text.startsWith(DelveOutput.READY_PREFIX)) ready = true
                return
            }
            converter = GoTest2Json(importPath(), sink = events)
            binary = converter
        }
        // Go writes an unrecovered panic (timeouts included) and a fatal runtime error to the binary's stderr.
        if (error && lineStart && CRASH_PREFIXES.any { text.startsWith(it) }) crashed = true
        converter.write(text)
    }

    private companion object {
        const val FRAMING_MARK = '\u0016'
        const val MAX_LINE = 4096
        val CRASH_PREFIXES = listOf("panic: ", "fatal error: ")
        const val CRASH_EXIT_CODE = 2
    }
}
