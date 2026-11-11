package io.github.xefise.golsp.lang

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.impl.TemplateContext
import com.intellij.codeInsight.template.impl.TemplateOptionalProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager

/**
 * Surround With in Go: the platform indents lines 2+ of a wrapped selection only by the spaces before `$SELECTION$`,
 * gofmt indents with tabs, so the rest of that prefix is added here.
 */
class GoSelectionIndentProcessor : TemplateOptionalProcessor, DumbAware {
    override fun processText(project: Project, template: Template, document: Document, templateRange: RangeMarker, editor: Editor?) {
        // Called again when the template finishes; the platform's own indent runs only once too.
        if (!templateRange.isValid || templateRange.getUserData(INDENTED) == true) return
        templateRange.putUserData(INDENTED, true)
        if (PsiDocumentManager.getInstance(project).getPsiFile(document)?.language != GoLanguage) return
        val segment = (0 until template.segmentsCount).firstOrNull { template.getSegmentName(it) == Template.SELECTION } ?: return
        val text = template.templateText
        val at = template.getSegmentOffset(segment)
        val prefix = text.substring(text.lastIndexOf('\n', at - 1) + 1, at).trimEnd(' ')
        if (prefix.isEmpty() || prefix.isNotBlank()) return
        val first = document.getLineNumber(templateRange.startOffset) + text.substring(0, at).count { it == '\n' }
        val last = document.getLineNumber(templateRange.endOffset) - text.substring(at).count { it == '\n' }
        for (line in last downTo first + 1) {
            val start = document.getLineStartOffset(line)
            if (start < document.getLineEndOffset(line)) document.insertString(start, prefix)
        }
    }

    override fun getOptionName(): String = "Indent wrapped Go code"

    override fun isEnabled(template: Template): Boolean = true

    override fun isVisible(template: Template, context: TemplateContext): Boolean = false

    private companion object {
        val INDENTED = Key.create<Boolean>("golsp.template.selectionIndented")
    }
}
