package io.github.xefise.golsp.execution

import com.intellij.execution.ExecutionException
import com.intellij.util.EnvironmentUtil
import io.github.xefise.golsp.execution.model.DotEnvParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

object GoEnvFileLoader {
    /** Reads at launch time, so edits to the file apply to the next run without touching the configuration. */
    @Throws(ExecutionException::class)
    fun load(path: Path): Map<String, String> {
        val text = try {
            // Lossy decoding, like vscode-go: a stray byte must not make the whole file unreadable.
            String(Files.readAllBytes(path), Charsets.UTF_8)
        } catch (e: NoSuchFileException) {
            throw ExecutionException("Env file not found: $path", e)
        } catch (e: IOException) {
            throw ExecutionException("Cannot read env file $path: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return DotEnvParser.parse(text, EnvironmentUtil.getEnvironmentMap())
    }
}
