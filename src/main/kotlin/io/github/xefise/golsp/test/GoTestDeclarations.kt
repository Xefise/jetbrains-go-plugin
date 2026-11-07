package io.github.xefise.golsp.test

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.xefise.golsp.lang.GoElementTypes
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoFunctionHeader
import io.github.xefise.golsp.lang.GoTokenTypes
import io.github.xefise.golsp.test.model.GoTestKind
import io.github.xefise.golsp.test.model.GoTestNames
import io.github.xefise.golsp.test.model.GoTestSource

/**
 * A runnable test: a top-level test function or a literal `t.Run` subtest. [fullName] is the path as written in the
 * source ("TestX/sub case"); [anchor] is the leaf that carries the gutter mark; [range] spans header and body or the
 * whole `t.Run(...)` call.
 */
class GoTestDeclaration(val fullName: String, val kind: GoTestKind, val anchor: PsiElement, val range: TextRange)

// Test discovery on the flat PSI only: no indexes, no `go` processes.
object GoTestDeclarations {
    private class Index(val all: List<GoTestDeclaration>) {
        val byAnchor: Map<PsiElement, GoTestDeclaration> = all.associateBy { it.anchor }
    }

    /** A `_test.go` file go builds (not `_x_test.go` / `.x_test.go`), in content, outside testdata and vendor. */
    fun isTestFileInContent(file: GoFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        val name = virtualFile.name
        if (!file.isTestFile() || name.startsWith("_") || name.startsWith(".")) return false
        val index = ProjectFileIndex.getInstance(file.project)
        if (!index.isInContent(virtualFile)) return false
        val root = index.getContentRootForFile(virtualFile)
        return generateSequence(virtualFile.parent) { it.parent }
            .takeWhile { it != root }
            .none { it.name == "testdata" || it.name == "vendor" }
    }

    fun of(file: GoFile): List<GoTestDeclaration> = index(file).all

    fun at(anchor: PsiElement): GoTestDeclaration? {
        val file = anchor.containingFile as? GoFile ?: return null
        return index(file).byAnchor[anchor]
    }

    // The innermost test whose range holds [element]: a subtest wins over its parent.
    fun enclosing(element: PsiElement): GoTestDeclaration? {
        val file = element.containingFile as? GoFile ?: return null
        val offset = element.textRange?.startOffset ?: return null
        return of(file).filter { it.range.containsOffset(offset) }.maxByOrNull { it.range.startOffset }
    }

    private fun index(file: GoFile): Index =
        CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(Index(if (file.isTestFile()) collect(file) else emptyList()), file)
        }

    private fun collect(file: GoFile): List<GoTestDeclaration> {
        val result = mutableListOf<GoTestDeclaration>()
        val headers = file.functionHeaders
        headers.forEachIndexed { index, header ->
            val kind = kindOf(header) ?: return@forEachIndexed
            val name = header.functionName ?: return@forEachIndexed
            val anchor = header.nameIdentifier ?: return@forEachIndexed
            val limit = headers.getOrNull(index + 1)?.textRange?.startOffset ?: file.textLength
            val body = bodyAfter(header, limit) ?: return@forEachIndexed
            if (kind == GoTestKind.EXAMPLE && !hasOutputComment(body)) return@forEachIndexed
            result += GoTestDeclaration(name, kind, anchor, TextRange(header.textRange.startOffset, body.last().textRange.endOffset))
            // Fuzz subtests run under generated seed names ("FuzzX/seed#0/sub"); their path is unknown here.
            if (kind == GoTestKind.TEST || kind == GoTestKind.BENCHMARK) {
                val type = GoTestSource.parameterType(kind)!!
                val receiver = header.parametersText?.let { GoTestSource.parameterName(it, type) }
                GoSubtestScanner(name, kind, setOfNotNull(receiver), result).scan(body)
            }
        }
        return result
    }

    // cmd/go: no receiver, no type parameters, exactly the signature of its kind; `TestMain(*T)` is a plain test.
    private fun kindOf(header: GoFunctionHeader): GoTestKind? {
        if (header.isMethod) return null
        val name = header.nameIdentifier ?: return null
        if (nextSignificant(name)?.node?.elementType != GoTokenTypes.LPAREN) return null
        val parameters = header.parametersText ?: return null
        val kind = GoTestNames.kindOf(name.text)
            ?: GoTestKind.TEST.takeIf { name.text == "TestMain" && GoTestSource.hasSignature(parameters, "T") }
            ?: return null
        return kind.takeIf { GoTestSource.hasSignature(parameters, GoTestSource.parameterType(it)) }
    }

    // Top-level elements from the body's `{` through its matching `}` (or up to [limit] if it is never closed);
    // null if anything but the body follows the parameters, i.e. the function has results.
    private fun bodyAfter(header: GoFunctionHeader, limit: Int): List<PsiElement>? {
        val open = nextSignificant(header)
        if (open == null || open.node.elementType != GoTokenTypes.LBRACE || open.textRange.startOffset >= limit) return null
        val body = mutableListOf<PsiElement>(open)
        var depth = 1
        var element = open.nextSibling
        while (element != null && depth > 0 && element.textRange.startOffset < limit) {
            body += element
            depth += bracketDelta(element)
            element = element.nextSibling
        }
        return body
    }

    // go/doc runs an example only if the last comment group in its body starts with "Output:".
    private fun hasOutputComment(body: List<PsiElement>): Boolean {
        var group = mutableListOf<String>()
        var lastGroup: List<String> = emptyList()
        var lastLine = -1
        var trailing = false
        var line = 0
        var codeOnLine = false
        for (element in body) {
            when (element) {
                is PsiComment -> {
                    // A comment after code on the same line is a group of its own, as in go/parser.
                    val joins = group.isNotEmpty() && !codeOnLine && !trailing && line <= lastLine + 1
                    if (!joins) {
                        group = mutableListOf()
                        trailing = codeOnLine
                    }
                    group += element.text
                    lastGroup = group
                    line += element.text.count { it == '\n' }
                    lastLine = line
                }
                is PsiWhiteSpace -> {
                    val newlines = element.text.count { it == '\n' }
                    line += newlines
                    if (newlines > 0) codeOnLine = false
                }
                else -> {
                    codeOnLine = true
                    group = mutableListOf()
                    line += element.text.count { it == '\n' }
                }
            }
        }
        return GoTestSource.isOutputGroup(lastGroup)
    }

    fun nextSignificant(element: PsiElement): PsiElement? =
        generateSequence(element.nextSibling) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }

    fun previousSignificant(element: PsiElement): PsiElement? =
        generateSequence(element.prevSibling) { it.prevSibling }.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }

    fun bracketDelta(element: PsiElement): Int = when (element.node.elementType) {
        // A subtest call holds the `(` it opens.
        GoTokenTypes.LPAREN, GoTokenTypes.LBRACE, GoTokenTypes.LBRACKET, GoElementTypes.SUBTEST_CALL -> 1
        GoTokenTypes.RPAREN, GoTokenTypes.RBRACE, GoTokenTypes.RBRACKET -> -1
        else -> 0
    }
}
