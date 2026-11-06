package io.github.xefise.golsp.lang

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

// Lexes every .go file of the local Go distribution; skipped when no GOROOT is found.
class GoLexerCorpusTest {
    @Test
    fun goRootSourcesLexCleanly() {
        val src = goRoot()?.resolve("src")
        assumeTrue("GOROOT/src not found", src != null && src.isDirectory)
        val files = src!!.walkTopDown()
            .onEnter { it.name != "testdata" }
            .filter { it.isFile && it.extension == "go" }
            .toList()
        assertTrue("suspiciously small corpus: ${files.size}", files.size > 1000)

        val failures = files.mapNotNull { file -> check(file.readText())?.let { "${file.relativeTo(src)}: $it" } }
        println("GoLexerCorpusTest: ${files.size - failures.size}/${files.size} files clean under $src")
        assertEquals(failures.take(20).joinToString("\n"), 0, failures.size)
    }

    private fun check(text: String): String? {
        val lexer = GoLexer()
        lexer.start(text)
        val balance = IntArray(3)
        var expectedStart = 0
        while (true) {
            val type = lexer.tokenType ?: break
            if (lexer.tokenStart != expectedStart || lexer.tokenEnd <= lexer.tokenStart) {
                return "gap or empty token at ${lexer.tokenStart}"
            }
            if (lexer.state != 0) return "non-zero state at ${lexer.tokenStart}"
            if (type == TokenType.BAD_CHARACTER) {
                return "BAD_CHARACTER '${lexer.tokenText}' at line ${text.substring(0, lexer.tokenStart).count { it == '\n' } + 1}"
            }
            when (type) {
                GoTokenTypes.LPAREN -> balance[0]++
                GoTokenTypes.RPAREN -> balance[0]--
                GoTokenTypes.LBRACE -> balance[1]++
                GoTokenTypes.RBRACE -> balance[1]--
                GoTokenTypes.LBRACKET -> balance[2]++
                GoTokenTypes.RBRACKET -> balance[2]--
            }
            expectedStart = lexer.tokenEnd
            lexer.advance()
        }
        if (expectedStart != text.length) return "lexing stopped at $expectedStart of ${text.length}"
        if (balance.any { it != 0 }) return "unbalanced () {} []: ${balance.toList()}"
        return null
    }

    private fun goRoot(): File? {
        System.getenv("GOROOT")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        goEnvGoRoot()?.let { return File(it) }
        return File("/usr/local/go").takeIf { it.isDirectory }
    }

    private fun goEnvGoRoot(): String? = try {
        val process = ProcessBuilder("go", "env", "GOROOT").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}
