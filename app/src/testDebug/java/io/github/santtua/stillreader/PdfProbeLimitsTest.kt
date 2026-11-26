package io.github.santtua.stillreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfProbeLimitsTest {
    @Test
    fun acceptsOrdinaryPageDimensions() {
        assertTrue(PdfProbe.acceptsPageSize(612, 792))
    }

    @Test
    fun rejectsDegenerateAndOversizedPages() {
        assertFalse(PdfProbe.acceptsPageSize(0, 100))
        assertFalse(PdfProbe.acceptsPageSize(100, -1))
        assertFalse(PdfProbe.acceptsPageSize(100, Int.MAX_VALUE))
        assertFalse(PdfProbe.acceptsPageSize(Int.MAX_VALUE, 100))
    }
}
