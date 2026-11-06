// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class GoParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoLexer()

    override fun createParser(project: Project?): PsiParser = GoFlatParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = GoTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = GoTokenTypes.STRINGS

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        GoElementTypes.PACKAGE_CLAUSE -> GoPackageClause(node)
        GoElementTypes.FUNCTION_HEADER -> GoFunctionHeader(node)
        GoElementTypes.RECEIVER -> GoReceiver(node)
        GoElementTypes.SUBTEST_CALL -> GoSubtestCall(node)
        else -> ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoFile(viewProvider)

    companion object {
        @JvmField
        val FILE: IFileElementType = IFileElementType(GoLanguage)
    }
}
