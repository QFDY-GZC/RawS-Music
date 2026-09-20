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
    fun centerPathMatchesReferenceSideLeadCurve() {
        val midpoint = rectAt(0.5f)
        val sourceCenterX = source[0] + source[2] * 0.5f
        val sourceCenterY = source[1] + source[3] * 0.5f
        val targetCenterX = target[0] + target[2] * 0.5f
        val targetCenterY = target[1] + target[3] * 0.5f
        val midpointCenterX = midpoint.left + midpoint.width * 0.5f
        val midpointCenterY = midpoint.top + midpoint.height * 0.5f
        val xProgress = (midpointCenterX - sourceCenterX) / (targetCenterX - sourceCenterX)
        val yProgress = (midpointCenterY - sourceCenterY) / (targetCenterY - sourceCenterY)

        // Fitted from the supplied recording after normalizing by artwork-size progress:
        // X reaches about 72.46% while Y reaches about 43.7% at half-size.
        assertEquals(0.724625f, xProgress, 0.002f)
        assertEquals(0.437f, yProgress, 0.002f)
        assertTrue(xProgress > yProgress + 0.25f)
    }

    @Test
    fun artworkSizeTracksPhysicalSheetExpansionDirectly() {
        val nearCollapsed = rectAt(0.10f)
        val geometricProgress =
            (nearCollapsed.width - source[2]) / (target[2] - source[2])

        assertEquals(0.10f, geometricProgress, EPSILON)
        assertEquals(0.90f, resolvePlayerArtworkHandoffFraction(0.90f), EPSILON)
    }


    @Test
    fun playerSurfaceOwnsBackdropAndContentRevealGeometry() {
        val start = resolvePlayerSurfaceHandoffBounds(
            sourceLeft = 24f,
            sourceTop = 900f,
            sourceRight = 1056f,
            sourceBottom = 980f,
            sourceCornerRadius = 40f,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            fraction = 0f,
        )
        val midpoint = resolvePlayerSurfaceHandoffBounds(
            sourceLeft = 24f,
            sourceTop = 900f,
            sourceRight = 1056f,
            sourceBottom = 980f,
            sourceCornerRadius = 40f,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            fraction = 0.5f,
        )
        val end = resolvePlayerSurfaceHandoffBounds(
            sourceLeft = 24f,
            sourceTop = 900f,
            sourceRight = 1056f,
            sourceBottom = 980f,
            sourceCornerRadius = 40f,
            viewportWidth = 1080f,
            viewportHeight = 2340f,
            fraction = 1f,
        )

        assertEquals(PlayerSurfaceHandoffBounds(24f, 900f, 1056f, 980f, 40f), start)
        assertEquals(12f, midpoint.left, EPSILON)
        assertEquals(450f, midpoint.top, EPSILON)
        assertEquals(1068f, midpoint.right, EPSILON)
        assertEquals(1660f, midpoint.bottom, EPSILON)
        assertEquals(20f, midpoint.cornerRadius, EPSILON)
        assertEquals(PlayerSurfaceHandoffBounds(0f, 0f, 1080f, 2340f, 0f), end)
    }


    @Test
    fun playerContentKeepsVisibleTravelInsideSharedSurface() {
        val collapsed = resolvePlayerSurfaceContentOffsetY(900f, 0f)
        val midpoint = resolvePlayerSurfaceContentOffsetY(900f, 0.5f)
        val expanded = resolvePlayerSurfaceContentOffsetY(900f, 1f)

        assertEquals(603f, collapsed, EPSILON)
        assertEquals(301.5f, midpoint, EPSILON)
        assertEquals(0f, expanded, EPSILON)
        assertTrue(collapsed > 48f)
    }

    @Test
    fun repeatedFractionAlwaysReturnsTheSameGeometry() {
        val first = rectAt(0.37f)
        val second = rectAt(0.37f)

        assertEquals(first, second)
    }


    @Test
    fun playerVisibleArtworkEndpointUsesTheSameInsetAndLocalScaleAsTheRealCard() {
        val rect = resolvePlayerArtworkContentRect(
            outerLeft = 0f,
            outerTop = 72f,
            outerWidth = 1080f,
            outerHeight = 1080f,
            contentInsetPx = 24f,
            contentScale = 0.975f,
        )

        // inner=1032px, visible=1006.2px; centered inside the 24px inset.
        assertEquals(36.9f, rect.left, 0.001f)
        assertEquals(108.9f, rect.top, 0.001f)
        assertEquals(1006.2f, rect.width, 0.001f)
        assertEquals(1006.2f, rect.height, 0.001f)
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

    @Test
    fun keepAspectRectUsesFinalVisibleBitmapBounds() {
        val envelope = PlayerArtworkHandoffRect(10f, 20f, 300f, 300f)
        assertEquals(
            PlayerArtworkHandoffRect(10f, 70f, 300f, 200f),
            resolvePlayerArtworkAspectRect(envelope, 1200, 800),
        )
        assertEquals(
            PlayerArtworkHandoffRect(60f, 20f, 200f, 300f),
            resolvePlayerArtworkAspectRect(envelope, 800, 1200),
        )
    }
    @Test
    fun pausedHolderScaleKeepsArtworkCenteredAndPublishesScaledEndpoint() {
        val base = PlayerArtworkHandoffRect(40f, 100f, 1000f, 800f)
        assertEquals(base, scalePlayerArtworkHandoffRectAroundCenter(base, 1f))
        assertEquals(
            PlayerArtworkHandoffRect(70f, 124f, 940f, 752f),
            scalePlayerArtworkHandoffRectAroundCenter(base, 0.94f),
        )
    }

}
