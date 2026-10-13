package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionReturnSourceTest {
    private val row = SharedCoverSnapshot(
        sceneId = "ALBUMS", elementId = "cover:album:1",
        boundsInWindow = Rect(0f, 0f, 80f, 80f), coverKey = "art", radiusDp = 0f,
    )
    private val header = row.copy(sceneId = "ALBUM_DETAIL")

    @Test
    fun preparedReturnKeepsItsSourceWhenLaterLayoutReportsOffscreen() {
        val registry = SharedCoverRegistry()
        registry.prepareTransition("forward", row, header)
        registry.registerPhysicalLayoutEndpoint(header.sceneId, header.elementId, this, header)
        assertTrue(registry.captureLiveCollectionReturnSource(header.sceneId, row.sceneId))
        registry.prepareTransition("ALBUM_DETAIL->ALBUMS", header, row)
        registry.registerPhysicalLayoutEndpoint(
            header.sceneId, header.elementId, this, header.copy(sharedEligible = false),
        )
        assertTrue(registry.captureLiveCollectionReturnSource(header.sceneId, row.sceneId))
        assertTrue(registry.hasSharedCollectionReturnSource(header.sceneId, row.sceneId))
        registry.clearFrozen()
        assertFalse(registry.captureLiveCollectionReturnSource(header.sceneId, row.sceneId))
    }
}
