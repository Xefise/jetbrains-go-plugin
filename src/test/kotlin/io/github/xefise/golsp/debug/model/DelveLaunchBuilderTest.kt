package io.github.xefise.golsp.debug.model

import io.github.xefise.golsp.execution.model.GoExecutionRequest
import io.github.xefise.golsp.execution.model.GoRunTarget
import io.github.xefise.golsp.execution.model.GoTestSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    private val testRequest = GoExecutionRequest(
        target = GoRunTarget.Package("/p/calc"),
        programArguments = listOf("-test.v", "-custom=1"),
        environment = mapOf("FOO" to "bar"),
        test = GoTestSpec(runPattern = "^TestSubtests$/^with_space$"),
    )

    @Test
    fun `test request launches dlv test mode on the package directory with the run filter before user arguments`() {
        assertEquals(
            mapOf(
                "type" to "go",
                "request" to "launch",
                "mode" to "test",
                "program" to "/p/calc",
                "args" to listOf("-test.run", "^TestSubtests$/^with_space$", "-test.v=test2json", "-custom=1"),
                "cwd" to "/p/calc",
                "env" to mapOf("FOO" to "bar"),
            ),
            DelveLaunchBuilder.launchParameters(testRequest),
        )
    }

    @Test
    fun `benchmark-only selection skips tests and filters benchmarks for the test binary`() {
        val bench = testRequest.copy(programArguments = emptyList(), test = GoTestSpec(runPattern = "^$", benchPattern = "^BenchmarkAdd$"))
        assertEquals(
            listOf("-test.run", "^$", "-test.bench", "^BenchmarkAdd$", "-test.v=test2json"),
            DelveLaunchBuilder.launchParameters(bench)["args"],
        )
    }

    @Test
    fun `whole-package test selection passes only the test2json switch and the user arguments`() {
        val all = testRequest.copy(test = GoTestSpec())
        assertEquals(listOf("-test.v=test2json", "-custom=1"), DelveLaunchBuilder.launchParameters(all)["args"])
    }

    @Test
    fun `user verbose flags in test arguments are dropped so the binary keeps test2json framing`() {
        val verbose = testRequest.copy(test = GoTestSpec(), programArguments = listOf("-test.v=true", "--test.v", "-test.vet=off", "-v", "pos"))
        assertEquals(listOf("-test.v=test2json", "-test.vet=off", "-v", "pos"), DelveLaunchBuilder.launchParameters(verbose)["args"])
    }

    @Test
    fun `test program is the package directory even for a file target`() {
        val file = testRequest.copy(target = GoRunTarget.File("/p/calc", "calc_test.go"))
        assertEquals("/p/calc", DelveLaunchBuilder.launchParameters(file)["program"])
    }

    @Test
    fun `go test flags of a test request become dlv build flags`() {
        val flagged = testRequest.copy(buildFlags = listOf("-race", "-tags=integration"))
        assertEquals(listOf("-race", "-tags=integration"), DelveLaunchBuilder.launchParameters(flagged)["buildFlags"])
    }

    @Test
    fun `test-binary flags among go test flags reach the binary before the IDE selection, build flags stay with dlv`() {
        val flagged = testRequest.copy(programArguments = emptyList(), buildFlags = listOf("-race", "-count", "1", "-run=TestOther", "-v"))
        val params = DelveLaunchBuilder.launchParameters(flagged)
        assertEquals(listOf("-race"), params["buildFlags"])
        // The flag package keeps the last -test.run, so the IDE selection wins over the user's.
        assertEquals(listOf("-test.count=1", "-test.run=TestOther", "-test.run", "^TestSubtests$/^with_space$", "-test.v=test2json"), params["args"])
    }

    @Test
    fun `only test-binary flags leave no dlv build flags`() {
        assertFalse("buildFlags" in DelveLaunchBuilder.launchParameters(testRequest.copy(buildFlags = listOf("-short"))))
    }

    @Test
    fun `application debug keeps test-looking build flags as build flags`() {
        val app = request.copy(buildFlags = listOf("-v", "-race"))
        assertEquals(listOf("-v", "-race"), DelveLaunchBuilder.launchParameters(app)["buildFlags"])
    }

    @Test
    fun `empty run pattern adds no run filter`() {
        val empty = testRequest.copy(programArguments = emptyList(), test = GoTestSpec(runPattern = ""))
        assertEquals(listOf("-test.v=test2json"), DelveLaunchBuilder.launchParameters(empty)["args"])
    }

    @Test
    fun `test request keeps the debuggee output on the dlv process`() {
        assertFalse("outputMode" in DelveLaunchBuilder.launchParameters(testRequest))
    }

    @Test
    fun `explicit package list cannot be debugged`() {
        val several = testRequest.copy(test = GoTestSpec(packages = listOf("./a", "./b")))
        assertThrows(IllegalStateException::class.java) { DelveLaunchBuilder.launchParameters(several) }
    }

    @Test
    fun `recursive test request cannot be debugged`() {
        val recursive = testRequest.copy(test = GoTestSpec(recursive = true))
        assertThrows(IllegalStateException::class.java) { DelveLaunchBuilder.launchParameters(recursive) }
    }
}
