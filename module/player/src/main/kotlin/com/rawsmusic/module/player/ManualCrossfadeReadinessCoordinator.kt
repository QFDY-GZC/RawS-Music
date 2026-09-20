package com.rawsmusic.module.player

import kotlinx.coroutines.delay

/**
 * Waits briefly for a planned/manual pending decoder without blocking the
 * renderer or opening FFmpeg on the transport thread.
 *
 * Reference keeps the current track rendering while the pending track reaches
 * its DSP-ready watermark. This coordinator mirrors that ownership: waiting
 * only delays the control decision; it never pauses or fades the current PCM.
 */
internal class ManualCrossfadeReadinessCoordinator(
    private val pollIntervalMs: Long = 10L,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Outcome<T>(
        val prepared: T?,
        val waitedMs: Long,
        val timedOut: Boolean,
        val requestBecameObsolete: Boolean,
        val terminalReason: String? = null,
    )

    suspend fun <T> await(
        timeoutMs: Long,
        isRequestCurrent: () -> Boolean,
        snapshot: () -> T?,
        terminalFailure: () -> String? = { null },
    ): Outcome<T> {
        val startMs = monotonicMs()
        snapshot()?.let {
            return Outcome(
                prepared = it,
                waitedMs = 0L,
                timedOut = false,
                requestBecameObsolete = false,
                terminalReason = null,
            )
        }

        val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
        while (isRequestCurrent()) {
            terminalFailure()?.let { reason ->
                return Outcome(
                    prepared = snapshot(),
                    waitedMs = (monotonicMs() - startMs).coerceAtLeast(0L),
                    timedOut = false,
                    requestBecameObsolete = false,
                    terminalReason = reason,
                )
            }
            val elapsedMs = (monotonicMs() - startMs).coerceAtLeast(0L)
            if (elapsedMs >= boundedTimeoutMs) {
                return Outcome(
                    prepared = snapshot(),
                    waitedMs = elapsedMs,
                    timedOut = true,
                    requestBecameObsolete = false,
                    terminalReason = null,
                )
            }
            delay(minOf(pollIntervalMs.coerceAtLeast(1L), boundedTimeoutMs - elapsedMs))
            snapshot()?.let {
                return Outcome(
                    prepared = it,
                    waitedMs = (monotonicMs() - startMs).coerceAtLeast(0L),
                    timedOut = false,
                    requestBecameObsolete = false,
                    terminalReason = null,
                )
            }
        }

        return Outcome(
            prepared = null,
            waitedMs = (monotonicMs() - startMs).coerceAtLeast(0L),
            timedOut = false,
            requestBecameObsolete = true,
            terminalReason = null,
        )
    }
}
