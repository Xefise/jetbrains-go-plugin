package io.github.xefise.golsp.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.xefise.golsp.lang.model.GoStringLiterals

object GoElementTypes {
    @JvmField
    val PACKAGE_CLAUSE: IElementType = IElementType("GO_PACKAGE_CLAUSE", GoLanguage)

    @JvmField
    val FUNCTION_HEADER: IElementType = IElementType("GO_FUNCTION_HEADER", GoLanguage)

    @JvmField
    val RECEIVER: IElementType = IElementType("GO_RECEIVER", GoLanguage)

    @JvmField
    val SUBTEST_CALL: IElementType = IElementType("GO_SUBTEST_CALL", GoLanguage)

    // Predeclared names are not reserved: `func (c *conn) close()` is an ordinary method.
    @JvmField
    val NAMES: TokenSet = TokenSet.create(GoTokenTypes.IDENTIFIER, GoTokenTypes.BUILTIN)
}

private fun PsiElement.firstChildOf(types: TokenSet): PsiElement? {
    var child = firstChild
    while (child != null) {
        if (child.node.elementType in types) return child
        child = child.nextSibling
    }
    return null
}

// `package <name>`.
class GoPackageClause(node: ASTNode) : ASTWrapperPsiElement(node) {
    val nameIdentifier: PsiElement? get() = firstChildOf(GoElementTypes.NAMES)
    val packageName: String? get() = nameIdentifier?.text
}

// Top-level `func [receiver] Name[type params](params)`; result types and body are not part of it.
class GoFunctionHeader(node: ASTNode) : ASTWrapperPsiElement(node) {
    val receiver: GoReceiver? get() = children.firstNotNullOfOrNull { it as? GoReceiver }
    val nameIdentifier: PsiElement? get() = firstChildOf(GoElementTypes.NAMES)
    val functionName: String? get() = nameIdentifier?.text
    val isMethod: Boolean get() = receiver != null

    // Text of the parameter list including parentheses, e.g. "(t *testing.T)".
    val parametersText: String?
        get() {
            var depth = 0
            var element = nameIdentifier?.nextSibling
            while (element != null) {
                when (element.node.elementType) {
                    GoTokenTypes.LBRACKET -> depth++
                    GoTokenTypes.RBRACKET -> depth--
                    GoTokenTypes.LPAREN -> if (depth == 0) return text.substring(element.startOffsetInParent)
                }
                element = element.nextSibling
            }
            return null
        }
}

// `(r *T[K])` of a method.
class GoReceiver(node: ASTNode) : ASTWrapperPsiElement(node) {
    // Base type name without pointer and type arguments: "T" for `(r *T[K])`.
    val typeName: String?
        get() {
            val idents = generateSequence(firstChild) { it.nextSibling }
                .takeWhile { it.node.elementType != GoTokenTypes.LBRACKET }
                .filter { it.node.elementType in GoElementTypes.NAMES }
                .toList()
            return idents.lastOrNull()?.text
        }
}

// `t.Run("name"` with a string-literal subtest name.
class GoSubtestCall(node: ASTNode) : ASTWrapperPsiElement(node) {
    val receiverName: String? get() = firstChildOf(GoElementTypes.NAMES)?.text
    val nameLiteral: PsiElement? get() = firstChildOf(TokenSet.create(GoTokenTypes.STRING, GoTokenTypes.RAW_STRING))
    val subtestName: String? get() = nameLiteral?.text?.let(GoStringLiterals::unquote)
}
