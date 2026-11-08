// Modified from go-language-helper (cag11), commit 556c274, Apache-2.0
package io.github.xefise.golsp.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import javax.swing.Icon

// Persisted IDs.
object GoModLanguage : Language("golsp.gomod") {
    private fun readResolve(): Any = GoModLanguage

    override fun getDisplayName(): String = "go.mod"
}

object GoWorkLanguage : Language("golsp.gowork") {
    private fun readResolve(): Any = GoWorkLanguage

    override fun getDisplayName(): String = "go.work"
}

class GoModFileType private constructor() : LanguageFileType(GoModLanguage) {
    override fun getName(): String = NAME
    override fun getDisplayName(): String = "go.mod"
    override fun getDescription(): String = "Go module file"
    override fun getDefaultExtension(): String = "mod"
    override fun getIcon(): Icon = GoIcons.MODULE_FILE

    companion object {
        const val NAME: String = "golsp.gomod"

        @JvmField
        val INSTANCE: GoModFileType = GoModFileType()
    }
}

class GoWorkFileType private constructor() : LanguageFileType(GoWorkLanguage) {
    override fun getName(): String = NAME
    override fun getDisplayName(): String = "go.work"
    override fun getDescription(): String = "Go workspace file"
    override fun getDefaultExtension(): String = "work"
    override fun getIcon(): Icon = GoIcons.MODULE_FILE

    companion object {
        const val NAME: String = "golsp.gowork"

        @JvmField
        val INSTANCE: GoWorkFileType = GoWorkFileType()
    }
}

// Checksums only: plain text, no language of its own.
class GoSumFileType private constructor() : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = NAME
    override fun getDisplayName(): String = "go.sum"
    override fun getDescription(): String = "Go checksum file"
    override fun getDefaultExtension(): String = "sum"
    override fun getIcon(): Icon = GoIcons.MODULE_FILE

    companion object {
        const val NAME: String = "golsp.gosum"

        @JvmField
        val INSTANCE: GoSumFileType = GoSumFileType()
    }
}
