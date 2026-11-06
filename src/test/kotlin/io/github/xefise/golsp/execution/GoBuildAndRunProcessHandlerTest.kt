package io.github.xefise.golsp.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// `sh` stands in for `go build` and the binary: the handler only sequences processes.
class GoBuildAndRunProcessHandlerTest : BasePlatformTestCase() {
    private lateinit var buildDirectory: Path

    override fun setUp() {
        super.setUp()
        buildDirectory = Files.createTempDirectory("go-run-test-")
    }

    override fun tearDown() {
        try {
            buildDirectory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private class Run(val handler: ProcessHandler) {
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val terminations = AtomicInteger()
    }

    private fun sh(script: String) = GeneralCommandLine("/bin/sh", "-c", script)

    private fun start(
        build: GeneralCommandLine,
        program: GeneralCommandLine,
        beforeStart: (ProcessHandler) -> Unit = {},
        onStdout: (ProcessHandler, String) -> Unit = { _, _ -> },
    ): Run {
        val run = Run(GoBuildAndRunProcessHandler(build, program, buildDirectory))
        run.handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when {
                    ProcessOutputType.isStdout(outputType) -> {
                        run.stdout.append(event.text)
                        onStdout(run.handler, run.stdout.toString())
                    }
                    ProcessOutputType.isStderr(outputType) -> run.stderr.append(event.text)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                run.terminations.incrementAndGet()
            }
        })
        beforeStart(run.handler)
        run.handler.startNotify()
        return run
    }

    private fun Run.await(): Run = also { assertTrue("handler did not terminate", handler.waitFor(TIMEOUT_MS)) }

    fun testProgramExitCodeIsTheHandlerExitCode() {
        if (SystemInfo.isWindows) return
        val run = start(sh("exit 0"), sh("echo ran; exit 7")).await()
        assertEquals(7, run.handler.exitCode)
        assertEquals("ran\n", run.stdout.toString())
        assertFalse(Files.exists(buildDirectory))
    }

    fun testFailedBuildEndsWithItsCodeWithoutRunningTheProgram() {
        if (SystemInfo.isWindows) return
        val run = start(sh("echo broken >&2; exit 2"), sh("echo ran")).await()
        assertEquals(2, run.handler.exitCode)
        assertEquals("", run.stdout.toString())
        assertEquals("broken\n", run.stderr.toString())
        assertFalse(Files.exists(buildDirectory))
    }

    fun testProgramThatCannotStartEndsTheRunWithAHint() {
        if (SystemInfo.isWindows) return
        val run = start(sh("exit 0"), GeneralCommandLine(buildDirectory.resolve("missing").toString())).await()
        assertEquals(GoBuildAndRunProcessHandler.LAUNCH_FAILED_EXIT_CODE, run.handler.exitCode)
        assertTrue(run.stderr.toString(), run.stderr.contains("`main` package"))
        assertEquals(1, run.terminations.get())
        assertFalse(Files.exists(buildDirectory))
    }

    fun testStopDuringABuildThatExitsCleanlyReportsStoppedAndSkipsTheProgram() {
        if (SystemInfo.isWindows) return
        // The build swallows SIGINT and exits 0, as a build racing with Stop can.
        val build = sh("trap 'exit 0' INT; echo building; while true; do sleep 0.05; done")
        val run = start(build, sh("echo ran"), onStdout = { handler, out -> if (out == "building\n") handler.destroyProcess() }).await()
        assertEquals(GoBuildAndRunProcessHandler.STOPPED_EXIT_CODE, run.handler.exitCode)
        assertEquals("building\n", run.stdout.toString())
    }

    fun testInputWrittenBeforeTheProgramStartsReachesIt() {
        if (SystemInfo.isWindows) return
        val run = start(sh("sleep 0.2"), sh("read line; echo \"got \$line\""), beforeStart = { handler ->
            handler.processInput!!.apply {
                write("hi\n".toByteArray())
                flush()
            }
        }).await()
        assertEquals("got hi\n", run.stdout.toString())
    }

    fun testInputClosedBeforeTheProgramStartsGivesItEndOfFileAfterTheData() {
        if (SystemInfo.isWindows) return
        val run = start(sh("sleep 0.2"), GeneralCommandLine("/bin/cat"), beforeStart = { handler ->
            handler.processInput!!.apply {
                write("last\n".toByteArray())
                close()
            }
        }).await()
        assertEquals("last\n", run.stdout.toString())
        assertEquals(0, run.handler.exitCode)
    }

    fun testStopEndsAProgramThatNeverReadsItsInputAfterALargeWrite() {
        if (SystemInfo.isWindows) return
        val writeNanos = AtomicLong(-1)
        val run = start(sh("exit 0"), sh("echo up; sleep 30"), onStdout = { handler, out ->
            if (out != "up\n") return@start
            // Far beyond a pipe buffer: a direct write would block until the program reads.
            val started = System.nanoTime()
            handler.processInput!!.apply {
                write(ByteArray(4 shl 20))
                flush()
            }
            writeNanos.set(System.nanoTime() - started)
            handler.destroyProcess()
        }).await()
        assertTrue("input write took ${writeNanos.get()} ns", writeNanos.get() in 0 until 2_000_000_000L)
        assertTrue(run.handler.isProcessTerminated)
        assertFalse(Files.exists(buildDirectory))
    }

    fun testDetachEndsTheHandlerOnceAndReleasesTheBuildDirectory() {
        if (SystemInfo.isWindows) return
        val run = start(sh("exit 0"), sh("echo up; sleep 0.3"), onStdout = { handler, out -> if (out == "up\n") handler.detachProcess() })
            .await()
        assertTrue(run.handler.isProcessTerminated)
        assertFalse(Files.exists(buildDirectory))
        // The detached program exits later; that must not produce a second termination.
        Thread.sleep(600)
        assertEquals(1, run.terminations.get())
        assertEquals(0, run.handler.exitCode)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
