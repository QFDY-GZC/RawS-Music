package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class AutoTransitionCancellationTest {
    @Test fun leavingAndReturningToSameSongRestoresAutomaticTransition() {
        val cancellation = AutoTransitionCancellation()
        cancellation.cancel(7, "old.flac")
        cancellation.onTrackChanged("old.flac", "next.flac")
        cancellation.onTrackChanged("next.flac", "old.flac")
        assertFalse(cancellation.isCancelled(7, "old.flac"))
    }

    @Test fun republisingSameSongDoesNotUndoCancellation() {
        val cancellation = AutoTransitionCancellation()
        cancellation.cancel(7, "old.flac")
        cancellation.onTrackChanged("old.flac", "old.flac")
        assertTrue(cancellation.isCancelled(7, "old.flac"))
    }

    @Test fun cancellationSurvivesRecipeRefreshAndSeekWithinSameTrack() {
        val cancellation = AutoTransitionCancellation()
        cancellation.cancel(7, "old.flac")
        repeat(3) { assertTrue(cancellation.isCancelled(7, "old.flac")) }
    }

    @Test fun nextTrackAndNewPlaybackSessionAutomaticallyResume() {
        val cancellation = AutoTransitionCancellation()
        cancellation.cancel(7, "old.flac")
        assertFalse(cancellation.isCancelled(7, "next.flac"))
        assertFalse(cancellation.isCancelled(8, "old.flac"))
    }

    @Test fun newerCancellationReplacesThePreviousTrack() {
        val cancellation = AutoTransitionCancellation()
        cancellation.cancel(7, "old.flac")
        cancellation.cancel(7, "next.flac")
        assertFalse(cancellation.isCancelled(7, "old.flac"))
        assertTrue(cancellation.isCancelled(7, "next.flac"))
    }
}
