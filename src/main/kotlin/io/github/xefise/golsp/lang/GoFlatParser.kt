package io.github.xefise.golsp.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

// Marks only what the gutter needs: the package clause, top-level func headers and t.Run("literal" calls.
class GoFlatParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        var depth = 0
        var packageSeen = false
        while (!builder.eof()) {
            val type = builder.tokenType
            // gofmt puts only top-level declarations in column 0; resync after an unclosed brace.
            if (isKeyword(builder, "func") && atColumnZero(builder)) depth = 0
            when {
                depth == 0 && !packageSeen && isKeyword(builder, "package") -> {
                    packageSeen = true
                    parsePackageClause(builder)
                }
                depth == 0 && isKeyword(builder, "func") && parseFunctionHeader(builder) -> Unit
                depth > 0 && type in GoElementTypes.NAMES && parseSubtestCall(builder) -> depth++
                else -> {
                    depth += bracketDelta(type)
                    if (depth < 0) depth = 0
                    builder.advanceLexer()
                }
            }
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }

    private fun parsePackageClause(builder: PsiBuilder) {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType in GoElementTypes.NAMES) builder.advanceLexer()
        marker.done(GoElementTypes.PACKAGE_CLAUSE)
    }

    // A top-level function literal (`var f = func() {}`) fails here: no name follows its parameter list.
    private fun parseFunctionHeader(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType == GoTokenTypes.LPAREN) {
            val receiver = builder.mark()
            if (!skipGroup(builder, GoTokenTypes.LPAREN, GoTokenTypes.RPAREN)) {
                receiver.drop()
                marker.rollbackTo()
                return false
            }
            receiver.done(GoElementTypes.RECEIVER)
        }
        if (builder.tokenType !in GoElementTypes.NAMES) {
            marker.rollbackTo()
            return false
        }
        builder.advanceLexer()
        val typeParamsOk = builder.tokenType != GoTokenTypes.LBRACKET ||
            skipGroup(builder, GoTokenTypes.LBRACKET, GoTokenTypes.RBRACKET)
        if (!typeParamsOk || builder.tokenType != GoTokenTypes.LPAREN ||
            !skipGroup(builder, GoTokenTypes.LPAREN, GoTokenTypes.RPAREN)
        ) {
            marker.rollbackTo()
            return false
        }
        marker.done(GoElementTypes.FUNCTION_HEADER)
        return true
    }

    // `x.Run("literal"`, consuming the "(" (the caller accounts for it); the gutter decides which receivers count.
    private fun parseSubtestCall(builder: PsiBuilder): Boolean {
        if (builder.lookAhead(1) != GoTokenTypes.DOT || builder.lookAhead(2) != GoTokenTypes.IDENTIFIER ||
            builder.lookAhead(3) != GoTokenTypes.LPAREN
        ) return false
        val literal = builder.lookAhead(4)
        if (literal != GoTokenTypes.STRING && literal != GoTokenTypes.RAW_STRING) return false
        val marker = builder.mark()
        builder.advanceLexer()
        builder.advanceLexer()
        if (builder.tokenText != "Run") {
            marker.rollbackTo()
            return false
        }
        builder.advanceLexer()
        builder.advanceLexer()
        builder.advanceLexer()
        marker.done(GoElementTypes.SUBTEST_CALL)
        return true
    }

    // Consumes a balanced group starting at `open`; false if it is not closed before EOF.
    private fun skipGroup(builder: PsiBuilder, open: IElementType, close: IElementType): Boolean {
        var depth = 0
        while (!builder.eof()) {
            val type = builder.tokenType
            if (type == open) depth++ else if (type == close) depth--
            builder.advanceLexer()
            if (depth == 0) return true
        }
        return false
    }

    private fun atColumnZero(builder: PsiBuilder): Boolean {
        val offset = builder.currentOffset
        return offset == 0 || builder.originalText[offset - 1] == '\n'
    }

    private fun isKeyword(builder: PsiBuilder, text: String): Boolean =
        builder.tokenType == GoTokenTypes.KEYWORD && builder.tokenText == text

    private fun bracketDelta(type: IElementType?): Int = when (type) {
        GoTokenTypes.LPAREN, GoTokenTypes.LBRACE, GoTokenTypes.LBRACKET -> 1
        GoTokenTypes.RPAREN, GoTokenTypes.RBRACE, GoTokenTypes.RBRACKET -> -1
        else -> 0
    }
}
