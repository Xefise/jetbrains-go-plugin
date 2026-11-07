package io.github.xefise.golsp.test

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import io.github.xefise.golsp.execution.GoEnvironmentVariablesField
import io.github.xefise.golsp.lang.GoFileType
import javax.swing.JComponent
import javax.swing.JList

class GoTestSettingsEditor(project: Project) : SettingsEditor<GoTestConfiguration>() {
    private val scope = ComboBox(GoTestScope.entries.toTypedArray()).apply {
        renderer = object : SimpleListCellRenderer<GoTestScope>() {
            override fun customize(list: JList<out GoTestScope>, value: GoTestScope?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = when (value) {
                    GoTestScope.FILE -> "File"
                    GoTestScope.DIRECTORY -> "Directory (recursive)"
                    else -> "Package"
                }
            }
        }
        addActionListener { updateTargetRows() }
    }
    // Not a checkConfiguration warning: any exception there leaves Debug without a runner.
    internal val recursiveDebugHint = JBLabel("Debug runs one package at a time; ./... can only be run.").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }
    private val directoryLabel = JBLabel("Package directory:")
    private val directory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Directory"))
    }
    private val filePathLabel = JBLabel("Test file:")
    private val filePath = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(GoFileType.INSTANCE).withTitle("Select Go Test File"),
        )
    }
    private val testNames = RawCommandLineEditor().apply {
        editorField.toolTipText = "Full test paths, quoted if they contain spaces: TestA \"TestB/sub case\"; empty runs all"
    }
    private val testArguments = RawCommandLineEditor().apply {
        editorField.toolTipText = "Passed to the test binary after -args"
    }
    private val envs = GoEnvironmentVariablesField(project)
    private val envFile = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select Env File"))
        textField.toolTipText = "KEY=VALUE lines; Environment variables override it"
    }
    private val buildFlags = RawCommandLineEditor().apply {
        editorField.toolTipText = "Flags of go test itself, e.g. -race -count=1 -timeout 30s"
    }

    private val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Test kind:", scope)
        .addComponentToRightColumn(recursiveDebugHint)
        .addLabeledComponent(directoryLabel, directory)
        .addLabeledComponent(filePathLabel, filePath)
        .addLabeledComponent("Test names:", testNames)
        .addLabeledComponent("Go test flags:", buildFlags)
        .addLabeledComponent("Test arguments:", testArguments)
        .addLabeledComponent("Environment variables:", envs)
        .addLabeledComponent("Env file:", envFile)
        .panel

    private fun selectedScope(): GoTestScope = scope.selectedItem as? GoTestScope ?: GoTestScope.PACKAGE

    private fun updateTargetRows() {
        val selected = selectedScope()
        val file = selected == GoTestScope.FILE
        directoryLabel.text = if (selected == GoTestScope.DIRECTORY) "Directory:" else "Package directory:"
        recursiveDebugHint.isVisible = selected == GoTestScope.DIRECTORY
        directoryLabel.isVisible = !file
        directory.isVisible = !file
        filePathLabel.isVisible = file
        filePath.isVisible = file
    }

    override fun resetEditorFrom(configuration: GoTestConfiguration) {
        scope.selectedItem = configuration.scope
        directory.text = configuration.directory
        filePath.text = configuration.filePath
        testNames.text = ParametersListUtil.join(configuration.testNames)
        testArguments.text = configuration.testArguments
        envs.envs = configuration.envs
        envFile.text = configuration.envFile
        buildFlags.text = configuration.buildFlags
        updateTargetRows()
    }

    override fun applyEditorTo(configuration: GoTestConfiguration) {
        configuration.scope = selectedScope()
        configuration.directory = directory.text.trim()
        configuration.filePath = filePath.text.trim()
        configuration.testNames = ParametersListUtil.parse(testNames.text).filter { it.isNotBlank() }
        configuration.testArguments = testArguments.text
        configuration.envs = envs.envs
        configuration.envFile = envFile.text.trim()
        configuration.buildFlags = buildFlags.text
    }

    override fun createEditor(): JComponent = panel
}
