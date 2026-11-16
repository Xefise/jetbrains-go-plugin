package io.github.xefise.golsp.coverage

import com.intellij.openapi.util.io.FileUtil
import io.github.xefise.golsp.coverage.model.GoCoverFileResolver
import io.github.xefise.golsp.coverage.model.GoModReplaces
import io.github.xefise.golsp.test.model.GoModules
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Reads the go.work and go.mod files that decide where the packages of a coverage run live. */
internal object GoCoverageModules {
    private const val GO_MOD = "go.mod"
    private const val GO_WORK = "go.work"
    private const val VENDOR = "vendor"

    /**
     * Modules of the go.work above [directory] (as go finds it, GOWORK aside), then the module that holds it, then the
     * local `replace` targets of go.work and of those main modules; `vendor/` of the main modules for the rest.
     */
    fun resolver(directory: Path?): GoCoverFileResolver {
        val modules = LinkedHashMap<String, String>()
        val start = directory?.toAbsolutePath()?.normalize() ?: return GoCoverFileResolver(modules)
        val mainRoots = mutableListOf<Path>()
        val replaces = mutableListOf<Pair<Path, GoModReplaces.Replace>>()
        val ancestors = generateSequence(start) { it.parent }
        ancestors.firstOrNull { Files.isRegularFile(it.resolve(GO_WORK)) }?.let { root ->
            val text = read(root.resolve(GO_WORK)) ?: return@let
            for (use in GoCoverFileResolver.goWorkUses(text)) resolve(root, use)?.let(mainRoots::add)
            // go.work replaces win over those of its modules.
            GoModReplaces.localReplaces(text).mapTo(replaces) { root to it }
        }
        ancestors.firstOrNull { Files.isRegularFile(it.resolve(GO_MOD)) }?.let(mainRoots::add)
        val roots = mainRoots.distinct()
        for (root in roots) {
            val text = read(root.resolve(GO_MOD)) ?: continue
            GoModules.modulePath(text)?.let { modules.putIfAbsent(it, systemIndependent(root)) }
            GoModReplaces.localReplaces(text).mapTo(replaces) { root to it }
        }
        // A main module's own path is never replaced, so modules registered above keep their roots.
        for ((base, replace) in replaces) {
            resolve(base, replace.directory)?.let { modules.putIfAbsent(replace.modulePath, systemIndependent(it)) }
        }
        val vendorRoots = roots.filter { Files.isDirectory(it.resolve(VENDOR)) }.map(::systemIndependent)
        return GoCoverFileResolver(modules, vendorRoots, ::isFile)
    }

    private fun isFile(path: String): Boolean = try {
        Files.isRegularFile(Path.of(path))
    } catch (_: InvalidPathException) {
        false
    }

    private fun resolve(base: Path, relative: String): Path? = try {
        base.resolve(relative).normalize()
    } catch (_: InvalidPathException) {
        null
    }

    private fun systemIndependent(path: Path): String = FileUtil.toSystemIndependentName(path.toString())

    private fun read(file: Path): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }
}
