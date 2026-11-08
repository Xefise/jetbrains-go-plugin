package io.github.xefise.golsp.toolchain

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import io.github.xefise.golsp.toolchain.model.GoBinaryInfo
import io.github.xefise.golsp.toolchain.model.GoBinaryInfoParser
import io.github.xefise.golsp.toolchain.model.GoVersion
import io.github.xefise.golsp.toolchain.model.ToolLookup
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Runs a prepared command line; tests replace it to avoid real `go`. */
fun interface GoProcessRunner {
    /** Null [indicator]: no cancellation. Throws [ExecutionException] when the process cannot start. */
    fun run(commandLine: GeneralCommandLine, timeoutMs: Int, indicator: ProgressIndicator?): ProcessOutput

    companion object {
        val DEFAULT = GoProcessRunner { commandLine, timeoutMs, indicator ->
            val handler = CapturingProcessHandler(commandLine)
            if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMs) else handler.runProcess(timeoutMs, true)
        }
    }
}

/** `go` invocations that inspect binaries and the local toolchain; background threads only, results cached by file stamp. */
object GoToolProcesses {
    @Volatile
    var runner: GoProcessRunner = GoProcessRunner.DEFAULT

    private data class Stamp(val modified: Long, val size: Long)
    private class Cached<T>(val stamp: Stamp, val value: T)

    private val binaryInfos = ConcurrentHashMap<String, Cached<GoBinaryInfo?>>()
    private val localVersions = ConcurrentHashMap<String, Cached<GoVersion>>()

    /** Build info of [binary] read by [go]; null when it is not a Go binary or `go version -m` fails. */
    fun binaryInfo(go: String, binary: String): GoBinaryInfo? =
        cached(binaryInfos, binary, go, GoBinaryInfoParser.arguments(binary), null) {
            if (it.exitCode == 0) GoBinaryInfoParser.parse(it.stdout) else null
        }

    /** The version [go] itself is, without toolchain switching: the Go that would run `go install`. */
    fun localGoVersion(go: String): GoVersion =
        cached(localVersions, go, go, listOf("env", "GOVERSION"), GoVersion.Unknown) {
            if (it.exitCode == 0) GoVersion.parse(it.stdout) else GoVersion.Unknown
        }

    /**
     * A command line on the login-shell environment with [removed] variables dropped, [extra] set and the
     * directory of [go] first in PATH.
     */
    fun commandLine(
        go: String,
        arguments: List<String>,
        extra: Map<String, String>,
        removed: Set<String>,
        workingDirectory: String,
    ): GeneralCommandLine {
        val env = GoToolLocator.loadShellEnvironment().filterKeys { it !in removed }.toMutableMap()
        val lookup = GoToolLocator.lookupEnvironment(env)
        File(go).parent?.let { ToolLookup.prependToPath(env["PATH"], it, lookup.pathSeparator) }?.let { env["PATH"] = it }
        env.putAll(extra)
        return GeneralCommandLine(listOf(go) + arguments)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            .withEnvironment(env)
            .withWorkDirectory(workingDirectory)
            .withCharset(StandardCharsets.UTF_8)
    }

    fun clearCaches() {
        binaryInfos.clear()
        localVersions.clear()
    }

    private fun runQuietly(go: String, arguments: List<String>): ProcessOutput? {
        // GOTOOLCHAIN=local: inspecting a file must never download a toolchain.
        val commandLine = commandLine(go, arguments, mapOf("GOTOOLCHAIN" to "local"), setOf("GOFLAGS"), System.getProperty("java.io.tmpdir"))
        return try {
            runner.run(commandLine, INSPECT_TIMEOUT_MS, null).takeUnless { it.isTimeout }
        } catch (e: ExecutionException) {
            logger<GoToolProcesses>().info("Cannot run '$go ${arguments.joinToString(" ")}'", e)
            null
        }
    }

    // A process that did not start, timed out or failed is not cached: the next refresh tries again.
    private fun <T> cached(
        cache: ConcurrentHashMap<String, Cached<T>>,
        path: String,
        go: String,
        arguments: List<String>,
        fallback: T,
        parse: (ProcessOutput) -> T,
    ): T {
        val file = File(path)
        val stamp = Stamp(file.lastModified(), file.length())
        cache[path]?.takeIf { it.stamp == stamp }?.let { return it.value }
        val output = runQuietly(go, arguments) ?: return fallback
        return parse(output).also { if (output.exitCode == 0) cache[path] = Cached(stamp, it) }
    }

    private const val INSPECT_TIMEOUT_MS = 10_000
}
