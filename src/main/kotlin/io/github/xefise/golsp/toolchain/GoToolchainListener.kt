package io.github.xefise.golsp.toolchain

import com.intellij.util.messages.Topic
import io.github.xefise.golsp.toolchain.model.GoToolchainState

/** Published on the project message bus from a background thread whenever the toolchain state changes. */
fun interface GoToolchainListener {
    fun toolchainChanged(state: GoToolchainState)

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC: Topic<GoToolchainListener> = Topic.create("Go toolchain changed", GoToolchainListener::class.java)
    }
}
