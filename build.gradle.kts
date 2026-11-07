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
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
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
