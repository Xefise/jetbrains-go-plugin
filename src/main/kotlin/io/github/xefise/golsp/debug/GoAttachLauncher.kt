package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessInfo
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import io.github.xefise.golsp.debug.model.DelveAttachServer
import io.github.xefise.golsp.execution.GoRunProfileState
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.toolchain.GoToolInstaller
import io.github.xefise.golsp.toolchain.GoToolchainService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts a loopback `dlv dap` in the background, waits for its address, then runs a temporary [GoAttachConfiguration]
 * against it. dlv serves one client and exits after the disconnect; [DlvServer] makes sure it never outlives a
 * session that failed, ended or whose project closed.
 */
internal class GoAttachLauncher(private val project: Project, private val processInfo: ProcessInfo) {
    private val pid = processInfo.pid
    private val sessionName = DelveAttachServer.sessionName(processInfo.executableDisplayName, pid)

    @Throws(ExecutionException::class)
    fun start() {
        if (!STARTING.add(pid)) throw ExecutionException("The debugger is already being attached to process $pid")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Starting dlv to attach to $pid", true) {
            @Volatile private var server: DlvServer? = null
            @Volatile private var port = 0

            override fun run(indicator: ProgressIndicator) {
                val handler = GoRunProfileState.startKillable(GoRunProfileState.toCommandLine(serverCommand(GoDelveLookup.find(project))))
                val started = DlvServer(project, handler, pid)
                server = started
                handler.startNotify()
                port = awaitReady(started, indicator)
            }

            override fun onSuccess() {
                server?.let { launchSession(it, port) }
            }

            override fun onCancel() {
                server?.release(immediately = true)
            }

            override fun onThrowable(error: Throwable) {
                server?.release(immediately = true)
                if (error !is ExecutionException) thisLogger().warn("Attach to $pid failed", error)
                notify(project, "Cannot attach to $pid", error.message ?: error.javaClass.name)
            }

            override fun onFinished() {
                STARTING.remove(pid)
            }
        })
    }

    private fun serverCommand(dlv: String) = run {
        val toolchain = GoToolchainService.getInstance(project)
        val path = toolchain.pathWithGoFirst(toolchain.lookupEnvironment().path)
        val workingDirectory = project.basePath
            ?: processInfo.executableCannonicalPath.map { Path.of(it).parent?.toString() }.orElse(null)
            ?: System.getProperty("user.home")
        DelveAttachServer.command(dlv, workingDirectory, GoEnvironmentLayers.withPath(emptyMap(), path))
    }

    private fun awaitReady(server: DlvServer, indicator: ProgressIndicator): Int {
        val handler = server.handler
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DelveAttachServer.READY_TIMEOUT_MS)
        while (true) {
            server.output.port()?.let { return it }
            if (handler.isProcessTerminated) throw ExecutionException(DelveAttachServer.startFailure(handler.exitCode, server.output.stderr()))
            if (System.nanoTime() > deadline) {
                throw ExecutionException("dlv dap did not report its address within ${DelveAttachServer.READY_TIMEOUT_MS / 1000} s")
            }
            indicator.checkCanceled()
            handler.waitFor(POLL_MS)
        }
    }

    private fun launchSession(server: DlvServer, port: Int) {
        if (project.isDisposed) {
            server.release(immediately = true)
            return
        }
        val settings = RunManager.getInstance(project).createConfiguration(sessionName, GoAttachConfigurationType.factory())
        val configuration = settings.configuration as GoAttachConfiguration
        configuration.processId = pid
        configuration.port = port
        server.watchSession(configuration)
        try {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        } catch (e: Throwable) {
            server.release(immediately = true)
            throw e
        }
    }

    /** The spawned dlv and everything that must stop it; every exit path ends in one [release]. */
    private class DlvServer(private val project: Project, val handler: KillableColoredProcessHandler, private val pid: Int) : Disposable {
        val output = DlvOutput()
        private val released = AtomicBoolean(false)
        private val reported = AtomicBoolean(false)
        @Volatile private var connection: MessageBusConnection? = null
        @Volatile private var sessionLaunched = false
        @Volatile private var sessionStarted = false
        @Volatile private var destroyedByUs = false

        init {
            // Listeners go on before startNotify, so neither output nor the termination can be missed.
            handler.addProcessListener(output)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    if (sessionLaunched) onTerminated()
                }
            })
            if (!Disposer.tryRegister(project, this)) destroy()
        }

        fun watchSession(configuration: GoAttachConfiguration) {
            val bus = project.messageBus.connect()
            connection = bus
            bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    if (env.runProfile === configuration) sessionStarted = true
                }

                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    if (env.runProfile === configuration) release(immediately = true)
                }

                override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                    if (env.runProfile === configuration) release(immediately = false)
                }
            })
            // executeConfiguration can fail before any listener event (invalid configuration, no runner).
            schedule(SESSION_START_TIMEOUT_MS) { if (!sessionStarted) release(immediately = true) }
            sessionLaunched = true
            if (handler.isProcessTerminated) onTerminated()
        }

        private fun onTerminated() {
            if (!reported.compareAndSet(false, true)) return
            release(immediately = false)
            DelveAttachServer.exitReport(output.stderr(), handler.exitCode, sessionStarted, destroyedByUs, SystemInfo.isMac)
                ?.let { notify(project, "dlv could not attach to $pid", it) }
        }

        fun release(immediately: Boolean) {
            if (!released.compareAndSet(false, true)) return
            stop(immediately)
            Disposer.dispose(this)
        }

        override fun dispose() {
            if (released.compareAndSet(false, true)) stop(immediately = true)
        }

        private fun stop(immediately: Boolean) {
            connection?.disconnect()
            // After a session dlv detaches and exits by itself; only a wedged one is stopped.
            if (immediately) destroy() else schedule(DETACH_GRACE_MS) { if (!handler.isProcessTerminated) destroy() }
        }

        private fun destroy() {
            destroyedByUs = true
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }

        private fun schedule(delayMs: Long, action: () -> Unit) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(action, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** What dlv prints: stdout until the ready line, stderr in full (capped). */
    private class DlvOutput : ProcessListener {
        private val stdout = StringBuilder()
        private val stderr = StringBuilder()
        private var port: Int? = null

        @Synchronized
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            when {
                ProcessOutputType.isStdout(outputType) && port == null -> {
                    stdout.append(event.text)
                    port = DelveAttachServer.readyPort(stdout)
                    trim(stdout)
                }
                ProcessOutputType.isStderr(outputType) -> {
                    stderr.append(event.text)
                    trim(stderr)
                }
            }
        }

        @Synchronized fun port(): Int? = port

        @Synchronized fun stderr(): String = stderr.toString()

        private fun trim(buffer: StringBuilder) {
            val excess = buffer.length - OUTPUT_LIMIT
            if (excess > 0) buffer.delete(0, excess)
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val DETACH_GRACE_MS = 5_000L
        const val SESSION_START_TIMEOUT_MS = 60_000L
        const val OUTPUT_LIMIT = 16_384

        val STARTING: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        fun notify(project: Project, title: String, content: String) {
            if (project.isDisposed) return
            NotificationGroupManager.getInstance()
                .getNotificationGroup(GoToolInstaller.NOTIFICATION_GROUP)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project)
        }
    }
}
