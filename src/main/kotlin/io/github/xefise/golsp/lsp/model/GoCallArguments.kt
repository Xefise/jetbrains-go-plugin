package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoIndentTokenKind

/** Lexer-level guess whether the caret sits in a call's argument list; gopls has the final say via signatureHelp. */
object GoCallArguments {
    // Bounds the backward scan so typing in a huge function stays cheap.
    private const val MAX_TOKENS = 2000

    /** [tokensBackward] are the tokens before the caret, nearest first; [text] is the document text. */
    fun isInside(tokensBackward: Sequence<GoIndentToken>, text: CharSequence): Boolean {
        val tokens = tokensBackward.take(MAX_TOKENS).iterator()
        var depth = 0
        while (tokens.hasNext()) {
            val token = tokens.next()
            when (token.kind) {
                GoIndentTokenKind.CLOSE -> depth++
                GoIndentTokenKind.OPEN -> {
                    if (depth > 0) {
                        depth--
                        continue
                    }
                    if (text[token.start] != '(') return false
                    while (tokens.hasNext()) {
                        val before = tokens.next()
                        if (before.kind == GoIndentTokenKind.SPACE || before.kind == GoIndentTokenKind.COMMENT) continue
                        // `f(`, `x.f(`, `f()(` and `F[int](`.
                        return before.kind == GoIndentTokenKind.CODE || before.kind == GoIndentTokenKind.CLOSE
                    }
                    return false
                }
                else -> Unit
            }
        }
        return false
    }
}
