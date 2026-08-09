package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerArtworkHandoffMotionTest {
    private val source = floatArrayOf(28f, 760f, 64f, 64f)
    private val target = floatArrayOf(42f, 72f, 996f, 996f)

    @Test
    fun endpointsRemainExact() {
        val start = rectAt(0f)
        val end = rectAt(1f)

        assertEquals(source[0], start.left, EPSILON)
        assertEquals(source[1], start.top, EPSILON)
        assertEquals(source[2], start.width, EPSILON)
        assertEquals(source[3], start.height, EPSILON)
        assertEquals(target[0], end.left, EPSILON)
        assertEquals(target[1], end.top, EPSILON)
        assertEquals(target[2], end.width, EPSILON)
        assertEquals(target[3], end.height, EPSILON)
    }

    @Test
    fun centerPathDoesNotCollapseToStraightRectangleLerp() {
        val midpoint = rectAt(0.5f)
        val sourceCenterX = source[0] + source[2] * 0.5f
        val sourceCenterY = source[1] + source[3] * 0.5f
        val targetCenterX = target[0] + target[2] * 0.5f
        val targetCenterY = target[1] + target[3] * 0.5f
        val linearCenterX = (sourceCenterX + targetCenterX) * 0.5f
        val linearCenterY = (sourceCenterY + targetCenterY) * 0.5f
        val midpointCenterX = midpoint.left + midpoint.width * 0.5f
        val midpointCenterY = midpoint.top + midpoint.height * 0.5f

        assertTrue(midpointCenterX > linearCenterX + 20f)
        assertTrue(midpointCenterY > linearCenterY + 20f)
    }

    @Test
    fun repeatedFractionAlwaysReturnsTheSameGeometry() {
        val first = rectAt(0.37f)
        val second = rectAt(0.37f)

        assertEquals(first, second)
    }

    private fun rectAt(fraction: Float): PlayerArtworkHandoffRect = resolvePlayerArtworkHandoffRect(
        sourceLeft = source[0],
        sourceTop = source[1],
        sourceWidth = source[2],
        sourceHeight = source[3],
        targetLeft = target[0],
        targetTop = target[1],
        targetWidth = target[2],
        targetHeight = target[3],
        fraction = fraction,
    )

    private companion object {
        const val EPSILON = 0.001f
    }
}
