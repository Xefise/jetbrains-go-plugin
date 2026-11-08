package io.github.xefise.golsp.debug

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xefise.golsp.lang.GoIcons

class GoRemoteDebugConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Remote Debug",
    "Attach the debugger to a running dlv --headless server",
    GoIcons.FILE,
), DumbAware {
    init {
        addFactory(GoRemoteDebugConfigurationFactory(this))
    }

    companion object {
        // Persisted run-configuration type and factory id; never rename.
        const val ID: String = "GoLspRemoteDebug"
    }
}

class GoRemoteDebugConfigurationFactory(type: GoRemoteDebugConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = GoRemoteDebugConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoRemoteDebugConfiguration(project, this, "Go Remote Debug")

    override fun getOptionsClass(): Class<out BaseState> = GoRemoteDebugOptions::class.java
}
