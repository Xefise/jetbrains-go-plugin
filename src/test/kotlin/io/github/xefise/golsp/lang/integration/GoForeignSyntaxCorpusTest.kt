package io.github.xefise.golsp.lang.integration

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import io.github.xefise.golsp.lang.model.GoForeignSyntax
import io.github.xefise.golsp.lang.model.GoIndentToken
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

// Valid Go must never be reported: scans the local Go distribution (skipped without one) and the sample projects.
class GoForeignSyntaxCorpusTest {
    @Test
    fun goRootSourcesHaveNoForeignSyntax() {
        val src = goRoot()?.resolve("src")
        assumeTrue("GOROOT/src not found", src != null && src.isDirectory)
        assertClean(src!!, minFiles = 1000)
    }

    @Test
    fun sampleProjectsHaveNoForeignSyntax() = assertClean(File(System.getProperty("golsp.projectDir") ?: System.getProperty("user.dir"), "testdata"), minFiles = 1)

    private fun assertClean(root: File, minFiles: Int) {
        val files = root.walkTopDown()
            .onEnter { it.name != "testdata" || it == root }
            .filter { it.isFile && it.extension == "go" }
            .toList()
        assertEquals("suspiciously small corpus under $root", true, files.size >= minFiles)
        val hits = files.flatMap { file ->
            val text = file.readText()
            GoForeignSyntax.find(text, tokens(text)).map { "${file.relativeTo(root)}: ${text.substring(it.start, it.end)}" }
        }
        println("GoForeignSyntaxCorpusTest: ${files.size} files scanned under $root, ${hits.size} reported")
        assertEquals(hits.take(20).joinToString("\n"), 0, hits.size)
    }

    private fun tokens(text: String): List<GoIndentToken> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null) {
            result += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return result
    }

    private fun goRoot(): File? {
        System.getenv("GOROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return try {
            val process = ProcessBuilder("go", "env", "GOROOT").redirectErrorStream(true).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0 && out.isNotEmpty()) File(out) else null
        } catch (_: Exception) {
            null
        }
    }
}
