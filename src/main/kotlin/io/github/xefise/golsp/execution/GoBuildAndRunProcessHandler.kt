package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.KillableProcess
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** `go build`, then the built binary, as one process; [buildDirectory] is deleted when the run ends or detaches. */
class GoBuildAndRunProcessHandler(
    private val buildCommand: GeneralCommandLine,
    private val programCommand: GeneralCommandLine,
    private val buildDirectory: Path,
) : ProcessHandler(), KillableProcess {

    private val lock = Any()
    private var current: KillableColoredProcessHandler? = null
    private var stopRequested = false
    private var detached = false
    // Termination and detach are mutually exclusive.
    private val completed = AtomicBoolean(false)
    private val input = GoProgramInput()

    override fun startNotify() {
        super.startNotify()
        // startProcess runs on EDT; spawning is kept off it.
        AppExecutorUtil.getAppExecutorService().execute { guarded { launch(buildCommand, ::onBuildFinished) } }
    }

    private fun onBuildFinished(exitCode: Int) {
        when {
            synchronized(lock) { stopRequested } -> finish(if (exitCode == 0) STOPPED_EXIT_CODE else exitCode)
            exitCode != 0 -> finish(exitCode)
            else -> {
                // The directory itself was registered first, and deleteOnExit runs in reverse order.
                buildDirectory.toFile().listFiles()?.forEach(File::deleteOnExit)
                launch(programCommand, ::finish)
            }
        }
    }

    private fun launch(commandLine: GeneralCommandLine, onExit: (Int) -> Unit) {
        if (synchronized(lock) { stopRequested }) return finish(STOPPED_EXIT_CODE)
        val isProgram = commandLine === programCommand
        val handler = try {
            GoRunProfileState.createKillable(commandLine)
        } catch (e: ExecutionException) {
            val hint = if (isProgram) "\nThe built file could not be started; is the target a `main` package?" else ""
            notifyTextAvailable("${e.message}$hint\n", ProcessOutputType.STDERR)
            return finish(LAUNCH_FAILED_EXIT_CODE)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                notifyTextAvailable(event.text, outputType)
            }

            // Detaching an inner handler also reports termination.
            override fun processTerminated(event: ProcessEvent) {
                if (!synchronized(lock) { detached }) guarded { onExit(event.exitCode) }
            }
        })
        val stop = synchronized(lock) {
            current = handler
            stopRequested
        }
        handler.startNotify()
        if (isProgram) input.connect(handler.processInput)
        if (stop) handler.destroyProcess()
    }

    // Any failure must still end the run, or the console waits forever.
    private fun guarded(step: () -> Unit) {
        try {
            step()
        } catch (e: Throwable) {
            thisLogger().warn("Go build-and-run step failed", e)
            try {
                notifyTextAvailable("Cannot run the Go program: ${e.message ?: e.javaClass.name}\n", ProcessOutputType.STDERR)
            } finally {
                synchronized(lock) { current }?.takeUnless { it.isProcessTerminated }?.destroyProcess()
                finish(LAUNCH_FAILED_EXIT_CODE)
            }
        }
    }

    private fun finish(exitCode: Int) {
        if (!completed.compareAndSet(false, true)) return
        try {
            releaseResources()
        } finally {
            notifyProcessTerminated(exitCode)
        }
    }

    override fun destroyProcessImpl() {
        val target = synchronized(lock) {
            stopRequested = true
            current
        }
        target?.destroyProcess()
    }

    // The program keeps running; only stdin and the build directory are released.
    override fun detachProcessImpl() {
        val target = synchronized(lock) {
            stopRequested = true
            detached = true
            current
        }
        target?.detachProcess()
        if (!completed.compareAndSet(false, true)) return
        try {
            releaseResources()
        } finally {
            notifyProcessDetached()
        }
    }

    private fun releaseResources() {
        try {
            input.connect(null)
            input.close()
        } finally {
            deleteBuildDirectory()
        }
    }

    // On Windows a running binary cannot be deleted; deleteOnExit is already registered for it.
    private fun deleteBuildDirectory() {
        if (!buildDirectory.toFile().deleteRecursively()) thisLogger().info("Could not delete $buildDirectory now")
    }

    override fun detachIsDefault(): Boolean = false

    // The console takes this stream once, before the binary exists.
    override fun getProcessInput(): OutputStream = input

    override fun canKillProcess(): Boolean = synchronized(lock) { current }?.canKillProcess() ?: false

    override fun killProcess() {
        synchronized(lock) { current }?.killProcess()
    }

    companion object {
        // A shell's code for SIGINT.
        const val STOPPED_EXIT_CODE: Int = 130
        const val LAUNCH_FAILED_EXIT_CODE: Int = 1
    }
}
