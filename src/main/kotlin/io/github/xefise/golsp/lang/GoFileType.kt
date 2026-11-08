package io.github.xefise.golsp.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

class GoFileType private constructor() : LanguageFileType(GoLanguage) {
    override fun getName(): String = NAME

    override fun getDisplayName(): String = "Go"

    override fun getDescription(): String = "Go source file"

    override fun getDefaultExtension(): String = "go"

    override fun getIcon(): Icon = GoIcons.FILE

    companion object {
        // Persisted in file type associations; must match plugin.xml.
        const val NAME: String = "golsp.go"

        @JvmField
        val INSTANCE: GoFileType = GoFileType()
    }
}
