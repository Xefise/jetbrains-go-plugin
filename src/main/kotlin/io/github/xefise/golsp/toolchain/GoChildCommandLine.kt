package io.github.xefise.golsp.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import io.github.xefise.golsp.toolchain.model.GoRootPolicy

/**
 * Inherits the login-shell environment like [GeneralCommandLine.ParentEnvironmentType.CONSOLE], minus a GOROOT
 * that is none of [goRoots]. The parent env is read when the process starts, so building this on EDT never waits for it.
 */
class GoChildCommandLine(command: List<String>, private val goRoots: List<String>) : GeneralCommandLine(command) {
    init {
        withParentEnvironmentType(ParentEnvironmentType.CONSOLE)
    }

    override fun getParentEnvironment(): Map<String, String> =
        GoRootPolicy.parentEnvironment(super.getParentEnvironment(), environment, goRoots, SystemInfo.isWindows)
}
