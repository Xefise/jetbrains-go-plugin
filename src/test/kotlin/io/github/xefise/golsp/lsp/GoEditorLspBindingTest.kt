package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveOptions
import com.intellij.codeInsight.editorActions.CodeBlockProviders
import com.intellij.ide.hierarchy.LanguageCallHierarchy
import com.intellij.ide.hierarchy.LanguageTypeHierarchy
import com.intellij.lang.LanguageImportStatements
import com.intellij.lang.folding.LanguageFolding
import com.intellij.lang.parameterInfo.LanguageParameterInfo
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.lang.LanguageStructureViewBuilder
import com.redhat.devtools.lsp4ij.features.callHierarchy.LSPCallHierarchyProvider
import com.redhat.devtools.lsp4ij.features.codeBlockProvider.LSPCodeBlockProvider
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewFactory
import com.redhat.devtools.lsp4ij.features.foldingRange.LSPFoldingRangeBuilder
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPParameterInfoHandler
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPTypeHierarchyProvider
import io.github.xefise.golsp.lang.GoFileType
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lang.GoModLanguage

class GoEditorLspBindingTest : BasePlatformTestCase() {
    fun testLsp4ijFeaturesAreBoundToGo() {
        assertTrue(LanguageFolding.INSTANCE.allForLanguage(GoLanguage).any { it is LSPFoldingRangeBuilder })
        assertTrue(LanguageParameterInfo.INSTANCE.allForLanguage(GoLanguage).any { it is LSPParameterInfoHandler })
        assertInstanceOf(LanguageStructureViewBuilder.getInstance().forLanguage(GoLanguage), LSPDocumentSymbolStructureViewFactory::class.java)
        assertTrue(CodeBlockProviders.INSTANCE.allForLanguage(GoLanguage).any { it is LSPCodeBlockProvider })
        assertTrue(LanguageCallHierarchy.INSTANCE.allForLanguage(GoLanguage).any { it is LSPCallHierarchyProvider })
        assertTrue(LanguageTypeHierarchy.INSTANCE.allForLanguage(GoLanguage).any { it is LSPTypeHierarchyProvider })
    }

    fun testGoModGetsNoGoOnlyBindings() {
        assertFalse(LanguageParameterInfo.INSTANCE.allForLanguage(GoModLanguage).any { it is LSPParameterInfoHandler })
        assertEmpty(LanguageImportStatements.INSTANCE.allForLanguage(GoModLanguage).filterIsInstance<GoImportOptimizer>())
    }

    fun testImportOptimizerIsRegisteredForGo() {
        val file = myFixture.configureByText("a.go", "package p\n")
        assertTrue(LanguageImportStatements.INSTANCE.forFile(file).any { it is GoImportOptimizer })
    }

    fun testImportOptimizerDoesNotWaitForGoplsOnEdt() {
        val file = myFixture.configureByText("a.go", "package p\n\nimport \"fmt\"\n")
        assertSame(EmptyRunnable.getInstance(), GoImportOptimizer().processFile(file))
    }

    fun testActionsOnSaveAreOnForGoByDefault() {
        val format = FormatOnSaveOptions.getInstance(project)
        val imports = OptimizeImportsOnSaveOptions.getInstance(project)
        assertTrue(format.isRunOnSaveEnabled && format.isFileTypeSelected(GoFileType.INSTANCE))
        assertTrue(imports.isRunOnSaveEnabled && imports.isFileTypeSelected(GoFileType.INSTANCE))
        assertFalse(format.isFileTypeSelected(PlainTextFileType.INSTANCE))
        assertFalse(imports.isFileTypeSelected(PlainTextFileType.INSTANCE))
    }
}
