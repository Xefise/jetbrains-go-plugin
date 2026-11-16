package io.github.xefise.golsp.test.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** One line of `go test -json` (cmd/test2json format plus the `build-*` events of `go test`). */
data class GoTestEvent(
    val action: String,
    val pkg: String? = null,
    val test: String? = null,
    val output: String? = null,
    val elapsedSeconds: Double? = null,
    /** On `build-output` / `build-fail`: "pkg" or "pkg [pkg.test]". */
    val importPath: String? = null,
    /** On a package `fail`: the [importPath] whose build failed. */
    val failedBuild: String? = null,
    /** Go 1.27+: "frame" for `=== RUN` / `--- PASS` style lines, "error" / "error-continue" for `t.Error` lines. */
    val outputType: String? = null,
) {
    companion object {
        /** Null for anything that is not a JSON object with an `Action`: plain text the `go` command printed. */
        fun parse(line: String): GoTestEvent? {
            val text = line.trim()
            if (!text.startsWith("{")) return null
            val json = try {
                JsonParser.parseString(text) as? JsonObject
            } catch (_: RuntimeException) {
                null
            } ?: return null
            val action = json.string("Action") ?: return null
            return GoTestEvent(
                action = action,
                pkg = json.string("Package"),
                test = json.string("Test"),
                output = json.string("Output"),
                elapsedSeconds = (json.get("Elapsed") as? JsonPrimitive)?.takeIf { it.isNumber }?.asDouble,
                importPath = json.string("ImportPath"),
                failedBuild = json.string("FailedBuild"),
                outputType = json.string("OutputType"),
            )
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
    }
}
