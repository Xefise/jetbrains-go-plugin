package io.github.xefise.golsp.lsp

import com.intellij.platform.lang.lsWidget.LanguageServicePopupSection
import com.intellij.platform.lang.lsWidget.LanguageServiceWidgetItemsProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoplsWidgetItemsProviderTest : BasePlatformTestCase() {
    private val provider get() = LanguageServiceWidgetItemsProvider.EP_NAME.findExtensionOrFail(GoplsWidgetItemsProvider::class.java)

    fun testGoFileShowsGoplsForCurrentFile() {
        val file = myFixture.configureByText("main.go", "package main\n").virtualFile
        val items = provider.createWidgetItems(project, file)
        assertEquals(LanguageServicePopupSection.ForCurrentFile, items.single().widgetActionLocation)
        assertTrue(items.single().statusBarTooltip.startsWith("gopls"))
    }

    fun testGoModShowsGoplsForCurrentFile() {
        val file = myFixture.configureByText("go.mod", "module example.com/m\n").virtualFile
        assertEquals(LanguageServicePopupSection.ForCurrentFile, provider.createWidgetItems(project, file).single().widgetActionLocation)
    }

    // Whether gopls already runs depends on earlier tests sharing the light project, so only the section is pinned here.
    fun testNonGoFileNeverShowsGoplsForCurrentFile() {
        val file = myFixture.configureByText("notes.txt", "hello\n").virtualFile
        assertTrue(provider.createWidgetItems(project, file).none { it.widgetActionLocation == LanguageServicePopupSection.ForCurrentFile })
    }

    fun testNoFileNeverShowsGoplsForCurrentFile() {
        assertTrue(provider.createWidgetItems(project, null).none { it.widgetActionLocation == LanguageServicePopupSection.ForCurrentFile })
    }
}
