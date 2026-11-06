package io.github.xefise.golsp.toolchain.model

import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** Output of `go env -json`; empty values are normalised to null. */
data class GoEnv(val values: Map<String, String>) {
    val goRoot: String? get() = value("GOROOT")
    val goVersion: String? get() = value("GOVERSION")
    val goPath: String? get() = value("GOPATH")
    val goBin: String? get() = value("GOBIN")
    val goModCache: String? get() = value("GOMODCACHE")
    val goOs: String? get() = value("GOOS")
    val goArch: String? get() = value("GOARCH")
    val goFlags: String? get() = value("GOFLAGS")
    val goToolchain: String? get() = value("GOTOOLCHAIN")

    fun value(name: String): String? = values[name]?.takeIf { it.isNotBlank() }
}

sealed interface GoEnvParseResult {
    data class Success(val env: GoEnv) : GoEnvParseResult
    data class Failure(val message: String) : GoEnvParseResult
}

object GoEnvParser {
    val ARGUMENTS: List<String> = listOf("env", "-json")

    fun parse(stdout: String): GoEnvParseResult {
        if (stdout.isBlank()) return GoEnvParseResult.Failure("`go env -json` printed nothing")
        val root = try {
            JsonParser.parseString(stdout)
        } catch (e: JsonParseException) {
            return GoEnvParseResult.Failure("`go env -json` printed invalid JSON: ${e.message}")
        }
        if (!root.isJsonObject) return GoEnvParseResult.Failure("`go env -json` did not print a JSON object")
        val values = root.asJsonObject.entrySet()
            .filter { (_, v) -> v.isJsonPrimitive && v.asJsonPrimitive.isString }
            .associate { (k, v) -> k to v.asString }
        if (values["GOROOT"].isNullOrBlank()) return GoEnvParseResult.Failure("`go env -json` reported no GOROOT")
        return GoEnvParseResult.Success(GoEnv(values))
    }
}
