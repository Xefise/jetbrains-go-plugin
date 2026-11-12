package io.github.xefise.golsp.lsp.model

/** What a completed type turns into in a func signature: `warehouse *Warehouse` for a parameter, `*Warehouse` for a result. */
object GoTypeCompletion {
    private val PREDECLARED = setOf(
        "any", "bool", "byte", "comparable", "complex64", "complex128", "error", "float32", "float64", "int", "int8",
        "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    )

    // Structs that are passed by value by convention.
    private val VALUE_STRUCTS = setOf("time.Time")

    // Names Go code uses for these types instead of the derived ones.
    private val IDIOMATIC_NAMES = mapOf("context.Context" to listOf("ctx"))

    /**
     * LSP snippet replacing the type at [site]; [type] is the completed type with its package qualifier, if any.
     * Null when the completion stays as gopls sent it.
     */
    fun insertText(site: GoTypeSlotSite, type: String, isStruct: Boolean): String? {
        val typeName = type.substringAfterLast('.')
        if (type in PREDECLARED || !GoWords.isIdentifier(typeName)) return null
        val pointer = site.pointerTyped || (isStruct && type !in VALUE_STRUCTS)
        val typeText = (if (pointer) "*" else "") + type
        return when (site.slot) {
            GoTypeSlot.NAMED, GoTypeSlot.RESULT -> typeText
            GoTypeSlot.PARAMETER -> withName(names(type), typeText)
            GoTypeSlot.RECEIVER -> withName(names(type).reversed(), typeText)
        }
    }

    /** Parameter name candidates, the full one first: `HTTPClient` → `httpClient`, `h`. */
    fun names(type: String): List<String> {
        IDIOMATIC_NAMES[type]?.let { return it }
        val qualifier = type.substringBeforeLast('.', "")
        val typeName = type.substringAfterLast('.')
        val upper = typeName.takeWhile { it.isUpperCase() }.length
        val full = when {
            upper == typeName.length -> typeName.lowercase()
            upper <= 1 -> typeName.replaceFirstChar { it.lowercaseChar() }
            else -> typeName.substring(0, upper - 1).lowercase() + typeName.substring(upper - 1)
        }
        val short = typeName.first().lowercase()
        // A name equal to the type or the package would shadow it inside the function.
        return listOf(full, short).distinct().filter { GoWords.isIdentifier(it) && it != typeName && it != qualifier }
            .ifEmpty { listOf("v") }
    }

    private fun withName(names: List<String>, typeText: String): String {
        val name = if (names.size == 1) "\${1:${names[0]}}" else "\${1|${names.joinToString(",")}|}"
        return "$name $typeText\$0"
    }
}
