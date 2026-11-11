package io.github.xefise.golsp.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.xefise.golsp.lang.model.GoPostfixExpression
import io.github.xefise.golsp.lang.model.GoPostfixOperand
import io.github.xefise.golsp.lang.model.GoPostfixToken
import io.github.xefise.golsp.lang.model.GoPostfixTokenKind

/** GoLand-style postfix templates on lexer tokens; gopls' own postfix items end with `!` and never share a key. */
class GoPostfixTemplateProvider : PostfixTemplateProvider, DumbAware {
    private val templates: Set<PostfixTemplate> = linkedSetOf(
        GoStatementPostfixTemplate(this, "if", "if expr {}") { b -> b.text("if ").expr().text(" {").body() },
        GoStatementPostfixTemplate(this, "nn", "if expr != nil {}") { b -> b.text("if ").expr().text(" != nil {").body() },
        GoStatementPostfixTemplate(this, "nil", "if expr == nil {}") { b -> b.text("if ").expr().text(" == nil {").body() },
        GoStatementPostfixTemplate(this, "err", "if expr != nil { return }") { b ->
            b.text("if ").expr().text(" != nil {").newline(1).text("return ").end().newline(0).text("}")
        },
        GoStatementPostfixTemplate(this, "return", "return expr") { b -> b.text("return ").expr().end() },
        GoStatementPostfixTemplate(this, "go", "go expr", callOnly = true) { b -> b.text("go ").expr().end() },
        GoStatementPostfixTemplate(this, "defer", "defer expr", callOnly = true) { b -> b.text("defer ").expr().end() },
        GoStatementPostfixTemplate(this, "for", "for _, v := range expr {}") { b ->
            b.text("for _, ").variable("VALUE", "v").text(" := range ").expr().text(" {").body()
        },
        GoStatementPostfixTemplate(this, "var", "name := expr") { b -> b.variable("NAME", "v").text(" := ").expr().end() },
        GoExpressionPostfixTemplate(this, "not", "!expr") { expr -> if (expr.startsWith("!")) expr.substring(1) else "!$expr" },
        GoExpressionPostfixTemplate(this, "par", "(expr)") { expr -> "($expr)" },
    )

    override fun getId(): String = "golsp.go.postfix"

    override fun getTemplates(): Set<PostfixTemplate> = templates

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) = Unit

    override fun afterExpand(file: PsiFile, editor: Editor) = Unit

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}

abstract class GoPostfixTemplate(provider: PostfixTemplateProvider, name: String, example: String) :
    PostfixTemplate("golsp.go.postfix.$name", name, example, provider), DumbAware {

    override fun calcDescription(): String = "Expands <code>expr.$presentableName</code> into <code>$example</code>."

    override fun isEditable(): Boolean = false

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        operandAt(context.containingFile, newOffset)?.let(::accepts) == true

    override fun expand(context: PsiElement, editor: Editor) {
        val offset = editor.caretModel.offset
        val operand = operandAt(context.containingFile, offset)?.takeIf(::accepts) ?: return
        expand(operand, editor)
    }

    protected abstract fun accepts(operand: GoPostfixOperand): Boolean

    protected abstract fun expand(operand: GoPostfixOperand, editor: Editor)
}

private class GoExpressionPostfixTemplate(
    provider: PostfixTemplateProvider,
    name: String,
    example: String,
    private val transform: (String) -> String,
) : GoPostfixTemplate(provider, name, example) {
    override fun accepts(operand: GoPostfixOperand): Boolean = true

    override fun expand(operand: GoPostfixOperand, editor: Editor) {
        val document = editor.document
        val replacement = transform(document.charsSequence.substring(operand.start, operand.end))
        document.replaceString(operand.start, operand.end, replacement)
        editor.caretModel.moveToOffset(operand.start + replacement.length)
    }
}

private class GoStatementPostfixTemplate(
    provider: PostfixTemplateProvider,
    name: String,
    example: String,
    private val callOnly: Boolean = false,
    private val build: (GoTemplateBuilder) -> Unit,
) : GoPostfixTemplate(provider, name, example) {
    override fun accepts(operand: GoPostfixOperand): Boolean = operand.isStatement && (!callOnly || operand.isCall)

    override fun expand(operand: GoPostfixOperand, editor: Editor) {
        val project = editor.project ?: return
        val document = editor.document
        val text = document.charsSequence
        val expr = text.substring(operand.start, operand.end)
        val lineStart = document.getLineStartOffset(document.getLineNumber(operand.start))
        val indent = text.substring(lineStart, operand.start).takeWhile { it == ' ' || it == '\t' }
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.isToReformat = false
        template.setToIndent(false)
        build(GoTemplateBuilder(template, expr, indent, indentUnit(project, document)))
        document.deleteString(operand.start, operand.end)
        editor.caretModel.moveToOffset(operand.start)
        TemplateManager.getInstance(project).startTemplate(editor, template)
    }

    private fun indentUnit(project: Project, document: Document): String {
        val options = CodeStyle.getIndentOptions(project, document)
        return if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE.coerceAtLeast(1))
    }
}

private class GoTemplateBuilder(
    private val template: Template,
    private val expr: String,
    private val indent: String,
    private val unit: String,
) {
    fun text(value: String) = apply { template.addTextSegment(value) }

    fun expr() = text(expr)

    fun end() = apply { template.addEndVariable() }

    fun newline(depth: Int) = text("\n" + indent + unit.repeat(depth))

    fun body() = newline(1).end().newline(0).text("}")

    fun variable(name: String, default: String) = apply {
        template.addVariable(name, "", "\"$default\"", true)
        template.addVariableSegment(name)
    }
}

private fun operandAt(file: PsiFile, offset: Int): GoPostfixOperand? {
    if (offset <= 0) return null
    val text = file.viewProvider.contents
    val last = file.findElementAt(offset - 1) ?: return null
    val backward = generateSequence(last) { PsiTreeUtil.prevLeaf(it) }
        .map { GoPostfixToken(postfixKind(it.node.elementType), it.textRange.startOffset, it.textRange.endOffset) }
    return GoPostfixExpression.find(text, offset, backward)
}

internal fun postfixKind(type: IElementType): GoPostfixTokenKind = when (type) {
    TokenType.WHITE_SPACE -> GoPostfixTokenKind.SPACE
    GoTokenTypes.LINE_COMMENT, GoTokenTypes.BLOCK_COMMENT -> GoPostfixTokenKind.COMMENT
    GoTokenTypes.STRING, GoTokenTypes.RAW_STRING, GoTokenTypes.CHAR -> GoPostfixTokenKind.STRING
    GoTokenTypes.NUMBER -> GoPostfixTokenKind.NUMBER
    GoTokenTypes.IDENTIFIER, GoTokenTypes.BUILTIN, GoTokenTypes.CONSTANT -> GoPostfixTokenKind.NAME
    GoTokenTypes.KEYWORD -> GoPostfixTokenKind.KEYWORD
    GoTokenTypes.DOT -> GoPostfixTokenKind.DOT
    GoTokenTypes.LPAREN -> GoPostfixTokenKind.LPAREN
    GoTokenTypes.RPAREN -> GoPostfixTokenKind.RPAREN
    GoTokenTypes.LBRACKET -> GoPostfixTokenKind.LBRACKET
    GoTokenTypes.RBRACKET -> GoPostfixTokenKind.RBRACKET
    GoTokenTypes.LBRACE -> GoPostfixTokenKind.LBRACE
    GoTokenTypes.RBRACE -> GoPostfixTokenKind.RBRACE
    GoTokenTypes.OPERATOR -> GoPostfixTokenKind.OPERATOR
    GoTokenTypes.COMMA -> GoPostfixTokenKind.COMMA
    GoTokenTypes.SEMICOLON -> GoPostfixTokenKind.SEMICOLON
    else -> GoPostfixTokenKind.OTHER
}
