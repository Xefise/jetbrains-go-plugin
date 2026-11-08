package io.github.xefise.golsp.debug.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DelveAttachBuilderTest {
    @Test
    fun `remote attach without mappings sends no substitutePath`() {
        assertEquals(
            mapOf("type" to "go", "request" to "attach", "mode" to "remote"),
            DelveAttachBuilder.attachParameters(DelveAttachTarget.Remote("localhost", 2345)),
        )
    }

    @Test
    fun `remote attach keeps mapping order and empty sides`() {
        val target = DelveAttachTarget.Remote(
            "10.0.0.5",
            40000,
            listOf(SubstitutePathRule("/home/me/app", "/app"), SubstitutePathRule("", "/go/src"), SubstitutePathRule("/home/me", "/")),
        )
        val json = Gson().toJson(DelveAttachBuilder.attachParameters(target))
        assertEquals(
            """{"type":"go","request":"attach","mode":"remote","substitutePath":[""" +
                """{"from":"/home/me/app","to":"/app"},{"from":"","to":"/go/src"},{"from":"/home/me","to":"/"}]}""",
            json,
        )
    }

    @Test
    fun `local attach sends the process id and no remote keys`() {
        val params = DelveAttachBuilder.attachParameters(DelveAttachTarget.Local(51234, 4242))
        assertEquals(mapOf("type" to "go", "request" to "attach", "mode" to "local", "processId" to 4242), params)
        assertFalse(params.containsKey("substitutePath"))
    }

    @Test
    fun `local target listens on loopback`() {
        assertEquals("127.0.0.1", DelveAttachTarget.Local(1, 2).host)
    }

    @Test
    fun `port bounds`() {
        assertNotNull(DelveAttachBuilder.portError(0))
        assertNull(DelveAttachBuilder.portError(1))
        assertNull(DelveAttachBuilder.portError(65535))
        assertNotNull(DelveAttachBuilder.portError(65536))
        assertNotNull(DelveAttachBuilder.portError(-1))
    }

    @Test
    fun `blank host is an error`() {
        assertNotNull(DelveAttachBuilder.hostError(""))
        assertNotNull(DelveAttachBuilder.hostError("  "))
        assertNull(DelveAttachBuilder.hostError("::1"))
    }

    @Test
    fun `a mapping needs at least one side`() {
        assertNull(DelveAttachBuilder.ruleError(SubstitutePathRule("", "/app"), 0))
        assertNull(DelveAttachBuilder.ruleError(SubstitutePathRule("/src", ""), 0))
        assertEquals(
            "Path mapping 2: both local and remote paths are empty",
            DelveAttachBuilder.ruleError(SubstitutePathRule(" ", ""), 1),
        )
    }

    @Test
    fun `target error reports host before port before mappings`() {
        assertEquals("Host is not specified", DelveAttachBuilder.targetError(DelveAttachTarget.Remote("", 0)))
        assertNotNull(DelveAttachBuilder.targetError(DelveAttachTarget.Remote("h", 0)))
        assertNotNull(DelveAttachBuilder.targetError(DelveAttachTarget.Remote("h", 1, listOf(SubstitutePathRule("", "")))))
        assertNull(DelveAttachBuilder.targetError(DelveAttachTarget.Remote("h", 1, listOf(SubstitutePathRule("/a", "/b")))))
        assertNotNull(DelveAttachBuilder.targetError(DelveAttachTarget.Local(0, 1)))
        assertNotNull(DelveAttachBuilder.targetError(DelveAttachTarget.Local(1, 0)))
        assertNull(DelveAttachBuilder.targetError(DelveAttachTarget.Local(1, 1)))
    }
}
