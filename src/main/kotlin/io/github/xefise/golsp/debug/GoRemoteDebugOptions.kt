package io.github.xefise.golsp.debug

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.BaseState
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

// Tag and attribute names are persisted in workspace.xml; never rename them.
@Tag("mapping")
class GoSubstitutePathEntry : BaseState() {
    @get:Attribute("local")
    var local: String? by string("")

    @get:Attribute("remote")
    var remote: String? by string("")
}

// Option keys are persisted in workspace.xml; never rename them. New options need a default that keeps old behaviour.
class GoRemoteDebugOptions : RunConfigurationOptions() {
    private val hostProperty = string(DEFAULT_HOST).provideDelegate(this, "host")
    private val portProperty = property(DEFAULT_PORT).provideDelegate(this, "port")
    private val substitutePathProperty = list<GoSubstitutePathEntry>().provideDelegate(this, "substitutePath")

    var host: String
        get() = hostProperty.getValue(this) ?: DEFAULT_HOST
        set(value) = hostProperty.setValue(this, value)

    var port: Int
        get() = portProperty.getValue(this)
        set(value) = portProperty.setValue(this, value)

    @get:XCollection(style = XCollection.Style.v2)
    var substitutePath: MutableList<GoSubstitutePathEntry>
        get() = substitutePathProperty.getValue(this)
        set(value) = substitutePathProperty.setValue(this, value)

    companion object {
        const val DEFAULT_HOST: String = "localhost"
        const val DEFAULT_PORT: Int = 2345
    }
}
