package io.github.xefise.golsp.test

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.xefise.golsp.lang.GoFile
import io.github.xefise.golsp.lang.GoPackageFiles
import io.github.xefise.golsp.test.model.GoTestSelection
import java.nio.file.Path

// Function or subtest -> its package filtered by full name; test file -> FILE; directory -> PACKAGE or `./...`.
class GoTestRunConfigurationProducer : LazyRunConfigurationProducer<GoTestConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory =
        runConfigurationType<GoTestConfigurationType>().configurationFactories.single()

    override fun setupConfigurationFromContext(
        configuration: GoTestConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = target(context) ?: return false
        configuration.scope = target.scope
        // Absolute paths: workspace serialization collapses them to $PROJECT_DIR$ and resolve() expands them back.
        if (target.scope == GoTestScope.FILE) configuration.filePath = target.location.path
        else configuration.directory = target.location.path
        configuration.testNames = target.testNames
        configuration.name = configuration.suggestedName() ?: return false
        return true
    }

    override fun isConfigurationFromContext(configuration: GoTestConfiguration, context: ConfigurationContext): Boolean {
        val target = target(context) ?: return false
        if (configuration.scope != target.scope || configuration.testNames != target.testNames) return false
        val path = if (target.scope == GoTestScope.FILE) configuration.filePath else configuration.directory
        // An unparsable stored path (hand-edited workspace.xml) simply does not match.
        return path.isNotBlank() && try {
            configuration.resolve(path) == Path.of(target.location.path).normalize()
        } catch (_: RuntimeConfigurationError) {
            false
        }
    }

    private class Target(val scope: GoTestScope, val location: VirtualFile, val testNames: List<String>)

    private fun target(context: ConfigurationContext): Target? {
        // A multi-selection would silently run only its first element.
        if ((context.dataContext.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.size ?: 0) > 1) return null
        return when (val location = context.psiLocation) {
            null -> null
            is PsiDirectory -> directoryTarget(location)
            else -> fileTarget(location)
        }
    }

    // Every scope runs `go test` in a package directory, which fails outside a module (GOPATH mode is not supported).
    private fun fileTarget(element: PsiElement): Target? {
        val file = element.containingFile as? GoFile ?: return null
        if (!GoTestDeclarations.isTestFileInContent(file)) return null
        val virtualFile = file.virtualFile ?: return null
        if (!GoPackageFiles.isInModule(virtualFile.parent)) return null
        val test = if (element == file) null else GoTestDeclarations.enclosing(element)
        if (test != null) {
            val directory = virtualFile.parent ?: return null
            return Target(GoTestScope.PACKAGE, directory, listOf(test.fullName))
        }
        val topLevel = file.functionHeaders.filterNot { it.isMethod }.mapNotNull { it.functionName }
        if (GoTestSelection.fileTests(topLevel).isEmpty()) return null
        return Target(GoTestScope.FILE, virtualFile, emptyList())
    }

    private fun directoryTarget(directory: PsiDirectory): Target? {
        if (!GoPackageFiles.isInModule(directory.virtualFile)) return null
        val tests = testsBeneath(directory)
        return when {
            tests.nested -> Target(GoTestScope.DIRECTORY, directory.virtualFile, emptyList())
            tests.own -> Target(GoTestScope.PACKAGE, directory.virtualFile, emptyList())
            else -> null
        }
    }

    private data class TestsBeneath(val own: Boolean, val nested: Boolean)

    // Depends on file names only, so it is recomputed on VFS structure changes, not on every keystroke.
    private fun testsBeneath(directory: PsiDirectory): TestsBeneath =
        CachedValuesManager.getCachedValue(directory) {
            CachedValueProvider.Result.create(
                scan(directory),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                ProjectRootManager.getInstance(directory.project),
            )
        }

    // Mirrors `./...`: stops at directories go ignores and at nested modules; returns at the first nested test file.
    // Runs on every context-menu update of the directory, so a huge tree is cut off and assumed to hold tests.
    private fun scan(directory: PsiDirectory): TestsBeneath {
        val start = directory.virtualFile
        val index = ProjectFileIndex.getInstance(directory.project)
        if (!index.isInContent(start)) return TestsBeneath(own = false, nested = false)
        val own = start.children.any(::isTestFile)
        var nested = false
        var directories = 0
        VfsUtilCore.visitChildrenRecursively(start, object : VirtualFileVisitor<Unit>(VirtualFileVisitor.NO_FOLLOW_SYMLINKS) {
            override fun visitFileEx(file: VirtualFile): Result {
                if (nested) return SKIP_CHILDREN
                if (file == start) return CONTINUE
                if (file.isDirectory) {
                    if (isIgnoredByGo(file) || !index.isInContent(file) || file.findChild("go.mod") != null) return SKIP_CHILDREN
                    if (++directories > MAX_SCANNED_DIRECTORIES) nested = true
                    return CONTINUE
                }
                if (file.parent != start && isTestFile(file)) nested = true
                return CONTINUE
            }
        })
        return TestsBeneath(own, nested)
    }

    // go ignores `_x_test.go` and `.x_test.go`.
    private fun isTestFile(file: VirtualFile): Boolean =
        !file.isDirectory && file.name.endsWith(GoTestConfiguration.TEST_FILE_SUFFIX) && !GoPackageFiles.isIgnoredByName(file.name)

    private fun isIgnoredByGo(directory: VirtualFile): Boolean {
        val name = directory.name
        return name.startsWith(".") || name.startsWith("_") || name == "testdata" || name == "vendor"
    }

    private companion object {
        const val MAX_SCANNED_DIRECTORIES = 500
    }
}
