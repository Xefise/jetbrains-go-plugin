package io.github.xefise.golsp.debug

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.VirtualConfigurationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.xefise.golsp.lang.GoIcons

/** Created by Attach to Process only; hidden from the Run/Debug Configurations dialog. */
class GoAttachConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Attach to Process",
    "Debug a running local Go process with dlv",
    GoIcons.FILE,
), VirtualConfigurationType, DumbAware {
    init {
        addFactory(GoAttachConfigurationFactory(this))
    }

    companion object {
        // Persisted run-configuration type and factory id; never rename.
        const val ID: String = "GoLspAttach"

        fun factory(): ConfigurationFactory =
            ConfigurationTypeUtil.findConfigurationType(GoAttachConfigurationType::class.java).configurationFactories.single()
    }
}

class GoAttachConfigurationFactory(type: GoAttachConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = GoAttachConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoAttachConfiguration(project, this, "Go Attach to Process")

    override fun getOptionsClass(): Class<out BaseState> = GoAttachOptions::class.java
}

// Option keys are persisted; never rename them. New options need a default that keeps old behaviour.
class GoAttachOptions : RunConfigurationOptions() {
    private val processIdProperty = property(0).provideDelegate(this, "processId")
    private val portProperty = property(0).provideDelegate(this, "port")

    var processId: Int
        get() = processIdProperty.getValue(this)
        set(value) = processIdProperty.setValue(this, value)

    var port: Int
        get() = portProperty.getValue(this)
        set(value) = portProperty.setValue(this, value)
}
