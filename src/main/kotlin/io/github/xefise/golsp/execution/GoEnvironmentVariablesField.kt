package io.github.xefise.golsp.execution

import com.intellij.execution.configuration.EnvironmentVariablesDialog
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.project.Project

// The IDE environment is always inherited, so the dialog must not offer to exclude it.
class GoEnvironmentVariablesField(project: Project) : EnvironmentVariablesTextFieldWithBrowseButton(project) {
    init {
        textField.toolTipText = "Use \$PATH\$ to reference the IDE environment, e.g. PATH=/my/bin:\$PATH\$"
    }

    override fun createDialog(): EnvironmentVariablesDialog = object : EnvironmentVariablesDialog(this, true) {}
}
