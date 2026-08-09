package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbMediaIdentityCoordinatorTest {
    @Test
    fun inactiveExclusiveRouteDoesNothing() {
        var syncCalls = 0
        var sends = 0
        val coordinator = coordinator(
            exclusive = { false },
            sync = { _, _, _, _ -> syncCalls++; false },
            send = { _, _, _, _ -> sends++ },
        )

        coordinator.ensure("play", AudioFile(title = "Track"), 12L, playing = true)

        assertEquals(0, syncCalls)
        assertEquals(0, sends)
    }

    @Test
    fun missingServiceFallsBackToExplicitIdentityIntent() {
        var sentPosition = Long.MIN_VALUE
        val coordinator = coordinator(
            sync = { _, _, position, _ ->
                assertEquals(0L, position)
                false
            },
            send = { _, _, position, _ -> sentPosition = position },
        )

        coordinator.ensure("cold_start", null, -25L, playing = false)

        assertEquals(-25L, sentPosition)
    }

    @Test
    fun liveServiceStillReceivesNonHeartbeatExplicitIdentity() {
        var sends = 0
        val coordinator = coordinator(
            sync = { _, _, _, _ -> true },
            send = { _, _, _, _ -> sends++ },
        )

        coordinator.ensure("foreground_rearm", null, 9L, playing = true)

        assertEquals(1, sends)
    }

    @Test
    fun liveServiceSuppressesDuplicateHeartbeatIntent() {
        var sends = 0
        val coordinator = coordinator(
            sync = { _, _, _, _ -> true },
            send = { _, _, _, _ -> sends++ },
        )

        coordinator.ensure("background_heartbeat", null, 9L, playing = true)

        assertEquals(0, sends)
    }

    private fun coordinator(
        exclusive: () -> Boolean = { true },
        sync: (AudioFile?, Boolean, Long, String) -> Boolean,
        send: (String, AudioFile?, Long, Boolean) -> Unit,
    ) = UsbMediaIdentityCoordinator(
        isExclusiveActive = exclusive,
        syncControllerIdentity = sync,
        sendServiceIdentity = send,
        logInfo = {},
    )
}
