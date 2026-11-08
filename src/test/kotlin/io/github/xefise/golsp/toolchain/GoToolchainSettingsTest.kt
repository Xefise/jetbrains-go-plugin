package io.github.xefise.golsp.toolchain

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.toolchain.model.ConfiguredToolPaths

class GoToolchainSettingsTest : BasePlatformTestCase() {
    private var events = 0

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable)
            .subscribe(GoToolchainSettingsListener.TOPIC, GoToolchainSettingsListener { events++ })
    }

    override fun tearDown() {
        try {
            GoToolchainSettings.getInstance().loadState(GoToolchainSettings.Options())
            GoProjectToolchainSettings.getInstance(project).loadState(GoProjectToolchainSettings.Options())
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultsAreEmptyAndMeanAutodetect() {
        val app = GoToolchainSettings.getInstance()
        assertEquals("", app.goBinary)
        assertEquals("", app.goplsPath)
        assertEquals("", app.dlvPath)
        assertEquals("", GoProjectToolchainSettings.getInstance(project).goBinary)
        assertEquals(ConfiguredToolPaths.NONE, GoProjectToolchainSettings.configuredPaths(project))
    }

    fun testProjectOverrideWinsAndToolPathsComeFromApplication() {
        GoToolchainSettings.getInstance().update("/a/go", "/a/gopls", "/a/dlv")
        GoProjectToolchainSettings.getInstance(project).update(" /p/go ")
        assertEquals(ConfiguredToolPaths("/p/go", "/a/gopls", "/a/dlv"), GoProjectToolchainSettings.configuredPaths(project))
    }

    fun testUpdateNotifiesOnlyOnRealChange() {
        val app = GoToolchainSettings.getInstance()
        app.update("/a/go", "", "")
        assertEquals(1, events)
        app.update(" /a/go", " ", "")
        assertEquals(1, events)
        GoProjectToolchainSettings.getInstance(project).update("/p/go")
        assertEquals(2, events)
        GoProjectToolchainSettings.getInstance(project).update("/p/go")
        assertEquals(2, events)
    }

    fun testBlankValuesAreNotPersisted() {
        GoToolchainSettings.getInstance().update("  ", "", "")
        assertNull(GoToolchainSettings.getInstance().state.goBinary)
    }
}
