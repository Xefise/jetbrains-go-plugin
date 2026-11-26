package io.github.xefise.golsp.lsp.model

/** gopls commands that may rewrite go.mod/go.sum/go.work on disk, bypassing `workspace/applyEdit` when the file is not open. */
object GoplsModuleCommands {
    val MODULE_FILE_NAMES: List<String> = listOf("go.mod", "go.sum", "go.work", "go.work.sum")

    // Work-done progress titles from gopls internal/server/command.go (v0.24.0).
    private val TITLES = setOf(
        "Running go get",
        "Updating go.sum",
        "Running go mod tidy",
        "Running go mod vendor",
        "Removing dependency",
        "Running go work command",
    )

    fun rewritesModuleFiles(progressTitle: String?): Boolean = progressTitle in TITLES

    fun isModuleFile(uri: String): Boolean = uri.substringAfterLast('/') in MODULE_FILE_NAMES
}
