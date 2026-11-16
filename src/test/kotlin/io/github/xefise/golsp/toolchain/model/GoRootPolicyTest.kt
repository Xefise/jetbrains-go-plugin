package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GoRootPolicyTest {
    private val shell = mapOf("PATH" to "/usr/bin", "GOROOT" to "/usr/local/go")

    private fun parent(
        parent: Map<String, String>,
        roots: List<String>,
        explicit: Map<String, String> = emptyMap(),
        windows: Boolean = false,
    ) = GoRootPolicy.parentEnvironment(parent, explicit, roots, windows)

    @Test
    fun `inherited GOROOT of another installation is dropped`() {
        assertEquals(mapOf("PATH" to "/usr/bin"), parent(shell, listOf("/opt/homebrew/Cellar/go/1.25.0/libexec")))
    }

    @Test
    fun `inherited GOROOT equal to a selected root is kept with trailing slash ignored`() {
        assertEquals(shell, parent(shell, listOf("/other", "/usr/local/go/")))
    }

    @Test
    fun `with no known root an inherited GOROOT is dropped`() {
        assertEquals(mapOf("PATH" to "/usr/bin"), parent(shell, emptyList()))
    }

    @Test
    fun `blank inherited GOROOT is dropped`() {
        assertEquals(mapOf("PATH" to "/usr/bin"), parent(shell + ("GOROOT" to " "), listOf(" ")))
    }

    @Test
    fun `explicit GOROOT leaves the parent untouched because it wins anyway`() {
        assertEquals(shell, parent(shell, listOf("/sdk"), explicit = mapOf("GOROOT" to "/custom")))
    }

    @Test
    fun `parent without GOROOT is unchanged`() {
        assertEquals(mapOf("PATH" to "/usr/bin"), parent(mapOf("PATH" to "/usr/bin"), listOf("/sdk")))
    }

    @Test
    fun `Windows keeps a matching GOROOT whatever the variable and path case`() {
        val env = mapOf("Path" to "C:\\Windows", "GoRoot" to "c:/go/")
        assertEquals(env, parent(env, listOf("C:\\Go"), windows = true))
    }

    @Test
    fun `Windows drops a foreign GOROOT under any variable case`() {
        val env = mapOf("Path" to "C:\\Windows", "GoRoot" to "c:/go/")
        assertEquals(mapOf("Path" to "C:\\Windows"), parent(env, listOf("C:\\Go1.25"), windows = true))
    }

    @Test
    fun `explicit GOROOT on Windows is recognised under any case`() {
        val env = mapOf("GOROOT" to "C:\\Old")
        assertEquals(env, parent(env, emptyList(), explicit = mapOf("goroot" to "C:\\New"), windows = true))
    }

    @Test
    fun `Unix GOROOT comparison is case-sensitive`() {
        assertEquals(emptyMap<String, String>(), parent(mapOf("GOROOT" to "/Usr/Local/Go"), listOf("/usr/local/go")))
    }

    @Test
    fun `go executable lives in GOROOT bin`() {
        assertEquals("/sdk/go/bin/go", GoRootPolicy.goExecutable("/sdk/go", false))
        assertEquals("/sdk/go/bin/go", GoRootPolicy.goExecutable("/sdk/go/", false))
        assertEquals("C:\\Go\\bin\\go.exe", GoRootPolicy.goExecutable("C:\\Go", true))
        assertEquals("C:\\Go\\bin\\go.exe", GoRootPolicy.goExecutable("C:\\Go\\", true))
    }
}
