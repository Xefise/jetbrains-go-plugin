package io.github.xefise.golsp.toolchain

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.toolchain.model.GoTool

class GoToolchainConfigurableTest : BasePlatformTestCase() {
    private lateinit var configurable: GoToolchainConfigurable

    override fun setUp() {
        super.setUp()
        // A configured go that does not exist keeps detection from running a real `go`.
        GoToolchainSettings.getInstance().update(MISSING_GO, "", "")
        configurable = GoToolchainConfigurable(project)
        configurable.createComponent()
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            GoToolchainSettings.getInstance().loadState(GoToolchainSettings.Options())
            GoProjectToolchainSettings.getInstance(project).loadState(GoProjectToolchainSettings.Options())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testResetShowsStoredValuesAndIsNotModified() {
        assertEquals(MISSING_GO, configurable.goField.text)
        assertEquals("", configurable.goplsField.text)
        assertTrue(configurable.bannerBoxes.getValue(GoTool.DLV).isSelected)
        assertFalse(configurable.isModified)
    }

    fun testApplyStoresEveryField() {
        configurable.goplsField.text = " /x/gopls "
        configurable.dlvField.text = "/x/dlv"
        configurable.projectGoField.text = "/p/go"
        configurable.bannerBoxes.getValue(GoTool.DLV).isSelected = false
        assertTrue(configurable.isModified)
        configurable.apply()
        val settings = GoToolchainSettings.getInstance()
        assertEquals(MISSING_GO, settings.goBinary)
        assertEquals("/x/gopls", settings.goplsPath)
        assertEquals("/x/dlv", settings.dlvPath)
        assertEquals("/p/go", GoProjectToolchainSettings.getInstance(project).goBinary)
        assertTrue(settings.isBannerHidden(GoTool.DLV))
        assertFalse(settings.isBannerHidden(GoTool.GOPLS))
        assertEquals("/x/gopls", configurable.goplsField.text)
        assertFalse(configurable.isModified)
    }

    fun testResetDiscardsEditsAndPicksUpExternalChanges() {
        configurable.goplsField.text = "/edited"
        GoToolchainSettings.getInstance().setBannerHidden(GoTool.GO, true)
        configurable.reset()
        assertEquals("", configurable.goplsField.text)
        assertFalse(configurable.bannerBoxes.getValue(GoTool.GO).isSelected)
        assertFalse(configurable.isModified)
    }

    fun testClearingTheGoPathStoresAutodetect() {
        configurable.goField.text = "  "
        configurable.apply()
        assertEquals("", GoToolchainSettings.getInstance().goBinary)
        assertFalse(configurable.isModified)
    }

    private companion object {
        const val MISSING_GO = "/nonexistent/golsp-test/go"
    }
}
