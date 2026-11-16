package io.github.xefise.golsp.test.model

/** Output one test printed itself, kept to describe its failure or skip; bounded, the head is what matters. */
internal class GoTestOutputLog {
    private val all = StringBuilder()
    private val errors = StringBuilder()

    fun append(text: String, outputType: String?) {
        all.appendCapped(text)
        if (isError(outputType)) errors.appendCapped(text)
    }

    /** Go 1.27+ tags `t.Error` lines; older streams only have the whole output. */
    fun failure(): GoTestFailure = GoTestFailureParser.failure((if (errors.isNotEmpty()) errors else all).toString())

    fun skipReason(): String = GoTestFailureParser.skipReason(all.toString())

    val text: String get() = all.toString().trimEnd()

    companion object {
        const val MAX_CHARS = 64 * 1024

        fun isError(outputType: String?): Boolean = outputType == "error" || outputType == "error-continue"

        fun StringBuilder.appendCapped(text: String) {
            if (length < MAX_CHARS) append(text, 0, minOf(text.length, MAX_CHARS - length))
        }
    }
}
