package io.github.xefise.golsp.lang.model

object GoStringLiterals {
    // Value of a Go interpreted ("...") or raw (`...`) string literal; null if malformed or unterminated.
    fun unquote(literal: String): String? {
        if (literal.length < 2) return null
        val quote = literal.first()
        if (literal.last() != quote) return null
        val body = literal.substring(1, literal.length - 1)
        return when (quote) {
            '`' -> if ('`' in body) null else body.replace("\r", "")
            '"' -> unescape(body)
            else -> null
        }
    }

    private fun unescape(body: String): String? {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"' || c == '\n') return null
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) return null
            val e = body[i + 1]
            i += 2
            when (e) {
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000B')
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                'x', 'u', 'U' -> {
                    val len = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    val value = body.substring(i, minOf(i + len, body.length)).takeIf { it.length == len }
                        ?.toIntOrNull(16) ?: return null
                    i += len
                    // \x is a raw byte; only ASCII bytes map to a char without knowing neighbouring bytes.
                    if (e == 'x' && value > 0x7F) return null
                    if (!Character.isValidCodePoint(value) || value in 0xD800..0xDFFF) return null
                    out.appendCodePoint(value)
                }
                in '0'..'7' -> {
                    val digits = body.substring(i - 1, minOf(i + 2, body.length))
                    if (digits.length != 3 || digits.any { it !in '0'..'7' }) return null
                    val value = digits.toInt(8)
                    if (value > 0x7F) return null
                    out.append(value.toChar())
                    i += 2
                }
                else -> return null
            }
        }
        return out.toString()
    }
}
