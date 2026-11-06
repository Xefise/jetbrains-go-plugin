package io.github.xefise.golsp.execution

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeFalse
import org.junit.Test

// Semantics are EnvironmentUtil.inlineParentOccurrences', the platform expander used by its own run configurations.
class GoParentEnvironmentTest {
    private val parent = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/me")

    private fun inline(vararg envs: Pair<String, String>) = GoParentEnvironment.inline(linkedMapOf(*envs), parent)

    @Test
    fun `a parent reference is replaced with the parent value`() {
        assertEquals(mapOf("PATH" to "/my/bin:/usr/bin:/bin"), inline("PATH" to "/my/bin:\$PATH\$"))
    }

    @Test
    fun `several references in one value are all replaced`() {
        assertEquals(mapOf("X" to "/home/me|/usr/bin:/bin"), inline("X" to "\$HOME\$|\$PATH\$"))
    }

    @Test
    fun `an unknown reference is kept literally`() {
        assertEquals(mapOf("A" to "x\$NOPE\$y"), inline("A" to "x\$NOPE\$y"))
    }

    @Test
    fun `a dollar without a closing dollar is kept as is`() {
        assertEquals(mapOf("PRICE" to "\$5", "B" to "a\$PATH"), inline("PRICE" to "\$5", "B" to "a\$PATH"))
    }

    @Test
    fun `an empty reference is kept as is`() {
        assertEquals(mapOf("A" to "\$\$"), inline("A" to "\$\$"))
    }

    @Test
    fun `an earlier variable of the table can be referenced when the parent lacks it`() {
        assertEquals(mapOf("A" to "x", "B" to "x/y"), inline("A" to "x", "B" to "\$A\$/y"))
    }

    @Test
    fun `references are case sensitive outside Windows`() {
        assumeFalse(SystemInfo.isWindows)
        assertEquals(mapOf("A" to "\$path\$"), inline("A" to "\$path\$"))
    }

    @Test
    fun `order of the table is preserved`() {
        assertEquals(listOf("Z", "A"), inline("Z" to "1", "A" to "\$HOME\$").keys.toList())
    }

    @Test
    fun `values without a dollar skip the parent environment`() {
        val envs = mapOf("A" to "1")
        assertSame(envs, GoParentEnvironment.inline(envs))
    }
}
