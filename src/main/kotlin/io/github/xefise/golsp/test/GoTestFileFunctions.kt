package io.github.xefise.golsp.test

import com.intellij.psi.TokenType
import io.github.xefise.golsp.lang.GoElementTypes
import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.GoTokenTypes

/** Top-level non-method function names, from the lexer alone: launch runs on EDT, where neither PSI nor VFS refresh belongs. */
object GoTestFileFunctions {
    private val IGNORE_CONSTRAINTS = setOf("//go:build ignore", "// +build ignore")

    /** Disk bytes as go sees them; malformed UTF-8 is replaced instead of failing the launch. */
    fun decode(bytes: ByteArray): String = bytes.decodeToString().removePrefix("﻿")

    /** `//go:build ignore` in the header: go never compiles the file, so it has no runnable tests. */
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

    fun topLevel(text: CharSequence): List<String> {
        val lexer = GoLexer().apply { start(text) }
        val names = mutableListOf<String>()
        var depth = 0
        var afterFunc = false
        while (true) {
            val type = lexer.tokenType ?: break
            if (type == TokenType.WHITE_SPACE || type in GoTokenTypes.COMMENTS) {
                lexer.advance()
                continue
            }
            if (afterFunc && type in GoElementTypes.NAMES) names += lexer.tokenText
            afterFunc = depth == 0 && type == GoTokenTypes.KEYWORD && lexer.tokenText == "func"
            depth = (depth + bracketDelta(type)).coerceAtLeast(0)
            lexer.advance()
        }
        return names
    }

    private fun bracketDelta(type: Any): Int = when (type) {
        GoTokenTypes.LPAREN, GoTokenTypes.LBRACE, GoTokenTypes.LBRACKET -> 1
        GoTokenTypes.RPAREN, GoTokenTypes.RBRACE, GoTokenTypes.RBRACKET -> -1
        else -> 0
    }
}
