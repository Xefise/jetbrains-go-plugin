package io.github.xefise.golsp.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.psi.search.GlobalSearchScope
import io.github.xefise.golsp.test.model.GoTestLocation
import io.github.xefise.golsp.test.model.GoTestRerun
import io.github.xefise.golsp.test.runner.GoTestLocator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Reruns the deepest failed tests of the tree with one `go test`, through a copy of the configuration. */
class GoTestRerunFailedAction(componentContainer: ComponentContainer, consoleProperties: TestConsoleProperties) :
    AbstractRerunFailedTestsAction(componentContainer) {

    // Import path of the single failed package of a ./... run and its directory, found during update on a background thread.
    @Volatile
    private var resolvedPackage: Pair<String, String>? = null

    init {
        init(consoleProperties)
    }

    override fun isActive(e: AnActionEvent): Boolean {
        val active = super.isActive(e)
        if (active) resolveSinglePackage()
        return active
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? GoTestConfiguration ?: return null
        val selection = selection(configuration) ?: return null
        val project = configuration.project
        val rerun = rerunConfiguration(configuration, selection) { importPath ->
            // Usually resolved by update already; otherwise this is one cached go.mod lookup on EDT.
            resolvedPackage?.takeIf { it.first == importPath }?.second ?: packageDirectory(project, importPath)
        }
        return RerunProfile(rerun)
    }

    private fun selection(configuration: GoTestConfiguration): GoTestRerun.Selection? {
        val root = model?.root ?: return null
        return selection(getFailedTests(configuration.project), root.allTests)
    }

    private fun resolveSinglePackage() {
        val configuration = myConsoleProperties.configuration as? GoTestConfiguration ?: return
        if (configuration.scope != GoTestScope.DIRECTORY) return
        val single = selection(configuration)?.packages?.singleOrNull() ?: return
        if (resolvedPackage?.first == single) return
        packageDirectory(configuration.project, single)?.let { resolvedPackage = single to it }
    }

    // Run and Debug both go through the copy's own getState; Delve finds the copy as the profile's peer.
    private class RerunProfile(private val rerun: GoTestConfiguration) : MyRunProfile(rerun) {
        @Throws(ExecutionException::class)
        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
            rerun.getState(executor, environment)
    }

    companion object {
        internal fun selection(failed: List<AbstractTestProxy>, all: List<AbstractTestProxy>): GoTestRerun.Selection? {
            fun targets(nodes: List<AbstractTestProxy>) = nodes.mapNotNull { it.locationUrl?.let(GoTestLocation::parseUrl) }
            return GoTestRerun.select(targets(failed), targets(all))
        }

        internal fun runProfile(rerun: GoTestConfiguration): RunConfigurationBase<*> = RerunProfile(rerun)

        /**
         * A copy of [base] that runs [selection]. A single failed package of `./...` becomes a package target, so it can
         * be debugged; [packageDirectory] maps its import path to a directory, null keeps the import path.
         */
        internal fun rerunConfiguration(
            base: GoTestConfiguration,
            selection: GoTestRerun.Selection,
            packageDirectory: (String) -> String?,
        ): GoTestConfiguration {
            val copy = base.clone() as GoTestConfiguration
            // No names: whole packages failed, so the original selection reruns.
            if (selection.testNames.isNotEmpty()) copy.testNames = selection.testNames
            copy.packages = emptyList()
            if (base.scope != GoTestScope.DIRECTORY) return copy
            val root = canonical(base.targetDirectory())
            val single = selection.packages.singleOrNull()?.let(packageDirectory)?.takeIf { canonical(Path.of(it)).startsWith(root) }
            if (single != null) {
                copy.scope = GoTestScope.PACKAGE
                copy.directory = single
            } else {
                copy.packages = selection.packages
            }
            return copy
        }

        // The configured root may go through a symlink that the VFS path of the package does not.
        private fun canonical(path: Path): Path = try {
            if (Files.exists(path)) path.toRealPath() else path.normalize()
        } catch (_: IOException) {
            path.normalize()
        }

        private fun packageDirectory(project: Project, importPath: String): String? = ReadAction.compute<String?, RuntimeException> {
            // The go.mod lookup needs indexes; without them the import path still works for Run.
            if (DumbService.isDumb(project)) null
            else GoTestLocator.packageDirectory(project, GlobalSearchScope.allScope(project), importPath)?.path
        }
    }
}
