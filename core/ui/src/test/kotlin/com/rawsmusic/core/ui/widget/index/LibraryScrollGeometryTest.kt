package com.rawsmusic.core.ui.widget.index

import org.junit.Assert.*
import org.junit.Test

class LibraryScrollGeometryTest {
    @Test fun normalViewportKeepsReferenceMarginsAndThumbSize() {
        val geometry = libraryScrollGeometry(800f, 1f)
        assertEquals(110f, geometry.thumbTop(0f, 1000f), 0.01f)
        assertEquals(626f, geometry.thumbTop(1000f, 1000f), 0.01f)
        assertEquals(64f, geometry.thumbHeight, 0.01f)
    }
    @Test fun dragMappingUsesThumbTravelNotEntireScreen() {
        val geometry = libraryScrollGeometry(800f, 1f)
        assertEquals(500f, geometry.scrollForThumb(368f, 1000f), 0.01f)
        assertEquals(1000f, geometry.scrollForThumb(900f, 1000f), 0.01f)
        assertEquals(0f, geometry.scrollForThumb(-40f, 1000f), 0.01f)
    }
    @Test fun shortViewportRetainsUsableTravelAndStaysInsideBounds() {
        val geometry = libraryScrollGeometry(180f, 1f)
        assertTrue(geometry.travel >= 64f)
        assertTrue(geometry.top >= 0f)
        assertTrue(geometry.thumbTop(100f, 100f) + geometry.thumbHeight <= 180f)
    }
    @Test fun emptyScrollAndZeroSizeNeverProduceInvalidCoordinates() {
        val geometry = libraryScrollGeometry(0f, 3f)
        assertEquals(0f, geometry.scrollForThumb(20f, 0f), 0f)
        assertEquals(0f, geometry.thumbTop(20f, 0f), 0f)
    }
}
