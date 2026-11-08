package io.github.xefise.golsp.toolchain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GoBinaryInfoParserTest {
    private fun fixture(name: String): String {
        val root = File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"))
        return File(root, "src/test/testData/toolchain/goversion-m/$name").readText()
    }

    private fun parse(text: String): GoBinaryInfo = requireNotNull(GoBinaryInfoParser.parse(text)) { text }

    @Test
    fun `captured gopls output yields module path and version`() {
        val info = parse(fixture("gopls-v0.24.0.txt"))
        assertEquals("golang.org/x/tools/gopls", info.packagePath)
        assertEquals("golang.org/x/tools/gopls", info.modulePath)
        assertEquals(ModuleVersion.parse("v0.24.0"), info.releasedVersion)
        assertEquals(GoVersion.parse("go1.27.3"), info.builtWith)
    }

    @Test
    fun `captured dlv output takes the main module not a dependency`() {
        val info = parse(fixture("dlv-v1.27.2.txt"))
        assertEquals("github.com/go-delve/delve/cmd/dlv", info.packagePath)
        assertEquals("github.com/go-delve/delve", info.modulePath)
        assertEquals("v1.27.2", info.moduleVersion)
    }

    @Test
    fun `local build reports devel and no released version`() {
        // Shape captured from `go build` of a local module with go1.27.3 (trailing tab after `(devel)` is real).
        val info = parse("/tmp/x/gopls: go1.27.3\n\tpath\texample.com/gopls\n\tmod\texample.com/gopls\t(devel)\t\n\tbuild\t-compiler=gc\n")
        assertEquals("(devel)", info.moduleVersion)
        assertNull(info.releasedVersion)
    }

    @Test
    fun `replaced main module has no released version`() {
        val info = parse(
            "/b/gopls: go1.27.3\n\tpath\tgolang.org/x/tools/gopls\n\tmod\tgolang.org/x/tools/gopls\tv0.24.0\n" +
                "\t=>\tgithub.com/fork/gopls\tv0.24.1\th1:abc=\n\tdep\tgolang.org/x/mod\tv0.30.0\th1:x=\n",
        )
        assertEquals("github.com/fork/gopls v0.24.1", info.replacement)
        assertNull(info.releasedVersion)
    }

    @Test
    fun `replaced dependency does not mark the main module replaced`() {
        val info = parse(
            "/b/dlv: go1.27.3\n\tmod\tgithub.com/go-delve/delve\tv1.27.2\th1:a=\n\tdep\tgolang.org/x/sys\tv0.1.0\n\t=>\t../sys\t(devel)\n",
        )
        assertNull(info.replacement)
        assertEquals(ModuleVersion.parse("v1.27.2"), info.releasedVersion)
    }

    @Test
    fun `pseudo-version from go install of a commit is a released version`() {
        val info = parse("/b/gopls: go1.27.3\n\tmod\tgolang.org/x/tools/gopls\tv0.24.1-0.20261010120000-abcdefabcdef\th1:x=\n")
        assertEquals(listOf("0", "20261010120000-abcdefabcdef"), info.releasedVersion?.preRelease)
    }

    @Test
    fun `dirty vcs-stamped local build has no released version`() {
        val info = parse("/b/gopls: go1.27.3\n\tmod\tgolang.org/x/tools/gopls\tv0.24.1-0.20261010120000-abcdefabcdef+dirty\t\n")
        assertNull(info.releasedVersion)
    }

    @Test
    fun `CRLF line endings and paths with colon-space are handled`() {
        val info = parse("C:\\My Tools: x\\gopls.exe: go1.27.3\r\n\tmod\tgolang.org/x/tools/gopls\tv0.24.0\r\n")
        assertEquals(GoVersion.parse("go1.27.3"), info.builtWith)
        assertEquals("v0.24.0", info.moduleVersion)
    }

    @Test
    fun `binary without module info parses with null module`() {
        val info = parse("/b/old: go1.15.15\n")
        assertNull(info.modulePath)
        assertNull(info.releasedVersion)
    }

    @Test
    fun `garbage and non-Go-binary output are rejected`() {
        assertNull(GoBinaryInfoParser.parse(""))
        assertNull(GoBinaryInfoParser.parse("\tmod\tgolang.org/x/tools/gopls\tv0.24.0\n"))
        assertNull(GoBinaryInfoParser.parse("/bin/ls: could not read Go build info from /bin/ls: not a Go executable\n"))
        assertNull(GoBinaryInfoParser.parse("Segmentation fault"))
    }
}
