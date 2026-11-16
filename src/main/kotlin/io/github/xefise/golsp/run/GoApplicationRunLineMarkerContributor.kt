package io.github.xefise.golsp.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.xefise.golsp.lang.GoElementTypes
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoFunctionHeader

class GoApplicationRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element.node.elementType !in GoElementTypes.NAMES) return null
        val header = element.parent as? GoFunctionHeader ?: return null
        if (header.nameIdentifier != element || !GoMainPackage.isEntryPoint(header)) return null
        val file = header.containingFile as? GoFile ?: return null
        if (!GoMainPackage.isMainPackageFile(file) || GoMainPackage.isIgnoredByGo(file) || !GoMainPackage.isInContent(file)) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0), null)
    }
}
