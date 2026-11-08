package io.github.xefise.golsp.debug

import com.intellij.execution.ExecutionException
import io.github.xefise.golsp.debug.model.DelveAttachTarget

/**
 * A run profile whose Debug attaches to an already listening dlv instead of launching one.
 * Its getState must reject an invalid [attachTarget] (DelveAttachBuilder.targetError): the descriptor only reads it.
 */
interface GoDelveAttachProfile {
    @Throws(ExecutionException::class)
    fun attachTarget(): DelveAttachTarget
}
