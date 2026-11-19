package io.github.xefise.golsp.lsp.model

/** gopls inlay hint kinds grouped under Settings | Editor | Inlay Hints; provider and option ids are persisted. */
object GoplsInlayHints {
    const val TYPES_PROVIDER: String = "go.gopls.types"
    const val PARAMETERS_PROVIDER: String = "go.gopls.parameters"
    const val VALUES_PROVIDER: String = "go.gopls.values"

    const val PROVIDER_ENABLED_BY_DEFAULT: Boolean = true

    /** [name] is both the gopls `hints` key and the option id of [providerId]. */
    data class Hint(val name: String, val providerId: String, val enabledByDefault: Boolean)

    val ALL: List<Hint> = listOf(
        // Rider-style type hints after `x :=` and range variables; gopls ships every hint off.
        Hint("assignVariableTypes", TYPES_PROVIDER, true),
        Hint("rangeVariableTypes", TYPES_PROVIDER, true),
        Hint("compositeLiteralTypes", TYPES_PROVIDER, false),
        Hint("functionTypeParameters", TYPES_PROVIDER, false),
        Hint("parameterNames", PARAMETERS_PROVIDER, false),
        Hint("compositeLiteralFields", PARAMETERS_PROVIDER, false),
        Hint("constantValues", VALUES_PROVIDER, false),
        Hint("ignoredError", VALUES_PROVIDER, false),
    )

    val PROVIDERS: List<String> = ALL.map { it.providerId }.distinct()

    /** Every hint with an explicit value; a null lookup means the user never touched it, so the default applies. */
    fun map(providerEnabled: (String) -> Boolean?, optionEnabled: (hint: String, providerId: String) -> Boolean?): Map<String, Boolean> {
        val providers = PROVIDERS.associateWith { providerEnabled(it) ?: PROVIDER_ENABLED_BY_DEFAULT }
        return ALL.associate { hint ->
            hint.name to (providers.getValue(hint.providerId) && (optionEnabled(hint.name, hint.providerId) ?: hint.enabledByDefault))
        }
    }

    val DEFAULTS: Map<String, Boolean> = map({ null }) { _, _ -> null }
}
