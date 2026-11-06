package io.github.xefise.golsp.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

class FileLengthTest {
    @Test
    fun `source files stay within the length limit`() {
        val files = SourceScanner.scan(*SourceScanner.SOURCE_ROOTS.toTypedArray())
        assertTrue("no sources found — wrong project dir?", files.isNotEmpty())
        val lengths = files
            .associate { it.path to it.text.lines().size - if (it.text.endsWith("\n")) 1 else 0 }
        lengths.filterValues { it > SOFT_LIMIT }.forEach { (path, lines) ->
            println("FileLengthTest: $path has $lines lines (target <= $SOFT_LIMIT)")
        }
        val tooLong = lengths.filterValues { it > HARD_LIMIT }
        assertTrue("Files over $HARD_LIMIT lines: $tooLong", tooLong.isEmpty())
    }

    private companion object {
        const val SOFT_LIMIT = 400
        const val HARD_LIMIT = 1000
    }
}
