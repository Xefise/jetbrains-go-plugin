package io.github.xefise.golsp.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class GoApplicationConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Application",
    "Run or debug a Go main package",
    AllIcons.RunConfigurations.Application,
), DumbAware {
    init {
        addFactory(GoApplicationConfigurationFactory(this))
    }

    companion object {
        // Persisted run-configuration type and factory id; never rename.
        const val ID: String = "GoLspApplication"
    }
}

class GoApplicationConfigurationFactory(type: GoApplicationConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = GoApplicationConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoApplicationConfiguration(project, this, "Go Application")

    override fun getOptionsClass(): Class<out BaseState> = GoApplicationOptions::class.java
}
