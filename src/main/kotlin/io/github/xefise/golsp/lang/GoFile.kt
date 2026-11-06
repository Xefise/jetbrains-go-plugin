package io.github.xefise.golsp.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.PsiTreeUtil

class GoFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoLanguage) {
    override fun getFileType(): FileType = GoFileType.INSTANCE

    override fun toString(): String = "Go File"

    val packageClause: GoPackageClause? get() = PsiTreeUtil.getChildOfType(this, GoPackageClause::class.java)

    val packageName: String? get() = packageClause?.packageName

    val functionHeaders: List<GoFunctionHeader>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, GoFunctionHeader::class.java)

    val subtestCalls: List<GoSubtestCall>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, GoSubtestCall::class.java)

    fun isTestFile(): Boolean = name.endsWith("_test.go")
}
