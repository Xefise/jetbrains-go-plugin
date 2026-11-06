package io.github.xefise.golsp.execution.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoRunCommandBuilderTest {
    private val pkg = GoRunTarget.Package("/p/cmd/app")

    @Test
    fun `package build runs in the package directory and targets dot`() {
        val spec = GoRunCommandBuilder.buildCommand("/sdk/go/bin/go", GoExecutionRequest(pkg), "/tmp/b/app")
        assertEquals("/sdk/go/bin/go", spec.executable)
        assertEquals(listOf("build", "-o", "/tmp/b/app", "."), spec.arguments)
        assertEquals("/p/cmd/app", spec.workingDirectory)
    }

    @Test
    fun `file build runs in the file directory and targets the file name`() {
        val request = GoExecutionRequest(GoRunTarget.File("/p/scripts", "tool.go"))
        val spec = GoRunCommandBuilder.buildCommand("go", request, "/tmp/b/tool")
        assertEquals(listOf("build", "-o", "/tmp/b/tool", "tool.go"), spec.arguments)
        assertEquals("/p/scripts", spec.workingDirectory)
    }

    @Test
    fun `build flags go between the output and the target`() {
        val request = GoExecutionRequest(pkg, buildFlags = listOf("-tags=integration", "-ldflags=-X main.v=1"))
        assertEquals(
            listOf("build", "-o", "/b/app", "-tags=integration", "-ldflags=-X main.v=1", "."),
            GoRunCommandBuilder.buildCommand("go", request, "/b/app").arguments,
        )
    }

    @Test
    fun `build does not depend on the working directory`() {
        val request = GoExecutionRequest(pkg, workingDirectory = "/elsewhere")
        assertEquals("/p/cmd/app", GoRunCommandBuilder.buildCommand("go", request, "/b/app").workingDirectory)
    }

    @Test
    fun `build and program get the same environment`() {
        val request = GoExecutionRequest(pkg, environment = mapOf("GOFLAGS" to "-mod=mod", "FOO" to "1"))
        assertEquals(request.environment, GoRunCommandBuilder.buildCommand("go", request, "/b/app").environment)
        assertEquals(request.environment, GoRunCommandBuilder.programCommand(request, "/b/app").environment)
    }

    @Test
    fun `program runs the binary with arguments verbatim in the working directory`() {
        val request = GoExecutionRequest(pkg, programArguments = listOf("-v", "a b", ""), workingDirectory = "/p")
        val spec = GoRunCommandBuilder.programCommand(request, "/b/app")
        assertEquals("/b/app", spec.executable)
        assertEquals(listOf("-v", "a b", ""), spec.arguments)
        assertEquals("/p", spec.workingDirectory)
    }

    @Test
    fun `working directory defaults to the target directory`() {
        assertEquals("/p/scripts", GoExecutionRequest(GoRunTarget.File("/p/scripts", "tool.go")).workingDirectory)
    }

    @Test
    fun `binary is named after the package directory`() {
        assertEquals("app", GoRunCommandBuilder.binaryName(pkg, windows = false))
    }

    @Test
    fun `binary is named after the file without the go extension`() {
        assertEquals("tool", GoRunCommandBuilder.binaryName(GoRunTarget.File("/p", "tool.go"), windows = false))
    }

    @Test
    fun `binary gets the exe suffix on Windows`() {
        assertEquals("app.exe", GoRunCommandBuilder.binaryName(pkg, windows = true))
    }

    @Test
    fun `binary falls back to main for a root directory`() {
        assertEquals("main", GoRunCommandBuilder.binaryName(GoRunTarget.Package("/"), windows = false))
    }

    @Test
    fun `binary keeps spaces of the directory name`() {
        assertEquals("hello app", GoRunCommandBuilder.binaryName(GoRunTarget.Package("/t/hello app"), windows = false))
    }

    @Test
    fun `file target path joins directory and file name`() {
        assertEquals("/p/scripts/tool.go", GoRunTarget.File("/p/scripts", "tool.go").path)
    }

    @Test
    fun `explicit variables override the env file`() {
        assertEquals(
            mapOf("A" to "explicit", "B" to "file", "C" to "explicit"),
            GoEnvironmentLayers.merge(mapOf("A" to "file", "B" to "file"), mapOf("A" to "explicit", "C" to "explicit")),
        )
    }

    @Test
    fun `an explicit empty value still overrides the env file`() {
        assertEquals(mapOf("A" to ""), GoEnvironmentLayers.merge(mapOf("A" to "file"), mapOf("A" to "")))
    }

    @Test
    fun `output flag in every spelling is rejected`() {
        for (flags in listOf(listOf("-o", "x"), listOf("--o", "x"), listOf("-race", "-o=bin/x"), listOf("--o=x"))) {
            val error = GoRunCommandBuilder.buildFlagsError(flags)
            assertTrue("$flags: $error", error != null && error.contains("output"))
        }
    }

    @Test
    fun `flags that merely start with o are allowed`() {
        assertNull(GoRunCommandBuilder.buildFlagsError(listOf("-overlay=o.json", "-ldflags=-o x", "-tags=o")))
    }

    @Test
    fun `go-first PATH replaces the user PATH and keeps the other variables`() {
        assertEquals(
            mapOf("PATH" to "/go/bin:/user/bin", "A" to "1"),
            GoEnvironmentLayers.withPath(mapOf("PATH" to "/user/bin", "A" to "1"), "/go/bin:/user/bin"),
        )
    }

    @Test
    fun `unknown go leaves the environment as is`() {
        assertEquals(mapOf("A" to "1"), GoEnvironmentLayers.withPath(mapOf("A" to "1"), null))
    }
}
