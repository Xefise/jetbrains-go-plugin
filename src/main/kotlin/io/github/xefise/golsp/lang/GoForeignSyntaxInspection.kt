package io.github.xefise.golsp.lang

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lang.model.GoForeignSyntax
import io.github.xefise.golsp.lang.model.GoIndentToken

class GoForeignSyntaxInspection : LocalInspectionTool(), DumbAware {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.language != GoLanguage) return null
        val text = file.viewProvider.contents
        return GoForeignSyntax.find(text, tokens(text)).map { problem ->
            val fixes = problem.fixes.map { GoReplaceTextFix(it.name, it.start, it.end, text.subSequence(it.start, it.end).toString(), it.replacement) }
            manager.createProblemDescriptor(
                file, TextRange(problem.start, problem.end), problem.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly, *fixes.toTypedArray(),
            )
        }.toTypedArray()
    }

    private fun tokens(text: CharSequence): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<GoIndentToken>()
        while (true) {
            val type = lexer.tokenType ?: break
            result += GoIndentToken(goIndentKind(type), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }
}

// File offsets: the descriptor is on the whole file.
private class GoReplaceTextFix(
    private val name: String,
    private val start: Int,
    private val end: Int,
    private val original: String,
    private val replacement: String,
) : LocalQuickFix {
    override fun getName(): String = name

    override fun getFamilyName(): String = "Rewrite in Go syntax"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        if (!matches(document.charsSequence)) return
        document.replaceString(start, end, replacement)
    }

    // The default preview edits a PSI copy; a plain document edit on that copy never reaches the diff.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val file = previewDescriptor.psiElement?.containingFile ?: return IntentionPreviewInfo.EMPTY
        val text = file.text
        if (!matches(text)) return IntentionPreviewInfo.EMPTY
        return IntentionPreviewInfo.CustomDiff(file.fileType, file.name, text, text.replaceRange(start, end, replacement))
    }

    private fun matches(text: CharSequence): Boolean = end <= text.length && text.subSequence(start, end).toString() == original
}
