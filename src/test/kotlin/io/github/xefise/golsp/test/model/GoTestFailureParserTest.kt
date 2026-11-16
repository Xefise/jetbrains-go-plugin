package io.github.xefise.golsp.test.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inputs are the test's own output as captured in testData/gotest-json/extra (testify v1.12.1, Go 1.27.3). */
class GoTestFailureParserTest {

    @Test
    fun `plain t Errorf gives the text after the location as message and the line as details`() {
        val failure = GoTestFailureParser.failure("    fail_test.go:18: got 1, want 2\n")
        assertEquals(GoTestFailure("got 1, want 2", "    fail_test.go:18: got 1, want 2"), failure)
    }

    @Test
    fun `multi-line error keeps every line in details and the first as message`() {
        val failure = GoTestFailureParser.failure("    fail_test.go:22: first line\n        second line\n")
        assertEquals("first line", failure.message)
        assertEquals("    fail_test.go:22: first line\n        second line", failure.details)
    }

    @Test
    fun `testify equal of ints gives expected and actual`() {
        val failure = GoTestFailureParser.failure(
            "    fail_test.go:36: \n" +
                "        \tError Trace:\t/work/cap/fail/fail_test.go:36\n" +
                "        \tError:      \tNot equal: \n" +
                "        \t            \texpected: 1\n" +
                "        \t            \tactual  : 2\n" +
                "        \tTest:       \tTestEqualInts\n",
        )
        assertEquals("Not equal", failure.message)
        assertEquals("1", failure.expected)
        assertEquals("2", failure.actual)
    }

    @Test
    fun `testify equal of strings unquotes quotes and newlines and stops before the diff`() {
        val failure = GoTestFailureParser.failure(
            "    fail_test.go:40: \n" +
                "        \tError Trace:\t/work/cap/fail/fail_test.go:40\n" +
                "        \tError:      \tNot equal: \n" +
                "        \t            \texpected: \"say \\\"hi\\\"\\nthere\"\n" +
                "        \t            \tactual  : \"say \\\"bye\\\"\\nthere\"\n" +
                "        \t            \t\n" +
                "        \t            \tDiff:\n" +
                "        \t            \t--- Expected\n" +
                "        \t            \t+++ Actual\n" +
                "        \t            \t@@ -1,2 +1,2 @@\n" +
                "        \t            \t-say \"hi\"\n" +
                "        \t            \t+say \"bye\"\n" +
                "        \t            \t there\n" +
                "        \tTest:       \tTestEqualStrings\n" +
                "        \tMessages:   \tgreeting for bob\n",
        )
        assertEquals("Not equal: greeting for bob", failure.message)
        assertEquals("say \"hi\"\nthere", failure.expected)
        assertEquals("say \"bye\"\nthere", failure.actual)
    }

    @Test
    fun `testify equal of structs keeps the go syntax value`() {
        val failure = GoTestFailureParser.failure(
            "    fail_test.go:44: \n" +
                "        \tError Trace:\t/work/cap/fail/fail_test.go:44\n" +
                "        \tError:      \tNot equal: \n" +
                "        \t            \texpected: fail.point{X:1, Y:2, Name:\"a\"}\n" +
                "        \t            \tactual  : fail.point{X:1, Y:3, Name:\"b\"}\n" +
                "        \t            \t\n" +
                "        \t            \tDiff:\n" +
                "        \t            \t- Y: (int) 2,\n" +
                "        \tTest:       \tTestEqualStructs\n",
        )
        assertEquals("fail.point{X:1, Y:2, Name:\"a\"}", failure.expected)
        assertEquals("fail.point{X:1, Y:3, Name:\"b\"}", failure.actual)
    }

    @Test
    fun `value spanning several lines is kept whole`() {
        val failure = GoTestFailureParser.failure(
            "    x_test.go:9: \n" +
                "        \tError:      \tNot equal: \n" +
                "        \t            \texpected: {\n" +
                "        \t            \t  \"a\": 1\n" +
                "        \t            \t}\n" +
                "        \t            \tactual  : {\n" +
                "        \t            \t  \"a\": 2\n" +
                "        \t            \t}\n" +
                "        \tTest:       \tTestJSON\n",
        )
        assertEquals("{\n  \"a\": 1\n}", failure.expected)
        assertEquals("{\n  \"a\": 2\n}", failure.actual)
    }

    @Test
    fun `testify assertion without comparison has message but no diff`() {
        val failure = GoTestFailureParser.failure(
            "    fail_test.go:52: \n" +
                "        \tError Trace:\t/work/cap/fail/fail_test.go:52\n" +
                "        \tError:      \tShould be true\n" +
                "        \tTest:       \tTestTrue\n" +
                "        \tMessages:   \tshould be true\n",
        )
        assertEquals("Should be true: should be true", failure.message)
        assertNull(failure.expected)
        assertNull(failure.actual)
    }

    @Test
    fun `strings on one side only are compared as printed`() {
        val failure = GoTestFailureParser.failure(
            "        \tError:      \tNot equal: \n" +
                "        \t            \texpected: \"1\"\n" +
                "        \t            \tactual  : 1\n",
        )
        assertEquals("\"1\"", failure.expected)
        assertEquals("1", failure.actual)
    }

    @Test
    fun `panic output gives the panic line as message`() {
        val failure = GoTestFailureParser.failure("panic: assignment to entry in nil map [recovered]\n\tpanic: x\n")
        assertEquals("panic: assignment to entry in nil map [recovered]", failure.message)
    }

    @Test
    fun `no output gives an empty failure`() {
        assertEquals(GoTestFailure("", ""), GoTestFailureParser.failure(""))
    }

    @Test
    fun `skip reason is the last line without its location`() {
        assertEquals("needs a database", GoTestFailureParser.skipReason("    skip_test.go:6: setting up\n    skip_test.go:7: needs a database\n"))
        assertEquals("", GoTestFailureParser.skipReason(""))
    }

    @Test
    fun `unquote handles go escapes and rejects anything that is not one literal`() {
        assertEquals("a\tb\\c\u00e9", GoTestFailureParser.unquote("\"a\\tb\\\\c\\u00e9\""))
        assertEquals("\\xff", GoTestFailureParser.unquote("\"\\xff\""))
        assertNull(GoTestFailureParser.unquote("\"a\" + \"b\""))
        assertNull(GoTestFailureParser.unquote("\"a\\q\""))
        assertNull(GoTestFailureParser.unquote("\""))
    }
}
