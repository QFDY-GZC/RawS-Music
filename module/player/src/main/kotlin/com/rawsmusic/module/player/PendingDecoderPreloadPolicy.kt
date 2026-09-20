package com.rawsmusic.module.player

/**
 * Time-based pending-decoder watermarks.
 *
 * Fixed frame counts collapse to only a few milliseconds at high sample rates.
 * This policy keeps readiness and handoff cushions approximately stable in time
 * while respecting the real fixed native queue capacity.
 */
internal object PendingDecoderPreloadPolicy {
    data class Plan(
        val minimumReadyFrames: Long,
        val targetReadyFrames: Long,
        val handoffReadyFrames: Long,
        val capacityBytes: Int,
    )

    fun plan(
        sampleRate: Int,
        frameSize: Int,
        maximumQueueBytes: Int,
    ): Plan {
        val safeRate = sampleRate.coerceAtLeast(1)
        val safeFrameSize = frameSize.coerceAtLeast(1)
        val safeMaximumBytes = maximumQueueBytes.coerceAtLeast(safeFrameSize)
        val maximumFrames = (safeMaximumBytes / safeFrameSize).coerceAtLeast(1)

        val minimum = maxOf(
            MINIMUM_READY_BLOCKS * BASE_BLOCK_FRAMES,
            framesForMs(safeRate, MINIMUM_READY_MS),
        ).coerceAtMost(maximumFrames)
        val target = maxOf(
            minimum,
            TARGET_READY_BLOCKS * BASE_BLOCK_FRAMES,
            framesForMs(safeRate, TARGET_READY_MS),
        ).coerceAtMost(maximumFrames)
        val handoff = maxOf(
            HANDOFF_READY_BLOCKS * BASE_BLOCK_FRAMES,
            framesForMs(safeRate, HANDOFF_READY_MS),
        ).coerceAtMost(target)

        return Plan(
            minimumReadyFrames = minimum.toLong(),
            targetReadyFrames = target.toLong(),
            handoffReadyFrames = handoff.toLong(),
            capacityBytes = target * safeFrameSize,
        )
    }

    private fun framesForMs(sampleRate: Int, durationMs: Long): Int =
        (sampleRate.toLong() * durationMs / 1000L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    private const val BASE_BLOCK_FRAMES = 1024
    private const val MINIMUM_READY_BLOCKS = 3
    private const val TARGET_READY_BLOCKS = 4
    private const val HANDOFF_READY_BLOCKS = 2
    private const val MINIMUM_READY_MS = 120L
    private const val TARGET_READY_MS = 500L
    private const val HANDOFF_READY_MS = 200L
}
