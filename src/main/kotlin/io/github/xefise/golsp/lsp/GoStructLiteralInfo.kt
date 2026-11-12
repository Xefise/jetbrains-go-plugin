package io.github.xefise.golsp.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPSignatureHelperPsiElement
import io.github.xefise.golsp.lang.goTokensBackward
import io.github.xefise.golsp.lsp.model.GoCompositeLiteral
import io.github.xefise.golsp.lsp.model.GoCompositeLiteralSite
import io.github.xefise.golsp.lsp.model.GoStructField
import io.github.xefise.golsp.lsp.model.GoStructFields
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Parameter info owner for `T{…}`; [lbrace] identifies the literal while the hint is open. */
internal class GoStructLiteralElement(file: PsiFile, offset: Int, val lbrace: Int, val fields: List<GoStructField>) :
    LSPSignatureHelperPsiElement(file, TextRange(offset, offset))

/** gopls has no signatureHelp inside composite literals, so the struct fields come from hover on the type name. */
internal object GoStructLiteralInfo {
    private const val HOVER_TIMEOUT_SECONDS = 5L

    fun siteAt(editor: Editor, offset: Int): GoCompositeLiteralSite? {
        val highlighter = (editor as? EditorEx)?.highlighter ?: return null
        return GoCompositeLiteral.find(goTokensBackward(highlighter, offset).asSequence(), editor.document.immutableCharSequence)
    }

    /** Blocks on gopls: background threads only. */
    fun create(editor: Editor, file: PsiFile, offset: Int): GoStructLiteralElement? {
        val site = siteAt(editor, offset) ?: return null
        val fields = fields(editor, file, site)?.takeIf { it.isNotEmpty() } ?: return null
        return GoStructLiteralElement(file, offset, site.lbrace, fields).also { it.activeSignatureHelp = signatureHelp(it.fields, site) }
    }

    fun signatureHelp(fields: List<GoStructField>, site: GoCompositeLiteralSite): SignatureHelp {
        val signature = SignatureInformation(fields.joinToString(", ") { it.label })
        signature.parameters = fields.map { ParameterInformation(it.label) }
        return SignatureHelp(listOf(signature), 0, GoStructFields.activeIndex(fields, site) ?: -1)
    }

    private fun fields(editor: Editor, file: PsiFile, site: GoCompositeLiteralSite): List<GoStructField>? {
        if (ApplicationManager.getApplication().isDispatchThread) return null
        val virtualFile = file.virtualFile ?: return null
        val deadline = GoplsRequests.deadline(HOVER_TIMEOUT_SECONDS)
        val markdown = try {
            val server = GoplsRequests.startedGopls(file.project, deadline) ?: return null
            val position = LSPIJUtils.toPosition(site.typeNameStart, editor.document)
            val params = HoverParams(TextDocumentIdentifier(LSPIJUtils.toUriAsString(virtualFile)), position)
            GoplsRequests.await(server.textDocumentService.hover(params), deadline)?.contents?.let(::text)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            logger<GoStructLiteralInfo>().debug("gopls hover for struct fields failed", e)
            null
        } ?: return null
        val fields = GoStructFields.parse(markdown) ?: return null
        // Unexported fields of another package's struct cannot be set in a literal.
        return if (site.qualified) fields.filter { it.exported } else fields
    }

    private fun text(contents: Either<List<Either<String, MarkedString>>, MarkupContent>): String? =
        if (contents.isRight) contents.right.value else null
}
