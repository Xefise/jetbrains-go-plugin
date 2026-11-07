// Modified from the Go project (src/cmd/internal/test2json/test2json.go: lineBuffer, isBenchmarkName, trimUTF8),
// go1.27.3, BSD-3-Clause, Copyright 2017 The Go Authors
package io.github.xefise.golsp.test.model

/**
 * Splits written bytes into whole lines (newline included) passed to [line]; lines longer than the capacity are
 * passed to [part] in UTF-8-safe fragments, the last one ending with the newline.
 */
internal class GoTest2JsonLineBuffer(
    capacity: Int,
    private val line: (ByteArray) -> Unit,
    private val part: (ByteArray) -> Unit,
) {
    private val buf = ByteArray(capacity)
    private var len = 0
    private var mid = false
    private var escaped = false

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val m = minOf(buf.size - len, end - pos)
            System.arraycopy(bytes, pos, buf, len, m)
            len += m
            pos += m

            var i = 0
            while (i < len) {
                val (j, w) = indexEOL(i)
                if (j < 0) {
                    if (!mid) {
                        val tab = indexOf(TAB, i)
                        if (tab >= 0 && isBenchmarkName(trimRightSpaces(i, tab))) {
                            part(buf.copyOfRange(i, tab + 1))
                            mid = true
                            i = tab + 1
                        }
                    }
                    break
                }
                val e = i + j + w
                if (mid) {
                    part(buf.copyOfRange(i, e))
                    mid = false
                } else {
                    line(buf.copyOfRange(i, e))
                }
                i = e
            }

            if (i == 0 && len == buf.size) {
                val t = trimUTF8(buf, len)
                part(buf.copyOfRange(0, t))
                System.arraycopy(buf, t, buf, 0, len - t)
                len -= t
                mid = true
            }
            if (i > 0) {
                System.arraycopy(buf, i, buf, 0, len - i)
                len -= i
            }
        }
    }

    fun flush() {
        if (len > 0) {
            part(buf.copyOfRange(0, len))
            len = 0
        }
    }

    /**
     * Position (relative to [from]) and width of the next line ending: a newline (width 1) or the empty string
     * before a ^V that does not begin the line (width 0, the ^V starts the next line).
     */
    private fun indexEOL(from: Int): Pair<Int, Int> {
        for (k in from until len) {
            val c = buf[k]
            if (c == NEWLINE) return (k - from) to 1
            if (escaped) {
                escaped = false
                continue
            }
            if (c == GoTest2Json.MARK_ESCAPE) {
                escaped = true
                continue
            }
            if (c == GoTest2Json.MARK_FRAMING && k > from) return (k - from) to 0
        }
        return -1 to 0
    }

    private fun indexOf(b: Byte, from: Int): Int {
        for (k in from until len) if (buf[k] == b) return k
        return -1
    }

    private fun trimRightSpaces(from: Int, to: Int): ByteArray {
        var e = to
        while (e > from && buf[e - 1] == SPACE) e--
        return buf.copyOfRange(from, e)
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private val BENCHMARK = "Benchmark".toByteArray()
        private const val UTF_MAX = 4

        /** Whether [b] can be the first field of a benchmark result line: "Benchmark" not followed by a lower-case rune. */
        fun isBenchmarkName(b: ByteArray): Boolean {
            if (b.size < BENCHMARK.size || !BENCHMARK.indices.all { b[it] == BENCHMARK[it] }) return false
            if (b.size == BENCHMARK.size) return true
            val rest = String(b, BENCHMARK.size, b.size - BENCHMARK.size, Charsets.UTF_8)
            return Character.getType(rest.codePointAt(0)) != Character.LOWERCASE_LETTER.toInt()
        }

        /** The largest `t <= length` such that `b[0, t)` does not end in the middle of a possibly-valid UTF-8 sequence. */
        fun trimUTF8(b: ByteArray, length: Int): Int {
            var i = 1
            while (i < UTF_MAX && i <= length) {
                val c = b[length - i].toInt() and 0xff
                if (c and 0xc0 != 0x80) {
                    when {
                        c and 0xe0 == 0xc0 -> if (i < 2) return length - i
                        c and 0xf0 == 0xe0 -> if (i < 3) return length - i
                        c and 0xf8 == 0xf0 -> if (i < 4) return length - i
                    }
                    break
                }
                i++
            }
            return length
        }
    }
}
