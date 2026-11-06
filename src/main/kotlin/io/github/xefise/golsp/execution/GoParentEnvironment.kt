package io.github.xefise.golsp.execution

import com.intellij.util.EnvironmentUtil

/** `$VAR$` references to the IDE environment in run-configuration variables, the platform's own syntax. */
object GoParentEnvironment {
    fun inline(envs: Map<String, String>): Map<String, String> =
        // getEnvironmentMap() may block until the shell env is loaded; skip it when nothing references it.
        if (envs.values.none { '$' in it }) envs else inline(envs, EnvironmentUtil.getEnvironmentMap())

    fun inline(envs: Map<String, String>, parent: Map<String, String>): Map<String, String> =
        LinkedHashMap(envs).also { EnvironmentUtil.inlineParentOccurrences(it, parent) }
}
