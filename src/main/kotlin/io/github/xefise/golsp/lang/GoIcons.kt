package io.github.xefise.golsp.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon
import javax.swing.Icon

object GoIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/go.svg", GoIcons::class.java)

    @JvmField
    val TEST_FILE: Icon = LayeredIcon.layeredIcon(
        arrayOf(IconLoader.getIcon("/icons/goTestBase.svg", GoIcons::class.java), AllIcons.Nodes.JunitTestMark),
    )

    @JvmField
    val MODULE_FILE: Icon = IconLoader.getIcon("/icons/goMod.svg", GoIcons::class.java)
}
