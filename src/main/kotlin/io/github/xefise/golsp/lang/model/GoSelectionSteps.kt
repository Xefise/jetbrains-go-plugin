package io.github.xefise.golsp.lang.model

/** Text span `[start, end)`. */
data class GoSpan(val start: Int, val end: Int) {
    fun contains(other: GoSpan): Boolean = start <= other.start && other.end <= end
}

/**
 * Extend Selection steps from lexer tokens, without a parser: token → selector/call chain → list item →
 * bracket contents → brackets → statement lines → … → file. Each step strictly contains the previous one.
 */
object GoSelectionSteps {
    private val STATEMENT_KEYWORDS = setOf(
        "break", "case", "const", "continue", "default", "defer", "else", "fallthrough", "for", "go", "goto",
        "if", "import", "package", "range", "return", "select", "switch", "type", "var",
    )

    /** [tokens]: the whole file in order, as the lexer splits it; [offset]: the caret. */
    fun compute(text: CharSequence, tokens: List<GoIndentToken>, offset: Int): List<GoSpan> =
        Scan(text, tokens.filter { it.kind != GoIndentTokenKind.SPACE }).steps(offset)

    private class Scan(private val text: CharSequence, private val t: List<GoIndentToken>) {
        private val partner = matchBrackets()
        private val steps = ArrayList<GoSpan>()

        fun steps(offset: Int): List<GoSpan> {
            var at = t.indexOfFirst { it.start <= offset && offset < it.end }
            // Caret right after a word or group (`foo(a|)`, `a.b| + c`) belongs to the left token, not to a closer or a gap.
            val prev = t.indexOfFirst { it.end == offset && it.start < offset }
            if (prev >= 0 && (isOperand(prev) || isClose(prev) && partner[prev] >= 0) && (at < 0 || !isOperand(at) && !isOpen(at))) at = prev
            var first = -1
            var last = -1
            if (at < 0) {
                steps += GoSpan(offset, offset)
            } else {
                steps += span(at, at)
                first = at
                last = at
                if (isBracket(at) && partner[at] >= 0) {
                    first = minOf(at, partner[at])
                    last = maxOf(at, partner[at])
                    add(span(first, last))
                }
            }
            while (true) {
                if (first >= 0) chain(first, last)
                val level = enclosing(steps.last())
                listItem(level)
                statement(level)
                if (level < 0) break
                content(level)
                add(span(level, partner[level]))
                first = level
                last = partner[level]
            }
            add(GoSpan(0, text.length))
            return steps.filter { it.start < it.end }
        }

        private fun add(s: GoSpan) {
            val last = steps.last()
            // A caret in whitespace starts from an empty span that a trimmed step may not cover.
            if (last.start == last.end && s.start < s.end || s.contains(last) && s != last) steps += s
        }

        private fun chain(first: Int, last: Int) {
            var s = first
            while (s > 0 && adjacent(s - 1, s)) {
                val q = s - 1
                s = when {
                    isDot(q) && (isOperand(s) || isChar(s, '(')) -> q
                    (isDot(s) || isOpen(s)) && isOperand(q) -> q
                    (isDot(s) || isOpen(s) || isOperand(s)) && isChar(q, ']') && partner[q] >= 0 -> partner[q]
                    (isDot(s) || isOpen(s)) && isClose(q) && partner[q] >= 0 -> partner[q]
                    else -> break
                }
            }
            if (isDot(s)) s++
            var e = last
            if (s <= e && !isDot(e)) add(span(s, e))
            while (e + 1 < t.size && adjacent(e, e + 1)) {
                val r = e + 1
                e = when {
                    isUnitEnd(e) && isDot(r) -> r
                    (isUnitEnd(e) || isDot(e) && isChar(r, '(')) && isOpen(r) && partner[r] >= 0 -> partner[r]
                    (isDot(e) || isChar(e, ']')) && isOperand(r) -> r
                    else -> break
                }
                if (!isDot(e)) add(span(s, e))
            }
        }

        // Items of a ( ) or [ ] list are split by commas and by line breaks, nested groups skipped.
        private fun listItem(level: Int) {
            if (level < 0 || !(isChar(level, '(') || isChar(level, '['))) return
            val close = partner[level]
            val items = ArrayList<GoSpan>()
            var commas = 0
            var itemFirst = -1
            var itemLast = -1
            fun flush() {
                if (itemFirst >= 0) items += span(itemFirst, itemLast)
                itemFirst = -1
            }
            var i = level + 1
            while (i < close) {
                if (i > level + 1 && newlineBetween(i - 1, i)) flush()
                if (isChar(i, ',')) {
                    commas++
                    flush()
                } else {
                    if (itemFirst < 0) itemFirst = i
                    itemLast = if (isOpen(i) && partner[i] in (i + 1)..<close) partner[i] else i
                }
                i = itemLast.coerceAtLeast(i) + 1
            }
            flush()
            if (commas > 0) items.firstOrNull { it.contains(steps.last()) }?.let(::add)
        }

        // Whole lines of the current step, widened until every bracket, string and comment on them is closed.
        private fun statement(level: Int) {
            val lo = if (level < 0) 0 else t[level].end
            val hi = if (level < 0) text.length else t[partner[level]].start
            val cur = steps.last()
            var ls = lineStart(cur.start)
            var le = lineEnd(maxOf(cur.start, cur.end - 1))
            var changed = true
            while (changed) {
                changed = false
                for (i in t.indices) {
                    val tok = t[i]
                    if (tok.end <= ls || tok.start >= le) continue
                    val p = partner[i]
                    var nls = minOf(ls, lineStart(tok.start))
                    var nle = maxOf(le, lineEnd(tok.end - 1))
                    if (p >= 0 && p < i) nls = minOf(nls, lineStart(t[p].start))
                    if (p > i) nle = maxOf(nle, lineEnd(t[p].start))
                    if (nls != ls || nle != le) {
                        ls = nls
                        le = nle
                        changed = true
                    }
                }
            }
            if (ls < lo || le > hi) return
            var end = le
            val trailing = t.lastOrNull { it.start in ls..<le }
            if (trailing != null && trailing.kind == GoIndentTokenKind.COMMENT && text[trailing.start + 1] == '/') {
                val before = t.lastOrNull { it.end <= trailing.start }
                if (before != null && before.end > ls) end = trailing.start
            }
            trimmed(ls, end)?.let(::add)
        }

        private fun content(level: Int) {
            trimmed(t[level].end, t[partner[level]].start)?.let(::add)
        }

        private fun trimmed(from: Int, to: Int): GoSpan? {
            var s = from
            var e = to
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            return if (s < e) GoSpan(s, e) else null
        }

        // Innermost matched bracket pair strictly around [s]; -1 at the top level.
        private fun enclosing(s: GoSpan): Int {
            for (o in t.indices.reversed()) {
                if (t[o].end > s.start || !isOpen(o)) continue
                val p = partner[o]
                if (p >= 0 && t[p].start >= s.end) return o
            }
            return -1
        }

        private fun matchBrackets(): IntArray {
            val result = IntArray(t.size) { -1 }
            val stack = ArrayList<Int>()
            for (i in t.indices) {
                when (t[i].kind) {
                    GoIndentTokenKind.OPEN -> stack += i
                    GoIndentTokenKind.CLOSE -> {
                        val want = openerOf(text[t[i].start])
                        val k = stack.indexOfLast { text[t[it].start] == want }
                        if (k >= 0) {
                            result[stack[k]] = i
                            result[i] = stack[k]
                            while (stack.size > k) stack.removeAt(stack.size - 1)
                        }
                    }
                    else -> Unit
                }
            }
            return result
        }

        private fun openerOf(c: Char): Char = when (c) {
            ')' -> '('
            ']' -> '['
            else -> '{'
        }

        private fun span(first: Int, last: Int) = GoSpan(t[first].start, t[last].end)

        private fun adjacent(a: Int, b: Int) = t[a].end == t[b].start

        private fun newlineBetween(a: Int, b: Int): Boolean = (t[a].end until t[b].start).any { text[it] == '\n' }

        private fun isChar(i: Int, c: Char) = t[i].end - t[i].start == 1 && text[t[i].start] == c

        private fun isDot(i: Int) = t[i].kind == GoIndentTokenKind.CODE && isChar(i, '.')

        private fun isOpen(i: Int) = t[i].kind == GoIndentTokenKind.OPEN

        private fun isClose(i: Int) = t[i].kind == GoIndentTokenKind.CLOSE

        private fun isBracket(i: Int) = isOpen(i) || isClose(i)

        private fun isOperand(i: Int): Boolean {
            val tok = t[i]
            if (tok.kind == GoIndentTokenKind.STRING) return true
            if (tok.kind != GoIndentTokenKind.CODE) return false
            val c = text[tok.start]
            return (c.isLetterOrDigit() || c == '_') && text.substring(tok.start, tok.end) !in STATEMENT_KEYWORDS
        }

        private fun isUnitEnd(i: Int) = isOperand(i) || isClose(i) && partner[i] >= 0

        private fun lineStart(pos: Int): Int {
            var i = pos
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        private fun lineEnd(pos: Int): Int {
            var i = pos
            while (i < text.length && text[i] != '\n') i++
            return i
        }
    }
}
