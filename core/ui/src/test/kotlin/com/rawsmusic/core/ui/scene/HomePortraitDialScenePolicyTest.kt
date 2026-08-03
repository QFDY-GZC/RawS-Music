package com.rawsmusic.core.ui.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePortraitDialScenePolicyTest {
    private val sourceLeft = 392f
    private val sourceTop = 510f
    private val sourceSide = 296f
    private val targetLeft = 216f
    private val targetTop = 820f
    private val targetSide = 648f

    private fun frame(progress: Float): HomePortraitDialSceneFrame =
        resolveHomePortraitDialSceneFrame(
            sourceLeftPx = sourceLeft,
            sourceTopPx = sourceTop,
            sourceWidthPx = sourceSide,
            sourceHeightPx = sourceSide,
            targetLeftPx = targetLeft,
            targetTopPx = targetTop,
            targetWidthPx = targetSide,
            targetHeightPx = targetSide,
            sourceCornerRadiusDp = 18f,
            targetCornerRadiusDp = 24f,
            progress = progress,
        )

    @Test
    fun closedFrameStartsAtMeasuredHomeHolder() {
        val frame = frame(0f)

        assertEquals(sourceLeft, frame.sharedLeftPx)
        assertEquals(sourceTop, frame.sharedTopPx)
        assertEquals(sourceSide, frame.sharedWidthPx)
        assertEquals(sourceSide, frame.sharedHeightPx)
        assertEquals(18f, frame.sharedCornerRadiusDp)
        assertEquals(1f, frame.homeForegroundAlpha)
        assertEquals(0f, frame.fullscreenContentAlpha)
        assertEquals(0.62f, frame.fullscreenContentScale)
    }

    @Test
    fun openFrameEndsAtMeasuredFullscreenHolder() {
        val frame = frame(1f)

        assertEquals(targetLeft, frame.sharedLeftPx)
        assertEquals(targetTop, frame.sharedTopPx)
        assertEquals(targetSide, frame.sharedWidthPx)
        assertEquals(targetSide, frame.sharedHeightPx)
        assertEquals(24f, frame.sharedCornerRadiusDp)
        assertEquals(0f, frame.homeForegroundAlpha)
        assertEquals(1f, frame.fullscreenContentAlpha)
        assertEquals(1f, frame.fullscreenContentScale)
        assertEquals(1f, frame.targetLaneRevealProgress)
    }

    @Test
    fun middleFrameActuallyTranslatesAndScalesSharedArtwork() {
        val frame = frame(0.5f)

        assertTrue(frame.sharedTopPx in sourceTop..targetTop)
        assertTrue(frame.sharedLeftPx in targetLeft..sourceLeft)
        assertTrue(frame.sharedWidthPx in sourceSide..targetSide)
        assertTrue(frame.homeForegroundScale < 1f)
        assertTrue(frame.sharedCornerRadiusDp in 18f..24f)
        assertTrue(frame.fullscreenContentScale in 0.62f..1f)
        assertTrue(frame.targetLaneRevealProgress in 0f..1f)
    }

    @Test
    fun horizontallyAlignedMeasuredEndpointsNeverCreateXDrift() {
        val sourceWidth = 296f
        val targetWidth = 648f
        val sharedCenterX = 540f
        val alignedSourceLeft = sharedCenterX - sourceWidth * 0.5f
        val alignedTargetLeft = sharedCenterX - targetWidth * 0.5f

        listOf(0f, 0.15f, 0.35f, 0.5f, 0.75f, 1f).forEach { progress ->
            val frame = resolveHomePortraitDialSceneFrame(
                sourceLeftPx = alignedSourceLeft,
                sourceTopPx = sourceTop,
                sourceWidthPx = sourceWidth,
                sourceHeightPx = sourceWidth,
                targetLeftPx = alignedTargetLeft,
                targetTopPx = targetTop,
                targetWidthPx = targetWidth,
                targetHeightPx = targetWidth,
                sourceCornerRadiusDp = 18f,
                targetCornerRadiusDp = 24f,
                progress = progress,
            )

            assertEquals(
                sharedCenterX,
                frame.sharedLeftPx + frame.sharedWidthPx * 0.5f,
                0.001f,
            )
        }
    }

    @Test
    fun returnUsesTheExactSameMeasuredRectangleTrackInReverse() {
        val progressSamples = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val forward = progressSamples.map(::frame)
        val reverse = progressSamples.reversed().map(::frame)

        forward.zip(reverse.reversed()).forEach { (opening, closing) ->
            assertEquals(opening.sharedLeftPx, closing.sharedLeftPx, 0.001f)
            assertEquals(opening.sharedTopPx, closing.sharedTopPx, 0.001f)
            assertEquals(opening.sharedWidthPx, closing.sharedWidthPx, 0.001f)
            assertEquals(opening.sharedHeightPx, closing.sharedHeightPx, 0.001f)
        }
    }

    @Test
    fun returnReflectionStaysHiddenUntilSceneProgressDropsBelowFortyPercent() {
        assertEquals(0f, resolveHomePortraitDialReturnReflectionAlpha(1f, true))
        assertEquals(0f, resolveHomePortraitDialReturnReflectionAlpha(0.40f, true))
        assertEquals(0f, resolveHomePortraitDialReturnReflectionAlpha(0.20f, false))
        val midAlpha = resolveHomePortraitDialReturnReflectionAlpha(0.20f, true)
        assertTrue(midAlpha > 0f)
        assertTrue(midAlpha < 1f)
        assertEquals(1f, resolveHomePortraitDialReturnReflectionAlpha(0f, true))
    }

}
