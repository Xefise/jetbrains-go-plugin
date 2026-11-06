// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class GoModFile(viewProvider: FileViewProvider, private val type: LanguageFileType) :
    PsiFileBase(viewProvider, type.language) {
    override fun getFileType(): FileType = type

    override fun toString(): String = type.displayName
}

// Flat tree: structure of go.mod/go.work comes from gopls.
abstract class GoModParserDefinitionBase(
    private val types: GoTokenSet,
    private val keywords: Set<String>,
    private val fileType: LanguageFileType,
    private val fileNode: IFileElementType,
) : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoModLexer(types, keywords)

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = fileNode

    override fun getCommentTokens(): TokenSet = types.COMMENTS

    override fun getStringLiteralElements(): TokenSet = types.STRINGS

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoModFile(viewProvider, fileType)
}

class GoModParserDefinition : GoModParserDefinitionBase(
    GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS, GoModFileType.INSTANCE, FILE,
) {
    companion object {
        @JvmField
        val FILE: IFileElementType = IFileElementType(GoModLanguage)
    }
}

class GoWorkParserDefinition : GoModParserDefinitionBase(
    GoWorkTokenTypes, GoModLexer.GO_WORK_KEYWORDS, GoWorkFileType.INSTANCE, FILE,
) {
    companion object {
        @JvmField
        val FILE: IFileElementType = IFileElementType(GoWorkLanguage)
    }
}
