package io.github.xefise.golsp.test

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.xefise.golsp.lang.GoElementTypes
import io.github.xefise.golsp.lang.GoSubtestCall
import io.github.xefise.golsp.lang.GoTokenTypes
import io.github.xefise.golsp.test.model.GoTestKind
import io.github.xefise.golsp.test.model.GoTestRunPattern

/**
 * Finds literal `t.Run("name", …)` subtests in one test function body by bracket depth. `t` must be a `*testing.T`/`B`
 * parameter of the function or of a function literal seen before. A subtest whose path go would print differently
 * (non-literal, empty or repeated name) is a dynamic frame: neither it nor anything nested in it gets a declaration.
 */
internal class GoSubtestScanner(
    private val functionName: String,
    private val kind: GoTestKind,
    initialReceivers: Set<String>,
    private val result: MutableList<GoTestDeclaration>,
) {
    // [path] is null for a dynamic frame.
    private class Frame(val path: String?, val openDepth: Int, val start: Int, val anchor: PsiElement?)

    private val receivers = initialReceivers.toMutableSet()
    private val stack = ArrayDeque<Frame>()
    private val recent = ArrayDeque<PsiElement>()
    // go dedupes by the printed name within a parent and names a repeat "x#01".
    private val seen = mutableMapOf<String, MutableSet<String>>()
    private var depth = 0

    fun scan(body: List<PsiElement>) {
        for (element in body) {
            if (element is PsiWhiteSpace || element is PsiComment) continue
            when {
                element is GoSubtestCall -> {
                    depth++
                    if (isReceiver(element.receiverName, GoTestDeclarations.previousSignificant(element))) open(element)
                }
                element.node.elementType == GoTokenTypes.LPAREN -> {
                    depth++
                    if (isDynamicRun()) stack.addLast(Frame(null, depth, element.textRange.startOffset, null))
                }
                else -> {
                    if (element.node.elementType == GoTokenTypes.IDENTIFIER) collectLiteralParameter(element)
                    depth += GoTestDeclarations.bracketDelta(element)
                    while (stack.isNotEmpty() && stack.last().openDepth > depth) close(stack.removeLast(), element.textRange.endOffset)
                }
            }
            recent.addLast(element)
            if (recent.size > WINDOW) recent.removeFirst()
        }
        val end = body.lastOrNull()?.textRange?.endOffset ?: return
        while (stack.isNotEmpty()) close(stack.removeLast(), end)
    }

    private fun open(call: GoSubtestCall) {
        val parent = stack.lastOrNull()
        val parentPath = if (parent == null) functionName else parent.path
        // The literal must be the whole argument: `t.Run("a"+x, …)` is dynamic.
        val closesArgument = GoTestDeclarations.nextSignificant(call)?.node?.elementType.let {
            it == GoTokenTypes.COMMA || it == GoTokenTypes.RPAREN
        }
        val name = call.subtestName?.takeIf { closesArgument && it.isNotEmpty() }
        val path = if (parentPath == null || name == null || !seen.getOrPut(parentPath) { mutableSetOf() }.add(GoTestRunPattern.rewrite(name))) {
            null
        } else {
            "$parentPath/$name"
        }
        stack.addLast(Frame(path, depth, call.textRange.startOffset, call.nameLiteral))
    }

    private fun close(frame: Frame, end: Int) {
        val path = frame.path ?: return
        val anchor = frame.anchor ?: return
        result += GoTestDeclaration(path, kind, anchor, TextRange(frame.start, end))
    }

    // `s.t.Run` is a field of something else, not the parameter in scope.
    private fun isReceiver(name: String?, before: PsiElement?): Boolean =
        name != null && name in receivers && before?.node?.elementType != GoTokenTypes.DOT

    // `t.Run(` with a non-literal name: the last three significant elements are `t`, `.`, `Run`.
    private fun isDynamicRun(): Boolean {
        if (recent.size < 3) return false
        val tail = recent.toList().takeLast(4)
        val (receiver, dot, run) = tail.takeLast(3)
        val before = if (tail.size == 4) tail[0] else null
        return receiver.node.elementType in GoElementTypes.NAMES && isReceiver(receiver.text, before) &&
            dot.node.elementType == GoTokenTypes.DOT && run.text == "Run"
    }

    // `(t *testing.T` or `, t *T` inside the body: a function literal parameter usable as a subtest receiver.
    private fun collectLiteralParameter(typeName: PsiElement) {
        if (typeName.text != "T" && typeName.text != "B") return
        val tokens = recent.toList()
        val qualified = tokens.size >= 5 && tokens[tokens.size - 1].node.elementType == GoTokenTypes.DOT
        val starAt = if (qualified) tokens.size - 3 else tokens.size - 1
        if (starAt < 2 || tokens[starAt].text != "*") return
        val name = tokens[starAt - 1]
        val opener = tokens[starAt - 2].node.elementType
        if (name.node.elementType in GoElementTypes.NAMES && (opener == GoTokenTypes.LPAREN || opener == GoTokenTypes.COMMA)) {
            receivers += name.text
        }
    }

    private companion object {
        const val WINDOW = 6
    }
}
