package com.rawsmusic.module.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTransitionPresentationPolicyTest {
    @Test
    fun lyricsPreviewArmsAtTenSecondsBeforeHandover() {
        assertFalse(shouldArm(positionMs = 19_999L))
        assertTrue(shouldArm(positionMs = 20_000L))
        assertTrue(shouldArm(positionMs = 29_999L))
        assertFalse(shouldArm(positionMs = 30_000L))
    }

    @Test
    fun lyricsPreviewRequiresPreparedCompatibleTargetAndKnownHandover() {
        assertFalse(
            AutoTransitionPresentationPolicy.shouldArmLyrics(
                positionMs = 25_000L,
                handoverPositionMs = null,
                targetPreparedAndCompatible = true,
            )
        )
        assertFalse(
            AutoTransitionPresentationPolicy.shouldArmLyrics(
                positionMs = 25_000L,
                handoverPositionMs = 30_000L,
                targetPreparedAndCompatible = false,
            )
        )
    }

    private fun shouldArm(positionMs: Long): Boolean =
        AutoTransitionPresentationPolicy.shouldArmLyrics(
            positionMs = positionMs,
            handoverPositionMs = 30_000L,
            targetPreparedAndCompatible = true,
        )
}
