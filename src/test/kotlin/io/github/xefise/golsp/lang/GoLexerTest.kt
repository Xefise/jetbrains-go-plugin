// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class GoLexerTest {
    private fun go(text: String) = tokens(GoLexer(), text)

    @Test
    fun classifiesIdentifierKinds() {
        assertEquals(
            listOf("KEYWORD:func", "IDENTIFIER:main", "LPAREN:(", "RPAREN:)", "LBRACE:{", "RBRACE:}"),
            go("func main() {}"),
        )
        assertEquals(listOf("BUILTIN:len", "LPAREN:(", "IDENTIFIER:xs", "RPAREN:)"), go("len(xs)"))
        assertEquals(listOf("CONSTANT:nil", "CONSTANT:true", "CONSTANT:iota"), go("nil true iota"))
    }

    @Test
    fun unicodeIdentifiers() {
        assertEquals(listOf("IDENTIFIER:привет", "IDENTIFIER:_x9", "IDENTIFIER:𝑥"), go("привет _x9 𝑥"))
        assertEquals(listOf("BAD_CHARACTER:😀"), go("😀"))
    }

    @Test
    fun interpretedStrings() {
        assertEquals(listOf("STRING:\"hi\""), go("\"hi\""))
        assertEquals(listOf("STRING:\"a\\\"b\"", "IDENTIFIER:x"), go("\"a\\\"b\" x"))
        assertEquals(listOf("STRING:\"`\""), go("\"`\""))
    }

    @Test
    fun rawStringsSpanLinesAndIgnoreEscapes() {
        assertEquals(listOf("RAW_STRING:`a\nb`"), go("`a\nb`"))
        assertEquals(listOf("RAW_STRING:`\\`", "IDENTIFIER:x"), go("`\\` x"))
        assertEquals(listOf("RAW_STRING:`/* \"x`", "IDENTIFIER:y"), go("`/* \"x` y"))
        assertEquals(listOf("RAW_STRING:`open\nto end"), go("`open\nto end"))
    }

    @Test
    fun runes() {
        assertEquals(listOf("CHAR:'x'"), go("'x'"))
        assertEquals(listOf("CHAR:'\\''", "IDENTIFIER:y"), go("'\\'' y"))
        assertEquals(listOf("CHAR:'\\x41'", "CHAR:'\\u12e4'", "CHAR:'\"'"), go("'\\x41' '\\u12e4' '\"'"))
        assertEquals(listOf("CHAR:'`'", "IDENTIFIER:z"), go("'`' z"))
    }

    @Test
    fun unterminatedLiteralsStopAtEndOfLine() {
        assertEquals(listOf("STRING:\"oops", "KEYWORD:var"), go("\"oops\nvar").take(2))
        assertEquals(listOf("STRING:\"tail\\", "KEYWORD:var"), go("\"tail\\\nvar").take(2))
        assertEquals(listOf("CHAR:'", "KEYWORD:var"), go("'\nvar").take(2))
    }

    @Test
    fun comments() {
        assertEquals(listOf("LINE_COMMENT:// note", "KEYWORD:var"), go("// note\nvar"))
        assertEquals(listOf("BLOCK_COMMENT:/* a\nb */"), go("/* a\nb */"))
        assertEquals(listOf("BLOCK_COMMENT:/* a"), go("/* a"))
        // Not nested: the first */ closes.
        assertEquals(listOf("BLOCK_COMMENT:/* /* */", "IDENTIFIER:x", "OPERATOR:*", "OPERATOR:/"), go("/* /* */ x */"))
        assertEquals(listOf("BLOCK_COMMENT:/**/", "IDENTIFIER:x"), go("/**/x"))
        assertEquals(listOf("STRING:\"// no\""), go("\"// no\""))
    }

    @Test
    fun decimalAndFloatNumbers() {
        for (n in listOf("0", "42", "1_000", "0755", "0.", "1.5", ".25", "1e9", "1.5e-3", "6.67E+11", "1_0.0_1e1_0", "089.5")) {
            assertEquals(n, listOf("NUMBER:$n"), go(n))
        }
    }

    @Test
    fun prefixedNumbers() {
        for (n in listOf("0x1f", "0XFF", "0x_1F", "0b1010", "0B_1", "0o17", "0O7_7", "0x1p-2", "0x1.8p3", "0X.8p0", "0x1P+2")) {
            assertEquals(n, listOf("NUMBER:$n"), go(n))
        }
    }

    @Test
    fun imaginaryNumbers() {
        for (n in listOf("1i", "0i", "1.5i", "1e3i", "0x1fi", "0b1i", "0o7i", ".5i")) {
            assertEquals(n, listOf("NUMBER:$n"), go(n))
        }
    }

    @Test
    fun numbersDoNotSwallowOperators() {
        assertEquals(listOf("NUMBER:0x1e", "OPERATOR:+", "NUMBER:2"), go("0x1e+2"))
        assertEquals(listOf("NUMBER:1", "OPERATOR:-", "NUMBER:2"), go("1-2"))
        assertEquals(listOf("NUMBER:1e5", "OPERATOR:-", "IDENTIFIER:x"), go("1e5-x"))
        assertEquals(
            listOf("IDENTIFIER:a", "LBRACKET:[", "NUMBER:1", "OPERATOR::", "NUMBER:2", "RBRACKET:]"),
            go("a[1:2]"),
        )
    }

    @Test
    fun malformedNumberSuffixStaysInLiteral() {
        assertEquals(listOf("NUMBER:123abc"), go("123abc"))
        assertEquals(listOf("NUMBER:1e"), go("1e"))
    }

    @Test
    fun longestOperatorWins() {
        assertEquals(listOf("IDENTIFIER:a", "OPERATOR:<<=", "IDENTIFIER:b"), go("a <<= b"))
        assertEquals(listOf("IDENTIFIER:a", "OPERATOR:&^=", "IDENTIFIER:b"), go("a &^= b"))
        assertEquals(listOf("IDENTIFIER:a", "OPERATOR::=", "IDENTIFIER:b"), go("a := b"))
        assertEquals(listOf("OPERATOR:<-", "IDENTIFIER:ch"), go("<-ch"))
        assertEquals(listOf("IDENTIFIER:fmt", "DOT:.", "IDENTIFIER:Println"), go("fmt.Println"))
        assertEquals(listOf("IDENTIFIER:args", "OPERATOR:..."), go("args..."))
        assertEquals(listOf("OPERATOR:~", "BUILTIN:int"), go("~int"))
    }

    @Test
    fun badCharacters() {
        assertEquals(listOf("BAD_CHARACTER:@", "BAD_CHARACTER:#", "BAD_CHARACTER:$", "BAD_CHARACTER:?"), go("@ # $ ?"))
        assertEquals(listOf("IDENTIFIER:x"), go("\uFEFFx"))
    }

    @Test
    fun tokensCoverTextWithoutGaps() {
        val text = "package p\r\n\n/* c */ var s = `x\ny` + \"\\n\" // e\nfunc f() { _ = 'a' }\n"
        val all = lexAll(GoLexer(), text)
        assertEquals(text, all.joinToString("") { it.text })
        all.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun restartsFromAnyTokenBoundary() {
        val text = "func f() {\n\ts := `a\n/*b*/`\n\t/* \"x */ r := '\\''\n}\n"
        val full = lexAll(GoLexer(), text)
        for (token in full) {
            assertEquals(full.dropWhile { it.start < token.start }, lexAll(GoLexer(), text, token.start))
        }
    }

    @Test
    fun goModWords() {
        val mod = tokens(GoModLexer(GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS), "module example.com/m\ngo 1.22\nrequire (\n\tgolang.org/x/mod v0.20.0 // indirect\n)\nreplace a => ../a\nretract [v1.0.0, v1.0.5]\n")
        assertEquals(
            listOf(
                "KEYWORD:module", "IDENTIFIER:example.com/m", "KEYWORD:go", "NUMBER:1.22",
                "KEYWORD:require", "LPAREN:(", "IDENTIFIER:golang.org/x/mod", "NUMBER:v0.20.0", "LINE_COMMENT:// indirect",
                "RPAREN:)", "KEYWORD:replace", "IDENTIFIER:a", "OPERATOR:=>", "IDENTIFIER:../a",
                "KEYWORD:retract", "LBRACKET:[", "NUMBER:v1.0.0", "COMMA:,", "NUMBER:v1.0.5", "RBRACKET:]",
            ),
            mod,
        )
        assertEquals(listOf("IDENTIFIER:func"), tokens(GoModLexer(GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS), "func"))
        assertEquals(listOf("STRING:\"a b\"", "RAW_STRING:`c`"), tokens(GoModLexer(GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS), "\"a b\" `c`"))
    }

    @Test
    fun goModVersionsOnlySemverLike() {
        val mod = tokens(GoModLexer(GoModTokenTypes, GoModLexer.GO_MOD_KEYWORDS), "9fans.net/go v0.0.0-20181112161624-c7f4c2e3d5f0 v2.0.0+incompatible 1.23rc1 v1 vtool 1abc 1.2.x")
        assertEquals(
            listOf(
                "IDENTIFIER:9fans.net/go", "NUMBER:v0.0.0-20181112161624-c7f4c2e3d5f0", "NUMBER:v2.0.0+incompatible",
                "NUMBER:1.23rc1", "NUMBER:v1", "IDENTIFIER:vtool", "IDENTIFIER:1abc", "IDENTIFIER:1.2.x",
            ),
            mod,
        )
    }

    @Test
    fun goWorkKeywords() {
        val work = tokens(GoModLexer(GoWorkTokenTypes, GoModLexer.GO_WORK_KEYWORDS), "go 1.23\nuse (\n\t./a\n)\nmodule x")
        assertEquals(
            listOf("KEYWORD:go", "NUMBER:1.23", "KEYWORD:use", "LPAREN:(", "IDENTIFIER:./a", "RPAREN:)", "IDENTIFIER:module", "IDENTIFIER:x"),
            work,
        )
    }
}
