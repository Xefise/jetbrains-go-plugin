package io.github.xefise.golsp.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoPsiTest : BasePlatformTestCase() {
    private fun parse(text: String, name: String = "a_test.go"): GoFile =
        myFixture.configureByText(name, text) as GoFile

    fun testPackageClause() {
        val file = parse("// Package doc.\n\n/* x */ package mypkg_test\n\nimport \"testing\"\n")
        assertEquals("mypkg_test", file.packageName)
        assertEquals("package mypkg_test", file.packageClause!!.text)
    }

    fun testMissingOrIncompletePackage() {
        assertNull(parse("func f() {}\n").packageName)
        assertNull(parse("package\n").packageName)
        assertNull(parse("").packageName)
    }

    fun testFunctionHeaders() {
        val file = parse(
            """
            package p

            func TestA(t *testing.T) {
                f := func() {}
                _ = f
            }

            func (s *Suite) TestMethod(t *testing.T) error { return nil }

            func (Stack[T]) Push(v T) {}

            func Map[K comparable, V any](m map[K]V) []K { return nil }

            func (c *conn) close() {}

            var handler = func(w int) {}
            var g = func(a A) B { return nil }
            var h = func() (int, error) { return 0, nil }
            var k = func() Box[int] { return nil }

            func noBody()

            func init() {}
            """.trimIndent(),
        )
        val headers = file.functionHeaders
        assertEquals(
            listOf("TestA", "TestMethod", "Push", "Map", "close", "noBody", "init"),
            headers.map { it.functionName },
        )
        assertEquals(listOf(null, "Suite", "Stack", null, "conn", null, null), headers.map { it.receiver?.typeName })
        assertEquals("(t *testing.T)", headers[0].parametersText)
        assertEquals("(m map[K]V)", headers[3].parametersText)
        assertEquals("func TestA(t *testing.T)", headers[0].text)
        assertEquals("TestA", headers[0].nameIdentifier!!.text)
        assertTrue(headers[1].isMethod)
    }

    fun testNestedAndIncompleteFunctionsAreNotHeaders() {
        val file = parse("package p\n\nfunc outer() {\n\tfunc inner() {}\n}\n\nfunc Broken(\n")
        assertEquals(listOf("outer"), file.functionHeaders.map { it.functionName })
    }

    fun testUnclosedBraceDoesNotHideLaterFunctions() {
        val file = parse("package p\n\nfunc Broken() {\n\tif x {\n\n\tfunc nested() {}\nfunc TestLater(t *testing.T) {\n\tt.Run(\"sub\", nil)\n}\n")
        assertEquals(listOf("Broken", "TestLater"), file.functionHeaders.map { it.functionName })
        assertEquals(listOf("sub"), file.subtestCalls.map { it.subtestName })
    }

    fun testSubtestCalls() {
        val file = parse(
            """
            package p

            func TestX(t *testing.T) {
                t.Run("simple", func(t *testing.T) {
                    t.Run(`raw name`, func(t *testing.T) {})
                    t.Run("esc\tname", nil)
                })
                t.Run(name, nil)
                t.Run(fmt.Sprint(1), nil)
                t.Runner("no", nil)
            }

            func BenchmarkY(b *testing.B) {
                b.Run("bench", nil)
            }
            """.trimIndent(),
        )
        val calls = file.subtestCalls
        assertEquals(listOf("simple", "raw name", "esc\tname", "bench"), calls.map { it.subtestName })
        assertEquals(listOf("t", "t", "t", "b"), calls.map { it.receiverName })
        assertEquals("t.Run(\"simple\"", calls[0].text)
        assertEquals(listOf("TestX", "BenchmarkY"), file.functionHeaders.map { it.functionName })
    }

    fun testTreeKeepsText() {
        val text = "package p\n\nfunc (r *R) M() { t.Run(\"x\", nil) }\n`unterminated"
        assertEquals(text, parse(text).text)
    }
}
