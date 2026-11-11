package io.github.xefise.golsp.lang

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoCommenterTest : BasePlatformTestCase() {
    private fun body(lines: String) = "package main\n\nfunc main() {\n$lines}\n"

    private fun perform(action: String, before: String, after: String) {
        myFixture.configureByText("a.go", before)
        myFixture.performEditorAction(action)
        myFixture.checkResult(after)
    }

    private fun roundTrip(action: String, before: String, commented: String) {
        perform(action, before, commented)
        myFixture.performEditorAction(action)
        assertEquals(before.replace(Regex("<[^>]+>"), ""), myFixture.editor.document.text)
    }

    private fun line(before: String, after: String) = roundTrip(IdeActions.ACTION_COMMENT_LINE, before, after)

    private fun block(before: String, after: String) = roundTrip(IdeActions.ACTION_COMMENT_BLOCK, before, after)

    fun testLineCommentCaretOnly() = perform(
        IdeActions.ACTION_COMMENT_LINE,
        body("\t<caret>ratings := []float64{}\n\tratings2 := []float64{}\n"),
        body("//\tratings := []float64{}\n\t<caret>ratings2 := []float64{}\n"),
    )

    fun testLineCommentFullLines() = line(
        body("<selection>\tratings := []float64{}\n\tratings2 := []float64{}\n</selection>"),
        body("<selection>//\tratings := []float64{}\n//\tratings2 := []float64{}\n</selection>"),
    )

    fun testLineCommentSelectionFromLineEnd() = line(
        body("\tratings := []float64{}<selection>\n\tratings2 := []float64{}</selection>\n"),
        body("//\tratings := []float64{}<selection>\n//\tratings2 := []float64{}</selection>\n"),
    )

    fun testLineCommentSelectionInsideBraces() = line(
        body("\tratings := []float64{<selection>}\n\tratings2 := []float64{</selection>}\n"),
        body("//\tratings := []float64{<selection>}\n//\tratings2 := []float64{</selection>}\n"),
    )

    fun testLineCommentShiftDownSelection() {
        myFixture.configureByText("a.go", body("<caret>\tratings := []float64{}\n\tratings2 := []float64{}\n"))
        repeat(2) { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN_WITH_SELECTION) }
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals(body("//\tratings := []float64{}\n//\tratings2 := []float64{}\n"), myFixture.editor.document.text)
    }

    fun testBlockCommentFullLinesGoesOnOwnLines() = block(
        body("<selection>\tratings := []float64{}\n\tratings2 := []float64{}\n</selection>"),
        body("<selection>/*\n\tratings := []float64{}\n\tratings2 := []float64{}\n*/\n</selection>"),
    )

    // The reported `{}/*` … `{}*/` shape: a selection that starts at the end of line 1 is wrapped as is.
    fun testBlockCommentWrapsExactSelectionFromLineEnd() = block(
        body("\tratings := []float64{}<selection>\n\tratings2 := []float64{}</selection>\n"),
        body("\tratings := []float64{}<selection>/*\n\tratings2 := []float64{}*/</selection>\n"),
    )

    fun testBlockCommentWrapsExactSelectionMidLine() = block(
        body("\tratings := []float64{<selection>}\n\tratings2 := []float64{</selection>}\n"),
        body("\tratings := []float64{<selection>/*}\n\tratings2 := []float64{*/</selection>}\n"),
    )

    fun testBlockCommentWithoutSelectionInsertsEmptyComment() =
        perform(
            IdeActions.ACTION_COMMENT_BLOCK,
            body("\t<caret>ratings := []float64{}\n"),
            body("\t/*<caret>*/ratings := []float64{}\n"),
        )

    fun testBlockUncommentWithCaretInsideComment() =
        perform(
            IdeActions.ACTION_COMMENT_BLOCK,
            body("\tx := 1 /* no<caret>te */\n"),
            body("\tx := 1  no<caret>te \n"),
        )

    fun testBlockCommentRefusesSelectionStartingInsideLineComment() {
        val text = body("\tx := 1 // no<selection>te\n\ty := 2</selection>\n")
        perform(IdeActions.ACTION_COMMENT_BLOCK, text, text)
    }

    fun testBlockCommentRefusesSelectionStartingRightAfterLineComment() {
        val text = body("\tx := 1 // note<selection>\n\ty := 2</selection>\n")
        perform(IdeActions.ACTION_COMMENT_BLOCK, text, text)
    }

    fun testBlockCommentRefusesSelectionSplittingBlockComment() {
        val text = body("\tx := 1 /* no<selection>te */\n\ty := 2</selection>\n")
        perform(IdeActions.ACTION_COMMENT_BLOCK, text, text)
    }
}
