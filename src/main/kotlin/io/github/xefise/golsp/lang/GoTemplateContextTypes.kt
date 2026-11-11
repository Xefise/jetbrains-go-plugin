package io.github.xefise.golsp.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import io.github.xefise.golsp.lang.model.GoCodeContext
import io.github.xefise.golsp.lang.model.GoIndentToken
import io.github.xefise.golsp.lang.model.GoTemplatePlace
import io.github.xefise.golsp.lang.model.GoTemplateContexts

sealed class GoTemplateContextType(presentableName: String) : TemplateContextType(presentableName) {
    protected abstract fun accepts(place: GoTemplatePlace): Boolean

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        if (file.language != GoLanguage) return false
        return accepts(cachedPlace(file, templateActionContext.startOffset))
    }
}

class GoAnyContextType : GoTemplateContextType("Go") {
    override fun accepts(place: GoTemplatePlace): Boolean = place.context != GoCodeContext.LITERAL
}

class GoTopLevelContextType : GoTemplateContextType("Top level") {
    override fun accepts(place: GoTemplatePlace): Boolean = place.context == GoCodeContext.TOP_LEVEL && place.startsStatement
}

class GoStatementContextType : GoTemplateContextType("Statement") {
    override fun accepts(place: GoTemplatePlace): Boolean = place.context == GoCodeContext.STATEMENT && place.startsStatement
}

private class CachedPlace(val stamp: Long, val offset: Int, val place: GoTemplatePlace)

private val CACHED_PLACE = Key.create<CachedPlace>("golsp.template.place")

// The three context types are asked in turn for the same request; lex the file once.
private fun cachedPlace(file: PsiFile, offset: Int): GoTemplatePlace {
    val document = file.viewProvider.document ?: return templatePlace(file.viewProvider.contents, offset)
    val stamp = document.modificationStamp
    document.getUserData(CACHED_PLACE)?.takeIf { it.stamp == stamp && it.offset == offset }?.let { return it.place }
    val place = templatePlace(document.immutableCharSequence, offset)
    document.putUserData(CACHED_PLACE, CachedPlace(stamp, offset, place))
    return place
}

internal fun codeContext(text: CharSequence, offset: Int): GoCodeContext = templatePlace(text, offset).context

internal fun templatePlace(text: CharSequence, offset: Int): GoTemplatePlace {
    val at = offset.coerceIn(0, text.length)
    val lexer = GoLexer()
    lexer.start(text, 0, text.length, 0)
    val tokens = ArrayList<GoIndentToken>()
    while (lexer.tokenType != null && lexer.tokenStart < at) {
        tokens += GoIndentToken(goIndentKind(lexer.tokenType), lexer.tokenStart, lexer.tokenEnd)
        lexer.advance()
    }
    return GoTemplateContexts.locate(text, tokens, at)
}
