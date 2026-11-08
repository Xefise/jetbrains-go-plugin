package io.github.xefise.golsp.toolchain

import com.intellij.ide.BrowserUtil
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolBanner
import io.github.xefise.golsp.toolchain.model.GoToolBanners
import io.github.xefise.golsp.toolchain.model.GoToolchainState
import io.github.xefise.golsp.toolchain.model.GoToolStatusSnapshot
import java.util.function.Function
import javax.swing.JComponent

/** Go, gopls or dlv missing or outdated; reads snapshots only, so it never runs a process. */
class GoToolBannerProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.isDirectory || !GoToolBanners.isGoFileName(file.name)) return null
        if (!TrustedProjects.isProjectTrusted(project)) return null
        val banner = banner(project) ?: return null
        return Function { editor -> panel(project, editor, banner) }
    }

    private fun panel(project: Project, editor: FileEditor, banner: GoToolBanner): EditorNotificationPanel {
        val name = banner.tool.baseName
        val panel = when (banner) {
            is GoToolBanner.GoNotFound -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "Go is not available: ${banner.message}"
                createActionLabel("Specify Go path") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GoToolchainConfigurable::class.java)
                }
                createActionLabel("Download Go") { BrowserUtil.browse(GO_DOWNLOAD_URL) }
            }
            is GoToolBanner.Install -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = "$name is not installed: ${purpose(banner.tool)} unavailable"
                createActionLabel("Install $name") { GoToolInstaller.getInstance().install(project, banner.tool) }
            }
            is GoToolBanner.Update -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = "$name should be updated: ${banner.reason}"
                createActionLabel("Update $name") { GoToolInstaller.getInstance().update(project, banner.tool) }
            }
            is GoToolBanner.Installing -> return EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = "Installing $name…"
            }
        }
        panel.createActionLabel("Don't show again") { GoToolchainSettings.getInstance().setBannerHidden(banner.tool, true) }
        return panel
    }

    private fun purpose(tool: GoTool): String = if (tool == GoTool.DLV) "debugging is" else "code insight is"

    companion object {
        const val GO_DOWNLOAD_URL = "https://go.dev/dl/"

        fun banner(project: Project): GoToolBanner? = GoToolBanners.decide(
            GoToolchainService.getInstance(project).state,
            GoToolStatusService.getInstance(project).snapshot,
            GoToolchainSettings.getInstance()::isBannerHidden,
        )
    }
}

class GoToolBannerRefresher(private val project: Project) : GoToolchainListener, GoToolStatusListener {
    override fun toolchainChanged(state: GoToolchainState) = update()

    override fun toolStatusChanged(snapshot: GoToolStatusSnapshot) = update()

    private fun update() {
        if (!project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
    }
}
