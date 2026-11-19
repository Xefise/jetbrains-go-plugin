package io.github.xefise.golsp.lsp.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Settings answered to gopls' `workspace/configuration` request for the "gopls" section. */
object GoplsSettings {
    const val SECTION: String = "gopls"

    // gopls keys are flat names; vscode-go's "ui."/"build." prefixes are only its own grouping.
    fun defaults(
        usePlaceholders: Boolean,
        analyses: Map<String, Boolean> = emptyMap(),
        hints: Map<String, Boolean> = GoplsInlayHints.DEFAULTS,
    ): JsonObject = JsonObject().apply {
        addProperty("semanticTokens", true)
        // Completed calls get tab stops for their arguments; gopls' own default is off.
        addProperty("usePlaceholders", usePlaceholders)
        add("hints", JsonObject().apply { hints.forEach { (name, on) -> addProperty(name, on) } })
        add("directoryFilters", JsonArray().apply { add("-**/node_modules") })
        // Tests run from our own gutter markers; gopls' test code lenses would duplicate them.
        add("codelenses", JsonObject().apply { addProperty("test", false) })
        // didCreateFiles arrives while a template-created file is still empty on disk, so gopls' clause lands on top of ours.
        addProperty("newGoFileHeader", false)
        if (analyses.isNotEmpty()) add("analyses", JsonObject().apply { analyses.forEach { (name, on) -> addProperty(name, on) } })
    }

    /** `{"gopls": defaults}` with [user] (the whole settings object) deep-merged on top; user values win. */
    fun build(
        user: JsonObject?,
        usePlaceholders: Boolean,
        analyses: Map<String, Boolean> = emptyMap(),
        hints: Map<String, Boolean> = GoplsInlayHints.DEFAULTS,
    ): JsonObject {
        val result = JsonObject().apply { add(SECTION, defaults(usePlaceholders, analyses, hints)) }
        return if (user == null) result else deepMerge(result, user)
    }

    fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
        val merged = base.deepCopy()
        for ((key, value) in override.entrySet()) {
            val existing: JsonElement? = merged.get(key)
            merged.add(key, if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value.deepCopy())
        }
        return merged
    }
}
