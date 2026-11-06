package io.github.xefise.golsp.execution.model

import java.nio.file.Path

/** What `go build` / dlv compiles; [directory] is where the build runs. */
sealed interface GoRunTarget {
    val directory: String

    /** The `program` path in Delve's terms. */
    val path: String

    data class Package(override val directory: String) : GoRunTarget {
        override val path: String get() = directory
    }

    data class File(override val directory: String, val fileName: String) : GoRunTarget {
        override val path: String get() = Path.of(directory, fileName).toString()
    }
}

/** What to run, independent of whether it is run plainly or under Delve. */
data class GoExecutionRequest(
    val target: GoRunTarget,
    val programArguments: List<String> = emptyList(),
    /** Variables set on top of the IDE environment, for the build and the program alike. */
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String = target.directory,
    val buildFlags: List<String> = emptyList(),
)

object GoEnvironmentLayers {
    /** Explicit variables win over the env file; both win over the inherited environment. */
    fun merge(envFile: Map<String, String>, explicit: Map<String, String>): Map<String, String> = envFile + explicit

    /** [path] already starts with the IDE's go, so tools spawned by go and dlv resolve to the same toolchain. */
    fun withPath(environment: Map<String, String>, path: String?): Map<String, String> =
        if (path == null) environment else environment + ("PATH" to path)
}

data class ProcessSpec(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
)

object GoRunCommandBuilder {
    private const val DEFAULT_BINARY_NAME = "main"
    private val OUTPUT_FLAGS = setOf("-o", "--o")

    /** The output path is ours (Run) or dlv's (Debug); a user `-o` would make the launched binary stale or missing. */
    fun buildFlagsError(buildFlags: List<String>): String? {
        val flag = buildFlags.firstOrNull { it in OUTPUT_FLAGS || OUTPUT_FLAGS.any { prefix -> it.startsWith("$prefix=") } }
            ?: return null
        return "Go build flags must not set the output file ('$flag'); the IDE chooses where the binary goes"
    }

    /** `go build` from the target directory, so the main module is resolved the same way as by `go run`. */
    fun buildCommand(goExecutable: String, request: GoExecutionRequest, outputBinary: String): ProcessSpec {
        val target = when (val t = request.target) {
            is GoRunTarget.Package -> "."
            is GoRunTarget.File -> t.fileName
        }
        return ProcessSpec(
            executable = goExecutable,
            arguments = listOf("build", "-o", outputBinary) + request.buildFlags + target,
            workingDirectory = request.target.directory,
            environment = request.environment,
        )
    }

    fun programCommand(request: GoExecutionRequest, binary: String): ProcessSpec = ProcessSpec(
        executable = binary,
        arguments = request.programArguments,
        workingDirectory = request.workingDirectory,
        environment = request.environment,
    )

    /** Mirrors `go build` naming: the package directory name or the file name without `.go`. */
    fun binaryName(target: GoRunTarget, windows: Boolean): String {
        val base = when (target) {
            is GoRunTarget.Package -> Path.of(target.directory).fileName?.toString()
            is GoRunTarget.File -> target.fileName.removeSuffix(".go")
        }
        val name = base?.takeIf { it.isNotBlank() } ?: DEFAULT_BINARY_NAME
        return if (windows) "$name.exe" else name
    }
}
