package io.github.xefise.golsp.lsp

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptionsBase
import com.intellij.openapi.fileTypes.FileType
import io.github.xefise.golsp.lang.GoFileType

/**
 * Turns on "Reformat code" and "Optimize imports" in Actions on Save for Go out of the box, like gopls in other editors.
 * Applies only while the project has no stored Actions on Save state; the user's own choice always wins.
 */
class GoActionsOnSaveDefaults : FormatOnSaveOptionsBase.DefaultsProvider {
    override fun getFileTypesFormattedOnSaveByDefault(): Collection<FileType> = listOf(GoFileType.INSTANCE)

    override fun getFileTypesWithOptimizeImportsOnSaveByDefault(): Collection<FileType> = listOf(GoFileType.INSTANCE)
}
