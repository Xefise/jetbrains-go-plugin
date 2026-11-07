package io.github.xefise.golsp.test

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class GoTestConfigurationType : ConfigurationTypeBase(
    ID,
    "Go Test",
    "Run Go tests with go test",
    AllIcons.RunConfigurations.TestState.Run,
), DumbAware {
    init {
        addFactory(GoTestConfigurationFactory(this))
    }

    companion object {
        // Persisted run-configuration type and factory id; never rename.
        const val ID: String = "GoLspTest"
    }
}

class GoTestConfigurationFactory(type: GoTestConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = GoTestConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        GoTestConfiguration(project, this, "Go Test")

    override fun getOptionsClass(): Class<out BaseState> = GoTestOptions::class.java
}
