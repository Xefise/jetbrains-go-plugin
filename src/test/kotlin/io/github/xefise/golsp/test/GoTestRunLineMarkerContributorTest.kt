package io.github.xefise.golsp.test

import com.intellij.execution.TestStateStorage
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.test.model.GoTestKind
import java.util.Date

class GoTestRunLineMarkerContributorTest : BasePlatformTestCase() {
    private val contributor = GoTestRunLineMarkerContributor()

    private fun markedLeaves(text: String, name: String = "calc_test.go"): List<PsiElement> {
        val file = myFixture.configureByText(name, text)
        return PsiTreeUtil.collectElements(file) { it.firstChild == null }.filter { contributor.getInfo(it) != null }
    }

    // Full names of the marked tests, in source order.
    private fun marked(text: String, name: String = "calc_test.go"): List<String> =
        markedLeaves(text, name).map { GoTestDeclarations.at(it)!!.fullName }

    private fun test(body: String) = "package calc\n\nimport \"testing\"\n\n$body\n"

    fun testContributorIsRegisteredForGoLanguage() {
        assertTrue(RunLineMarkerContributor.EXTENSION.allForLanguage(GoLanguage).any { it is GoTestRunLineMarkerContributor })
    }

    fun testTestFunctionIsMarkedOnItsName() {
        val text = test("func TestAdd(t *testing.T) {}")
        assertEquals(listOf(text.indexOf("TestAdd")), markedLeaves(text).map(PsiElement::getTextOffset))
    }

    fun testMarkerOffersExecutorActions() {
        val leaf = markedLeaves(test("func TestAdd(t *testing.T) {}")).single()
        assertTrue(contributor.getInfo(leaf)!!.actions.isNotEmpty())
    }

    fun testEveryTestKindIsMarkedWithItsKind() {
        val text = test(
            """
            func TestA(t *testing.T) {}
            func BenchmarkB(b *testing.B) {}
            func FuzzC(f *testing.F) {}
            func ExampleD() {
            	// Output: d
            }
            func Test(t *testing.T) {}
            func Test_under(t *testing.T) {}
            """.trimIndent(),
        )
        val leaves = markedLeaves(text)
        assertEquals(listOf("TestA", "BenchmarkB", "FuzzC", "ExampleD", "Test", "Test_under"), leaves.map { GoTestDeclarations.at(it)!!.fullName })
        assertEquals(
            listOf(GoTestKind.TEST, GoTestKind.BENCHMARK, GoTestKind.FUZZ, GoTestKind.EXAMPLE, GoTestKind.TEST, GoTestKind.TEST),
            leaves.map { GoTestDeclarations.at(it)!!.kind },
        )
    }

    fun testLowercaseAfterPrefixIsNotATest() {
        assertEquals(emptyList<String>(), marked(test("func Testfoo(t *testing.T) {}\nfunc Benchmarkx(b *testing.B) {}\nfunc Examplelower() {\n\t// Output:\n}")))
    }

    fun testTestMainIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func TestMain(m *testing.M) {}")))
    }

    fun testMethodWithTestNameIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("type S struct{}\n\nfunc (s *S) TestThing(t *testing.T) {}")))
    }

    fun testNonTestFileIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func TestAdd(t *testing.T) {\n\tt.Run(\"x\", nil)\n}"), "calc.go"))
    }

    fun testExampleWithoutOutputCommentIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func ExampleAdd() {\n\tprintln(1)\n}")))
    }

    fun testExampleWithOutputOnlyInAnEarlierCommentGroupIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func ExampleAdd() {\n\t// Output: 1\n\tprintln(1)\n\n\t// trailing note\n}")))
    }

    fun testExampleWithMultiLineOutputGroupIsMarked() {
        assertEquals(listOf("ExampleAdd"), marked(test("func ExampleAdd() {\n\tprintln(1)\n\t// Output:\n\t// 1\n}")))
    }

    fun testExampleWithUnorderedOutputInAnyCaseIsMarked() {
        assertEquals(listOf("ExampleAdd"), marked(test("func ExampleAdd() {\n\tprintln(1)\n\t// unordered OUTPUT:\n\t// 1\n}")))
    }

    fun testExampleWithBlockOutputCommentIsMarked() {
        assertEquals(listOf("ExampleAdd"), marked(test("func ExampleAdd() {\n\tprintln(1)\n\t/* Output: 1 */\n}")))
    }

    fun testExampleWithParametersIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func ExampleAdd(x int) {\n\t// Output: 1\n}")))
    }

    // The doc comment of the next function is outside the example body.
    fun testOutputCommentAfterExampleBodyDoesNotCount() {
        assertEquals(listOf("TestNext"), marked(test("func ExampleAdd() {\n\tprintln(1)\n}\n\n// Output: 1\nfunc TestNext(t *testing.T) {}")))
    }

    fun testLiteralSubtestIsMarkedWithParentPath() {
        val text = test("func TestAdd(t *testing.T) {\n\tt.Run(\"sub case\", func(t *testing.T) {})\n}")
        val leaves = markedLeaves(text)
        assertEquals(listOf(text.indexOf("TestAdd"), text.indexOf("\"sub case\"")), leaves.map(PsiElement::getTextOffset))
        assertEquals(listOf("TestAdd", "TestAdd/sub case"), leaves.map { GoTestDeclarations.at(it)!!.fullName })
    }

    fun testSubtestNameKeepsSlashAndRawStringsAreUnquoted() {
        val text = test("func TestAdd(t *testing.T) {\n\tt.Run(\"a/b\", func(t *testing.T) {})\n\tt.Run(`raw name`, func(t *testing.T) {})\n}")
        assertEquals(listOf("TestAdd", "TestAdd/a/b", "TestAdd/raw name"), marked(text))
    }

    fun testNestedSubtestsGetFullPath() {
        val text = test(
            """
            func TestAdd(t *testing.T) {
            	t.Run("outer", func(t *testing.T) {
            		t.Run("inner", func(t *testing.T) {})
            	})
            	t.Run("second", func(t *testing.T) {})
            }
            """.trimIndent(),
        )
        assertEquals(setOf("TestAdd", "TestAdd/outer", "TestAdd/outer/inner", "TestAdd/second"), marked(text).toSet())
    }

    fun testSubtestOfBenchmarkIsMarked() {
        assertEquals(setOf("BenchmarkAdd", "BenchmarkAdd/small"), marked(test("func BenchmarkAdd(b *testing.B) {\n\tb.Run(\"small\", func(b *testing.B) {})\n}")).toSet())
    }

    fun testLiteralInsideDynamicSubtestIsNotMarked() {
        val text = test(
            """
            func TestAdd(t *testing.T) {
            	for _, tc := range cases {
            		t.Run(tc.name, func(t *testing.T) {
            			t.Run("lit", func(t *testing.T) {})
            		})
            	}
            	t.Run("after", func(t *testing.T) {})
            }
            """.trimIndent(),
        )
        assertEquals(setOf("TestAdd", "TestAdd/after"), marked(text).toSet())
    }

    fun testRunOnNonTestingReceiverIsNotMarked() {
        assertEquals(listOf("TestServer"), marked(test("func TestServer(t *testing.T) {\n\tr := gin.New()\n\tr.Run(\":8080\")\n}")))
    }

    // Fuzz subtests run under generated seed names, so a literal path would be wrong.
    fun testSubtestInsideFuzzIsNotMarked() {
        assertEquals(listOf("FuzzAdd"), marked(test("func FuzzAdd(f *testing.F) {\n\tf.Fuzz(func(t *testing.T, a int) {\n\t\tt.Run(\"x\", func(t *testing.T) {})\n\t})\n}")))
    }

    fun testFileInExcludedDirectoryIsNotMarked() {
        val file = myFixture.addFileToProject("gen/gen_test.go", test("func TestGen(t *testing.T) {}"))
        val leaf = file.findElementAt(file.text.indexOf("TestGen"))!!
        assertNotNull(contributor.getInfo(leaf))
        val dir = file.virtualFile.parent
        PsiTestUtil.addExcludedRoot(module, dir)
        try {
            assertNull(contributor.getInfo(leaf))
        } finally {
            PsiTestUtil.removeExcludedRoot(module, dir)
        }
    }

    fun testWrongSignaturesAreNotMarked() {
        val text = test(
            """
            func TestNoParams() {}
            func TestWrongType(t *testing.B) {}
            func TestTwoNames(a, b *testing.T) {}
            func TestTwoParams(t *testing.T, x int) {}
            func TestResult(t *testing.T) error { return nil }
            func TestGeneric[P any](t *testing.T) {}
            func BenchmarkWithT(t *testing.T) {}
            func FuzzWithT(t *testing.T) {}
            func TestUnnamed(*testing.T) {}
            """.trimIndent(),
        )
        assertEquals(listOf("TestUnnamed"), marked(text))
    }

    // cmd/go treats TestMain(*testing.T) as an ordinary test.
    fun testTestMainTakingTIsATest() {
        assertEquals(listOf("TestMain"), marked(test("func TestMain(t *testing.T) {}")))
    }

    fun testLiteralThatIsNotTheWholeArgumentIsDynamic() {
        val text = test("func TestAdd(t *testing.T) {\n\tt.Run(\"a\"+x, func(t *testing.T) {\n\t\tt.Run(\"in\", nil)\n\t})\n\tt.Run(\"b\", nil)\n}")
        assertEquals(setOf("TestAdd", "TestAdd/b"), marked(text).toSet())
    }

    fun testRunOnAFieldIsNotASubtest() {
        assertEquals(listOf("TestAdd"), marked(test("func TestAdd(t *testing.T) {\n\ts.t.Run(\"x\", nil)\n}")))
    }

    fun testReceiverMustBeTheTestingParameter() {
        assertEquals(listOf("TestAdd"), marked(test("func TestAdd(tt *testing.T) {\n\tt.Run(\"x\", nil)\n}")))
    }

    fun testUnnamedParameterHasNoSubtests() {
        assertEquals(listOf("TestAdd"), marked(test("func TestAdd(*testing.T) {\n\tt.Run(\"x\", nil)\n}")))
    }

    fun testRenamedLiteralParameterIsAReceiverForNestedSubtests() {
        val text = test("func TestAdd(t *testing.T) {\n\tt.Run(\"o\", func(st *testing.T) {\n\t\tst.Run(\"i\", nil)\n\t})\n}")
        assertEquals(setOf("TestAdd", "TestAdd/o", "TestAdd/o/i"), marked(text).toSet())
    }

    // go names the empty subtest "#00" and a repeat "x#01".
    fun testEmptyAndRepeatedLiteralsAreNotMarked() {
        val text = test(
            """
            func TestAdd(t *testing.T) {
            	t.Run("", nil)
            	t.Run("a b", nil)
            	t.Run("a_b", func(t *testing.T) {
            		t.Run("inner", nil)
            	})
            	t.Run("c", nil)
            }
            """.trimIndent(),
        )
        assertEquals(setOf("TestAdd", "TestAdd/a b", "TestAdd/c"), marked(text).toSet())
    }

    fun testSameLiteralUnderDifferentParentsIsMarkedTwice() {
        val text = test("func TestAdd(t *testing.T) {\n\tt.Run(\"p\", func(t *testing.T) { t.Run(\"x\", nil) })\n\tt.Run(\"q\", func(t *testing.T) { t.Run(\"x\", nil) })\n}")
        assertEquals(setOf("TestAdd", "TestAdd/p", "TestAdd/p/x", "TestAdd/q", "TestAdd/q/x"), marked(text).toSet())
    }

    fun testFilesGoIgnoresAreNotMarked() {
        val body = test("func TestAdd(t *testing.T) {}")
        for (path in listOf("pkg/_skip_test.go", "pkg/.hidden_test.go", "pkg/testdata/x_test.go", "pkg/vendor/v/v_test.go")) {
            val file = myFixture.addFileToProject(path, body)
            assertNull(path, contributor.getInfo(file.findElementAt(file.text.indexOf("TestAdd"))!!))
        }
    }

    fun testExampleOutputAfterDirectiveAndBlankCommentLineIsMarked() {
        assertEquals(listOf("ExampleAdd"), marked(test("func ExampleAdd() {\n\tprintln(1)\n\t//go:noinline\n\t//\n\t// output: 1\n}")))
    }

    // A comment after code on the same line is its own group, so the next lines start a fresh one.
    fun testTrailingCommentDoesNotJoinTheOutputGroup() {
        assertEquals(listOf("ExampleAdd"), marked(test("func ExampleAdd() {\n\tprintln(1) // note\n\t// Output: 1\n}")))
    }

    fun testExampleWithResultIsNotMarked() {
        assertEquals(emptyList<String>(), marked(test("func ExampleAdd() int {\n\t// Output: 1\n\treturn 1\n}")))
    }

    fun testUrlUsesModuleImportPathAndPrintedName() {
        myFixture.addFileToProject("go.mod", "module example.com/m\n")
        val file = myFixture.addFileToProject("calc/calc_test.go", test("func TestAdd(t *testing.T) {\n\tt.Run(\"sub case\", nil)\n}")) as GoFile
        val sub = GoTestDeclarations.of(file).single { it.fullName == "TestAdd/sub case" }
        assertEquals("gotest://example.com/m/calc#TestAdd/sub_case", GoTestRunLineMarkerContributor.testUrl(file, sub))
    }

    fun testIconShowsTheLastRunState() {
        myFixture.addFileToProject("go.mod", "module example.com/m\n")
        val file = myFixture.addFileToProject("calc/calc_test.go", test("func TestAdd(t *testing.T) {}\nfunc TestSub(t *testing.T) {}"))
        val storage = TestStateStorage.getInstance(project)
        fun record(magnitude: TestStateInfo.Magnitude) = TestStateStorage.Record(magnitude.value, Date(), 0, 0, "", "", "")
        storage.writeState("gotest://example.com/m/calc#TestAdd", record(TestStateInfo.Magnitude.FAILED_INDEX))
        storage.writeState("gotest://example.com/m/calc#TestSub", record(TestStateInfo.Magnitude.PASSED_INDEX))
        try {
            assertSame(AllIcons.RunConfigurations.TestState.Red2, contributor.getInfo(file.findElementAt(file.text.indexOf("TestAdd"))!!)!!.icon)
            assertSame(AllIcons.RunConfigurations.TestState.Green2, contributor.getInfo(file.findElementAt(file.text.indexOf("TestSub"))!!)!!.icon)
        } finally {
            storage.removeState("gotest://example.com/m/calc#TestAdd")
            storage.removeState("gotest://example.com/m/calc#TestSub")
        }
    }

    fun testIconWithoutModuleIsPlainRun() {
        val file = myFixture.addFileToProject("nomod/x_test.go", test("func TestAdd(t *testing.T) {}"))
        assertSame(AllIcons.RunConfigurations.TestState.Run, contributor.getInfo(file.findElementAt(file.text.indexOf("TestAdd"))!!)!!.icon)
    }
}
