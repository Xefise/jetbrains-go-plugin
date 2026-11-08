package io.github.xefise.golsp.debug.model

/** One Delve `substitutePath` entry: [from] is the local path, [to] the path on the machine dlv runs on. */
data class SubstitutePathRule(val from: String, val to: String)

/** Where an already running `dlv` DAP server listens. */
sealed interface DelveAttachTarget {
    val host: String
    val port: Int

    /** A headless dlv started by the user, possibly on another machine. */
    data class Remote(
        override val host: String,
        override val port: Int,
        val substitutePath: List<SubstitutePathRule> = emptyList(),
    ) : DelveAttachTarget

    /** A `dlv dap` the plugin started on loopback to attach to the local process [processId]. */
    data class Local(override val port: Int, val processId: Int) : DelveAttachTarget {
        override val host: String get() = LOOPBACK
    }

    companion object {
        const val LOOPBACK: String = "127.0.0.1"
    }
}

object DelveAttachBuilder {
    fun attachParameters(target: DelveAttachTarget): Map<String, Any> {
        val params = linkedMapOf<String, Any>("type" to "go", "request" to "attach")
        when (target) {
            is DelveAttachTarget.Remote -> {
                params["mode"] = "remote"
                // Delve applies the rules in order, first match wins.
                if (target.substitutePath.isNotEmpty()) {
                    params["substitutePath"] = target.substitutePath.map { linkedMapOf("from" to it.from, "to" to it.to) }
                }
            }
            is DelveAttachTarget.Local -> {
                params["mode"] = "local"
                params["processId"] = target.processId
            }
        }
        return params
    }

    fun hostError(host: String): String? = if (host.isBlank()) "Host is not specified" else null

    fun portError(port: Int): String? = if (port in 1..65535) null else "Port must be between 1 and 65535, got $port"

    /** [index] is zero-based; the message counts rows from one, as the editor table shows them. */
    fun ruleError(rule: SubstitutePathRule, index: Int): String? = when {
        rule.from.isBlank() && rule.to.isBlank() -> "Path mapping ${index + 1}: both local and remote paths are empty"
        else -> null
    }

    fun targetError(target: DelveAttachTarget): String? {
        hostError(target.host)?.let { return it }
        portError(target.port)?.let { return it }
        when (target) {
            is DelveAttachTarget.Remote ->
                target.substitutePath.forEachIndexed { index, rule -> ruleError(rule, index)?.let { return it } }
            is DelveAttachTarget.Local -> if (target.processId <= 0) return "Invalid process id ${target.processId}"
        }
        return null
    }
}
