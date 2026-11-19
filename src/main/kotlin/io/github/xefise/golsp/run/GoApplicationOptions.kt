package io.github.xefise.golsp.run

import com.intellij.execution.configurations.RunConfigurationOptions

// Constant names are persisted as option values; never rename them.
enum class GoRunKind { PACKAGE, FILE }

// Option keys are persisted in workspace.xml; never rename them. New options need a default that keeps old behaviour.
class GoApplicationOptions : RunConfigurationOptions() {
    private val packageDirectoryProperty = string("").provideDelegate(this, "packageDirectory")
    private val programArgumentsProperty = string("").provideDelegate(this, "programArguments")
    private val runKindProperty = enum(GoRunKind.PACKAGE).provideDelegate(this, "runKind")
    private val filePathProperty = string("").provideDelegate(this, "filePath")
    private val workingDirectoryProperty = string("").provideDelegate(this, "workingDirectory")
    private val envsProperty = linkedMap<String, String>().provideDelegate(this, "envs")
    private val envFileProperty = string("").provideDelegate(this, "envFile")
    private val buildFlagsProperty = string("").provideDelegate(this, "buildFlags")
    private val raceDetectorProperty = property(false).provideDelegate(this, "raceDetector")

    var packageDirectory: String
        get() = packageDirectoryProperty.getValue(this).orEmpty()
        set(value) = packageDirectoryProperty.setValue(this, value)

    var programArguments: String
        get() = programArgumentsProperty.getValue(this).orEmpty()
        set(value) = programArgumentsProperty.setValue(this, value)

    var runKind: GoRunKind
        get() = runKindProperty.getValue(this)
        set(value) = runKindProperty.setValue(this, value)

    var filePath: String
        get() = filePathProperty.getValue(this).orEmpty()
        set(value) = filePathProperty.setValue(this, value)

    /** Empty means the package directory, or the file's directory for [GoRunKind.FILE]. */
    var workingDirectory: String
        get() = workingDirectoryProperty.getValue(this).orEmpty()
        set(value) = workingDirectoryProperty.setValue(this, value)

    var envs: Map<String, String>
        get() = envsProperty.getValue(this)
        set(value) = envsProperty.setValue(this, LinkedHashMap(value))

    var envFile: String
        get() = envFileProperty.getValue(this).orEmpty()
        set(value) = envFileProperty.setValue(this, value)

    var buildFlags: String
        get() = buildFlagsProperty.getValue(this).orEmpty()
        set(value) = buildFlagsProperty.setValue(this, value)

    /** Adds `-race` to the build flags; kept apart from them so the flags field stays as typed. */
    var raceDetector: Boolean
        get() = raceDetectorProperty.getValue(this)
        set(value) = raceDetectorProperty.setValue(this, value)
}
