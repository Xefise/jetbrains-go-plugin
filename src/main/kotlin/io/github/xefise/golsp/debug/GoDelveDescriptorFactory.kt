package io.github.xefise.golsp.debug

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.DAPDebugRunner
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory
import io.github.xefise.golsp.lang.GoFileType

class GoDelveDescriptorFactory : DebugAdapterDescriptorFactory() {
    override fun createDebugAdapterDescriptor(
        options: RunConfigurationOptions,
        environment: ExecutionEnvironment,
    ): DebugAdapterDescriptor = GoDelveDescriptor(options, environment, serverDefinition)

    // Run is handled by the configuration's own plain-process state, not by DAP `noDebug`.
    override fun canRun(executorId: String): Boolean = executorId == DAPDebugRunner.DEBUG_EXECUTOR_ID

    // Keeps LSP4IJ from offering generic DAP configurations for .go files.
    override fun prepareConfiguration(configuration: RunConfiguration, file: VirtualFile, project: Project): Boolean =
        false

    override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean =
        file.fileType == GoFileType.INSTANCE
}
