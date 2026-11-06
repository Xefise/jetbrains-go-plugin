package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.xefise.golsp.execution.model.GoEnvironmentLayers
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunCommandBuilder
import io.github.xefise.golsp.execution.model.ProcessSpec
import io.github.xefise.golsp.toolchain.GoToolchainService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GoRunProfileState(
    environment: ExecutionEnvironment,
    private val goExecutable: String,
    private val requestProvider: () -> GoExecutionRequest,
) : CommandLineState(environment) {

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val launch = requestProvider()
        val request = launch.copy(environment = GoEnvironmentLayers.withPath(launch.environment, goFirstPath(environment.project, launch)))
        val buildDirectory = try {
            Files.createTempDirectory(BUILD_DIRECTORY_PREFIX)
        } catch (e: IOException) {
            throw ExecutionException("Cannot create a temporary directory for the Go build: ${e.message}", e)
        }
        // Safety net for an IDE exit mid-run; the handler deletes it as soon as the run ends.
        buildDirectory.toFile().deleteOnExit()
        try {
            val binary = buildDirectory.resolve(GoRunCommandBuilder.binaryName(request.target, SystemInfo.isWindows)).toString()
            val handler = GoBuildAndRunProcessHandler(
                toCommandLine(GoRunCommandBuilder.buildCommand(goExecutable, request, binary)),
                toCommandLine(GoRunCommandBuilder.programCommand(request, binary)),
                buildDirectory,
            )
            ProcessTerminatedListener.attach(handler)
            return handler
        } catch (e: Throwable) {
            buildDirectory.toFile().deleteRecursively()
            throw e
        }
    }

    companion object {
        private const val HARD_KILL_DELAY_MS = 2500L
        private const val BUILD_DIRECTORY_PREFIX = "go-run-"

        @Throws(ExecutionException::class)
        fun startKillable(commandLine: GeneralCommandLine): KillableColoredProcessHandler =
            createKillable(commandLine).also(ProcessTerminatedListener::attach)

        // Stop sends SIGINT to the process tree first; whatever ignores it (a wedged dlv or program) is killed hard.
        @Throws(ExecutionException::class)
        fun createKillable(commandLine: GeneralCommandLine): KillableColoredProcessHandler {
            val handler = KillableColoredProcessHandler(commandLine)
            handler.addProcessListener(object : ProcessListener {
                override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                    if (!willBeDestroyed) return
                    AppExecutorUtil.getAppScheduledExecutorService().schedule(
                        { if (!handler.isProcessTerminated) handler.killProcess() },
                        HARD_KILL_DELAY_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            })
            return handler
        }

        /** A PATH set by the user replaces the IDE's, but go still goes first, in Run and Debug alike. */
        fun goFirstPath(project: Project, request: GoExecutionRequest): String? {
            val toolchain = GoToolchainService.getInstance(project)
            return toolchain.pathWithGoFirst(request.environment["PATH"] ?: toolchain.lookupEnvironment().path)
        }

        fun toCommandLine(spec: ProcessSpec): GeneralCommandLine =
            GeneralCommandLine(listOf(spec.executable) + spec.arguments)
                .withWorkingDirectory(Path.of(spec.workingDirectory))
                .withEnvironment(spec.environment)
                // CONSOLE takes the login-shell environment on macOS, so `go` finds its own tools.
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }
}
