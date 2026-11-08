package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.DebugMode
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.ServerReadyConfig
import io.github.xefise.golsp.debug.model.DelveAttachBuilder
import io.github.xefise.golsp.debug.model.DelveAttachTarget
import io.github.xefise.golsp.debug.model.DelveLaunchBuilder
import io.github.xefise.golsp.execution.GoRunConfigurationBase
import io.github.xefise.golsp.execution.GoRunProfileState
import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.lang.GoFileType
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool

class GoDelveDescriptor(
    options: RunConfigurationOptions,
    environment: ExecutionEnvironment,
    serverDefinition: DebugAdapterServerDefinition?,
) : DebugAdapterDescriptor(options, environment, serverDefinition) {

    private val request: GoExecutionRequest by lazy {
        val profile = runProfile() as? GoRunConfigurationBase<*>
            ?: throw ExecutionException("The Go Delve debug adapter cannot launch '${environment.runProfile.name}': not a Go Application or Go Test configuration")
        profile.launchRequest()
    }

    private val attachProfile: GoDelveAttachProfile? by lazy { runProfile() as? GoDelveAttachProfile }

    private val isAttach: Boolean get() = attachProfile != null

    // Validated by the profile's getState before LSP4IJ asks the descriptor anything.
    private val attachTarget: DelveAttachTarget by lazy { checkNotNull(attachProfile) { "not an attach configuration" }.attachTarget() }

    // Rerun Failed Tests wraps the configuration it launches.
    private fun runProfile(): Any? = environment.runProfile.let { (it as? WrappingRunConfiguration<*>)?.peer ?: it }

    @Throws(ExecutionException::class)
    override fun startServer(): ProcessHandler {
        // LSP4IJ never starts a server in ATTACH mode: it connects to the ready config's address directly.
        if (isAttach) throw ExecutionException("Attach configurations connect to a running dlv and start no server")
        thisLogger().info("startServer on thread '${Thread.currentThread().name}'")
        val toolchain = GoToolchainService.getInstance(environment.project)
        val dlv = toolchain.findTool(GoTool.DLV)
            ?: throw ExecutionException("dlv not found in GOBIN, PATH or ~/go/bin; click Install in the Go editor banner or set its path in the Go settings")
        val path = GoRunProfileState.goFirstPath(environment.project, request)
        // Port 0: dlv picks a free port and LSP4IJ reads it from the ready line, so there is no port race.
        return GoRunProfileState.startKillable(GoRunProfileState.toCommandLine(DelveLaunchBuilder.serverCommand(dlv, request, path)))
    }

    override fun getDapParameters(): Map<String, Any> {
        thisLogger().info("getDapParameters on thread '${Thread.currentThread().name}'")
        if (isAttach) return DelveAttachBuilder.attachParameters(attachTarget)
        return DelveLaunchBuilder.launchParameters(request)
    }

    override fun getDebugMode(): DebugMode = if (isAttach) DebugMode.ATTACH else DebugMode.LAUNCH

    // ATTACH: a plain socket connect without retries, so the server must already listen.
    override fun getServerReadyConfig(debugMode: DebugMode): ServerReadyConfig =
        if (debugMode == DebugMode.ATTACH) ServerReadyConfig(attachTarget.host, attachTarget.port)
        else ServerReadyConfig(DelveLaunchBuilder.READY_PATTERN)

    override fun getFileType(): FileType = GoFileType.INSTANCE

    // The base asks the server definition's language mappings, and go-delve has none: breakpoints would never be sent.
    override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = file.fileType == GoFileType.INSTANCE
}
