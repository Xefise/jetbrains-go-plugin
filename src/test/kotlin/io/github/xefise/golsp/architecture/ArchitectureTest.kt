package io.github.xefise.golsp.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureTest {
    private val sources = SourceScanner.scan("src/main/kotlin", "src/main/java")

    @Test
    fun `main sources are found`() {
        assertTrue("no sources under src/main — wrong project dir?", sources.isNotEmpty())
    }

    @Test
    fun `main sources respect package rules`() {
        val violations = ArchitectureRules.violations(sources)
        assertTrue(violations.joinToString("\n", prefix = "Architecture violations:\n"), violations.isEmpty())
    }

    @Test
    fun `package graph has no cycles`() {
        assertEquals(emptyList<List<String>>(), ArchitectureRules.cycles())
        assertEquals(1, ArchitectureRules.cycles(mapOf("a" to setOf("b"), "b" to setOf("a"))).size)
    }

    @Test
    fun `rules catch violations`() {
        val bad = listOf(
            file("lang", "Lexer.kt", "import $ROOT.toolchain.GoToolchain"),
            file("lang", "Alias.kt", "import $ROOT.toolchain.GoToolchain as T"),
            file("lang", "Star.kt", "import $ROOT.toolchain.*"),
            file("lang", "Tick.kt", "import io.github.xefise.`golsp`.`toolchain`.GoToolchain"),
            file("lang", "Fqn.kt", "val t = $ROOT.toolchain.GoToolchain()"),
            file("toolchain", "Dep.kt", "import $ROOT.execution.GoRunConfigurationBase"),
            file("execution.model", "Req.kt", "import com.intellij.openapi.project.Project"),
            file("run", "Conf.kt", "import com.redhat.devtools.lsp4ij.LanguageServerManager"),
            file("run", "ConfAlias.kt", "import com.redhat.devtools.lsp4ij.LanguageServerManager as M"),
            file("run", "ConfFqn.kt", "val m = com.redhat.devtools.lsp4ij.LanguageServerManager.getInstance(p)"),
            file("run", "Lsp4j.kt", "import org.eclipse.lsp4j.Position"),
            file("run", "GoRunConfigurationBase.kt", "import com.redhat.devtools.lsp4ij.dap.DAPRunConfigurationBase"),
            file("debug", "Dap.kt", "import com.redhat.devtools.lsp4ij.internal.StringUtils"),
            file("debug", "DapFqn.kt", "val s = com.redhat.devtools.lsp4ij.internal.StringUtils.EMPTY"),
            file("misc", "Misc.kt"),
            SourceFile("src/main/kotlin/io/github/xefise/golsp/run/Moved.kt", "package $ROOT.lang\n"),
        )
        bad.forEach { assertEquals(it.path + ": " + ArchitectureRules.violations(it), 1, ArchitectureRules.violations(it).size) }

        val good = listOf(
            file("execution", "GoRunConfigurationBase.kt", "import com.redhat.devtools.lsp4ij.dap.DAPRunConfigurationBase"),
            file("debug.model", "Launch.kt", "import kotlin.collections.List", "import com.google.gson.JsonObject", "import $ROOT.execution.model.GoExecutionRequest"),
            file("run", "Gutter.kt", "import $ROOT.lang.GoFile", "import com.intellij.execution.lineMarker.RunLineMarkerContributor"),
            file("run", "Mentions.kt", "// see $ROOT.lsp.GoplsServer and com.redhat.devtools.lsp4ij.Foo", "val s = \"$ROOT.debug.X org.eclipse.lsp4j.Y\""),
            file("lang", "Prefix.kt", "import com.redhat.devtools.lsp4ijx.Thing"),
            file("run", "Self.kt", "import $ROOT.run.model.GoAppSettings"),
        )
        good.forEach { assertEquals(it.path, emptyList<String>(), ArchitectureRules.violations(it)) }
    }

    private fun file(pkg: String, name: String, vararg lines: String): SourceFile = SourceFile(
        "src/main/kotlin/${"$ROOT.$pkg".replace('.', '/')}/$name",
        "package $ROOT.$pkg\n\n" + lines.joinToString("\n") + "\n",
    )

    private companion object {
        const val ROOT = ArchitectureRules.ROOT
    }
}
