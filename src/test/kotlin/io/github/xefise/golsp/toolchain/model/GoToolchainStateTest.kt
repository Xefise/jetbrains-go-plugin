package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoToolchainStateTest {
    private val base = mapOf("GOROOT" to "/sdk", "GOVERSION" to "go1.27.3", "GOPATH" to "/gp", "GOMOD" to "/p/go.mod")

    private fun ready(go: String = "/sdk/bin/go", env: Map<String, String> = base) =
        GoToolchainState.Ready(GoToolchain(go, GoEnv(env)))

    @Test
    fun `volatile go env values do not change the identity`() {
        assertTrue(ready().sameAs(ready(env = base + ("GOMOD" to "/dev/null") + ("GOGCCFLAGS" to "-x"))))
    }

    @Test
    fun `version, GOBIN or binary changes are a different toolchain`() {
        assertFalse(ready().sameAs(ready(env = base + ("GOVERSION" to "go1.27.4"))))
        assertFalse(ready().sameAs(ready(env = base + ("GOBIN" to "/bin2"))))
        assertFalse(ready().sameAs(ready(go = "/other/go")))
    }

    @Test
    fun `another local GOROOT behind the same binary is a different toolchain`() {
        val shim = "/home/u/.asdf/shims/go"
        assertFalse(
            GoToolchain(shim, GoEnv(base), localGoRoot = "/asdf/1.24/go")
                .sameAs(GoToolchain(shim, GoEnv(base), localGoRoot = "/asdf/1.25/go")),
        )
    }

    @Test
    fun `GOTOOLCHAIN alone does not change the identity`() {
        assertTrue(ready().sameAs(ready(env = base + ("GOTOOLCHAIN" to "auto"))))
    }

    @Test
    fun `goRoots lists the local and the effective GOROOT once each`() {
        assertEquals(listOf("/sdk"), GoToolchain("/sdk/bin/go", GoEnv(base)).goRoots)
        val switched = GoToolchain("/sdk/bin/go", GoEnv(base + ("GOROOT" to "/modcache/go1.28")), localGoRoot = "/sdk")
        assertEquals(listOf("/sdk", "/modcache/go1.28"), switched.goRoots)
    }

    @Test
    fun `empty and absent values are the same`() {
        assertTrue(ready().sameAs(ready(env = base + ("GOFLAGS" to ""))))
    }

    @Test
    fun `non ready states compare by value`() {
        assertTrue(GoToolchainState.Detecting.sameAs(GoToolchainState.Detecting))
        assertFalse(GoToolchainState.Detecting.sameAs(ready()))
        assertFalse(
            GoToolchainState.NotFound(NotFoundReason.GO_NOT_FOUND, "a")
                .sameAs(GoToolchainState.NotFound(NotFoundReason.GO_ENV_TIMEOUT, "a")),
        )
    }
}
