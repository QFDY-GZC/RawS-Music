package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP
import com.rawsmusic.core.ui.widget.player.STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArtworkReleasePolicyTest {
    @Test
    fun `ArtworkImageNode provider callback accepts only exact physical holder identity generation`() {
        assertTrue(
            shouldAcceptArtworkProviderResult(
                boundKey = "song-b",
                currentGeneration = 7L,
                callbackKey = "song-b",
                callbackGeneration = 7L,
            )
        )
        assertFalse(
            shouldAcceptArtworkProviderResult(
                boundKey = "song-c",
                currentGeneration = 8L,
                callbackKey = "song-b",
                callbackGeneration = 7L,
            )
        )
        assertFalse(
            shouldAcceptArtworkProviderResult(
                boundKey = "song-b",
                currentGeneration = 8L,
                callbackKey = "song-b",
                callbackGeneration = 7L,
            )
        )
        assertFalse(
            shouldAcceptArtworkProviderResult(
                boundKey = "",
                currentGeneration = 7L,
                callbackKey = "song-b",
                callbackGeneration = 7L,
            )
        )
    }

    @Test
    fun `stationary release commits at the ArtworkPagerMotion twenty percent boundary`() {
        assertFalse(
            shouldCommitPlaybackArtworkSwipe(
                progress = 0.199f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = 0f,
            )
        )
        assertTrue(
            shouldCommitPlaybackArtworkSwipe(
                progress = 0.20f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = 0f,
            )
        )
    }

    @Test
    fun `release velocity continuously biases the signed page position`() {
        // Next is a leftward drag: -0.12 page plus -800 px/s * 1e-4 reaches -0.20.
        assertTrue(
            shouldCommitPlaybackArtworkSwipe(
                progress = 0.12f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = -800f,
            )
        )
        // Opposing velocity subtracts intent instead of tripping a separate fling boolean.
        assertFalse(
            shouldCommitPlaybackArtworkSwipe(
                progress = 0.19f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = 1_000f,
            )
        )
    }

    @Test
    fun `Reference delays transport for the middle release band until AA settle finishes`() {
        assertFalse(shouldCommitPlaybackTransportImmediately(0.20f))
        assertFalse(shouldCommitPlaybackTransportImmediately(0.60f))
        assertTrue(shouldCommitPlaybackTransportImmediately(0.601f))
        assertTrue(shouldCommitPlaybackTransportImmediately(1f))
    }

    @Test
    fun `deferred visual settle is not the next gesture logical centre until transport commits`() {
        assertFalse(
            playbackTargetActsAsCommittedGestureCenter(
                pendingCommit = true,
                deferredTransportPending = true,
                boundSongMatchesTarget = false,
            )
        )
        assertTrue(
            playbackTargetActsAsCommittedGestureCenter(
                pendingCommit = true,
                deferredTransportPending = false,
                boundSongMatchesTarget = false,
            )
        )
        assertTrue(
            playbackTargetActsAsCommittedGestureCenter(
                pendingCommit = false,
                deferredTransportPending = false,
                boundSongMatchesTarget = true,
            )
        )
    }

    @Test
    fun `committed gesture settle survives authoritative binding lag`() {
        assertFalse(
            shouldDiscardSettledArtworkTargetAsStale(
                gestureCommitPending = true,
                deferredGestureTransportPending = false,
                manualProgrammaticPending = false,
                boundSongKey = "song-a",
                targetKey = "song-b",
            )
        )
        assertFalse(
            shouldDiscardSettledArtworkTargetAsStale(
                gestureCommitPending = true,
                deferredGestureTransportPending = true,
                manualProgrammaticPending = false,
                boundSongKey = "song-a",
                targetKey = "song-b",
            )
        )
        assertFalse(
            shouldDiscardSettledArtworkTargetAsStale(
                gestureCommitPending = false,
                deferredGestureTransportPending = false,
                manualProgrammaticPending = false,
                boundSongKey = "song-b",
                targetKey = "song-b",
            )
        )
        assertFalse(
            shouldDiscardSettledArtworkTargetAsStale(
                gestureCommitPending = false,
                deferredGestureTransportPending = false,
                manualProgrammaticPending = true,
                boundSongKey = "song-a",
                targetKey = "song-b",
            )
        )
        assertTrue(
            shouldDiscardSettledArtworkTargetAsStale(
                gestureCommitPending = false,
                deferredGestureTransportPending = false,
                manualProgrammaticPending = false,
                boundSongKey = "unrelated-song",
                targetKey = "song-b",
            )
        )
    }

    @Test
    fun `long automatic artwork dissolve requires both enabled auto crossfade and natural advance`() {
        assertTrue(
            shouldUseAutomaticCrossfadeArtwork(
                automaticCrossfadeEnabled = true,
                isNaturalQueueAdvance = true,
            )
        )
        assertFalse(
            shouldUseAutomaticCrossfadeArtwork(
                automaticCrossfadeEnabled = false,
                isNaturalQueueAdvance = true,
            )
        )
        assertFalse(
            shouldUseAutomaticCrossfadeArtwork(
                automaticCrossfadeEnabled = true,
                isNaturalQueueAdvance = false,
            )
        )
        assertFalse(
            shouldUseAutomaticCrossfadeArtwork(
                automaticCrossfadeEnabled = false,
                isNaturalQueueAdvance = false,
            )
        )
    }
    @Test
    fun `physical adjacent holder owns bind across blank published topology`() {
        assertEquals(
            PlayerArtworkDirection.Next,
            physicalAdjacentPlaybackBindingDirection(
                previousSlotKey = "song-a",
                nextSlotKey = "song-c",
                boundKey = "song-c",
                rewriteKind = null,
            ),
        )
        assertEquals(
            PlayerArtworkDirection.Previous,
            physicalAdjacentPlaybackBindingDirection(
                previousSlotKey = "song-a",
                nextSlotKey = null,
                boundKey = "song-a",
                rewriteKind = null,
            ),
        )
        assertEquals(
            null,
            physicalAdjacentPlaybackBindingDirection(
                previousSlotKey = "song-a",
                nextSlotKey = "song-c",
                boundKey = "different-song",
                rewriteKind = null,
            ),
        )
    }

    @Test
    fun `explicit rewrite always outranks physical adjacent navigation`() {
        assertEquals(
            null,
            physicalAdjacentPlaybackBindingDirection(
                previousSlotKey = "song-a",
                nextSlotKey = "song-c",
                boundKey = "song-c",
                rewriteKind = PlaybackArtworkKeyContinuity.RewriteKind.ArtworkChanged,
            ),
        )
        assertEquals(
            null,
            physicalAdjacentPlaybackBindingDirection(
                previousSlotKey = "song-a",
                nextSlotKey = "song-c",
                boundKey = "song-c",
                rewriteKind = PlaybackArtworkKeyContinuity.RewriteKind.MetadataOnly,
            ),
        )
    }

    @Test
    fun `manual queue advances are never classified as natural artwork transitions`() {
        assertTrue(
            isNaturalPlaybackArtworkAdvance(
                queueAdvanced = true,
                manualNavigationBinding = false,
                hasManualDirectionHint = false,
            )
        )
        assertFalse(
            isNaturalPlaybackArtworkAdvance(
                queueAdvanced = true,
                manualNavigationBinding = true,
                hasManualDirectionHint = false,
            )
        )
        assertFalse(
            isNaturalPlaybackArtworkAdvance(
                queueAdvanced = true,
                manualNavigationBinding = false,
                hasManualDirectionHint = true,
            )
        )
    }

    @Test
    fun `ArtworkPager holder promotion preserves the exact endpoint transform`() {
        fun assertSameTransform(
            a: ForegroundItemTransform,
            b: ForegroundItemTransform,
        ) {
            assertEquals(a.translationX, b.translationX, 0.0001f)
            assertEquals(a.scaleX, b.scaleX, 0.0001f)
            assertEquals(a.scaleY, b.scaleY, 0.0001f)
            assertEquals(a.rotationX, b.rotationX, 0.0001f)
            assertEquals(a.rotationY, b.rotationY, 0.0001f)
            assertEquals(a.rotationZ, b.rotationZ, 0.0001f)
            assertEquals(a.alpha, b.alpha, 0.0001f)
            assertEquals(a.pivotFractionX, b.pivotFractionX, 0.0001f)
            assertEquals(a.pivotFractionY, b.pivotFractionY, 0.0001f)
        }

        val extent = 1000f
        val density = 3f
        listOf(PlayerArtworkDirection.Previous, PlayerArtworkDirection.Next).forEach { direction ->
            val incomingAtCommit = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Target,
                direction = direction,
                progress = 1f,
                itemExtentPx = extent,
                density = density,
            )
            val promotedCurrent = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Current,
                direction = direction,
                progress = 0f,
                itemExtentPx = extent,
                density = density,
            )
            assertSameTransform(incomingAtCommit, promotedCurrent)

            val outgoingAtCommit = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Current,
                direction = direction,
                progress = 1f,
                itemExtentPx = extent,
                density = density,
            )
            val opposite = when (direction) {
                PlayerArtworkDirection.Next -> PlayerArtworkDirection.Previous
                PlayerArtworkDirection.Previous -> PlayerArtworkDirection.Next
            }
            val parkedOpposite = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Target,
                direction = opposite,
                progress = 0f,
                itemExtentPx = extent,
                density = density,
            )
            assertSameTransform(outgoingAtCommit, parkedOpposite)
        }
    }


    @Test
    fun `follow-up flick uses its own travel rather than inherited settle ratio`() {
        // A first page may already be visually near B. A tiny second touch with no velocity must not
        // become C merely because the old settle ratio is ~0.9.
        assertFalse(
            shouldCommitPlaybackArtworkFollowupFlick(
                gestureTravelProgress = 0.03f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = 0f,
            )
        )
        // A short but genuinely fast next flick still contributes one more page, matching ArtworkPagerMotion's
        // position+velocity decision and allowing repeated flick retargets before B fully settles.
        assertTrue(
            shouldCommitPlaybackArtworkFollowupFlick(
                gestureTravelProgress = 0.08f,
                direction = PlayerArtworkDirection.Next,
                velocityPxPerSecond = -1_600f,
            )
        )
        assertTrue(
            shouldCommitPlaybackArtworkFollowupFlick(
                gestureTravelProgress = 0.08f,
                direction = PlayerArtworkDirection.Previous,
                velocityPxPerSecond = 1_600f,
            )
        )
    }

    @Test
    fun `programmatic AA keeps the Reference artworkAnimationTimeMs clock`() {
        assertEquals(550, programmaticArtworkDurationMs(1f))
        assertEquals(550, programmaticArtworkDurationMs(0.5f))
        assertEquals(550, programmaticArtworkDurationMs(0.1f))
    }

    @Test
    fun `programmatic retarget keeps Reference base clock across pages`() {
        assertEquals(550, programmaticRetargetDurationMs(startFraction = 0.4f, destinationPages = 1f))
        assertEquals(550, programmaticRetargetDurationMs(startFraction = 0.4f, destinationPages = 2f))
        assertEquals(550, programmaticRetargetDurationMs(startFraction = 0.4f, destinationPages = 3f))
        assertTrue(
            programmaticRetargetDurationMs(
                startFraction = 0.4f,
                destinationPages = 2f,
                retainedVelocityPagesPerSecond = 2f,
            ) < 550
        )
    }

    @Test
    fun `committed settle continuation keeps one absolute page coordinate`() {
        assertEquals(
            0.90f,
            continuedArtworkAbsolutePosition(
                settledStartRatio = 0.72f,
                signedDragPosition = -0.18f,
                direction = PlayerArtworkDirection.Next,
            ),
            0.0001f,
        )
        val nextAbsolute = continuedArtworkAbsolutePosition(
            settledStartRatio = 0.72f,
            signedDragPosition = -0.40f,
            direction = PlayerArtworkDirection.Next,
        )
        assertEquals(1.12f, nextAbsolute, 0.0001f)
        assertEquals(0.12f, continuedArtworkLocalProgress(nextAbsolute), 0.0001f)

        val previousAbsolute = continuedArtworkAbsolutePosition(
            settledStartRatio = 0.65f,
            signedDragPosition = 0.50f,
            direction = PlayerArtworkDirection.Previous,
        )
        assertEquals(1.15f, previousAbsolute, 0.0001f)
        assertEquals(0.15f, continuedArtworkLocalProgress(previousAbsolute), 0.0001f)
    }

    @Test
    fun `reverse grab rebase is pixel identical before settle completes`() {
        val extent = 1000f
        val density = 3f
        listOf(0.08f, 0.37f, 0.72f, 0.94f).forEach { oldProgress ->
            val rebasedProgress = 1f - oldProgress
            val oldA = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Current,
                direction = PlayerArtworkDirection.Next,
                progress = oldProgress,
                itemExtentPx = extent,
                density = density,
            )
            val newA = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Target,
                direction = PlayerArtworkDirection.Previous,
                progress = rebasedProgress,
                itemExtentPx = extent,
                density = density,
            )
            val oldB = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Target,
                direction = PlayerArtworkDirection.Next,
                progress = oldProgress,
                itemExtentPx = extent,
                density = density,
            )
            val newB = playerArtworkForegroundTransform(
                style = PlayerArtworkAnimationStyle.PerspectiveDepth,
                role = PlayerArtworkItemRole.Current,
                direction = PlayerArtworkDirection.Previous,
                progress = rebasedProgress,
                itemExtentPx = extent,
                density = density,
            )
            listOf(oldA to newA, oldB to newB).forEach { (before, after) ->
                assertEquals(before.translationX, after.translationX, 0.0001f)
                assertEquals(before.scaleX, after.scaleX, 0.0001f)
                assertEquals(before.scaleY, after.scaleY, 0.0001f)
                assertEquals(before.rotationX, after.rotationX, 0.0001f)
                assertEquals(before.rotationY, after.rotationY, 0.0001f)
                assertEquals(before.rotationZ, after.rotationZ, 0.0001f)
                assertEquals(before.alpha, after.alpha, 0.0001f)
            }
        }
    }

    @Test
    fun `Reference direct manipulation normalizes one page to the dense AA step`() {
        assertEquals(750f, ReferenceArtworkPageStepPx(1000f), 0.0001f)
        assertEquals(0.35f, 262.5f / ReferenceArtworkPageStepPx(1000f), 0.0001f)
    }

    @Test
    fun `ArtworkPager default scale parameter produces a seventy five percent side holder`() {
        val side = playerArtworkForegroundTransform(
            style = PlayerArtworkAnimationStyle.PerspectiveDepth,
            role = PlayerArtworkItemRole.Target,
            direction = PlayerArtworkDirection.Next,
            progress = 0f,
            itemExtentPx = 1000f,
            density = 3f,
        )
        assertEquals(0.75f, side.scaleX, 0.0001f)
        assertEquals(0.75f, side.scaleY, 0.0001f)
        assertEquals(-10f, ReferenceArtworkRotationY(1f, apiLevel = 28), 0.0001f)
        assertEquals(5f, ReferenceArtworkRotationX(1f, apiLevel = 28), 0.0001f)
        assertEquals(0f, ReferenceArtworkRotationZ(1f, apiLevel = 28), 0.0001f)
        assertEquals(0f, side.alpha, 0.0001f)
        // Reference leaves ArtworkItemNode on the platform default camera distance; Compose must do the
        // same instead of writing 1280*density into RenderNode units.
        assertEquals(null, side.cameraDistance)
    }

    @Test
    fun `normal ArtworkPager holder uses w alpha not z edge holder alpha`() {
        // w(): visible = 1 - abs(distance); alpha is 0 below 0.05 visible and then
        // (visible - 0.05) / 0.95. z()'s 0.65..0.40 window is only for synthetic edge holders.
        assertEquals(0f, ReferenceNormalHolderAlpha(1f), 0.0001f)
        assertEquals(0f, ReferenceNormalHolderAlpha(0.95f), 0.0001f)
        assertEquals(0.05263158f, ReferenceNormalHolderAlpha(0.90f), 0.0001f)
        assertEquals(0.47368422f, ReferenceNormalHolderAlpha(0.50f), 0.0001f)
        assertEquals(1f, ReferenceNormalHolderAlpha(0f), 0.0001f)
    }

    @Test
    fun `ArtworkPager modern Android suppresses z rotation but keeps x y perspective`() {
        assertEquals(5f, ReferenceArtworkRotationX(1f, apiLevel = 28), 0.0001f)
        assertEquals(-10f, ReferenceArtworkRotationY(1f, apiLevel = 28), 0.0001f)
        assertEquals(0f, ReferenceArtworkRotationZ(1f, apiLevel = 28), 0.0001f)
        assertEquals(0f, ReferenceArtworkRotationX(1f, apiLevel = 27), 0.0001f)
        assertEquals(0f, ReferenceArtworkRotationY(1f, apiLevel = 27), 0.0001f)
        assertEquals(5.5f, ReferenceArtworkRotationZ(1f, apiLevel = 27), 0.0001f)
    }

    @Test
    fun `Reference player image keeps eight dp inner margin and point nine seven five scene scale`() {
        val itemSidePx = 1080f
        val density = 3f
        val insetPx = STANDARD_PLAYER_ARTWORK_CONTENT_INSET_DP * density
        val visibleSide = (itemSidePx - insetPx * 2f) * STANDARD_PLAYER_ARTWORK_CONTENT_SCALE
        assertEquals(1006.2f, visibleSide, 0.001f)
        assertEquals(36.9f, (itemSidePx - visibleSide) * 0.5f, 0.001f)
    }

    @Test
    fun `programmatic AA uses reference cubic tolerance rather than exact compose cubic`() {
        assertEquals(0f, ReferenceProgrammaticArtworkEasing(0f), 0.000001f)
        assertEquals(0.19703125f, ReferenceProgrammaticArtworkEasing(0.25f), 0.00001f)
        assertEquals(0.79411244f, ReferenceProgrammaticArtworkEasing(0.50f), 0.00001f)
        assertEquals(0.9779489f, ReferenceProgrammaticArtworkEasing(0.80f), 0.00001f)
        assertEquals(1f, ReferenceProgrammaticArtworkEasing(1f), 0.000001f)
    }

    @Test
    fun `inward carousel keeps both parked neighbours fully hidden`() {
        // The visual lane compresses +/-1 to +/-0.78, but that visual distance must never be reused
        // as the opacity distance: ArtworkPager alpha at 0.78 is ~0.179 and exposes three covers at idle.
        assertEquals(0f, inwardCarouselHolderAlpha(-1f), 0.0001f)
        assertEquals(0f, inwardCarouselHolderAlpha(1f), 0.0001f)
        assertEquals(1f, inwardCarouselHolderAlpha(0f), 0.0001f)
        assertEquals(0.47368422f, inwardCarouselHolderAlpha(0.50f), 0.0001f)

        val parkedPrevious = playerArtworkForegroundTransform(
            style = PlayerArtworkAnimationStyle.InwardCarousel,
            role = PlayerArtworkItemRole.Target,
            direction = PlayerArtworkDirection.Previous,
            progress = 0f,
            itemExtentPx = 1000f,
            density = 3f,
        )
        val parkedNext = playerArtworkForegroundTransform(
            style = PlayerArtworkAnimationStyle.InwardCarousel,
            role = PlayerArtworkItemRole.Target,
            direction = PlayerArtworkDirection.Next,
            progress = 0f,
            itemExtentPx = 1000f,
            density = 3f,
        )
        assertEquals(0f, parkedPrevious.alpha, 0.0001f)
        assertEquals(0f, parkedNext.alpha, 0.0001f)
    }

    @Test
    fun `ArtworkPager view and compose geometry share the same page translation and scale laws`() {
        assertEquals(750f, ReferenceNormalHolderTranslationXPx(1f, 1000f), 0.0001f)
        assertEquals(-750f, ReferenceNormalHolderTranslationXPx(-1f, 1000f), 0.0001f)
        assertEquals(0.75f, ReferenceNormalHolderScale(1f), 0.0001f)
        assertEquals(0.875f, ReferenceNormalHolderScale(0.5f), 0.0001f)
        assertEquals(1f, ReferenceNormalHolderScale(0f), 0.0001f)
    }

    @Test
    fun `gesture page step uses the physical AA holder extent instead of outer pointer width`() {
        val holderExtent = 900f
        val outerPointerExtent = 1080f
        assertEquals(
            ReferenceArtworkPageStepPx(holderExtent),
            resolveArtworkGesturePageStepPx(holderExtent, outerPointerExtent),
            0.0001f,
        )
        assertFalse(
            resolveArtworkGesturePageStepPx(holderExtent, outerPointerExtent) ==
                ReferenceArtworkPageStepPx(outerPointerExtent)
        )
    }

    @Test
    fun `gesture page step falls back only before the physical holder is measured`() {
        val outerPointerExtent = 1080f
        assertEquals(
            ReferenceArtworkPageStepPx(outerPointerExtent),
            resolveArtworkGesturePageStepPx(0f, outerPointerExtent),
            0.0001f,
        )
    }

}
