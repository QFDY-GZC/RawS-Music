package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RendererStartPublicationPolicyTest {
    @Test
    fun `each decoder reopen publishes once while pause resume does not republish`() {
        val states = listOf("IDLE", "PREPARING", "PLAYING", "PLAYING", "PAUSED",
            "PLAYING", "STOPPED", "PREPARING", "PLAYING")
        assertEquals(2, states.zipWithNext().count { (old, new) ->
            shouldPublishReopenedTrackStart(old, new)
        })
    }

    @Test
    fun `failed or cancelled preparation does not publish a song`() {
        for (state in listOf("ERROR", "STOPPED", "IDLE", "PAUSED", "PREPARING")) {
            assertFalse(shouldPublishReopenedTrackStart("PREPARING", state))
        }
    }
}
