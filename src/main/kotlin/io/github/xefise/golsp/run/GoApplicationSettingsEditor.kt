package io.github.xefise.golsp.run

import com.intellij.execution.configuration.EnvironmentVariablesDialog
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import io.github.xefise.golsp.lang.GoFileType
import javax.swing.JComponent
import javax.swing.JList

class GoApplicationSettingsEditor(project: Project) : SettingsEditor<GoApplicationConfiguration>() {
    private val runKind = ComboBox(GoRunKind.entries.toTypedArray()).apply {
        renderer = object : SimpleListCellRenderer<GoRunKind>() {
            override fun customize(list: JList<out GoRunKind>, value: GoRunKind?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = if (value == GoRunKind.FILE) "File" else "Package"
            }
        }
        addActionListener { updateTargetRows() }
    }
    private val packageDirectoryLabel = JBLabel("Package directory:")
    private val packageDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Go Package Directory"),
        )
    }
    private val filePathLabel = JBLabel("File:")
    private val filePath = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(GoFileType.INSTANCE).withTitle("Select Go File"),
        )
    }
    private val programArguments = RawCommandLineEditor()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Working Directory"),
        )
        textField.toolTipText = "Empty: the package directory, or the file's directory"
    }
    private val envs = GoEnvironmentVariablesField(project).apply {
        textField.toolTipText = "Use \$PATH\$ to reference the IDE environment, e.g. PATH=/my/bin:\$PATH\$"
    }
    private val envFile = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select Env File"))
        textField.toolTipText = "KEY=VALUE lines; Environment variables override it"
    }
    private val buildFlags = RawCommandLineEditor()

    private val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Run kind:", runKind)
        .addLabeledComponent(packageDirectoryLabel, packageDirectory)
        .addLabeledComponent(filePathLabel, filePath)
        .addLabeledComponent("Program arguments:", programArguments)
        .addLabeledComponent("Working directory:", workingDirectory)
        .addLabeledComponent("Environment variables:", envs)
        .addLabeledComponent("Env file:", envFile)
        .addLabeledComponent("Go build flags:", buildFlags)
        .panel

    private fun selectedKind(): GoRunKind = runKind.selectedItem as? GoRunKind ?: GoRunKind.PACKAGE

    private fun updateTargetRows() {
        val file = selectedKind() == GoRunKind.FILE
        packageDirectoryLabel.isVisible = !file
        packageDirectory.isVisible = !file
        filePathLabel.isVisible = file
        filePath.isVisible = file
    }

    override fun resetEditorFrom(configuration: GoApplicationConfiguration) {
        runKind.selectedItem = configuration.runKind
        packageDirectory.text = configuration.packageDirectory
        filePath.text = configuration.filePath
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
        envs.envs = configuration.envs
        envFile.text = configuration.envFile
        buildFlags.text = configuration.buildFlags
        updateTargetRows()
    }

    override fun applyEditorTo(configuration: GoApplicationConfiguration) {
        configuration.runKind = selectedKind()
        configuration.packageDirectory = packageDirectory.text.trim()
        configuration.filePath = filePath.text.trim()
        configuration.programArguments = programArguments.text
        configuration.workingDirectory = workingDirectory.text.trim()
        configuration.envs = envs.envs
        configuration.envFile = envFile.text.trim()
        configuration.buildFlags = buildFlags.text
    }

    override fun createEditor(): JComponent = panel

    // The IDE environment is always inherited, so the dialog must not offer to exclude it.
    private class GoEnvironmentVariablesField(project: Project) : EnvironmentVariablesTextFieldWithBrowseButton(project) {
        override fun createDialog(): EnvironmentVariablesDialog = object : EnvironmentVariablesDialog(this, true) {}
    }
}
