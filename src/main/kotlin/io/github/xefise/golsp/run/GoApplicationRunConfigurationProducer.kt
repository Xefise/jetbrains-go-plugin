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

// Package kind inside a module; a single-file `go build main.go` outside one, where `go build .` fails.
class GoApplicationRunConfigurationProducer : LazyRunConfigurationProducer<GoApplicationConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory =
        runConfigurationType<GoApplicationConfigurationType>().configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: GoApplicationConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = target(context) ?: return false
        // Absolute paths: workspace serialization collapses them to $PROJECT_DIR$ and resolve() expands them back.
        if (target.inModule || target.file == null) {
            configuration.runKind = GoRunKind.PACKAGE
            configuration.packageDirectory = target.directory.path
            configuration.name = target.directory.name
        } else {
            configuration.runKind = GoRunKind.FILE
            configuration.filePath = target.file.path
            configuration.name = target.file.name
        }
        return true
    }

    override fun isConfigurationFromContext(configuration: GoApplicationConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        return when (configuration.runKind) {
            GoRunKind.PACKAGE -> target.inModule && sameFile(configuration, configuration.packageDirectory, target.directory)
            GoRunKind.FILE -> target.file != null && sameFile(configuration, configuration.filePath, target.file)
        }
    }

    private class Target(val directory: VirtualFile, val file: VirtualFile?, val inModule: Boolean)

    private fun target(context: ConfigurationContext): Target? = when (val location = context.psiLocation) {
        null -> null
        // `go build .` needs a module, so a directory outside one gets no configuration.
        is PsiDirectory -> location.takeIf { GoMainPackage.isMainPackageDirectory(it) && GoMainPackage.isInModule(it.virtualFile) }
            ?.let { Target(it.virtualFile, null, inModule = true) }
        else -> fileTarget(location)
    }

    private fun fileTarget(element: PsiElement): Target? {
        val file = element.containingFile as? GoFile ?: return null
        if (!GoMainPackage.isMainPackageFile(file) || !GoMainPackage.isInContent(file)) return null
        val virtualFile = file.virtualFile ?: return null
        val directory = file.containingDirectory ?: return null
        val inModule = GoMainPackage.isInModule(directory.virtualFile)
        // Outside a module only the file itself is built, so it must hold `func main()`; its siblings are not compiled.
        val runnable = GoMainPackage.hasEntryPoint(file) || inModule && GoMainPackage.isMainPackageDirectory(directory)
        return if (runnable) Target(directory.virtualFile, virtualFile, inModule) else null
    }

    private fun sameFile(configuration: GoApplicationConfiguration, path: String, file: VirtualFile): Boolean =
        path.isNotBlank() && configuration.resolve(path) == Path.of(file.path).normalize()
}
