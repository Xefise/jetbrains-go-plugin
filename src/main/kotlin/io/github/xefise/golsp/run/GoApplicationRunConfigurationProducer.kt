package io.github.xefise.golsp.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoPackageFiles
import java.nio.file.Path

// Package kind inside a module; a single-file `go build main.go` outside one, where `go build .` fails,
// and for `//go:build ignore` generators, which the package build skips.
class GoApplicationRunConfigurationProducer : LazyRunConfigurationProducer<GoApplicationConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory =
        runConfigurationType<GoApplicationConfigurationType>().configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: GoApplicationConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = target(context) ?: return false
        configuration.runKind = target.kind
        // Absolute paths: workspace serialization collapses them to $PROJECT_DIR$ and resolve() expands them back.
        when (target.kind) {
            GoRunKind.PACKAGE -> configuration.packageDirectory = target.directory.path
            GoRunKind.FILE -> configuration.filePath = target.file!!.path
        }
        configuration.name = configuration.suggestedName() ?: return false
        return true
    }

    override fun isConfigurationFromContext(configuration: GoApplicationConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        return when (configuration.runKind) {
            GoRunKind.PACKAGE -> target.kind == GoRunKind.PACKAGE && sameFile(configuration, configuration.packageDirectory, target.directory)
            GoRunKind.FILE -> target.file != null && sameFile(configuration, configuration.filePath, target.file)
        }
    }

    private class Target(val directory: VirtualFile, val file: VirtualFile?, val kind: GoRunKind)

    private fun target(context: ConfigurationContext): Target? {
        // A multi-selection would silently run only its first element.
        if ((context.dataContext.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.size ?: 0) > 1) return null
        return when (val location = context.psiLocation) {
            null -> null
            // `go build .` needs a module, so a directory outside one gets no configuration.
            is PsiDirectory -> location.takeIf { GoMainPackage.isMainPackageDirectory(it) && GoPackageFiles.isInModule(it.virtualFile) }
                ?.let { Target(it.virtualFile, null, GoRunKind.PACKAGE) }
            else -> fileTarget(location)
        }
    }

    private fun fileTarget(element: PsiElement): Target? {
        val file = element.containingFile as? GoFile ?: return null
        if (!GoMainPackage.isMainPackageFile(file) || GoMainPackage.isIgnoredByGo(file) || !GoMainPackage.isInContent(file)) return null
        val virtualFile = file.virtualFile ?: return null
        val directory = file.containingDirectory ?: return null
        if (GoMainPackage.isBuiltAlone(file)) {
            return if (GoMainPackage.hasEntryPoint(file)) Target(directory.virtualFile, virtualFile, GoRunKind.FILE) else null
        }
        val inModule = GoPackageFiles.isInModule(directory.virtualFile)
        // Outside a module only the file itself is built, so it must hold `func main()`; its siblings are not compiled.
        val runnable = GoMainPackage.hasEntryPoint(file) || inModule && GoMainPackage.isMainPackageDirectory(directory)
        val kind = if (inModule) GoRunKind.PACKAGE else GoRunKind.FILE
        return if (runnable) Target(directory.virtualFile, virtualFile, kind) else null
    }

    // An unparsable stored path (hand-edited workspace.xml) simply does not match.
    private fun sameFile(configuration: GoApplicationConfiguration, path: String, file: VirtualFile): Boolean =
        path.isNotBlank() && try {
            configuration.resolve(path) == Path.of(file.path).normalize()
        } catch (_: RuntimeConfigurationError) {
            false
        }
}
