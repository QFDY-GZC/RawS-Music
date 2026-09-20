package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals

class RendererRestartTimelinePolicyTest {
    @Test
    fun sameTrackRendererRestartKeepsVisiblePosition() {
        assertEquals(
            120_000L,
            RendererRestartTimelinePolicy.initialDisplayPositionMs(
                pendingPositionMs = 120_000L,
                pendingPath = "/music/a.flac",
                songPath = "/music/a.flac",
            ),
        )
    }

    @Test
    fun differentTrackStillStartsAtZero() {
        assertEquals(
            0L,
            RendererRestartTimelinePolicy.initialDisplayPositionMs(
                pendingPositionMs = 120_000L,
                pendingPath = "/music/a.flac",
                songPath = "/music/b.flac",
            ),
        )
    }
}
