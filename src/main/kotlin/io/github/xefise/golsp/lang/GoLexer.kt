// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

// Every token is scanned from its own start offset only, so state 0 is valid at any token boundary.
class GoLexer : LexerBase() {
    private val types = GoTokenTypes

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
        val next = charAt(tokenStart + 1)

        if (isWhitespace(c)) return whitespace()
        if (c == '/' && next == '/') return lineComment()
        if (c == '/' && next == '*') return blockComment()
        if (c == '`') return rawString()
        if (c == '"') return quoted('"', types.STRING)
        if (c == '\'') return quoted('\'', types.CHAR)
        if (isDecimal(c) || (c == '.' && isDecimal(next))) return number()

        when (c) {
            '(' -> return single(types.LPAREN)
            ')' -> return single(types.RPAREN)
            '{' -> return single(types.LBRACE)
            '}' -> return single(types.RBRACE)
            '[' -> return single(types.LBRACKET)
            ']' -> return single(types.RBRACKET)
            ',' -> return single(types.COMMA)
            ';' -> return single(types.SEMICOLON)
        }

        val operatorLength = matchOperator()
        if (operatorLength > 0) {
            tokenEnd = tokenStart + operatorLength
            return types.OPERATOR
        }
        if (c == '.') return single(types.DOT)

        val cp = codePointAt(tokenStart)
        if (isIdentifierStart(cp)) return identifier()
        tokenEnd = tokenStart + Character.charCount(cp).coerceAtMost(bufferEnd - tokenStart)
        return TokenType.BAD_CHARACTER
    }

    private fun single(type: IElementType): IElementType {
        tokenEnd = tokenStart + 1
        return type
    }

    private fun whitespace(): IElementType {
        var i = tokenStart
        while (i < bufferEnd && isWhitespace(buffer[i])) i++
        tokenEnd = i
        return TokenType.WHITE_SPACE
    }

    private fun lineComment(): IElementType {
        var i = tokenStart + 2
        while (i < bufferEnd && buffer[i] != '\n') i++
        tokenEnd = i
        return types.LINE_COMMENT
    }

    // Go block comments do not nest: the first "*/" closes. Unterminated runs to the end.
    private fun blockComment(): IElementType {
        var i = tokenStart + 2
        while (i < bufferEnd) {
            if (buffer[i] == '*' && i + 1 < bufferEnd && buffer[i + 1] == '/') {
                i += 2
                break
            }
            i++
        }
        tokenEnd = minOf(i, bufferEnd)
        return types.BLOCK_COMMENT
    }

    private fun rawString(): IElementType {
        var i = tokenStart + 1
        while (i < bufferEnd && buffer[i] != '`') i++
        tokenEnd = if (i < bufferEnd) i + 1 else bufferEnd
        return types.RAW_STRING
    }

    // Interpreted string or rune; an unterminated literal stops before the newline, even after a backslash.
    private fun quoted(quote: Char, type: IElementType): IElementType {
        var i = tokenStart + 1
        while (i < bufferEnd) {
            val ch = buffer[i]
            when {
                ch == '\n' -> break
                ch == '\\' -> i += if (charAt(i + 1) == '\n') 1 else 2
                ch == quote -> {
                    i++
                    break
                }
                else -> i++
            }
        }
        tokenEnd = minOf(i, bufferEnd)
        return type
    }

    private fun number(): IElementType {
        var i = tokenStart
        val prefix = charAt(i + 1)
        when {
            buffer[i] == '0' && (prefix == 'x' || prefix == 'X') -> {
                i = digits(i + 2, ::isHex)
                if (charAt(i) == '.') i = digits(i + 1, ::isHex)
                i = exponent(i, 'p', 'P')
            }
            buffer[i] == '0' && (prefix == 'b' || prefix == 'B' || prefix == 'o' || prefix == 'O') ->
                i = digits(i + 2, ::isDecimal)
            else -> {
                i = digits(i, ::isDecimal)
                if (charAt(i) == '.') i = digits(i + 1, ::isDecimal)
                i = exponent(i, 'e', 'E')
            }
        }
        if (charAt(i) == 'i') i++
        // Malformed suffixes (0b12x, 1abc) stay in the literal rather than starting an identifier.
        while (i < bufferEnd && isIdentifierPart(codePointAt(i))) i += Character.charCount(codePointAt(i))
        tokenEnd = minOf(i, bufferEnd)
        return types.NUMBER
    }

    private fun digits(from: Int, accept: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && (accept(buffer[i]) || buffer[i] == '_')) i++
        return i
    }

    private fun exponent(from: Int, lower: Char, upper: Char): Int {
        val c = charAt(from)
        if (c != lower && c != upper) return from
        var i = from + 1
        if (charAt(i) == '+' || charAt(i) == '-') i++
        return if (isDecimal(charAt(i))) digits(i, ::isDecimal) else from
    }

    private fun identifier(): IElementType {
        var i = tokenStart
        while (i < bufferEnd) {
            val cp = codePointAt(i)
            if (!isIdentifierPart(cp)) break
            i += Character.charCount(cp)
        }
        tokenEnd = minOf(i, bufferEnd)
        val text = buffer.subSequence(tokenStart, tokenEnd).toString()
        return when (text) {
            in KEYWORDS -> types.KEYWORD
            in CONSTANTS -> types.CONSTANT
            in BUILTINS -> types.BUILTIN
            else -> types.IDENTIFIER
        }
    }

    private fun codePointAt(offset: Int): Int {
        val c = buffer[offset]
        if (Character.isHighSurrogate(c) && offset + 1 < bufferEnd && Character.isLowSurrogate(buffer[offset + 1])) {
            return Character.toCodePoint(c, buffer[offset + 1])
        }
        return c.code
    }

    private fun matchOperator(): Int {
        for (op in OPERATORS) {
            if (op.length > bufferEnd - tokenStart) continue
            if (op.indices.all { buffer[tokenStart + it] == op[it] }) return op.length
        }
        return 0
    }

    companion object {
        val KEYWORDS: Set<String> = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else",
            "fallthrough", "for", "func", "go", "goto", "if", "import", "interface",
            "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
        )

        val BUILTINS: Set<String> = setOf(
            "append", "cap", "clear", "close", "complex", "copy", "delete", "imag",
            "len", "make", "max", "min", "new", "panic", "print", "println", "real", "recover",
            "any", "bool", "byte", "comparable", "complex64", "complex128", "error",
            "float32", "float64", "int", "int8", "int16", "int32", "int64", "rune",
            "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        )

        val CONSTANTS: Set<String> = setOf("true", "false", "iota", "nil")

        // Longest first so that "<<=" wins over "<<" and "<".
        private val OPERATORS = arrayOf(
            "<<=", ">>=", "&^=", "...",
            "&&", "||", "<-", "++", "--", "==", "!=", "<=", ">=", ":=",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "&^",
            "+", "-", "*", "/", "%", "&", "|", "^", "<", ">", "=", "!", "~", ":",
        )

        // BOM is only legal first in a file, but flagging it elsewhere is gopls' job.
        fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF'

        fun isDecimal(c: Char): Boolean = c in '0'..'9'

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        fun isIdentifierStart(cp: Int): Boolean = cp == '_'.code || Character.isLetter(cp)

        fun isIdentifierPart(cp: Int): Boolean = isIdentifierStart(cp) || Character.isDigit(cp)
    }
}
