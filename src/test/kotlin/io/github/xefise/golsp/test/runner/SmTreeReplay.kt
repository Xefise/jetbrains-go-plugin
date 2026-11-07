package io.github.xefise.golsp.test.runner

import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.testframework.Printable
import com.intellij.execution.testframework.Printer
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File

/** Drives [GoTestEventsConverter] into the real id-based SMTestRunner tree, as `SMTestRunnerConnectionUtil` wires it. */
class SmTreeReplay(project: Project, private val converter: GoTestEventsConverter = GoTestEventsConverter(FRAMEWORK)) {
    val root = SMTestProxy.SMRootTestProxy()
    val processor = GeneralIdBasedToSMTRunnerEventsConvertor(project, root, FRAMEWORK)

    init {
        converter.setProcessor(processor)
        converter.startTesting()
    }

    fun stdout(text: String) = converter.process(text, ProcessOutputType.STDOUT)

    fun stderr(text: String) = converter.process(text, ProcessOutputType.STDERR)

    fun terminate(exitCode: Int = 1): SMTestProxy.SMRootTestProxy {
        converter.flushBufferOnProcessTermination(exitCode)
        converter.finishTesting()
        return root
    }

    companion object {
        const val FRAMEWORK = "gotest"

        fun fixture(name: String): List<String> {
            val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
            return File(projectDir, "src/test/testData/gotest-json/$name.jsonl").readLines()
        }

        fun replay(project: Project, fixture: String): SMTestProxy.SMRootTestProxy {
            val replay = SmTreeReplay(project)
            fixture(fixture).forEach { replay.stdout("$it\n") }
            return replay.terminate()
        }

        /** One line per node, children indented: "name: status". */
        fun render(root: SMTestProxy): String = buildString { root.children.forEach { render(it, 0) } }

        private fun StringBuilder.render(node: SMTestProxy, depth: Int) {
            append("  ".repeat(depth)).append(node.name).append(": ").append(status(node)).append('\n')
            node.children.forEach { render(it, depth + 1) }
        }

        fun status(node: SMTestProxy): String = when {
            node.wasTerminated() -> "terminated"
            node.isInProgress -> "running"
            node.isIgnored -> "skipped"
            node.isDefect -> "failed"
            node.isPassed -> "passed"
            else -> node.magnitudeInfo.name
        }

        fun node(root: SMTestProxy, vararg path: String): SMTestProxy = path.fold(root) { node, name ->
            node.children.singleOrNull { it.name == name } ?: throw AssertionError("no $name in ${node.name}: ${node.children.map { it.name }}")
        }

        /** Output the node itself received (children excluded), by stream. */
        fun output(node: SMTestProxy, type: Key<*>): String {
            val recorder = OutputRecorder()
            node.printOwnPrintablesOn(recorder)
            return recorder.text(type)
        }
    }

    private class OutputRecorder : Printer {
        private val chunks = mutableListOf<Pair<Key<*>, String>>()

        fun text(type: Key<*>): String = chunks.filter { it.first == type }.joinToString("") { it.second }

        override fun printWithAnsiColoring(text: String, processOutputType: Key<*>) {
            chunks += processOutputType to text
        }

        // Failure messages and other state text, not process output.
        override fun print(text: String, contentType: ConsoleViewContentType) = Unit
        override fun onNewAvailable(printable: Printable) = printable.printOn(this)
        override fun printHyperlink(text: String, info: HyperlinkInfo?) = Unit
        override fun mark() = Unit
    }
}
