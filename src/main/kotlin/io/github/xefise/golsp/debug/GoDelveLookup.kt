package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import io.github.xefise.golsp.toolchain.GoToolchainService
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.GoToolchainState

internal object GoDelveLookup {
    private const val DETECTION_WAIT_MS = 10_000L

    /** Waits for toolchain detection off the EDT only; on the EDT a still-detecting toolchain is reported as such. */
    @Throws(ExecutionException::class)
    fun find(project: Project): String {
        val toolchain = GoToolchainService.getInstance(project)
        toolchain.awaitDetected(DETECTION_WAIT_MS)
        return toolchain.findTool(GoTool.DLV) ?: throw ExecutionException(
            if (toolchain.state is GoToolchainState.Detecting) "The Go toolchain is still being detected; try again in a moment"
            else "dlv not found in GOBIN, PATH or ~/go/bin; click Install in the Go editor banner or set its path in the Go settings",
        )
    }
}
