package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricZoomSpecTest {
    @Test
    fun `pinch ratio maps directly to adjacent scene progress`() {
        assertEquals(109f, LyricZoomSpec.rawScaleFromPinchRatio(100, 1.30f), 0.001f)
        assertEquals(92.5f, LyricZoomSpec.rawScaleFromPinchRatio(100, 0.70f), 0.001f)
    }

    @Test
    fun `raw pinch is segmented into adjacent five percent endpoint layouts`() {
        assertEquals(LyricZoomSegment(100, 105, 0.4f), LyricZoomSpec.segmentForRawScale(100, 102f))
        assertEquals(LyricZoomSegment(105, 110, 0f), LyricZoomSpec.segmentForRawScale(100, 105f))
        assertEquals(LyricZoomSegment(105, 110, 0.8f), LyricZoomSpec.segmentForRawScale(100, 109f))
        assertEquals(LyricZoomSegment(100, 95, 0.4f), LyricZoomSpec.segmentForRawScale(100, 98f))
        assertEquals(LyricZoomSegment(95, 90, 0f), LyricZoomSpec.segmentForRawScale(100, 95f))
        assertEquals(LyricZoomSegment(125, 130, 1f), LyricZoomSpec.segmentForRawScale(100, 130f))
        assertEquals(LyricZoomSegment(80, 75, 1f), LyricZoomSpec.segmentForRawScale(100, 75f))
    }

    @Test
    fun `shared holder keeps one visual owner while single sided rows enter and exit`() {
        val source = LyricZoomRowBounds(300f, 380f)
        val target = LyricZoomRowBounds(285f, 410f)
        val sharedSource = LyricZoomSpec.endpointTransform(
            sourceBounds = source,
            targetBounds = target,
            endpointBounds = source,
            progress = 0.4f,
            endpoint = LyricZoomEndpoint.SOURCE,
            ownership = LyricZoomRowOwnership.SHARED,
            fontScaleRatio = 1.02f,
        )
        val sharedTarget = LyricZoomSpec.endpointTransform(
            sourceBounds = source,
            targetBounds = target,
            endpointBounds = target,
            progress = 0.4f,
            endpoint = LyricZoomEndpoint.TARGET,
            ownership = LyricZoomRowOwnership.SHARED,
            fontScaleRatio = 0.98f,
        )
        assertEquals(1f, sharedSource.alpha, 0.001f)
        assertEquals(0f, sharedTarget.alpha, 0.001f)

        val sourceOnly = LyricZoomSpec.endpointTransform(
            source, target, source, 0.75f, LyricZoomEndpoint.SOURCE,
            LyricZoomRowOwnership.SOURCE_ONLY, 1f,
        )
        val targetOnly = LyricZoomSpec.endpointTransform(
            source, target, target, 0.75f, LyricZoomEndpoint.TARGET,
            LyricZoomRowOwnership.TARGET_ONLY, 1f,
        )
        assertEquals(0.25f, sourceOnly.alpha, 0.001f)
        assertEquals(0.75f, targetOnly.alpha, 0.001f)
    }

    @Test
    fun `release keeps completed steps and uses virtuallist thirty percent threshold`() {
        assertEquals(100, LyricZoomSpec.releaseTargetScale(100, 101.4f))
        assertEquals(100, LyricZoomSpec.releaseTargetScale(100, 101.5f))
        assertEquals(105, LyricZoomSpec.releaseTargetScale(100, 101.5001f))
        assertEquals(110, LyricZoomSpec.releaseTargetScale(100, 109.0f))
        assertEquals(100, LyricZoomSpec.releaseTargetScale(100, 98.5f))
        assertEquals(95, LyricZoomSpec.releaseTargetScale(100, 98.4999f))
        assertEquals(90, LyricZoomSpec.releaseTargetScale(100, 91.0f))
    }

    @Test
    fun `release zoom clamps to lyric font preference range`() {
        assertEquals(130, LyricZoomSpec.releaseTargetScale(125, 170f))
        assertEquals(75, LyricZoomSpec.releaseTargetScale(80, 20f))
    }

    @Test
    fun `hidden interlude keeps spacing only while active interlude contributes body height`() {
        assertEquals(40f, LyricZoomSpec.displayGapPx(20f, 2, 0, 48f), 0.001f)
        assertEquals(88f, LyricZoomSpec.displayGapPx(20f, 2, 1, 48f), 0.001f)
    }

    @Test
    fun `active row remains fixed while following rows expand around it`() {
        val anchor = LyricZoomRowBounds(200f, 260f)
        val following = LyricZoomRowBounds(300f, 360f)
        val anchorFrame = LyricZoomSpec.rowTransform(
            bounds = anchor,
            anchorCenterYPx = anchor.centerYPx,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1.20f,
        )
        val followingFrame = LyricZoomSpec.rowTransform(
            bounds = following,
            anchorCenterYPx = anchor.centerYPx,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1.20f,
        )

        assertEquals(0f, anchorFrame.translationYPx, 0.001f)
        assertTrue(followingFrame.translationYPx > 0f)
        assertEquals(1.20f, followingFrame.scale, 0.001f)
    }

    @Test
    fun `source row fades only after it loses viewport area`() {
        val row = LyricZoomRowBounds(720f, 780f)
        val source = LyricZoomSpec.rowTransform(
            bounds = row,
            anchorCenterYPx = 220f,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1f,
            sourceOwned = true,
        )
        val exiting = LyricZoomSpec.rowTransform(
            bounds = row,
            anchorCenterYPx = 220f,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1.25f,
            sourceOwned = true,
        )
        assertEquals(1f, source.alpha, 0.001f)
        assertTrue(exiting.alpha < 1f)
    }

    @Test
    fun `target only edge row fades in as shrink brings it into viewport`() {
        val row = LyricZoomRowBounds(820f, 880f)
        val hidden = LyricZoomSpec.rowTransform(
            bounds = row,
            anchorCenterYPx = 220f,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1f,
            sourceOwned = false,
        )
        val entering = LyricZoomSpec.rowTransform(
            bounds = row,
            anchorCenterYPx = 220f,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 0.85f,
            sourceOwned = false,
        )
        assertEquals(0f, hidden.alpha, 0.001f)
        assertTrue(entering.alpha > 0f)
    }
    @Test
    fun `release settle lands on measured target row bounds`() {
        val source = LyricZoomRowBounds(300f, 380f)
        val start = LyricZoomSpec.rowTransform(
            bounds = source,
            anchorCenterYPx = 240f,
            viewportTopPx = 0f,
            viewportBottomPx = 900f,
            ratio = 1.12f,
            sourceOwned = true,
        )
        val target = LyricZoomRowBounds(330f, 450f)
        val finished = LyricZoomSpec.interpolateToTargetBounds(
            sourceBounds = source,
            startTransform = start,
            targetBounds = target,
            viewportTopPx = 0f,
            viewportBottomPx = 900f,
            fraction = 1f,
        )

        assertEquals(target.centerYPx - source.centerYPx, finished.translationYPx, 0.001f)
        assertEquals(target.heightPx / source.heightPx, finished.scale, 0.001f)
        assertEquals(1f, finished.alpha, 0.001f)
    }

    @Test
    fun `gesture visual ratio respects the real lyric font clamp`() {
        assertEquals(24f / 28f, LyricZoomSpec.visualFontRatio(28, 24..40, 100f, 75f), 0.001f)
        assertEquals(36.4f / 28f, LyricZoomSpec.visualFontRatio(28, 24..40, 100f, 130f), 0.001f)
    }

    @Test
    fun `partially clipped target row still hands off at full item alpha`() {
        val source = LyricZoomRowBounds(620f, 700f)
        val start = LyricZoomSpec.rowTransform(
            bounds = source,
            anchorCenterYPx = 260f,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            ratio = 1.10f,
            sourceOwned = true,
        )
        val target = LyricZoomRowBounds(760f, 840f)
        val finished = LyricZoomSpec.interpolateToTargetBounds(
            sourceBounds = source,
            startTransform = start,
            targetBounds = target,
            viewportTopPx = 0f,
            viewportBottomPx = 800f,
            fraction = 1f,
        )

        assertEquals(1f, finished.alpha, 0.001f)
    }

    @Test
    fun `target layout gap mirrors lazy column spacing and active interlude height`() {
        assertEquals(20f, LyricZoomSpec.displayGapPx(20f, 1, 0, 48f), 0.001f)
        assertEquals(88f, LyricZoomSpec.displayGapPx(20f, 2, 1, 48f), 0.001f)
    }

    @Test
    fun `edge response follows Reference square root damping`() {
        assertEquals(0.0f, LyricZoomSpec.ReferenceEdgeResistance(0f), 0.001f)
        assertTrue(LyricZoomSpec.ReferenceEdgeResistance(1f) > 0f)
        assertTrue(LyricZoomSpec.ReferenceEdgeResistance(-1f) < 0f)
        assertTrue(
            LyricZoomSpec.rawScalePercent(100, 2f) < LyricZoomSpec.MAX_SCALE +
                LyricZoomSpec.MAX_EDGE_OVERSHOOT_PERCENT
        )
    }

}
