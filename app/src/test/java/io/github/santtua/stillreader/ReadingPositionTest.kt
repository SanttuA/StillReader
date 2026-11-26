package io.github.santtua.stillreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionTest {
    @Test
    fun clampsStoredPositionsToTheCurrentDocument() {
        val position = ReadingPosition(50, -1, 0.4f, 4.9f, -0.3f)
        assertEquals(ReadingPosition(2, 0, 0.4f, 4f, 0f), position.bounded(3))
    }

    @Test
    fun replacesNonFinitePreferencesAndBoundsProviderTitles() {
        val position = ReadingPosition(offset = Float.NaN, zoom = Float.POSITIVE_INFINITY, pan = Float.NaN)
        assertEquals(ReadingPosition(), position.bounded(3))
        assertEquals("A title", cleanBookTitle(" A\u202E title\u0000 "))
        assertEquals(160, cleanBookTitle("a".repeat(900))?.length)
        assertNull(cleanBookTitle(" \n\u202E "))
    }
}
