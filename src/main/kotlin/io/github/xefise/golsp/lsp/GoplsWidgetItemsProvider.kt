package io.github.xefise.golsp.lsp

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItem
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItemsProvider
import com.intellij.platform.lang.lsWidget.OpenSettingsAction
import io.github.xefise.golsp.lang.GoFileType
import io.github.xefise.golsp.lang.GoIcons
import io.github.xefise.golsp.lang.GoModFileType
import io.github.xefise.golsp.lang.GoWorkFileType
import io.github.xefise.golsp.lsp.model.GoplsWidgetStatus
import io.github.xefise.golsp.lsp.model.GoplsWidgetStatuses
import io.github.xefise.golsp.toolchain.GoToolStatusListener
import io.github.xefise.golsp.toolchain.GoToolStatusService
import io.github.xefise.golsp.toolchain.GoToolchainListener
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool
import javax.swing.Icon

/** The gopls entry of the status bar "Language Services" widget; reads snapshots only, never runs a process. */
class GoplsWidgetItemsProvider : LanguageServiceWidgetItemsProvider() {
    override fun createWidgetItems(project: Project, currentFile: VirtualFile?): List<LanguageServiceWidgetItem> {
        val goFile = currentFile?.fileType.let { it == GoFileType.INSTANCE || it == GoModFileType.INSTANCE || it == GoWorkFileType.INSTANCE }
        val status = GoplsWidgetStatuses.status(
            goFile,
            GoplsRequests.isStarted(project),
            { GoToolchainService.getInstance(project).state },
            { GoToolStatusService.getInstance(project).status(GoTool.GOPLS) },
        ) ?: return emptyList()
        return listOf(GoplsWidgetItem(project, status))
    }

    override fun registerWidgetUpdaters(project: Project, widgetDisposable: Disposable, updateWidget: () -> Unit) {
        val connection = project.messageBus.connect(widgetDisposable)
        connection.subscribe(GoToolchainListener.TOPIC, GoToolchainListener { updateWidget() })
        connection.subscribe(GoToolStatusListener.TOPIC, GoToolStatusListener { updateWidget() })
    }
}

private class GoplsWidgetItem(private val project: Project, private val status: GoplsWidgetStatus) : LanguageServiceWidgetItem() {
    override val statusBarIcon: Icon get() = GoIcons.FILE

    override val statusBarTooltip: String get() = status.text

    override val isError: Boolean get() = status.isError

    override val widgetActionLocation: LanguageServicePopupSection
        get() = if (status.forCurrentFile) LanguageServicePopupSection.ForCurrentFile else LanguageServicePopupSection.Other

    override fun createWidgetMainAction(): AnAction = OpenSettingsAction(GoplsConfigurable::class.java, status.text, GoIcons.FILE)

    override fun createWidgetInlineActions(): List<AnAction> =
        listOf(GoplsRestartAction(project), OpenSettingsAction(GoplsConfigurable::class.java))
}

private class GoplsRestartAction(private val project: Project) : DumbAwareAction("Restart gopls", null, AllIcons.Actions.Restart) {
    // LSP4IJ's restart is kept off EDT.
    override fun actionPerformed(e: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread { GoplsRestart.restartIfCreated(project) }
    }
}
