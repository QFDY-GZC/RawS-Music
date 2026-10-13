package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CollectionRectMotionTest {
    @Test fun gridDetailStartsAtSelectedRectangleAndEndsAtIdentity() {
        val motion = collectionDetailRectMotion(Rect(20f, 300f, 260f, 580f), 1000f, 2000f)
        assertEquals(0.26f, motion.endpointScale, 0.0001f)
        assertEquals(20f, motion.offsetX(1f), 0.001f)
        assertEquals(300f, motion.offsetY(1f), 0.001f)
        assertEquals(1f, motion.scale(0f), 0.001f)
        assertEquals(0f, motion.offsetY(0f), 0.001f)
    }
    @Test fun gridSourceExpansionIsNotClampedToGenericPivotRange() {
        val motion = collectionGridRectMotion(Rect(20f, 300f, 260f, 580f), 1000f, 2000f)
        assertTrue(motion.endpointScale > 1.5f)
        assertEquals(-300f * motion.endpointScale, motion.offsetY(1f), 1f)
    }
    @Test fun reversingProgressRestoresTheSameGeometryWithoutAccumulatingOffsets() {
        var amount = 0.5f
        val transform = RetainedSceneItemTransform(500f, 1000f, { 1f })
            .withCollectionRectMotion(collectionDetailRectMotion(Rect(20f, 300f, 260f, 580f), 1000f, 2000f)) { amount }
        assertTrue(transform.rectangleMapped)
        assertEquals(0.63f, transform.scaleProvider(), 0.0001f)
        assertEquals(150f, transform.translationYProvider(), 0.001f)
        amount = 0f
        assertEquals(1f, transform.scaleProvider(), 0.001f)
        assertEquals(0f, transform.translationYProvider(), 0.001f)
        amount = 0.5f
        assertEquals(150f, transform.translationYProvider(), 0.001f)
    }
}
