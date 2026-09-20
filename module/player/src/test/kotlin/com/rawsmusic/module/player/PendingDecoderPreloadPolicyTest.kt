package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingDecoderPreloadPolicyTest {
    @Test
    fun `high sample rate readiness remains time based`() {
        val plan = PendingDecoderPreloadPolicy.plan(
            sampleRate = 384_000,
            frameSize = 8,
            maximumQueueBytes = 2 * 1024 * 1024,
        )
        assertEquals(46_080L, plan.minimumReadyFrames)
        assertEquals(192_000L, plan.targetReadyFrames)
        assertEquals(76_800L, plan.handoffReadyFrames)
        assertEquals(1_536_000, plan.capacityBytes)
    }

    @Test
    fun `queue capacity caps every watermark without losing ordering`() {
        val plan = PendingDecoderPreloadPolicy.plan(
            sampleRate = 768_000,
            frameSize = 8,
            maximumQueueBytes = 256 * 1024,
        )
        assertEquals(32_768L, plan.minimumReadyFrames)
        assertEquals(32_768L, plan.targetReadyFrames)
        assertEquals(32_768L, plan.handoffReadyFrames)
        assertEquals(256 * 1024, plan.capacityBytes)
        assertTrue(plan.handoffReadyFrames <= plan.minimumReadyFrames)
        assertTrue(plan.minimumReadyFrames <= plan.targetReadyFrames)
    }
}
