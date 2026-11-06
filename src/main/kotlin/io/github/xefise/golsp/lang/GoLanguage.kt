package io.github.xefise.golsp.lang

import com.intellij.lang.Language

// Persisted ID; distinct from the official Go plugin's "go".
object GoLanguage : Language("golsp.go") {
    private fun readResolve(): Any = GoLanguage

    override fun getDisplayName(): String = "Go"
}
