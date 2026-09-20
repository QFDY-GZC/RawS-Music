package com.rawsmusic.module.player

import com.rawsmusic.module.data.prefs.TransitionPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualTrackTransitionPolicyTest {
    @Test
    fun shortFadeAttemptsNativeOverlapWhenPairIsSafe() {
        val decision = ManualTrackTransitionPolicy.decide(
            mode = TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
            pathOnlyCrossfadeIsSafe = true,
        )

        assertTrue(decision.attemptOverlap)
        assertTrue(decision.allowSequentialFallback)
    }

    @Test
    fun sourceMetadataMismatchDoesNotPreRejectOverlap() {
        val decision = ManualTrackTransitionPolicy.decide(
            mode = TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
            pathOnlyCrossfadeIsSafe = true,
        )

        assertTrue(decision.attemptOverlap)
        assertTrue(decision.allowSequentialFallback)
    }

    @Test
    fun fullCrossfadeKeepsFiveSecondOverlapButUsesShortSequentialFallback() {
        val durations = ManualTrackTransitionPolicy.durations(
            mode = TransitionPreferences.ManualTrackTransitionMode.CROSSFADE,
            shortFadeMs = 400,
            fullCrossfadeMs = 5_000,
        )

        assertEquals(5_000, durations.overlapMs)
        assertEquals(400, durations.sequentialFallbackMs)
        assertEquals(400, durations.usbSequentialMs)
    }

    @Test
    fun shortFadeUsesOneDurationForOverlapAndFallback() {
        val durations = ManualTrackTransitionPolicy.durations(
            mode = TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
            shortFadeMs = 400,
            fullCrossfadeMs = 5_000,
        )

        assertEquals(400, durations.overlapMs)
        assertEquals(400, durations.sequentialFallbackMs)
        assertEquals(400, durations.usbSequentialMs)
    }

    @Test
    fun noneNeverStartsOverlapOrFallbackFade() {
        val decision = ManualTrackTransitionPolicy.decide(
            mode = TransitionPreferences.ManualTrackTransitionMode.NONE,
            pathOnlyCrossfadeIsSafe = true,
        )

        assertFalse(decision.attemptOverlap)
        assertFalse(decision.allowSequentialFallback)
    }
}
