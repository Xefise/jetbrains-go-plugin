package io.github.xefise.golsp.lsp.model

import io.github.xefise.golsp.lang.GoLexer
import io.github.xefise.golsp.lang.goIndentKind
import io.github.xefise.golsp.lang.model.GoIndentToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoRefactoringsMenuTest {
    private fun offers(source: String, hasSelection: Boolean = false): Boolean {
        val offset = source.indexOf("<caret>")
        val text = source.replace("<caret>", "")
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<GoIndentToken>()
        while (lexer.tokenType != null) {
            tokens += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return GoRefactoringsMenu.mayOffer(text, tokens, offset, hasSelection)
    }

    private fun body(line: String) = "package main\n\nfunc main() {\n\t$line\n}\n"

    @Test
    fun `selection offers anywhere`() = assertTrue(offers(body("x := 1<caret>"), hasSelection = true))

    @Test
    fun `plain statement in a func body offers nothing`() = assertFalse(offers(body("x := 1<caret>")))

    @Test
    fun `blank top level offers nothing`() = assertFalse(offers("package main\n\n<caret>\n"))

    @Test
    fun `caret inside an empty composite literal offers`() = assertTrue(offers(body("p := Point{<caret>}")))

    @Test
    fun `caret on the opening brace of a composite literal offers`() = assertTrue(offers(body("p := Point<caret>{}")))

    @Test
    fun `caret right after a closed composite literal offers nothing`() = assertFalse(offers(body("p := Point{}<caret>")))

    @Test
    fun `caret on the opening brace of an if body offers through the if keyword only`() =
        assertFalse(offers(body("if ok <caret>{\n\t}")))

    @Test
    fun `caret on if offers`() = assertTrue(offers(body("i<caret>f ok {\n\t}")))

    @Test
    fun `caret right after switch offers`() = assertTrue(offers(body("switch<caret> x {\n\t}")))

    @Test
    fun `identifier that merely contains if offers nothing`() = assertFalse(offers(body("iff<caret> := 1")))

    @Test
    fun `caret inside an interpreted string offers`() = assertTrue(offers(body("s := \"he<caret>llo\"")))

    @Test
    fun `caret inside a raw string offers`() = assertTrue(offers(body("s := `he<caret>llo`")))

    @Test
    fun `caret inside a rune literal offers nothing`() = assertFalse(offers(body("r := 'x<caret>'")))

    @Test
    fun `caret inside a comment offers nothing`() = assertFalse(offers(body("// Point{<caret>}")))

    @Test
    fun `caret on a struct field offers`() = assertTrue(offers("package main\n\ntype S struct {\n\tNa<caret>me string\n}\n"))

    @Test
    fun `caret on a function parameter offers`() = assertTrue(offers("package main\n\nfunc f(a<caret> int, b string) {}\n"))

    @Test
    fun `caret on func offers`() = assertTrue(offers("package main\n\nfu<caret>nc f() {}\n"))

    @Test
    fun `caret on a function name offers`() = assertTrue(offers("package main\n\nfunc com<caret>pute() {}\n"))

    @Test
    fun `caret on a called name outside brackets offers nothing`() = assertFalse(offers(body("com<caret>pute()")))

    @Test
    fun `caret at the end of a file in a broken literal still offers`() = assertTrue(offers("package main\n\nvar p = Point{<caret>"))

    @Test
    fun `offset past the text offers nothing`() =
        assertFalse(GoRefactoringsMenu.mayOffer("x", emptyList(), 5, hasSelection = false))

    private fun kind(kind: String): GoCodeActionKind = GoCodeActionCatalog.find(kind)!!

    @Test
    fun `entries drop kinds that have platform handlers`() {
        val all = listOf(
            "refactor.extract.function", "refactor.extract.method", "refactor.extract.variable",
            "refactor.extract.variable-all", "refactor.extract.constant", "refactor.extract.constant-all",
            "refactor.inline.call", "refactor.inline.variable", "refactor.extract.toNewFile", "refactor.rewrite.fillStruct",
        ).map(::kind)
        assertEquals(listOf("refactor.extract.toNewFile", "refactor.rewrite.fillStruct"), GoRefactoringsMenu.entries(all) { it }.map { it.kind })
    }

    @Test
    fun `entries drop source test and organize imports even when they reach the menu`() {
        val fake = listOf(
            GoCodeActionKind("source.test", GoCodeActionGroup.SOURCE, "Run test", needsSelection = false),
            GoCodeActionKind("source.organizeImports", GoCodeActionGroup.SOURCE, "Organize", needsSelection = false),
            kind("source.addTest"),
        )
        assertEquals(listOf("source.addTest"), GoRefactoringsMenu.entries(fake) { it }.map { it.kind })
    }

    @Test
    fun `entries group by kind group then catalog order`() {
        val shuffled = listOf("source.doc", "refactor.rewrite.invertIf", "source.addTest", "refactor.extract.toNewFile", "refactor.rewrite.fillStruct")
            .map(::kind)
        // EXTRACT < REWRITE < SOURCE; fillStruct precedes invertIf and addTest precedes doc in the catalog.
        assertEquals(
            listOf("refactor.extract.toNewFile", "refactor.rewrite.fillStruct", "refactor.rewrite.invertIf", "source.addTest", "source.doc"),
            GoRefactoringsMenu.entries(shuffled) { it }.map { it.kind },
        )
    }

    @Test
    fun `unknown rewrite kind sorts after the known rewrites`() {
        val list = listOf(kind("refactor.rewrite.somethingNew"), kind("refactor.rewrite.invertIf"), kind("source.doc"))
        assertEquals(
            listOf("refactor.rewrite.invertIf", "refactor.rewrite.somethingNew", "source.doc"),
            GoRefactoringsMenu.entries(list) { it }.map { it.kind },
        )
    }

    @Test
    fun `no actions give no entries`() = assertEquals(emptyList<GoCodeActionKind>(), GoRefactoringsMenu.entries(emptyList<GoCodeActionKind>()) { it })
}
