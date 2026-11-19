package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsCustomSettingsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.lang.Language
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lsp.model.GoplsInlayHints
import javax.swing.JComponent
import javax.swing.JPanel

/** The `hints` map for gopls, read from the Inlay Hints settings of the gopls providers. */
internal object GoplsInlayHintsSettings {
    // What each project's gopls last pulled; per project, so a pull by one gopls never hides the change from another.
    private val lastSent = Key.create<Map<String, Boolean>>("golsp.gopls.hints.lastSent")

    fun current(): Map<String, Boolean> = ReadAction.computeBlocking<Map<String, Boolean>, RuntimeException> {
        val settings = DeclarativeInlayHintsSettings.getInstance()
        GoplsInlayHints.map(settings::isProviderEnabled, settings::isOptionEnabled)
    }

    fun forGopls(project: Project): Map<String, Boolean> = current().also { project.putUserData(lastSent, it) }

    /** True when [project]'s gopls has not seen [hints] yet; records them as sent. */
    fun markSent(project: Project, hints: Map<String, Boolean>): Boolean {
        if (project.getUserData(lastSent) == hints) return false
        project.putUserData(lastSent, hints)
        return true
    }
}

/** Settings-only provider: gopls hints are drawn by LSP4IJ, this one only owns the checkboxes and previews. */
class GoplsInlayHintsProvider : InlayHintsProvider, DumbAware {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? = null
}

/** The only Apply callback of the declarative hints settings; DeclarativeInlayHintsSettings has no listener. */
class GoplsInlayHintsApplyListener : InlayHintsCustomSettingsProvider<Unit> {
    private val component by lazy { JPanel() }

    override fun createComponent(project: Project, language: Language): JComponent = component

    override fun isDifferentFrom(project: Project, settings: Unit): Boolean = false

    override fun getSettingsCopy() {}

    override fun putSettings(project: Project, settings: Unit, language: Language) {}

    // Also called on Reset and once per provider on Apply, so a project's gopls is only poked when its map really changed.
    override fun persistSettings(project: Project, settings: Unit, language: Language) {
        val hints = GoplsInlayHintsSettings.current()
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed && GoplsInlayHintsSettings.markSent(it, hints) }
            .forEach(GoplsOptions::notifyGopls)
    }
}
