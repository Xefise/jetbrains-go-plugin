package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoEnvParserTest {
    private fun fixture(name: String): String {
        val root = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        return File(root, "src/test/testData/toolchain/$name").readText()
    }

    private fun success(json: String): GoEnv {
        val result = GoEnvParser.parse(json)
        assertTrue("expected success, got $result", result is GoEnvParseResult.Success)
        return (result as GoEnvParseResult.Success).env
    }

    private fun failure(json: String): String {
        val result = GoEnvParser.parse(json)
        assertTrue("expected failure, got $result", result is GoEnvParseResult.Failure)
        return (result as GoEnvParseResult.Failure).message
    }

    @Test
    fun `real darwin go env exposes the toolchain fields`() {
        val env = success(fixture("go-env-darwin.json"))
        assertEquals("/opt/homebrew/Cellar/go/1.27.3/libexec", env.goRoot)
        assertEquals("go1.27.3", env.goVersion)
        assertEquals("/Users/me/go", env.goPath)
        assertEquals("/Users/me/go/bin", env.goBin)
        assertEquals("/Users/me/go/pkg/mod", env.goModCache)
        assertEquals("darwin", env.goOs)
        assertEquals("arm64", env.goArch)
        assertEquals("auto", env.goToolchain)
    }

    @Test
    fun `empty values read as null`() {
        val env = success(fixture("go-env-darwin.json"))
        assertNull(env.goFlags)
        assertNull(env.value("GOWORK"))
        assertEquals("", env.values["GOWORK"])
    }

    @Test
    fun `windows paths with escaped backslashes survive`() {
        val env = success("""{"GOROOT": "C:\\Program Files\\Go", "GOBIN": "", "GOPATH": "C:\\Users\\me\\go"}""")
        assertEquals("C:\\Program Files\\Go", env.goRoot)
        assertEquals("C:\\Users\\me\\go", env.goPath)
        assertNull(env.goBin)
    }

    @Test
    fun `non string values are ignored`() {
        val env = success("""{"GOROOT": "/sdk/go", "WEIRD": 1, "NESTED": {"a": "b"}, "NULL": null}""")
        assertEquals(setOf("GOROOT"), env.values.keys)
    }

    @Test
    fun `missing GOROOT is a failure`() {
        assertTrue(failure("""{"GOVERSION": "go1.27.3"}""").contains("GOROOT"))
    }

    @Test
    fun `blank output is a failure`() {
        failure("   \n")
    }

    @Test
    fun `invalid json is a failure`() {
        failure("go: warning: GOPATH set to GOROOT")
    }

    @Test
    fun `json array is a failure`() {
        failure("""["GOROOT"]""")
    }
}
