package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DelveLaunchBuilderTest {
    private val request = GoExecutionRequest(
        target = GoRunTarget.Package("/p/cmd/app"),
        programArguments = listOf("-n", "3"),
        environment = mapOf("FOO" to "bar"),
        workingDirectory = "/p",
    )

    @Test
    fun `launch parameters describe a debug launch of the package directory`() {
        assertEquals(
            mapOf(
                "type" to "go",
                "request" to "launch",
                "mode" to "debug",
                "program" to "/p/cmd/app",
                "args" to listOf("-n", "3"),
                "cwd" to "/p",
                "env" to mapOf("FOO" to "bar"),
            ),
            DelveLaunchBuilder.launchParameters(request, DelveOutputMode.LOCAL),
        )
    }

    @Test
    fun `file target launches the file path`() {
        val file = request.copy(target = GoRunTarget.File("/p/scripts", "tool.go"))
        assertEquals("/p/scripts/tool.go", DelveLaunchBuilder.launchParameters(file)["program"])
    }

    @Test
    fun `build flags are passed as an array`() {
        val flagged = request.copy(buildFlags = listOf("-tags=integration", "-ldflags=-X main.v=a b"))
        assertEquals(listOf("-tags=integration", "-ldflags=-X main.v=a b"), DelveLaunchBuilder.launchParameters(flagged)["buildFlags"])
    }

    @Test
    fun `no build flags omits buildFlags`() {
        assertFalse("buildFlags" in DelveLaunchBuilder.launchParameters(request))
    }

    @Test
    fun `server for a file target runs in the file directory`() {
        val file = request.copy(target = GoRunTarget.File("/p/scripts", "tool.go"), workingDirectory = "/p")
        assertEquals("/p/scripts", DelveLaunchBuilder.serverCommand("dlv", file, null).workingDirectory)
    }

    @Test
    fun `local output mode omits outputMode`() {
        assertFalse("outputMode" in DelveLaunchBuilder.launchParameters(request, DelveOutputMode.LOCAL))
    }

    @Test
    fun `remote output mode sets outputMode remote`() {
        assertEquals("remote", DelveLaunchBuilder.launchParameters(request, DelveOutputMode.REMOTE)["outputMode"])
    }

    @Test
    fun `default output mode is local`() {
        assertFalse("outputMode" in DelveLaunchBuilder.launchParameters(request))
    }

    @Test
    fun `server listens on a dlv-chosen loopback port in the package directory`() {
        val spec = DelveLaunchBuilder.serverCommand("/Users/me/go/bin/dlv", request, "/sdk/go/bin:/usr/bin")
        assertEquals("/Users/me/go/bin/dlv", spec.executable)
        assertEquals(listOf("dap", "--listen=127.0.0.1:0"), spec.arguments)
        assertEquals("/p/cmd/app", spec.workingDirectory)
        assertEquals(mapOf("FOO" to "bar", "PATH" to "/sdk/go/bin:/usr/bin"), spec.environment)
    }

    @Test
    fun `server gets the user environment so its own go build sees GOFLAGS and friends`() {
        val flagged = request.copy(environment = mapOf("GOFLAGS" to "-tags=x", "CGO_ENABLED" to "0"))
        assertEquals(
            mapOf("GOFLAGS" to "-tags=x", "CGO_ENABLED" to "0", "PATH" to "/go/bin"),
            DelveLaunchBuilder.serverCommand("dlv", flagged, "/go/bin").environment,
        )
    }

    @Test
    fun `go-first PATH wins over a PATH from the user environment`() {
        val withPath = request.copy(environment = mapOf("PATH" to "/user/bin"))
        assertEquals("/go/bin:/user/bin", DelveLaunchBuilder.serverCommand("dlv", withPath, "/go/bin:/user/bin").environment["PATH"])
    }

    @Test
    fun `server environment is the user environment when no PATH override is known`() {
        assertEquals(mapOf("FOO" to "bar"), DelveLaunchBuilder.serverCommand("dlv", request, null).environment)
    }
}
