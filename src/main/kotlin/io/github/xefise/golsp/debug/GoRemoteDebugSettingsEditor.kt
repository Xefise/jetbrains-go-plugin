package io.github.xefise.golsp.debug

import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import io.github.xefise.golsp.debug.model.SubstitutePathRule
import java.text.ParseException
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class GoRemoteDebugSettingsEditor : SettingsEditor<GoRemoteDebugConfiguration>() {
    private class Row(var local: String = "", var remote: String = "")

    private class PathColumn(name: String, private val get: (Row) -> String, private val set: (Row, String) -> Unit) :
        ColumnInfo<Row, String>(name) {
        override fun valueOf(item: Row): String = get(item)
        override fun isCellEditable(item: Row): Boolean = true
        // Not trimmed: a trailing space is a legal path character.
        override fun setValue(item: Row, value: String) = set(item, value)
    }

    private val host = JBTextField()
    private val port = JSpinner(SpinnerNumberModel(GoRemoteDebugOptions.DEFAULT_PORT, 1, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }
    private val mappings = ListTableModel<Row>(
        PathColumn("Local path", Row::local) { row, value -> row.local = value },
        PathColumn("Remote path", Row::remote) { row, value -> row.remote = value },
    )
    private val table = TableView(mappings).apply { setShowGrid(false) }
    private val mappingsPanel = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            table.stopEditing()
            mappings.addRow(Row())
            val row = mappings.rowCount - 1
            table.selectionModel.setSelectionInterval(row, row)
            table.editCellAt(row, 0)
        }
        .createPanel()

    private val hint = JBLabel(
        "<html>Start the server with <code>dlv debug --headless --listen=:2345 --accept-multiclient --continue</code> " +
            "(or <code>dlv exec</code>/<code>dlv attach</code>). Stop only disconnects: the program and dlv keep running.<br>" +
            "A program paused at a breakpoint stays paused after Stop, so press Resume first.<br>" +
            "Rules apply in order by path prefix; an empty Local path matches every path, so put such a rule last.</html>",
    ).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    private val panel: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Host:", host)
        .addLabeledComponent("Port:", port)
        .addLabeledComponentFillVertically("Path mappings (substitutePath):", mappingsPanel)
        .addComponent(hint)
        .panel

    override fun resetEditorFrom(configuration: GoRemoteDebugConfiguration) {
        host.text = configuration.host
        port.value = configuration.port.coerceIn(1, 65535)
        mappings.items = configuration.substitutePath.mapTo(mutableListOf()) { Row(it.from, it.to) }
    }

    override fun applyEditorTo(configuration: GoRemoteDebugConfiguration) {
        table.stopEditing()
        configuration.host = host.text.trim()
        try {
            port.commitEdit()
        } catch (_: ParseException) {
            // An unparsable text keeps the last valid value, as the spinner itself does on focus loss.
        }
        configuration.port = port.value as Int
        configuration.substitutePath = mappings.items
            .filterNot { it.local.isBlank() && it.remote.isBlank() }
            .map { SubstitutePathRule(it.local, it.remote) }
    }

    override fun createEditor(): JComponent = panel
}
