package io.github.xefise.golsp.test.model

/**
 * Location URLs of test tree nodes: `gotest://<import path>` for a package, `gotest://<import path>#<full name>` for a
 * test, where the full name is the one `go test` reports ("TestX/with_space", "FuzzX/seed#0").
 */
object GoTestLocation {
    const val PROTOCOL: String = "gotest"

    data class Target(val importPath: String, val testName: String?)

    fun packageUrl(importPath: String): String = "$PROTOCOL://$importPath"

    fun testUrl(importPath: String, fullName: String): String = "$PROTOCOL://$importPath#$fullName"

    /** [path] is the URL without `gotest://`. Import paths cannot contain `#`, test names can: split at the first one. */
    fun parse(path: String): Target? {
        val cut = path.indexOf('#')
        val importPath = if (cut < 0) path else path.substring(0, cut)
        if (importPath.isEmpty()) return null
        val testName = if (cut < 0) null else path.substring(cut + 1).takeIf { it.isNotEmpty() }
        return Target(importPath, testName)
    }

    /** A whole `gotest://` URL; null for other protocols. */
    fun parseUrl(url: String): Target? {
        val prefix = "$PROTOCOL://"
        return if (url.startsWith(prefix)) parse(url.substring(prefix.length)) else null
    }

    /**
     * Picks the `t.Run` call a subtest path points at. [subtestPath] is the full name without the top-level function,
     * split on `/`; [callNames] are the literal names of the `t.Run` calls in that function, in source order (null for
     * a non-literal name). Calls are matched level by level, each after the previous one; a literal "a/b" spans two
     * levels, as in `go test`. Returns the index of the deepest matched call, or null if not even the first level
     * matched (dynamic names, `#01` duplicates).
     */
    fun matchSubtest(subtestPath: List<String>, callNames: List<String?>): Int? {
        var rest = subtestPath
        var from = 0
        var matched: Int? = null
        while (rest.isNotEmpty()) {
            val found = (from until callNames.size).firstNotNullOfOrNull { index ->
                val levels = callNames[index]?.let { GoTestRunPattern.rewrite(it).split('/') }
                if (levels != null && levels.size <= rest.size && rest.subList(0, levels.size) == levels) index to levels.size
                else null
            } ?: break
            matched = found.first
            from = found.first + 1
            rest = rest.drop(found.second)
        }
        return matched
    }
}
