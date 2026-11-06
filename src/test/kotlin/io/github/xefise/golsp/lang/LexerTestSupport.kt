package io.github.xefise.golsp.lang

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType

data class LexedToken(val type: String, val text: String, val start: Int, val end: Int)

fun lexAll(lexer: Lexer, text: CharSequence, start: Int = 0): List<LexedToken> {
    lexer.start(text, start, text.length, 0)
    val result = mutableListOf<LexedToken>()
    while (true) {
        val type = lexer.tokenType ?: break
        result += LexedToken(type.toString(), text.substring(lexer.tokenStart, lexer.tokenEnd), lexer.tokenStart, lexer.tokenEnd)
        lexer.advance()
    }
    return result
}

// "TYPE:text" pairs without whitespace and without the dialect prefix.
fun tokens(lexer: Lexer, text: String): List<String> = lexAll(lexer, text)
    .filter { it.type != TokenType.WHITE_SPACE.toString() }
    .map { PREFIX.replace(it.type, "") + ":" + it.text }

private val PREFIX = Regex("^GO(MOD|WORK)?_")
