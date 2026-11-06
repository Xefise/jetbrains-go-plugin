package io.github.xefise.golsp.debug

import com.redhat.devtools.lsp4ij.dap.configurations.extractors.NetworkAddressExtractor
import io.github.xefise.golsp.debug.model.DelveLaunchBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DelveReadyPatternTest {
    private val extractor = NetworkAddressExtractor(DelveLaunchBuilder.READY_PATTERN)

    @Test
    fun `ready line printed by dlv dap is recognised`() {
        // Captured from `dlv dap --listen=127.0.0.1:0`, Delve 1.27.2.
        val result = extractor.extract("DAP server listening at: 127.0.0.1:63144\n")
        assertEquals(true, result.matches())
        assertEquals("127.0.0.1", result.address())
        assertEquals("63144", result.port())
    }

    @Test
    fun `unrelated dlv output is not taken as ready`() {
        assertFalse(extractor.extract("Warning: build flags ignored with dap").matches())
    }
}
