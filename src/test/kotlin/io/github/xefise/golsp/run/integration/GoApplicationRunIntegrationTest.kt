package io.github.xefise.golsp.run.integration

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.util.Key
import io.github.xefise.golsp.run.GoApplicationConfiguration
import io.github.xefise.golsp.run.GoApplicationConfigurationType
import io.github.xefise.golsp.run.GoRunKind
import io.github.xefise.golsp.toolchain.integration.GoIntegrationTestCase
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GoApplicationRunIntegrationTest : GoIntegrationTestCase() {

    fun testRunPrintsProgramOutputIncludingEmptyLines() {
        copyTestProject("hello", HELLO_DIR)
        val run = execute(configuration(absolutePath(HELLO_DIR)))
        // total = 0+1+4+9+16; every worker sums 0+0 + 1+4 + 9+16 = 30; fmt prints map keys sorted.
        val expected = """
            hello


            after empty lines
            total: 30
            {0 0 origin} [{0 0 origin} {1 2 a} {3 4 b}] map[apples:3 pears:5]
            map[worker-1:30 worker-2:30 worker-3:30]

        """.trimIndent()
        assertEquals(expected, run.stdout)
        assertEquals(0, run.exitCode)
    }

    fun testRunPassesProgramArgumentsWithSpaces() {
        fixture.addFileToProject("args/go.mod", "module args\n\ngo 1.27\n")
        fixture.addFileToProject(
            "args/main.go",
            "package main\n\nimport (\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc main() {\n\tfor _, a := range os.Args[1:] {\n\t\tfmt.Printf(\"[%s]\\n\", a)\n\t}\n}\n",
        )
        val run = execute(configuration(absolutePath("args")).withConfig { programArguments = "one \"two words\"" })
        assertEquals("[one]\n[two words]\n", run.stdout)
        assertEquals(0, run.exitCode)
    }

    fun testProgramExitCodeIsTheRunExitCode() {
        addMain("exit", "import \"os\"\n\nfunc main() {\n\tos.Exit(3)\n}\n")
        val run = execute(configuration(absolutePath("exit")))
        assertEquals(3, run.exitCode)
        assertFalse(run.stderr, run.stderr.contains("exit status"))
    }

    fun testCompileErrorStopsBeforeTheProgramWithTheCompilerError() {
        addMain("broken", "func main() {\n\tprintln(\"must not run\")\n\tundefinedCall()\n}\n")
        val run = execute(configuration(absolutePath("broken")))
        assertTrue("exit code ${run.exitCode}", run.exitCode != 0)
        assertEquals("", run.stdout)
        assertTrue(run.stderr, run.stderr.contains("undefined: undefinedCall"))
        assertFalse(run.stderr, run.stderr.contains("must not run"))
    }

    fun testEnvFileExplicitVariablesAndWorkingDirectoryReachTheProgram() {
        addMain(
            "envs",
            "import (\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc main() {\n\twd, _ := os.Getwd()\n" +
                "\tfmt.Printf(\"A=%s B=%s C=%s\\nwd=%s\\n\", os.Getenv(\"A\"), os.Getenv(\"B\"), os.Getenv(\"C\"), wd)\n}\n",
        )
        fixture.addFileToProject("envs/app.env", "A=from-file\nB=from-file\nC=\"${'$'}{A}-ref\"\n")
        fixture.addFileToProject("envs/data/keep.txt", "")
        val run = execute(configuration(absolutePath("envs")).withConfig {
            envFile = absolutePath("envs/app.env")
            envs = mapOf("B" to "explicit")
            workingDirectory = absolutePath("envs/data")
        })
        // os.Getwd resolves symlinks such as macOS /var -> /private/var.
        assertEquals("A=from-file B=explicit C=from-file-ref\nwd=${File(absolutePath("envs/data")).canonicalPath}\n", run.stdout)
        assertEquals(0, run.exitCode)
    }

    fun testFileKindBuildsOnlyThatFile() {
        // Two mains in one directory: the package does not build, each file does.
        addMain("files", "func main() {\n\tprintln(\"a\")\n}\n", fileName = "a.go")
        addMain("files", "func main() {\n\tprintln(\"b\")\n}\n", fileName = "b.go", withModule = false)
        val run = execute(configuration("").withConfig {
            runKind = GoRunKind.FILE
            filePath = absolutePath("files/b.go")
        })
        assertEquals("b\n", run.stderr)
        assertEquals(0, run.exitCode)
    }

    fun testBuildFlagsReachGoBuild() {
        addMain("flags", "var version = \"dev\"\n\nfunc main() {\n\tprint(version)\n}\n")
        val run = execute(configuration(absolutePath("flags")).withConfig { buildFlags = "\"-ldflags=-X main.version=42\"" })
        assertEquals("42", run.stderr)
    }

    fun testInputTypedDuringTheBuildReachesTheProgram() {
        addMain(
            "stdin",
            "import (\n\t\"bufio\"\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc main() {\n\tline, _ := bufio.NewReader(os.Stdin).ReadString('\\n')\n" +
                "\tfmt.Printf(\"got %q\\n\", line)\n}\n",
        )
        val run = execute(configuration(absolutePath("stdin"))) { handler ->
            handler.processInput!!.apply {
                write("hi\n".toByteArray())
                flush()
            }
        }
        assertEquals("got \"hi\\n\"\n", run.stdout)
    }

    fun testStopTerminatesARunningProgram() {
        addMain(
            "sleep",
            "import (\n\t\"fmt\"\n\t\"time\"\n)\n\nfunc main() {\n\tfmt.Println(\"ready\")\n\ttime.Sleep(time.Minute)\n}\n",
        )
        val handler = AtomicReference<ProcessHandler>()
        val stdout = StringBuffer()
        val run = execute(configuration(absolutePath("sleep")), onStart = handler::set, onStdout = { text ->
            stdout.append(text)
            if (stdout.contains("ready")) handler.get().destroyProcess()
        })
        assertEquals("ready\n", run.stdout)
        assertTrue("exit code ${run.exitCode}", run.exitCode != 0)
    }

    fun testUntrustedProjectRefusesToRun() {
        copyTestProject("hello", HELLO_DIR)
        val settings = configuration(absolutePath(HELLO_DIR))
        val previous = System.setProperty(TRUST_HEADLESS_DISABLED, "false")
        try {
            TrustedProjects.setProjectTrusted(project, false)
            assertFalse(TrustedProjects.isProjectTrusted(project))
            val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
            val error = try {
                settings.configuration.getState(env.executor, env)
                null
            } catch (e: ExecutionException) {
                e
            }
            assertNotNull("getState must refuse an untrusted project", error)
            assertTrue(error!!.message, error.message!!.contains("untrusted"))
        } finally {
            if (previous == null) System.clearProperty(TRUST_HEADLESS_DISABLED) else System.setProperty(TRUST_HEADLESS_DISABLED, previous)
        }
    }

    private fun configuration(packageDirectory: String): RunnerAndConfigurationSettings {
        val type = ConfigurationTypeUtil.findConfigurationType(GoApplicationConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("app", type.configurationFactories.single())
        (settings.configuration as GoApplicationConfiguration).packageDirectory = packageDirectory
        return settings
    }

    private fun addMain(dir: String, body: String, fileName: String = "main.go", withModule: Boolean = true) {
        if (withModule) fixture.addFileToProject("$dir/go.mod", "module $dir\n\ngo 1.27\n")
        fixture.addFileToProject("$dir/$fileName", "package main\n\n$body")
    }

    private fun RunnerAndConfigurationSettings.withConfig(block: GoApplicationConfiguration.() -> Unit): RunnerAndConfigurationSettings =
        also { (configuration as GoApplicationConfiguration).block() }

    private class Captured(val stdout: String, val stderr: String, val exitCode: Int)

    /** Runs through the Run executor and its resolved runner, the same path as the toolbar button. */
    private fun execute(
        settings: RunnerAndConfigurationSettings,
        onStdout: (String) -> Unit = {},
        onStart: (ProcessHandler) -> Unit = {},
    ): Captured {
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val exitCode = AtomicReference<Int>()
        val failure = AtomicReference<Throwable>()
        val connection = project.messageBus.connect(testRootDisposable)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            // Called before startNotify(), so no output can be missed.
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        when {
                            ProcessOutputType.isStdout(outputType) -> {
                                stdout.append(event.text)
                                onStdout(event.text)
                            }
                            ProcessOutputType.isStderr(outputType) -> stderr.append(event.text)
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        exitCode.set(event.exitCode)
                    }
                })
                onStart(handler)
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                failure.set(cause ?: IllegalStateException("process not started"))
            }
        })
        val env = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build()
        env.runner.execute(env)
        waitUntil("go build and the program to finish") { exitCode.get() != null || failure.get() != null }
        failure.get()?.let { throw AssertionError("Run did not start", it) }
        connection.disconnect()
        return Captured(stdout.toString(), stderr.toString(), exitCode.get())
    }

    private companion object {
        const val HELLO_DIR = "hello app"
        const val TRUST_HEADLESS_DISABLED = "idea.trust.headless.disabled"
    }
}
