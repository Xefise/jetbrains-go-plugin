package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.InlayHintsCustomSettingsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import com.intellij.codeInsight.hints.declarative.impl.util.DeclarativeHintsDumpUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xefise.golsp.lang.GoLanguage
import io.github.xefise.golsp.lsp.model.GoplsInlayHints

class GoplsInlayHintsSettingsTest : BasePlatformTestCase() {
    private val beans get() = InlayHintsProviderExtensionBean.EP.extensionList.filter { it.language == GoLanguage.id }

    override fun tearDown() {
        try {
            restoreDefaults()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testProvidersAndOptionsMatchTheModel() {
        assertEquals(GoplsInlayHints.PROVIDERS.toSet(), beans.map { it.requiredProviderId() }.toSet())
        val groups = mapOf(
            GoplsInlayHints.TYPES_PROVIDER to InlayGroup.TYPES_GROUP,
            GoplsInlayHints.PARAMETERS_PROVIDER to InlayGroup.PARAMETERS_GROUP,
            GoplsInlayHints.VALUES_PROVIDER to InlayGroup.VALUES_GROUP,
        )
        for (bean in beans) {
            val id = bean.requiredProviderId()
            assertEquals(id, groups.getValue(id), bean.requiredGroup())
            assertEquals(id, GoplsInlayHints.PROVIDER_ENABLED_BY_DEFAULT, bean.isEnabledByDefault)
            assertTrue(id, bean.getProviderName().isNotBlank())
            assertTrue(id, bean.getDescription().orEmpty().isNotBlank())
            assertInstanceOf(bean.instance, GoplsInlayHintsProvider::class.java)
            assertInstanceOf(InlayHintsCustomSettingsProvider.getCustomSettingsProvider(id, GoLanguage), GoplsInlayHintsApplyListener::class.java)
            val expected = GoplsInlayHints.ALL.filter { it.providerId == id }.associate { it.name to it.enabledByDefault }
            assertEquals(id, expected, bean.options.associate { it.requireOptionId() to it.enabledByDefault })
            bean.options.forEach { option ->
                assertTrue(option.requireOptionId(), option.getName(bean) != option.nameKey)
                assertTrue(option.requireOptionId(), option.getDescription(bean).orEmpty().isNotBlank())
            }
        }
    }

    // The settings page reads previews from inlayProviders/<providerId>/<optionId>.go and fails on bad hint markup.
    fun testEveryOptionHasAParsablePreviewWithHints() {
        for (hint in GoplsInlayHints.ALL) {
            val path = "inlayProviders/${hint.providerId}/${hint.name}.${GoLanguage.associatedFileType!!.defaultExtension}"
            val text = GoplsInlayHintsProvider::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
            assertNotNull(path, text)
            val hints = DeclarativeHintsDumpUtil.extractHints(text!!)
            assertTrue(path, hints.isNotEmpty())
            assertTrue(path, hints.all { it.text.isNotBlank() })
        }
    }

    fun testCurrentReadsProviderAndOptionFlags() {
        assertEquals(GoplsInlayHints.DEFAULTS, GoplsInlayHintsSettings.current())
        writeSettings {
            setOptionEnabled("parameterNames", GoplsInlayHints.PARAMETERS_PROVIDER, true)
            setProviderEnabled(GoplsInlayHints.TYPES_PROVIDER, false)
        }
        val hints = GoplsInlayHintsSettings.current()
        assertEquals(true, hints["parameterNames"])
        assertEquals(false, hints["assignVariableTypes"])
        assertEquals(false, hints["rangeVariableTypes"])
    }

    fun testMarkSentIsTrackedPerProjectAndSkipsRepeats() {
        val other = mapOf("parameterNames" to true)
        assertTrue(GoplsInlayHintsSettings.markSent(project, other))
        assertFalse(GoplsInlayHintsSettings.markSent(project, other))
        GoplsInlayHintsSettings.forGopls(project)
        assertTrue(GoplsInlayHintsSettings.markSent(project, other))
    }

    private fun restoreDefaults() = writeSettings {
        GoplsInlayHints.PROVIDERS.forEach { setProviderEnabled(it, GoplsInlayHints.PROVIDER_ENABLED_BY_DEFAULT) }
        GoplsInlayHints.ALL.forEach { setOptionEnabled(it.name, it.providerId, it.enabledByDefault) }
    }

    // A lambda inside a test* method compiles to a test*$lambda method that JUnit 3 would try to run.
    private fun writeSettings(block: DeclarativeInlayHintsSettings.() -> Unit) {
        WriteAction.runAndWait<RuntimeException> { DeclarativeInlayHintsSettings.getInstance().block() }
    }
}
