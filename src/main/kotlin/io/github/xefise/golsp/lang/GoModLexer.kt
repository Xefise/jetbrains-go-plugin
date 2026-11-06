// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

// go.mod / go.work: whitespace-separated words as in x/mod/modfile, not Go tokens. Stateless like GoLexer.
class GoModLexer(private val types: GoTokenSet, private val keywords: Set<String>) : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var currentToken: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            currentToken = null
            return
        }
        currentToken = scan()
    }

    private fun charAt(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private fun scan(): IElementType {
        val c = buffer[tokenStart]
        if (GoLexer.isWhitespace(c)) {
            var i = tokenStart
            while (i < bufferEnd && GoLexer.isWhitespace(buffer[i])) i++
            tokenEnd = i
            return TokenType.WHITE_SPACE
        }
        if (c == '/' && charAt(tokenStart + 1) == '/') {
            var i = tokenStart
            while (i < bufferEnd && buffer[i] != '\n') i++
            tokenEnd = i
            return types.LINE_COMMENT
        }
        when (c) {
            '(' -> return single(types.LPAREN)
            ')' -> return single(types.RPAREN)
            '[' -> return single(types.LBRACKET)
            ']' -> return single(types.RBRACKET)
            ',' -> return single(types.COMMA)
            '"' -> return quoted()
            '`' -> return rawString()
        }
        var i = tokenStart
        while (i < bufferEnd && isWordPart(buffer[i]) && !(buffer[i] == '/' && charAt(i + 1) == '/')) i++
        tokenEnd = i
        val word = buffer.subSequence(tokenStart, tokenEnd).toString()
        return when {
            word in keywords -> types.KEYWORD
            word == "=>" -> types.OPERATOR
            isVersion(word) -> types.NUMBER
            else -> types.IDENTIFIER
        }
    }

    private fun single(type: IElementType): IElementType {
        tokenEnd = tokenStart + 1
        return type
    }

    private fun quoted(): IElementType {
        var i = tokenStart + 1
        while (i < bufferEnd) {
            val ch = buffer[i]
            when {
                ch == '\n' -> break
                ch == '\\' -> i += if (charAt(i + 1) == '\n') 1 else 2
                ch == '"' -> {
                    i++
                    break
                }
                else -> i++
            }
        }
        tokenEnd = minOf(i, bufferEnd)
        return types.STRING
    }

    private fun rawString(): IElementType {
        var i = tokenStart + 1
        while (i < bufferEnd && buffer[i] != '`') i++
        tokenEnd = if (i < bufferEnd) i + 1 else bufferEnd
        return types.RAW_STRING
    }

    private fun isWordPart(c: Char): Boolean =
        !GoLexer.isWhitespace(c) && c != '(' && c != ')' && c != '[' && c != ']' && c != ',' && c != '"' && c != '`'

    private fun isVersion(word: String): Boolean = VERSION.matches(word)

    companion object {
        // Semver-like (v1.2.3, v0.0.0-2024...-abcdef, +incompatible) or a bare `go` directive version (1.22, 1.22.0, 1.23rc1).
        private val VERSION = Regex("""v\d+(\.\d+)*([-+][0-9A-Za-z.+-]*)?|\d+(\.\d+)+([a-z]+\d*)?""")


        val GO_MOD_KEYWORDS: Set<String> = setOf(
            "module", "go", "toolchain", "godebug", "require", "exclude", "replace", "retract", "tool", "ignore",
        )

        val GO_WORK_KEYWORDS: Set<String> = setOf("go", "toolchain", "godebug", "use", "replace")
    }
}
