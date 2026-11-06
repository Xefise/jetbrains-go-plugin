package io.github.xefise.golsp.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import io.github.xefise.golsp.lang.GoFile
import java.nio.file.Path

// Produces package-kind configurations only; file-kind is picked by the user in the editor.
class GoApplicationRunConfigurationProducer : LazyRunConfigurationProducer<GoApplicationConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory =
        runConfigurationType<GoApplicationConfigurationType>().configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: GoApplicationConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = target(context) ?: return false
        configuration.runKind = GoRunKind.PACKAGE
        // Absolute path: workspace serialization collapses it to $PROJECT_DIR$ and resolve() expands it back.
        configuration.packageDirectory = target.directory.path
        configuration.name = target.directory.name
        return true
    }

    override fun isConfigurationFromContext(configuration: GoApplicationConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        return when (configuration.runKind) {
            GoRunKind.PACKAGE -> sameFile(configuration, configuration.packageDirectory, target.directory)
            GoRunKind.FILE -> target.file != null && sameFile(configuration, configuration.filePath, target.file)
        }
    }

    private class Target(val directory: VirtualFile, val file: VirtualFile?)

    private fun target(context: ConfigurationContext): Target? = when (val location = context.psiLocation) {
        null -> null
        is PsiDirectory -> location.takeIf(GoMainPackage::isMainPackageDirectory)?.let { Target(it.virtualFile, null) }
        else -> fileTarget(location)
    }

    private fun fileTarget(element: PsiElement): Target? {
        val file = element.containingFile as? GoFile ?: return null
        if (!GoMainPackage.isMainPackageFile(file) || !GoMainPackage.isInContent(file)) return null
        val virtualFile = file.virtualFile ?: return null
        val directory = file.containingDirectory ?: return null
        if (!GoMainPackage.hasEntryPoint(file) && !GoMainPackage.isMainPackageDirectory(directory)) return null
        return Target(directory.virtualFile, virtualFile)
    }

    private fun sameFile(configuration: GoApplicationConfiguration, path: String, file: VirtualFile): Boolean =
        path.isNotBlank() && configuration.resolve(path) == Path.of(file.path).normalize()
}
