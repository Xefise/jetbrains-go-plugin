package io.github.xefise.golsp.debug

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoDelveDescriptorTest : BasePlatformTestCase() {
    // isDebuggableFile decides which breakpoints LSP4IJ sends to dlv; it never touches the environment.
    private val descriptor by lazy { GoDelveDescriptor(RunConfigurationOptions(), ExecutionEnvironment(), null) }

    private fun debuggable(name: String): Boolean = descriptor.isDebuggableFile(LightVirtualFile(name, ""), project)

    fun testGoSourceFileIsDebuggable() {
        assertTrue(debuggable("main.go"))
    }

    fun testGoTestFileIsDebuggable() {
        assertTrue(debuggable("main_test.go"))
    }

    fun testGoModIsNotDebuggable() {
        assertFalse(debuggable("go.mod"))
    }

    fun testOtherFileTypeIsNotDebuggable() {
        assertFalse(debuggable("index.ts"))
    }
}
