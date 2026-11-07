package io.github.xefise.golsp.test.runner

import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.events.TreeNodeEvent
import com.intellij.openapi.util.Key
import io.github.xefise.golsp.test.model.GoDelveTestStream
import io.github.xefise.golsp.test.model.GoTestNodeEvent
import io.github.xefise.golsp.test.model.GoTestNodeEventMapper
import io.github.xefise.golsp.test.model.GoTestTreeReducer
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

/**
 * Turns `go test -json` stdout into an id-based SMTestRunner tree: packages are suites, tests (parents included) are
 * tests, and every message carries `nodeId` so interleaved parallel tests and packages land on their own nodes.
 * Requires [com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties.isIdBasedTestTree].
 *
 * With [test2JsonPackage] the input is a `dlv dap` process debugging that package's tests instead, see
 * [GoDelveTestStream]; the import path is resolved on the output reader thread, never on EDT.
 */
class GoTestEventsConverter(testFrameworkName: String, test2JsonPackage: (() -> String)? = null) :
    // Buffer until newline: a JSON event split across reads must reach the reducer whole. Attribute validation is off
    // as for any id-based tree: messages carry nodeId and an empty name.
    OutputToGeneralTestEventsConverter(testFrameworkName, false, false, false) {

    private val reducer = GoTestTreeReducer()
    private val mapper = GoTestNodeEventMapper()

    // stdout and stderr are read on different threads; the reducer, the mapper and event order need one writer.
    private val lock = Any()
    private var lastVisitor: ServiceMessageVisitor? = null
    private var reporterAttached = false

    // Its callbacks run inside write/finish, i.e. under the lock.
    private val delve = test2JsonPackage?.let { pkg ->
        GoDelveTestStream(
            pkg,
            events = { line -> lastVisitor?.let { acceptEvent(line, it) } },
            dlvOutput = { text, error -> lastVisitor?.let { send(GoTestNodeEvent.Output(null, text, error), it) } },
        )
    }

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean =
        synchronized(lock) {
            val stdout = ProcessOutputType.isStdout(outputType)
            if (!stdout && !ProcessOutputType.isStderr(outputType)) return false
            when {
                delve != null -> {
                    lastVisitor = visitor
                    delve.write(text, error = !stdout)
                }
                stdout -> {
                    lastVisitor = visitor
                    acceptEvent(text, visitor)
                }
                // Text of the go command itself; the active node of a parallel run would be an arbitrary test.
                else -> send(GoTestNodeEvent.Output(null, text, error = true), visitor)
            }
            true
        }

    private fun acceptEvent(line: String, visitor: ServiceMessageVisitor) {
        if (!reporterAttached && looksLikeEvent(line)) {
            reporterAttached = true
            // A package reports wall-clock Elapsed; summing its parallel tests would overstate it.
            send(ServiceMessageBuilder.testsStarted().addAttribute("durationStrategy", "manual"), visitor)
        }
        mapper.map(reducer.accept(line)).forEach { send(it, visitor) }
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        super.flushBufferOnProcessTermination(exitCode)
        synchronized(lock) {
            // No visitor means no stdout line was ever seen, so the reducer has nothing to close.
            val visitor = lastVisitor ?: return
            delve?.finish()
            mapper.mapAfterExit(reducer.finish()).forEach { send(it, visitor) }
        }
    }

    // The reducer parses the line; this only decides when the framework counts as attached.
    private fun looksLikeEvent(text: String): Boolean = text.trimStart().startsWith("{") && text.contains("\"Action\"")

    private fun send(event: GoTestNodeEvent, visitor: ServiceMessageVisitor) {
        val message = when (event) {
            is GoTestNodeEvent.SuiteStarted -> ServiceMessageBuilder.testSuiteStarted(event.name)
                .addAttribute(NODE_ID, event.id)
                .addAttribute(PARENT_NODE_ID, TreeNodeEvent.ROOT_NODE_ID)
                .addAttribute(LOCATION, event.locationUrl)
            is GoTestNodeEvent.TestStarted -> ServiceMessageBuilder.testStarted(event.name)
                .addAttribute(NODE_ID, event.id)
                .addAttribute(PARENT_NODE_ID, event.parentId)
                .addAttribute(LOCATION, event.locationUrl)
            is GoTestNodeEvent.Output -> (if (event.error) ServiceMessageBuilder.testStdErr("") else ServiceMessageBuilder.testStdOut(""))
                .addAttribute(NODE_ID, event.id ?: TreeNodeEvent.ROOT_NODE_ID)
                .addAttribute("out", event.text)
            // In the id-based tree testFinished closes suites too, with their state computed from the children.
            is GoTestNodeEvent.Finished -> ServiceMessageBuilder.testFinished("").addAttribute(NODE_ID, event.id)
                .withDuration(event.durationMs)
            is GoTestNodeEvent.Failed -> ServiceMessageBuilder.testFailed("").addAttribute(NODE_ID, event.id)
                .addAttribute("message", event.message)
                .withDuration(event.durationMs)
            is GoTestNodeEvent.Ignored -> ServiceMessageBuilder.testIgnored("").addAttribute(NODE_ID, event.id)
                .addAttribute("message", "")
        }
        send(message, visitor)
    }

    private fun send(message: ServiceMessageBuilder, visitor: ServiceMessageVisitor) {
        super.processServiceMessages(message.toString(), ProcessOutputType.STDOUT, visitor)
    }

    private fun ServiceMessageBuilder.withDuration(durationMs: Long?): ServiceMessageBuilder =
        if (durationMs == null) this else addAttribute("duration", durationMs.toString())

    private companion object {
        const val NODE_ID = "nodeId"
        const val PARENT_NODE_ID = "parentNodeId"
        const val LOCATION = "locationHint"    }
}
