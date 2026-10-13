package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionMetadataPublicationTest {
    @Test fun lyricLineCommitDoesNotReannouncePlaybackOrRebuildTheNotification() {
        val events = mutableListOf<String>()
        publishMediaSessionMetadata(
            commitMetadata = { events += "lyric" },
            currentState = { error("A lyric-only commit must not read transport state") },
            publishPlaybackState = { events += "state" },
            publishNotification = { events += "notification" },
            metadataOnly = true,
        )
        assertEquals(listOf("lyric"), events)
    }

    @Test fun metadataCommitReannouncesPlaybackBeforeNotification() {
        val events = mutableListOf<String>()
        publishMediaSessionMetadata(
            commitMetadata = { events += "metadata" },
            currentState = { PlayState.PLAYING },
            publishPlaybackState = { events += it.name },
            publishNotification = { events += "notification" },
        )
        assertEquals(listOf("metadata", "PLAYING", "notification"), events)
    }

    @Test fun stateIsReadAfterCommitInsteadOfReusingAnEarlierPlayingSnapshot() {
        var state = PlayState.PLAYING
        val states = mutableListOf<PlayState>()
        publishMediaSessionMetadata(
            commitMetadata = { state = PlayState.PAUSED },
            currentState = { state },
            publishPlaybackState = { states += it },
            publishNotification = {},
        )
        assertEquals(listOf(PlayState.PAUSED), states)
    }

    @Test fun terminalStateIsNotPromotedToPlaying() {
        val states = mutableListOf<PlayState>()
        for (state in listOf(PlayState.IDLE, PlayState.STOPPED, PlayState.ERROR, PlayState.PREPARING)) {
            publishMediaSessionMetadata({}, { state }, { states += it }, {})
        }
        assertEquals(listOf(PlayState.IDLE, PlayState.STOPPED, PlayState.ERROR, PlayState.PREPARING), states)
    }

    @Test fun failedMetadataCommitDoesNotPublishAPartialSnapshot() {
        val events = mutableListOf<String>()
        try {
            publishMediaSessionMetadata(
                commitMetadata = { throw IllegalStateException("commit failed") },
                currentState = { PlayState.PLAYING },
                publishPlaybackState = { events += "state" },
                publishNotification = { events += "notification" },
            )
            throw AssertionError("Expected commit failure")
        } catch (_: IllegalStateException) {
            assertEquals(emptyList<String>(), events)
        }
    }
}
