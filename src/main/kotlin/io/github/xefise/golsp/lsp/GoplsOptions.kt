package io.github.xefise.golsp.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.redhat.devtools.lsp4ij.LanguageServerManager
import org.eclipse.lsp4j.DidChangeConfigurationParams

/** Editor-facing gopls options; sent as defaults under the user's LSP4IJ JSON configuration. */
@Service(Service.Level.APP)
@State(
    name = "GoLspGoplsSettings",
    storages = [Storage("golsp-gopls.xml", roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.CODE,
)
class GoplsOptions : SimplePersistentStateComponent<GoplsOptions.Options>(Options()) {
    class Options : BaseState() {
        var usePlaceholders by property(false)
    }

    val usePlaceholders: Boolean get() = state.usePlaceholders

    fun update(usePlaceholders: Boolean) {
        if (state.usePlaceholders == usePlaceholders) return
        state.usePlaceholders = usePlaceholders
        notifyOpenProjects()
    }

    private fun notifyOpenProjects() {
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach(::notifyGopls)
    }

    companion object {
        fun getInstance(): GoplsOptions = service()

        // gopls ignores the payload and pulls workspace/configuration again, which reaches GoplsLanguageClient.createSettings.
        fun notifyGopls(project: Project) {
            if (!GoplsRequests.isStarted(project)) return
            LanguageServerManager.getInstance(project).getLanguageServer(GoplsLanguageServerFactory.SERVER_ID).thenAccept { server ->
                server?.workspaceService?.didChangeConfiguration(DidChangeConfigurationParams(JsonObject()))
            }.exceptionally { error ->
                logger<GoplsOptions>().info("gopls didChangeConfiguration failed", error)
                null
            }
        }
    }
}

/** Settings → Languages & Frameworks → Go → gopls. */
class GoplsConfigurable : BoundSearchableConfigurable("gopls", ID, ID) {
    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Insert argument placeholders when completing functions")
                .bindSelected({ GoplsOptions.getInstance().usePlaceholders }) { GoplsOptions.getInstance().update(it) }
                .comment("Argument names are selected for overtyping; Tab or Enter moves to the next one. A <code>usePlaceholders</code> value in the gopls JSON configuration wins.")
        }
        row {
            comment("gopls analyzers are switched on and off in Editor | Inspections | Go.")
        }
    }

    override fun getHelpTopic(): String? = null

    companion object {
        const val ID = "golsp.settings.gopls"
    }
}
