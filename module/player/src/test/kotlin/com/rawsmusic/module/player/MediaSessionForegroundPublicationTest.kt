package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayState
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSessionForegroundPublicationTest {
    @Test fun foregroundEntryPublishesCurrentPlayingStateBeforeNotification() {
        val publications = mutableListOf<String>()
        refreshMediaSessionOnForeground(true, PlayState.PLAYING,
            { publications += it.name }, { publications += "notification" })
        assertEquals(listOf("PLAYING", "notification"), publications)
    }

    @Test fun pausedSelectionIsReannouncedWithoutStartingPlayback() {
        val publications = mutableListOf<String>()
        refreshMediaSessionOnForeground(true, PlayState.PAUSED,
            { publications += it.name }, { publications += "notification" })
        assertEquals(listOf("PAUSED", "notification"), publications)
    }

    @Test fun emptyOrStoppedTransportDoesNotResurrectAMediaCard() {
        val publications = mutableListOf<String>()
        for (state in PlayState.entries) {
            refreshMediaSessionOnForeground(false, state,
                { publications += it.name }, { publications += "notification" })
        }
        for (state in listOf(PlayState.IDLE, PlayState.STOPPED, PlayState.ERROR)) {
            refreshMediaSessionOnForeground(true, state,
                { publications += it.name }, { publications += "notification" })
        }
        assertEquals(emptyList<String>(), publications)
    }
}
