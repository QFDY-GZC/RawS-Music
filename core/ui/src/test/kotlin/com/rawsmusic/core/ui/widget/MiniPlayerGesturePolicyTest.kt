package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerGesturePolicyTest {
    @Test
    fun capturedDragOwnsChildControlsUntilReleaseGuardExpires() {
        assertFalse(
            miniPlayerChildControlAllowed(
                pointerCaptured = true,
                controlsBlockedUntilUptimeMs = 2_000L,
                nowUptimeMs = 1_000L,
            )
        )
        assertFalse(
            miniPlayerChildControlAllowed(
                pointerCaptured = false,
                controlsBlockedUntilUptimeMs = 2_000L,
                nowUptimeMs = 1_999L,
            )
        )
        assertTrue(
            miniPlayerChildControlAllowed(
                pointerCaptured = false,
                controlsBlockedUntilUptimeMs = 2_000L,
                nowUptimeMs = 2_000L,
            )
        )
    }

    @Test
    fun automaticAudioCrossfadeUsesArtworkOnlyWhenRendererTargetIsTheCommittedSong() {
        assertTrue(
            shouldUseAutomaticMiniPlayerArtworkFade(
                activeAutomaticTargetPath = "/music/next.flac",
                committedSongPath = "/music/next.flac",
                currentIdentity = "song-a",
                committedIdentity = "song-b",
                manualGestureActive = false,
            )
        )
        assertFalse(
            shouldUseAutomaticMiniPlayerArtworkFade(
                activeAutomaticTargetPath = "/music/another.flac",
                committedSongPath = "/music/next.flac",
                currentIdentity = "song-a",
                committedIdentity = "song-b",
                manualGestureActive = false,
            )
        )
        assertFalse(
            shouldUseAutomaticMiniPlayerArtworkFade(
                activeAutomaticTargetPath = null,
                committedSongPath = "/music/next.flac",
                currentIdentity = "song-a",
                committedIdentity = "song-b",
                manualGestureActive = false,
            )
        )
    }

    @Test
    fun manualGestureAndSameIdentityUpdatesNeverStartMiniPlayerAutomaticDissolve() {
        assertFalse(
            shouldUseAutomaticMiniPlayerArtworkFade(
                activeAutomaticTargetPath = "/music/next.flac",
                committedSongPath = "/music/next.flac",
                currentIdentity = "song-a",
                committedIdentity = "song-b",
                manualGestureActive = true,
            )
        )
        assertFalse(
            shouldUseAutomaticMiniPlayerArtworkFade(
                activeAutomaticTargetPath = "/music/next.flac",
                committedSongPath = "/music/next.flac",
                currentIdentity = "song-a",
                committedIdentity = "song-a",
                manualGestureActive = false,
            )
        )
    }
}
