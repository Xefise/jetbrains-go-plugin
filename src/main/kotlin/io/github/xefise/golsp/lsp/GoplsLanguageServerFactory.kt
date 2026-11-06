package io.github.xefise.golsp.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class GoplsLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider = GoplsConnectionProvider(project)

    override fun createLanguageClient(project: Project): LanguageClientImpl = GoplsLanguageClient(project)

    override fun createClientFeatures(): LSPClientFeatures = GoplsClientFeatures()

    companion object {
        // Persisted in plugin.xml (lsp4ij server id) and in users' LSP4IJ settings; must never change.
        const val SERVER_ID: String = "gopls"
    }
}
