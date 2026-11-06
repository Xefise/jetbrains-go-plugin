package io.github.xefise.golsp.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import io.github.xefise.golsp.lsp.model.GoplsSettings

class GoplsLanguageClient(project: Project) : LanguageClientImpl(project) {
    // The base returns the JSON the user typed in LSP4IJ's server settings; ours are defaults under it.
    override fun createSettings(): Any = GoplsSettings.build(super.createSettings() as? JsonObject)
}
