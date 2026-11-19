import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import java.time.Duration
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
}

// Machine-specific settings live in the git-ignored local.properties; -P / gradle.properties win over it.
// Read through providers so that the configuration cache is invalidated when the file changes.
val localProperties: Provider<Properties> = providers.fileContents(layout.projectDirectory.file("local.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) } }

fun localSetting(name: String): Provider<String> = providers.gradleProperty(name).filter { it.isNotBlank() }
    .orElse(localProperties.map { it.getProperty(name)?.trim().orEmpty() }.filter { it.isNotEmpty() })

val webstormPath: String = run {
    val path = localSetting("webstormPath").orNull
        ?: throw GradleException(
            "webstormPath is not set. Add `webstormPath=<WebStorm 2026.3 install dir>` to local.properties " +
                "in the project root (git-ignored), or pass -PwebstormPath=<dir>"
        )
    if (!file(path).isDirectory) {
        throw GradleException("webstormPath '$path' is not a directory; fix it in local.properties or pass -PwebstormPath=<dir>")
    }
    path
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Installed WebStorm 2026.3; switch to webstorm("2026.3") once it resolves from the repository.
        local(webstormPath)
        plugin("com.redhat.devtools.lsp4ij:0.21.0")
        // Product module v2 (DefaultDebugExecutor, XDebugger API); not on the classpath by default.
        bundledModule("intellij.platform.debugger")
        // SMTestRunner (test tree, OutputToGeneralTestEventsConverter, SMTestLocator).
        bundledPlugin("com.intellij.platform.testRunner")
        // CoverageEngine API and the agent's ProjectData/ClassData/LineData.
        bundledModule("intellij.platform.coverage")
        bundledModule("intellij.platform.coverage.agent")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            local(webstormPath)
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        // INTERNAL_API_USAGES is enforced by verifyPlugin.doLast below: the verifier's ignored-problems file
        // cannot mute internal API usages, and exactly one of them is unavoidable.
    }
}

// Internal API usages must match verifier-allowed-internal-api.txt exactly: nothing unlisted, every listed one present
// (the CoverageEngine.getQualifiedName override, without which the editor shows no coverage).
tasks.verifyPlugin {
    val reports = verificationReportsDirectory
    val allowedFile = layout.projectDirectory.file("verifier-allowed-internal-api.txt").asFile
    inputs.file(allowedFile)
    // Reports of earlier runs (other IDE builds) must not satisfy or fail the check below.
    doFirst { reports.get().asFile.deleteRecursively() }
    doLast {
        val allowed = allowedFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map(::Regex)
        val verdicts = reports.get().asFile.walkTopDown().filter { it.name == "verification-verdict.txt" }.toList()
        if (verdicts.isEmpty()) throw GradleException("No plugin verifier report under ${reports.get().asFile}")
        for (verdict in verdicts) {
            val report = verdict.resolveSibling("internal-api-usages.txt")
            val usages = if (report.isFile) report.readLines().filter { it.isNotBlank() } else emptyList()
            val unlisted = usages.filterNot { usage -> allowed.any { it.matches(usage) } }
            if (unlisted.isNotEmpty()) {
                throw GradleException("Internal API usages not listed in ${allowedFile.name}:\n" + unlisted.joinToString("\n"))
            }
            val missing = allowed.filterNot { pattern -> usages.any { pattern.matches(it) } }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Allowed internal API usage not found in $report (report format changed or the usage is gone; " +
                        "update ${allowedFile.name}): " + missing.joinToString { it.pattern },
                )
            }
        }
    }
}

tasks.test {
    systemProperty("golsp.projectDir", projectDir.absolutePath)
    exclude("**/integration/**")
}

// Tests against real go/gopls/dlv; kept out of `test` so the unit suite stays fast.
intellijPlatformTesting.testIde.register("integrationTest") {
    testFramework(TestFrameworkType.Platform)
    task {
        useJUnit()
        // A deadlock in a blocking LSP/DAP call must fail the build, not hang it.
        timeout = Duration.ofMinutes(20)
        // Headless runs Task.Backgroundable in the caller thread; LSP4IJ's progress task would then block its own message thread.
        systemProperty("intellij.progress.task.ignoreHeadless", "true")
        systemProperty("golsp.projectDir", projectDir.absolutePath)
        // Inherited by go, gopls and dlv started from the tests: never download a toolchain mid-test.
        environment("GOTOOLCHAIN", "local")
        include("**/integration/**")
    }
}

// Regenerates the bundled gopls analyzer catalog and one inspection per analyzer from gopls' api.json:
// ./gradlew generateGoplsAnalyzers -PgoplsDir="$(go env GOMODCACHE)/golang.org/x/tools/gopls@v0.24.0"
tasks.register("generateGoplsAnalyzers") {
    val goplsDir = providers.gradleProperty("goplsDir")
    val catalogFile = layout.projectDirectory.file("src/main/resources/golsp/gopls-analyzers.json").asFile
    val inspectionsFile = layout.projectDirectory.file("src/main/resources/META-INF/golsp-gopls-analyzers.xml").asFile
    doLast {
        val dir = File(goplsDir.orNull ?: throw GradleException("Pass -PgoplsDir=<module cache>/golang.org/x/tools/gopls@<version>"))
        val version = Regex("""gopls@(v[^/\\]+)$""").find(dir.path.trimEnd('/', '\\'))?.groupValues?.get(1)
            ?: throw GradleException("goplsDir must end with gopls@<version>: $dir")
        @Suppress("UNCHECKED_CAST")
        val analyzers = (groovy.json.JsonSlurper().parse(dir.resolve("internal/doc/api.json")) as Map<String, Any?>)["Analyzers"]
            as List<Map<String, Any?>>
        // Off in gopls' default staticcheck subset but worth having: `defer mu.Lock()` right after Lock (SA2003),
        // dead stores like `s = strings.TrimSpace(s)` on a range copy (SA4006, SA4017).
        val enabledByUs = setOf("SA2003", "SA4006", "SA4017")
        val groups = listOf(
            "QF" to "Staticcheck: quick fixes (QF)",
            "SA" to "Staticcheck: bugs (SA)",
            "ST" to "Staticcheck: style (ST)",
            "S" to "Staticcheck: simplifications (S)",
        )
        fun attr(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val catalog = mapOf(
            "goplsVersion" to version,
            "analyzers" to analyzers.map { mapOf("name" to it["Name"], "doc" to it["Doc"], "url" to it["URL"], "default" to it["Default"]) },
        )
        catalogFile.parentFile.mkdirs()
        catalogFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(catalog)) + "\n")
        val entries = analyzers.joinToString("\n") { analyzer ->
            val name = analyzer["Name"] as String
            // First sentence only: staticcheck titles run up to ~170 chars and get clipped in the inspections tree.
            val title = (analyzer["Doc"] as String).lineSequence().first().trim()
                .split(Regex("""(?<=[.;?])\s+""")).first().trimEnd('.', ';')
                .let { if (it.substringBefore(' ').contains('.')) it else it.replaceFirstChar(Char::uppercase) }
            val group = groups.firstOrNull { (prefix, _) -> Regex("${prefix}\\d+").matches(name) }?.second ?: "gopls"
            val enabled = analyzer["Default"] == true || name in enabledByUs
            """        <localInspection language="golsp.go" shortName="Gopls_${attr(name)}" displayName="${attr("$name: $title")}"
                         groupPath="Go" groupName="${attr(group)}" enabledByDefault="$enabled" level="WARNING"
                         hasStaticDescription="true" implementationClass="io.github.xefise.golsp.lsp.GoplsAnalyzerInspection"/>"""
        }
        inspectionsFile.writeText(
            "<!-- Generated from gopls $version internal/doc/api.json by `./gradlew generateGoplsAnalyzers`; do not edit. -->\n" +
                "<idea-plugin>\n    <extensions defaultExtensionNs=\"com.intellij\">\n$entries\n    </extensions>\n</idea-plugin>\n",
        )
    }
}

tasks.processResources {
    from("THIRD_PARTY_NOTICES.md") { into("META-INF") }
    from("licenses") { into("META-INF/licenses") }
}

// Smoke check of incompatible-with in GoLand: ./gradlew runGoLand -PgolandPath=<GoLand install dir>
localSetting("golandPath").orNull?.let { golandPath ->
    intellijPlatformTesting.runIde.register("runGoLand") {
        localPath = file(golandPath)
        plugins {
            plugin("com.redhat.devtools.lsp4ij:0.21.0")
        }
    }
}

// Sandbox-only IDE options: trusted testdata, no startup tips, no update checks; opt-in built-in MCP server.
tasks.runIde {
    // Locals, not script-level vals: doFirst must not capture the script object (configuration cache).
    val sandboxTestdata = layout.projectDirectory.dir("testdata").asFile.absolutePath
    val debuggerMcpPlugin = localSetting("debuggerMcpPluginPath").orNull?.let(::File)?.takeIf { it.isDirectory }
    val mcpSandbox = localSetting("mcpSandbox").orNull.toBoolean()
    val mcpPort = localSetting("mcpPort").orNull?.toInt() ?: 64542
    args(layout.projectDirectory.dir("testdata/hello").asFile.absolutePath)
    // runIde is never up to date, unlike its prepareSandbox, so the options are re-applied on every launch.
    doFirst {
        // Copied after prepareSandbox's sync, which would otherwise delete it.
        debuggerMcpPlugin?.copyRecursively(sandboxPluginsDirectory.get().asFile.resolve(debuggerMcpPlugin.name), overwrite = true)
        val options = sandboxConfigDirectory.get().asFile.resolve("options").apply { mkdirs() }
        val mcpServer = options.resolve("mcpServer.xml")
        if (mcpSandbox) {
            mcpServer.writeText(
                """
                <application>
                  <component name="McpServerSettings">
                    <option name="enableMcpServer" value="true" />
                    <option name="enableBraveMode" value="true" />
                    <option name="mcpServerPort" value="$mcpPort" />
                    <option name="consent" value="GRANTED" />
                  </component>
                </application>
                """.trimIndent()
            )
        } else if (mcpServer.isFile && mcpServer.readText().contains("enableBraveMode\" value=\"true")) {
            // Brave mode lets MCP clients run commands without confirmation; it must not outlive the flag.
            mcpServer.delete()
        }
        options.resolve("trusted-paths.xml").writeText(
            """
            <application>
              <component name="Trusted.Paths.Settings">
                <option name="TRUSTED_PATHS">
                  <list>
                    <option value="$sandboxTestdata" />
                  </list>
                </option>
              </component>
            </application>
            """.trimIndent()
        )
        // The dev build of this plugin is not on Marketplace, so the plugin update check only produces an error balloon.
        options.resolve("updates.xml").writeText(
            """
            <application>
              <component name="UpdatesConfigurable">
                <option name="CHECK_NEEDED" value="false" />
                <option name="PLUGINS_CHECK_NEEDED" value="false" />
                <option name="SHOW_WHATS_NEW_EDITOR" value="false" />
              </component>
            </application>
            """.trimIndent()
        )
        options.resolve("ide.general.xml").takeUnless { it.exists() }?.writeText(
            """
            <application>
              <component name="GeneralSettings">
                <option name="showTipsOnStartup" value="false" />
              </component>
            </application>
            """.trimIndent()
        )
    }
}
