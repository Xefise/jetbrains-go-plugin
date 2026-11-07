// Modified from the Go project (src/cmd/internal/test2json/test2json.go: Converter; src/cmd/go/internal/test/test.go:
// summary line of runTestActor), go1.27.3, BSD-3-Clause, Copyright 2017 The Go Authors
package io.github.xefise.golsp.test.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Converts the output of one package's test binary run with `-test.v=test2json` into the lines `go test -json`
 * prints for it, so they can go through [GoTestEvent.parse] / [GoTestTreeReducer.accept] unchanged.
 *
 * Feed stdout and stderr in arrival order with [write] (any chunking), report the exit with [exited], then [close].
 * Each event reaches [sink] as one JSON line without the trailing newline; the `start` event is sent right away.
 * [timestamps] adds `Time` and `Elapsed`, as `go test -json` does. Calls are synchronized: stdout and stderr may be
 * fed from different threads.
 */
class GoTest2Json internal constructor(
    private val pkg: String,
    private val timestamps: Boolean,
    inBufferSize: Int,
    outBufferSize: Int,
    private val sink: (String) -> Unit,
) {
    constructor(pkg: String, timestamps: Boolean = true, sink: (String) -> Unit) :
        this(pkg, timestamps, IN_BUFFER, OUT_BUFFER, sink)

    private class Event(
        val action: String,
        var test: String = "",
        val elapsed: Double? = null,
        val output: ByteArray? = null,
        val outputType: String = "",
        val key: String = "",
        val value: String = "",
        val path: String = "",
    )

    private val startNanos = System.nanoTime()
    private var testName = ""
    private val report = ArrayList<Event>()
    private var result = ""
    private var framedResult = ""
    private var markFraming = false
    private var markErrEnd = false
    private var markEscape = false
    private var isFraming = false
    private var pendingHighSurrogate: Char? = null
    private var lastInputByte: Byte? = null
    private var closed = false

    private val output = GoTest2JsonLineBuffer(outBufferSize, ::writeOutputEvent, ::writeOutputEvent)
    private val input = GoTest2JsonLineBuffer(inBufferSize, ::handleInputLine) { output.write(it) }

    init {
        writeEvent(Event("start"))
    }

    @Synchronized
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length > 0) lastInputByte = bytes[offset + length - 1]
        input.write(bytes, offset, length)
    }

    /** Decoded text; a surrogate pair split between chunks is joined before encoding. */
    @Synchronized
    fun write(text: String) {
        var chunk = pendingHighSurrogate?.let { it + text } ?: text
        pendingHighSurrogate = null
        if (chunk.isNotEmpty() && chunk.last().isHighSurrogate()) {
            pendingHighSurrogate = chunk.last()
            chunk = chunk.dropLast(1)
        }
        write(chunk.toByteArray(Charsets.UTF_8))
    }

    /**
     * The test process exited: writes the summary line `go test` adds after the binary (`ok` or `FAIL`, which also
     * closes tests still waiting for their report) and fails the package on a non-zero [exitCode].
     */
    @Synchronized
    fun exited(exitCode: Int) {
        flushPendingSurrogate()
        val seconds = String.format(Locale.ROOT, "%.3fs", (System.nanoTime() - startNanos) / 1e9)
        val tail = StringBuilder()
        if (lastInputByte != null && lastInputByte != NEWLINE) tail.append('\n')
        if (exitCode == 0) {
            tail.append("ok  \t").append(pkg).append('\t').append(seconds).append('\n')
        } else {
            if (lastInputByte == null) tail.append("exit status ").append(exitCode).append('\n')
            tail.append(MARK_FRAMING.toInt().toChar()).append("FAIL\t").append(pkg).append('\t').append(seconds).append('\n')
        }
        write(tail.toString().toByteArray(Charsets.UTF_8))
        markExited(exitCode == 0)
    }

    /**
     * The exit code the binary's final framed `PASS`/`FAIL` line implies (0 or 1), or null before it; ask before
     * [exited]. An unframed `PASS` (printed by `TestMain` or `init`) does not count.
     */
    @Synchronized
    fun reportedExitCode(): Int? = when (framedResult) {
        "pass" -> 0
        "fail" -> 1
        else -> null
    }

    private fun flushPendingSurrogate() {
        val pending = pendingHighSurrogate ?: return
        pendingHighSurrogate = null
        write(pending.toString().toByteArray(Charsets.UTF_8))
    }

    private fun markExited(success: Boolean) {
        if (success) {
            if (result != "skip") result = "pass"
        } else {
            result = "fail"
        }
    }

    /** Flushes partial lines and sends the package result, if the output or [exited] gave one. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        flushPendingSurrogate()
        input.flush()
        output.flush()
        if (result.isEmpty()) return
        val elapsed = if (timestamps) Math.round((System.nanoTime() - startNanos) / 1e6) / 1e3 else null
        writeEvent(Event(result, elapsed = elapsed))
    }

    private fun handleInputLine(whole: ByteArray) {
        if (whole.isEmpty()) return
        var line = whole
        var sawMarker = false
        if (markFraming && line[0] != MARK_FRAMING) {
            output.write(line)
            return
        }
        if (line[0] == MARK_FRAMING) {
            output.flush()
            sawMarker = true
            line = line.copyOfRange(1, line.size)
        }

        var trimEnd = line.size
        if (trimEnd > 0 && line[trimEnd - 1] == NEWLINE) {
            trimEnd--
            if (trimEnd > 0 && line[trimEnd - 1] == CR) trimEnd--
        }
        var trim = line.copyOfRange(0, trimEnd)

        // === CONT followed by an empty test name can lose its trailing spaces.
        if (trim.contentEquals(EMPTY_NAME)) {
            line = EMPTY_NAME_LINE
            trim = line.copyOfRange(0, line.size - 1)
        }

        if (trim.contentEquals(BIG_PASS) || trim.contentEquals(BIG_FAIL) || trim.startsWith(BIG_FAIL_ERROR_PREFIX)) {
            flushReport(0)
            testName = ""
            markFraming = sawMarker
            writeFraming(line)
            result = if (trim.contentEquals(BIG_PASS)) "pass" else "fail"
            if (sawMarker) framedResult = result
            return
        }

        // A binary without tests prints only "?   \tpkg\t[no test files]": plain output, but the summary says skip.
        if (line.startsWith(SKIP_LINE_PREFIX) && trim.endsWith(SKIP_LINE_SUFFIX) && report.isEmpty()) result = "skip"

        val origLine = line
        var indent = 0
        var ok = UPDATES.any { line.startsWith(it) }
        if (!ok) {
            while (line.startsWith(FOUR_SPACE)) {
                line = line.copyOfRange(4, line.size)
                indent++
            }
            ok = REPORTS.any { line.startsWith(it) }
        }

        if (!ok) {
            // Indentation indexes the stack of running subtests; deeper than the stack means "the latest one".
            if (indent > 0 && indent <= report.size) testName = report[indent - 1].test
            output.write(origLine)
            return
        }

        val rest = String(line, 4, line.size - 4, Charsets.UTF_8)
        val action = rest.substringBefore(' ').removeSuffix(":").lowercase()
        var name = if (' ' in rest) rest.substringAfter(' ').trim() else ""

        if (line[0] == DASH) {
            var elapsed: Double? = null
            val i = name.indexOf(" (")
            if (i >= 0) {
                if (name.endsWith("s)")) {
                    val t = name.substring(i + 2, name.length - 2).toGoFloat()
                    if (t != null && timestamps) elapsed = t
                }
                name = name.substring(0, i)
            }
            if (report.size < indent) {
                output.write(origLine)
                return
            }
            markFraming = sawMarker
            flushReport(indent)
            testName = name
            report += Event(action, test = name, elapsed = elapsed)
            writeFraming(origLine)
            return
        }

        val e = when (action) {
            "artifacts" -> {
                val path = name.substringAfter(' ', "")
                name = name.substringBefore(' ')
                Event(action, path = path)
            }
            "attr" -> {
                val keyValue = name.substringAfter(' ', "")
                name = name.substringBefore(' ')
                Event(action, key = keyValue.substringBefore(' '), value = keyValue.substringAfter(' ', ""))
            }
            else -> Event(action)
        }
        markFraming = sawMarker
        flushReport(0)
        testName = name

        // Only generated to get testName right.
        if (action == "name") return

        // The pause notification goes before the event, so the test doesn't seem to print after being paused.
        if (action == "pause") writeFraming(origLine)
        writeEvent(e)
        if (action != "pause") writeFraming(origLine)
    }

    private fun writeFraming(line: ByteArray) {
        isFraming = true
        try {
            output.write(line)
        } finally {
            isFraming = false
        }
    }

    private fun flushReport(depth: Int) {
        testName = ""
        while (report.size > depth) writeEvent(report.removeAt(report.size - 1))
    }

    private fun writeOutputEvent(chunk: ByteArray) {
        var typ = when {
            isFraming -> "frame"
            markErrEnd -> "error-continue"
            else -> ""
        }
        // Markers can sit anywhere in a line (t.Error after an unterminated fmt.Print), and an escape and the byte it
        // escapes can arrive in separate chunks: the error and escape state lives across calls.
        var out = chunk
        var i = 0
        while (i < out.size) {
            if (markEscape) {
                markEscape = false
                i++
                continue
            }
            when (out[i]) {
                MARK_ESCAPE -> {
                    out = out.removeAt(i)
                    i--
                    markEscape = true
                }
                MARK_ERR_BEGIN -> {
                    if (i > 0) writeEvent(Event("output", output = out.copyOfRange(0, i), outputType = typ))
                    markErrEnd = true
                    typ = "error"
                    out = out.copyOfRange(i + 1, out.size)
                    i = 0
                }
                MARK_ERR_END -> {
                    out = out.removeAt(i)
                    if (i < out.size && out[i] == NEWLINE) i++
                    writeEvent(Event("output", output = out.copyOfRange(0, i), outputType = typ))
                    markErrEnd = false
                    typ = ""
                    out = out.copyOfRange(i, out.size)
                    i = 0
                }
            }
            i++
        }
        if (out.isNotEmpty()) writeEvent(Event("output", output = out, outputType = typ))
    }

    private fun writeEvent(e: Event) {
        if (e.test.isEmpty()) e.test = testName
        val json = JsonObject()
        if (timestamps) json.addProperty("Time", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now()))
        json.addProperty("Action", e.action)
        json.addNonEmpty("Package", pkg)
        json.addNonEmpty("Test", e.test)
        e.elapsed?.let { json.addProperty("Elapsed", it) }
        // Invalid UTF-8 becomes U+FFFD, as encoding/json does.
        e.output?.let { json.addProperty("Output", String(it, Charsets.UTF_8)) }
        json.addNonEmpty("OutputType", e.outputType)
        json.addNonEmpty("Key", e.key)
        json.addNonEmpty("Value", e.value)
        json.addNonEmpty("Path", e.path)
        sink(GSON.toJson(json))
    }

    internal companion object {
        const val MARK_FRAMING: Byte = 0x16 // ^V
        const val MARK_ERR_BEGIN: Byte = 0x0f // ^O
        const val MARK_ERR_END: Byte = 0x0e // ^N
        const val MARK_ESCAPE: Byte = 0x1b // ^[

        // A directive line must fit the input buffer; a single output event is at most the output buffer.
        const val IN_BUFFER = 4096
        const val OUT_BUFFER = 1024

        private const val NEWLINE = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val DASH = '-'.code.toByte()
        private val GSON = GsonBuilder().disableHtmlEscaping().create()

        private val BIG_PASS = bytes("PASS")
        private val BIG_FAIL = bytes("FAIL")
        private val BIG_FAIL_ERROR_PREFIX = bytes("FAIL\t")
        private val EMPTY_NAME = bytes("=== NAME")
        private val EMPTY_NAME_LINE = bytes("=== NAME  \n")
        private val UPDATES = listOf(
            "=== RUN   ", "=== PAUSE ", "=== CONT  ", "=== NAME  ", "=== PASS  ", "=== FAIL  ", "=== SKIP  ",
            "=== ATTR  ", "=== ARTIFACTS ",
        ).map(::bytes)
        private val REPORTS = listOf("--- PASS: ", "--- FAIL: ", "--- SKIP: ", "--- BENCH: ").map(::bytes)
        private val FOUR_SPACE = bytes("    ")
        private val SKIP_LINE_PREFIX = bytes("?   \t")
        private val SKIP_LINE_SUFFIX = bytes("\t[no test files]")

        private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

        private fun ByteArray.startsWith(prefix: ByteArray) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        private fun ByteArray.endsWith(suffix: ByteArray) =
            size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

        private fun ByteArray.removeAt(i: Int): ByteArray = copyOfRange(0, i) + copyOfRange(i + 1, size)

        private fun JsonObject.addNonEmpty(key: String, value: String) {
            if (value.isNotEmpty()) addProperty(key, value)
        }

        /** strconv.ParseFloat for decimal durations: Kotlin also accepts "1d" and "NaN", and out of range is an error in Go. */
        private fun String.toGoFloat(): Double? =
            if (isNotEmpty() && all { it.isDigit() || it in ".eE+-" }) toDoubleOrNull()?.takeIf { it.isFinite() } else null
    }
}
