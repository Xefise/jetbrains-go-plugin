package io.github.xefise.golsp.execution.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GoTestCommandBuilderTest {
    private val pkg = GoRunTarget.Package("/p/calc")

    private fun args(request: GoExecutionRequest): List<String> = GoTestCommandBuilder.testCommand("go", request).arguments

    @Test
    fun `package runs go test json on dot in the package directory`() {
        val spec = GoTestCommandBuilder.testCommand("/sdk/bin/go", GoExecutionRequest(pkg, test = GoTestSpec()))
        assertEquals("/sdk/bin/go", spec.executable)
        assertEquals(listOf("test", "-json", "."), spec.arguments)
        assertEquals("/p/calc", spec.workingDirectory)
    }

    @Test
    fun `recursive targets every package below the directory`() {
        assertEquals(listOf("test", "-json", "./..."), args(GoExecutionRequest(pkg, test = GoTestSpec(recursive = true))))
    }

    @Test
    fun `explicit packages replace the recursive pattern`() {
        val request = GoExecutionRequest(pkg, test = GoTestSpec(recursive = true, runPattern = "^TestA$", packages = listOf("m/a", "m/b")))
        assertEquals(listOf("test", "-json", "-run", "^TestA$", "m/a", "m/b"), args(request))
    }

    @Test
    fun `run pattern is passed before the package`() {
        val request = GoExecutionRequest(pkg, test = GoTestSpec(runPattern = "^TestA$/^sub$"))
        assertEquals(listOf("test", "-json", "-run", "^TestA$/^sub$", "."), args(request))
    }

    @Test
    fun `bench pattern comes with its run pattern`() {
        val request = GoExecutionRequest(pkg, test = GoTestSpec(runPattern = "^$", benchPattern = "^BenchmarkX$"))
        assertEquals(listOf("test", "-json", "-run", "^$", "-bench", "^BenchmarkX$", "."), args(request))
    }

    @Test
    fun `empty patterns are not passed because an empty run selects everything anyway`() {
        val request = GoExecutionRequest(pkg, test = GoTestSpec(runPattern = "", benchPattern = ""))
        assertEquals(listOf("test", "-json", "."), args(request))
    }

    @Test
    fun `user flags precede the selection so the IDE selection wins`() {
        val request = GoExecutionRequest(
            pkg,
            buildFlags = listOf("-race", "-run", "TestOld", "-ldflags=-X main.v=a b"),
            test = GoTestSpec(runPattern = "^TestNew$"),
        )
        assertEquals(
            listOf("test", "-json", "-race", "-run", "TestOld", "-ldflags=-X main.v=a b", "-run", "^TestNew$", "."),
            args(request),
        )
    }

    @Test
    fun `test arguments go to the binary after -args verbatim`() {
        val request = GoExecutionRequest(pkg, programArguments = listOf("-update", "a b", ""), test = GoTestSpec(recursive = true))
        assertEquals(listOf("test", "-json", "./...", "-args", "-update", "a b", ""), args(request))
    }

    @Test
    fun `environment is passed through`() {
        val env = mapOf("GOFLAGS" to "-mod=mod")
        assertEquals(env, GoTestCommandBuilder.testCommand("go", GoExecutionRequest(pkg, environment = env, test = GoTestSpec())).environment)
    }

    @Test
    fun `go test runs in the target directory even with another working directory`() {
        val request = GoExecutionRequest(pkg, workingDirectory = "/elsewhere", test = GoTestSpec())
        assertEquals("/p/calc", GoTestCommandBuilder.testCommand("go", request).workingDirectory)
    }

    @Test
    fun `a request without a test spec is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GoTestCommandBuilder.testCommand("go", GoExecutionRequest(pkg)) }
    }

    @Test
    fun `flags that take over the run are rejected with a go test message`() {
        for (flag in listOf("-o=x.test", "-o", "-c", "--c", "-C", "-C=sub", "-json=false", "-json", "-args", "--args")) {
            val error = GoTestCommandBuilder.testFlagsError(listOf("-race", flag))
            assertTrue(flag, error!!.startsWith("Go test flags must not contain '$flag'"))
        }
    }

    @Test
    fun `ordinary flags sharing a prefix with reserved ones pass`() {
        assertNull(GoTestCommandBuilder.testFlagsError(listOf("-count=1", "-cover", "-coverprofile=c.out", "-cpu=1,2", "-cpu", "-count", "-outputdir=out", "-outputdir", "-v", "json", "")))
    }
}
