package io.github.xefise.golsp.toolchain

import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.xefise.golsp.toolchain.model.GoTool
import io.github.xefise.golsp.toolchain.model.LookupEnvironment
import io.github.xefise.golsp.toolchain.model.ToolLookup
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** File-system lookup of Go tools; never starts a process and never waits for the shell environment. */
object GoToolLocator {
    fun find(tool: GoTool): String? = find(tool, environment())

    fun find(tool: GoTool, env: LookupEnvironment): String? = ToolLookup.find(tool, env, ::isExecutableFile)

    fun environment(): LookupEnvironment = lookupEnvironment(currentEnvironmentMap())

    fun lookupEnvironment(env: Map<String, String>): LookupEnvironment {
        val home = System.getProperty("user.home")
        return LookupEnvironment(
            path = env["PATH"],
            home = home,
            goPath = env["GOPATH"],
            goRoot = env["GOROOT"],
            isWindows = SystemInfo.isWindows,
            goBinDirs = listOfNotNull(env["GOBIN"]?.takeIf { it.isNotBlank() }),
            sdkDirNames = lazy { home?.let { File(it, ToolLookup.SDK_DIR).list()?.toList() }.orEmpty() },
        )
    }

    // EnvironmentUtil carries the login-shell env (right PATH even when the IDE starts from the Dock), but
    // getEnvironmentMap() blocks until it is loaded and has no public readiness check, so it is only read off-EDT.
    @Volatile
    private var shellEnvironment: Map<String, String>? = null
    private val shellLoadStarted = AtomicBoolean()

    /** Login-shell env once loaded, otherwise the raw process env while loading starts in the background. */
    fun currentEnvironmentMap(): Map<String, String> {
        shellEnvironment?.let { return it }
        if (shellLoadStarted.compareAndSet(false, true)) AppExecutorUtil.getAppExecutorService().execute { loadShellEnvironment() }
        return System.getenv()
    }

    /** Blocks until the login-shell env is loaded; background threads only. */
    fun loadShellEnvironment(): Map<String, String> =
        shellEnvironment ?: EnvironmentUtil.getEnvironmentMap().also { shellEnvironment = it }

    fun isExecutableFile(path: String): Boolean = File(path).let { it.isFile && it.canExecute() }
}
