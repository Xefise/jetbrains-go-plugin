package io.github.xefise.golsp.lang

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType

// Which files `go build` takes into a package, decided from names and the file header: no `go` processes.
object GoPackageFiles {
    private val IGNORE_CONSTRAINTS = setOf("//go:build ignore", "// +build ignore")
    private val MODULE_FILES = listOf("go.mod", "go.work")

    /** go skips `_x.go` and `.x.go` even when they are named on the command line. */
    fun isIgnoredByName(name: String): Boolean = name.startsWith("_") || name.startsWith(".")

    /** `//go:build ignore` in the header: the package build skips the file; only `go run file.go` compiles it. */
    fun isIgnoredByBuildConstraint(text: CharSequence): Boolean {
        val lexer = GoLexer().apply { start(text) }
        while (true) {
            val type = lexer.tokenType ?: return false
            when (type) {
                TokenType.WHITE_SPACE -> Unit
                GoTokenTypes.LINE_COMMENT -> if (lexer.tokenText.trimEnd() in IGNORE_CONSTRAINTS) return true
                else -> return false
            }
            lexer.advance()
        }
    }

    // GOPATH mode (GO111MODULE=off) and GOWORK overrides are ignored: only the files decide, as for most setups.
    fun isInModule(directory: VirtualFile): Boolean =
        generateSequence(directory) { it.parent }.any { dir -> MODULE_FILES.any { dir.findChild(it) != null } }

    /** Nearest directory with `go.mod` at or above [directory]. */
    fun moduleRoot(directory: VirtualFile): VirtualFile? =
        generateSequence(directory) { it.parent }.firstOrNull { it.findChild("go.mod") != null }
}
