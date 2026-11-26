package io.github.santtua.stillreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfRenderProtocolTest {
    @Test
    fun ordinaryPagesKeepTheirAspectRatio() {
        assertEquals(RasterSize(612, 792), PdfRenderProtocol.rasterSize(612, 792, 612))
    }

    @Test
    fun hugeAndThinPagesStayInsideTheAllocationLimits() {
        for ((width, height) in listOf(200_000 to 200_000, 1 to 200_000, 200_000 to 1)) {
            val size = PdfRenderProtocol.rasterSize(width, height, PdfRenderProtocol.MAX_EDGE)
            assertNotNull(PdfRenderProtocol.byteCount(size.width, size.height))
        }
        assertNull(PdfRenderProtocol.byteCount(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(PdfRenderProtocol.byteCount(2048, 2048))
    }
}
