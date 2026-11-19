package io.github.xefise.golsp.test

import com.intellij.execution.configurations.RunConfigurationOptions

// Constant names are persisted as option values; never rename them.
enum class GoTestScope { PACKAGE, FILE, DIRECTORY }

// Option keys are persisted in workspace.xml; never rename them. New options need a default that keeps old behaviour.
// Each Kotlin property must be named like its key: a mismatch silently drops the value on load.
class GoTestOptions : RunConfigurationOptions() {
    private val scopeProperty = enum(GoTestScope.PACKAGE).provideDelegate(this, "testScope")
    private val directoryProperty = string("").provideDelegate(this, "directory")
    private val filePathProperty = string("").provideDelegate(this, "filePath")
    private val testNamesProperty = list<String>().provideDelegate(this, "testNames")
    private val testArgumentsProperty = string("").provideDelegate(this, "testArguments")
    private val envsProperty = linkedMap<String, String>().provideDelegate(this, "envs")
    private val envFileProperty = string("").provideDelegate(this, "envFile")
    private val buildFlagsProperty = string("").provideDelegate(this, "buildFlags")
    private val raceDetectorProperty = property(false).provideDelegate(this, "raceDetector")

    var testScope: GoTestScope
        get() = scopeProperty.getValue(this)
        set(value) = scopeProperty.setValue(this, value)

    /** The package for [GoTestScope.PACKAGE], the root of `./...` for [GoTestScope.DIRECTORY]. */
    var directory: String
        get() = directoryProperty.getValue(this).orEmpty()
        set(value) = directoryProperty.setValue(this, value)

    var filePath: String
        get() = filePathProperty.getValue(this).orEmpty()
        set(value) = filePathProperty.setValue(this, value)

    /** Full test paths as `go test` reports them; empty runs the whole target. */
    var testNames: List<String>
        get() = testNamesProperty.getValue(this)
        set(value) = testNamesProperty.setValue(this, value.toMutableList())

    /** Passed to the test binary after `-args`. */
    var testArguments: String
        get() = testArgumentsProperty.getValue(this).orEmpty()
        set(value) = testArgumentsProperty.setValue(this, value)

    var envs: Map<String, String>
        get() = envsProperty.getValue(this)
        set(value) = envsProperty.setValue(this, LinkedHashMap(value))

    var envFile: String
        get() = envFileProperty.getValue(this).orEmpty()
        set(value) = envFileProperty.setValue(this, value)

    /** Build and test flags of `go test` itself (`-race`, `-count=1`, `-v`), before the package. */
    var buildFlags: String
        get() = buildFlagsProperty.getValue(this).orEmpty()
        set(value) = buildFlagsProperty.setValue(this, value)

    /** Adds `-race` to the build flags; kept apart from them so the flags field stays as typed. */
    var raceDetector: Boolean
        get() = raceDetectorProperty.getValue(this)
        set(value) = raceDetectorProperty.setValue(this, value)
}
