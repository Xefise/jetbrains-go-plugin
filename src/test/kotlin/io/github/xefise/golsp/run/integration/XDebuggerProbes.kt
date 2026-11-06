package io.github.xefise.golsp.run.integration

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueContainer
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.util.concurrent.CompletableFuture
import javax.swing.Icon

/** Callback-to-future adapters over the public XDebugger frame API, so tests can wait on them with a timeout. */
object XDebuggerProbes {
    data class Value(val name: String, val type: String?, val text: String, val xValue: XValue)

    /** Values of [container] plus the values of its groups (DAP scopes such as "Locals"), flattened. */
    fun children(container: XValueContainer): CompletableFuture<List<Value>> = ownChildren(container).thenCompose { (values, groups) ->
        val nested = groups.map { children(it) }
        CompletableFuture.allOf(*nested.toTypedArray()).thenApply { values + nested.flatMap { it.join() } }
    }

    private fun ownChildren(container: XValueContainer): CompletableFuture<Pair<List<Value>, List<XValueGroup>>> {
        val result = CompletableFuture<Pair<List<Value>, List<XValueGroup>>>()
        val collected = mutableListOf<Pair<String, XValue>>()
        val groups = mutableListOf<XValueGroup>()
        container.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                synchronized(collected) {
                    (0 until children.size()).forEach { collected += children.getName(it) to children.getValue(it) }
                    groups += children.topGroups
                    groups += children.bottomGroups
                }
                if (last) {
                    val values = synchronized(collected) { collected.toList() }
                    val rendered = values.map { (_, v) -> presentation(v) }
                    CompletableFuture.allOf(*rendered.toTypedArray()).thenRun {
                        val own = values.zip(rendered) { (name, v), p -> p.join().let { Value(name, it.first, it.second, v) } }
                        result.complete(own to synchronized(collected) { groups.toList() })
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun tooManyChildren(remaining: Int) = Unit
            override fun setAlreadySorted(alreadySorted: Boolean) = Unit
            override fun setErrorMessage(errorMessage: String) {
                result.completeExceptionally(AssertionError(errorMessage))
            }

            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) =
                setErrorMessage(errorMessage)

            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: XDebuggerTreeNodeHyperlink?,
            ) = Unit

            override fun isObsolete(): Boolean = false
        })
        return result
    }

    /** (type, value text) as the Variables view would render them. */
    fun presentation(value: XValue): CompletableFuture<Pair<String?, String>> {
        val result = CompletableFuture<Pair<String?, String>>()
        value.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                result.complete(type to value)
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                result.complete(presentation.type to render(presentation))
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit
            override fun isObsolete(): Boolean = false
        }, XValuePlace.TREE)
        return result
    }

    fun frames(stack: XExecutionStack): CompletableFuture<List<XStackFrame>> {
        val result = CompletableFuture<List<XStackFrame>>()
        val frames = mutableListOf<XStackFrame>()
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                synchronized(frames) {
                    frames += stackFrames
                    if (last) result.complete(frames.toList())
                }
            }

            override fun errorOccurred(errorMessage: String) {
                result.completeExceptionally(AssertionError(errorMessage))
            }

            override fun isObsolete(): Boolean = false
        })
        return result
    }

    fun stacks(context: XSuspendContext): CompletableFuture<List<XExecutionStack>> {
        val result = CompletableFuture<List<XExecutionStack>>()
        val stacks = mutableListOf<XExecutionStack>()
        context.computeExecutionStacks(object : XSuspendContext.XExecutionStackContainer {
            override fun addExecutionStack(executionStacks: List<XExecutionStack>, last: Boolean) {
                synchronized(stacks) {
                    stacks += executionStacks
                    if (last) result.complete(stacks.toList())
                }
            }

            override fun errorOccurred(errorMessage: String) {
                result.completeExceptionally(AssertionError(errorMessage))
            }

            override fun isObsolete(): Boolean = false
        })
        return result
    }

    fun frameText(frame: XStackFrame): String = SimpleColoredText().also { frame.customizePresentation(it) }.toString()

    private fun render(presentation: XValuePresentation): String {
        val text = StringBuilder()
        presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) {
                text.append(value)
            }

            override fun renderValue(value: String, key: TextAttributesKey) {
                text.append(value)
            }

            override fun renderStringValue(value: String) {
                text.append('"').append(value).append('"')
            }

            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) =
                renderStringValue(value)

            override fun renderNumericValue(value: String) {
                text.append(value)
            }

            override fun renderKeywordValue(value: String) {
                text.append(value)
            }

            override fun renderComment(comment: String) {
                text.append(comment)
            }

            override fun renderSpecialSymbol(symbol: String) {
                text.append(symbol)
            }

            override fun renderError(error: String) {
                text.append(error)
            }
        })
        return text.toString()
    }
}
