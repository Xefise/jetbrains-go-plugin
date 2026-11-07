package io.github.xefise.golsp.execution.model

/** What `dlv dap` itself prints on its stdout, as opposed to the debuggee's output. */
object DelveOutput {
    /** Start of the line dlv prints once it listens, before it can launch anything. */
    const val READY_PREFIX: String = "DAP server listening at: "
}
