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
        val profile = goConfiguration()
            ?: throw ExecutionException("The Go Delve debug adapter works only with Go run configurations")
        profile.launchRequest()
    }

    // Rerun Failed Tests wraps the configuration it launches.
    private fun goConfiguration(): GoRunConfigurationBase<*>? =
        environment.runProfile.let { (it as? WrappingRunConfiguration<*>)?.peer ?: it } as? GoRunConfigurationBase<*>

    @Throws(ExecutionException::class)
    override fun startServer(): ProcessHandler {
        thisLogger().info("startServer on thread '${Thread.currentThread().name}'")
        val toolchain = GoToolchainService.getInstance(environment.project)
        val dlv = toolchain.findTool(GoTool.DLV)
            ?: throw ExecutionException("dlv not found in GOBIN, PATH or ~/go/bin; install it with `go install github.com/go-delve/delve/cmd/dlv@latest`")
        val path = GoRunProfileState.goFirstPath(environment.project, request)
        // Port 0: dlv picks a free port and LSP4IJ reads it from the ready line, so there is no port race.
        return GoRunProfileState.startKillable(GoRunProfileState.toCommandLine(DelveLaunchBuilder.serverCommand(dlv, request, path)))
    }

    override fun getDapParameters(): Map<String, Any> {
        thisLogger().info("getDapParameters on thread '${Thread.currentThread().name}'")
        return DelveLaunchBuilder.launchParameters(request)
    }

    override fun getDebugMode(): DebugMode = DebugMode.LAUNCH

    override fun getServerReadyConfig(debugMode: DebugMode): ServerReadyConfig =
        ServerReadyConfig(DelveLaunchBuilder.READY_PATTERN)

    override fun getFileType(): FileType = GoFileType.INSTANCE

    // The base asks the server definition's language mappings, and go-delve has none: breakpoints would never be sent.
    override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean = file.fileType == GoFileType.INSTANCE
}
