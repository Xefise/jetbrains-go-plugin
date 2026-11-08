package io.github.xefise.golsp.coverage

import com.intellij.openapi.util.io.FileUtil
import io.github.xefise.golsp.coverage.model.GoCoverFileResolver
import io.github.xefise.golsp.test.model.GoModules
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Reads the go.work and go.mod files that decide where the packages of a coverage run live. */
internal object GoCoverageModules {
    private const val GO_MOD = "go.mod"
    private const val GO_WORK = "go.work"

    /** Modules of the go.work above [directory] (as go finds it, GOWORK aside), then the module that holds it. */
    fun resolver(directory: Path?): GoCoverFileResolver {
        val modules = LinkedHashMap<String, String>()
        val start = directory?.toAbsolutePath()?.normalize() ?: return GoCoverFileResolver(modules)
        val ancestors = generateSequence(start) { it.parent }
        ancestors.firstOrNull { Files.isRegularFile(it.resolve(GO_WORK)) }?.let { root ->
            val text = read(root.resolve(GO_WORK)) ?: return@let
            for (use in GoCoverFileResolver.goWorkUses(text)) {
                val module = try {
                    root.resolve(use).normalize()
                } catch (_: InvalidPathException) {
                    continue
                }
                addModule(modules, module)
            }
        }
        ancestors.firstOrNull { Files.isRegularFile(it.resolve(GO_MOD)) }?.let { addModule(modules, it) }
        return GoCoverFileResolver(modules)
    }

    private fun addModule(modules: MutableMap<String, String>, root: Path) {
        val modulePath = read(root.resolve(GO_MOD))?.let(GoModules::modulePath) ?: return
        modules.putIfAbsent(modulePath, FileUtil.toSystemIndependentName(root.toString()))
    }

    private fun read(file: Path): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }
}
