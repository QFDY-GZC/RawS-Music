package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RepeatOneManualNavigationTest {
    private fun harness() = PlayerQueueControlCoordinatorTest.Harness().apply {
        playMode = PlayMode.REPEAT_ONE
        queue = queue.copy(currentIndex = 1, repeatMode = RepeatMode.ONE)
        current = b
    }

    @Test fun manualButtonsMoveToRealAdjacentTracksWithoutChangingRepeatMode() {
        val h = harness()
        assertEquals(h.c, h.coordinator.next())
        assertEquals("manual_next:c", h.switches.last())
        assertEquals(h.b, h.coordinator.previous())
        assertEquals("manual_previous:b", h.switches.last())
        assertEquals(PlayMode.REPEAT_ONE, h.playMode)
    }

    @Test fun consecutiveArtworkSwipesAndReverseUseTheSameRealQueue() {
        val h = harness()
        assertEquals(h.c, h.coordinator.nextFromArtworkGesture())
        assertEquals(h.a, h.coordinator.nextFromArtworkGesture())
        assertEquals(h.c, h.coordinator.previousFromArtworkGesture())
        assertEquals(listOf("artwork_gesture_next:c", "artwork_gesture_next:a",
            "artwork_gesture_previous:c"), h.switches)
        assertEquals(0, h.updateQueueCalls)
    }

    @Test fun previewsMatchManualTransportIncludingCarouselLanes() {
        val h = harness()
        assertEquals(h.c, h.coordinator.previewNextSong())
        assertEquals(h.a, h.coordinator.previewPreviousSong())
        assertEquals(h.c, h.coordinator.previewRelativeSong(h.queue, 1))
        assertEquals(h.a, h.coordinator.previewRelativeSong(h.queue, -1))
    }

    @Test fun naturalCompletionStillRepeatsCurrentSong() {
        val h = harness()
        assertEquals(h.b, h.coordinator.automaticAdvance())
        assertEquals("automatic:b", h.switches.last())
        assertFalse(h.coordinator.hasAutomaticContinuation())
    }

    @Test fun singleSongQueueRemainsSafeForManualNavigation() {
        val h = harness()
        h.queue = h.queue.copy(songs = listOf(h.b), currentIndex = 0)
        assertEquals(h.b, h.coordinator.nextFromArtworkGesture())
        assertEquals(h.b, h.coordinator.previousFromArtworkGesture())
    }
}
