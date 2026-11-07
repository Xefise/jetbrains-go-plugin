package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import java.io.File

/** Applies reducer ops the way SMTestRunner would and fails on anything it would reject. */
class TestTreeRecorder {
    class Node(val name: String, val parent: Node?) {
        val children = mutableListOf<Node>()
        var status: String? = null
        var message: String? = null
        val output = StringBuilder()
        val errorOutput = StringBuilder()
    }

    private val root = Node("<root>", null)
    private val nodes = HashMap<Int, Node>()

    fun apply(ops: List<GoTestTreeOp>) = ops.forEach(::apply)

    private fun apply(op: GoTestTreeOp) {
        when (op) {
            is GoTestTreeOp.PackageStarted -> start(op.id, root, op.importPath)
            is GoTestTreeOp.TestStarted -> {
                val parent = nodes[op.parentId] ?: fail("parent ${op.parentId} of ${op.fullName} unknown") as Nothing
                assertNull("parent of ${op.fullName} already finished", parent.status)
                start(op.id, parent, op.name)
            }
            is GoTestTreeOp.Output -> {
                val node = if (op.id == null) root else open(op.id)
                (if (op.error) node.errorOutput else node.output).append(op.text)
            }
            is GoTestTreeOp.Passed -> finish(op.id, "passed", null)
            is GoTestTreeOp.Failed -> finish(op.id, "failed", op.message)
            is GoTestTreeOp.Skipped -> finish(op.id, "skipped", null)
        }
    }

    private fun start(id: Int, parent: Node, name: String) {
        assertNull("id $id reused", nodes[id])
        val node = Node(name, parent)
        parent.children += node
        nodes[id] = node
    }

    private fun open(id: Int): Node {
        val node = nodes[id]
        assertNotNull("node $id unknown", node)
        assertNull("node ${node!!.name} already finished", node.status)
        return node
    }

    private fun finish(id: Int, status: String, message: String?) {
        val node = open(id)
        node.children.forEach { assertNotNull("${it.name} still open when ${node.name} finished", it.status) }
        node.status = status
        node.message = message
    }

    fun assertAllFinished() = nodes.values.forEach { assertNotNull("${it.name} never finished", it.status) }

    /** One line per node, children indented in start order: "name: status". */
    fun render(): String = buildString { root.children.forEach { render(it, 0) } }

    private fun StringBuilder.render(node: Node, depth: Int) {
        append("  ".repeat(depth)).append(node.name).append(": ").append(node.status).append('\n')
        node.children.forEach { render(it, depth + 1) }
    }

    fun node(vararg path: String): Node =
        path.fold(root) { node, name -> node.children.singleOrNull { it.name == name } ?: fail("no $name in ${node.name}") as Nothing }

    val rootOutput: String get() = root.output.toString() + root.errorOutput

    companion object {
        fun replay(fixture: String): TestTreeRecorder {
            val projectDir = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
            val lines = File(projectDir, "src/test/testData/gotest-json/$fixture.jsonl").readLines()
            val reducer = GoTestTreeReducer()
            val recorder = TestTreeRecorder()
            lines.forEach { recorder.apply(reducer.accept(it)) }
            recorder.apply(reducer.finish())
            recorder.assertAllFinished()
            return recorder
        }

        fun assertTree(expected: String, recorder: TestTreeRecorder) = assertEquals(expected.trimIndent() + "\n", recorder.render())
    }
}
