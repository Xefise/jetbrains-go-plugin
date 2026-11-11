// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

// Token types let Comment with Block Comment refuse a range that starts or ends inside an existing comment.
class GoCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = GoTokenTypes.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = GoTokenTypes.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType? = null
    override fun getDocumentationCommentPrefix(): String? = null
    override fun getDocumentationCommentLinePrefix(): String? = null
    override fun getDocumentationCommentSuffix(): String? = null
    override fun isDocumentationComment(element: PsiComment?): Boolean = false
}

// go.mod and go.work have line comments only.
class GoModCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

abstract class GoBraceMatcherBase(private val pairs: Array<BracePair>) : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = pairs

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

class GoBraceMatcher : GoBraceMatcherBase(
    arrayOf(
        BracePair(GoTokenTypes.LBRACE, GoTokenTypes.RBRACE, true),
        BracePair(GoTokenTypes.LPAREN, GoTokenTypes.RPAREN, false),
        BracePair(GoTokenTypes.LBRACKET, GoTokenTypes.RBRACKET, false),
    ),
)

class GoModBraceMatcher : GoBraceMatcherBase(modPairs(GoModTokenTypes))

class GoWorkBraceMatcher : GoBraceMatcherBase(modPairs(GoWorkTokenTypes))

private fun modPairs(types: GoTokenSet): Array<BracePair> = arrayOf(
    BracePair(types.LPAREN, types.RPAREN, true),
    BracePair(types.LBRACKET, types.RBRACKET, false),
)

class GoQuoteHandler : SimpleTokenSetQuoteHandler(GoTokenTypes.STRINGS)

class GoModQuoteHandler : SimpleTokenSetQuoteHandler(GoModTokenTypes.STRING, GoModTokenTypes.RAW_STRING)

class GoWorkQuoteHandler : SimpleTokenSetQuoteHandler(GoWorkTokenTypes.STRING, GoWorkTokenTypes.RAW_STRING)
