package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level seek ownership coordinator.
 *
 * Decoder and output code may advance a seek only through this object. In the
 * real APK the native barrier is authoritative; the Kotlin backend exists for
 * unit tests and compatibility with an older native library.
 */
internal class SeekTransitionCoordinator(
    private val tag: String,
    private val fadeOutBlocking: (
        durationMs: Int,
        reason: String,
        shouldContinue: () -> Boolean,
    ) -> Boolean,
    private val armFadeIn: (durationMs: Int, reason: String) -> Unit,
    private val seekFadeMs: () -> Int,
    backend: SeekBarrierBackend? = null,
) : AutoCloseable {

    data class Request(
        val serial: Long,
        val targetMs: Long,
        val keepPaused: Boolean,
        val requireFadeOut: Boolean,
    )

    private val serialSource = AtomicLong(0L)
    private val barrier: SeekBarrierBackend =
        backend ?: NativeSeekBarrier.createOrNull(tag) ?: KotlinSeekBarrierBackend()

    init {
        AppLogger.i(
            tag,
            "SeekBarrier: backend=${if (barrier.isNativeBacked) "native" else "kotlin_fallback"}",
        )
    }

    fun begin(targetMs: Long, keepPaused: Boolean, requireFadeOut: Boolean): Request {
        val request = Request(
            serial = serialSource.incrementAndGet(),
            targetMs = targetMs.coerceAtLeast(0L),
            keepPaused = keepPaused,
            requireFadeOut = requireFadeOut && !keepPaused,
        )
        check(
            barrier.arm(
                serial = request.serial,
                targetMs = request.targetMs,
                keepPaused = request.keepPaused,
                requireFadeOut = request.requireFadeOut,
            ),
        ) { "Unable to arm seek barrier serial=${request.serial}" }
        return request
    }

    /** Waits for native render-clock fade-out before allowing decoder mutation. */
    fun awaitDecoderBarrier(request: Request): Boolean {
        if (!isCurrent(request.serial)) return false
        if (!request.requireFadeOut) return canStartDecoder(request.serial)

        val completed = fadeOutBlocking(
            seekFadeMs().coerceAtLeast(1),
            "seek_${request.serial}_fade_out",
        ) { isCurrent(request.serial) }
        if (!isCurrent(request.serial)) return false

        // A renderer timeout must not strand the decoder forever. The barrier is
        // still advanced explicitly, and telemetry records whether zero was reached.
        val advanced = barrier.markFadeOutComplete(request.serial)
        AppLogger.d(
            tag,
            "SeekBarrier: fade-out serial=${request.serial} completed=$completed advanced=$advanced " +
                "snapshot=${barrier.snapshot()}",
        )
        return advanced && canStartDecoder(request.serial)
    }

    fun canStartDecoder(serial: Long): Boolean = barrier.canStartDecoder(serial)

    fun markDecoderCommitted(serial: Long): Boolean {
        val accepted = barrier.markDecoderCommitted(serial)
        if (!accepted) {
            AppLogger.w(tag, "SeekBarrier: reject decoder commit serial=$serial snapshot=${barrier.snapshot()}")
        }
        return accepted
    }

    /** Called exactly at the first post-seek output block boundary. */
    fun markOutputCommitted(serial: Long): Boolean {
        val accepted = barrier.markOutputCommitted(serial)
        if (!accepted) {
            AppLogger.w(tag, "SeekBarrier: reject output commit serial=$serial snapshot=${barrier.snapshot()}")
            return false
        }
        armFadeIn(seekFadeMs().coerceAtLeast(1), "seek_${serial}_first_post_seek_block")
        return true
    }

    fun releasePaused(serial: Long): Boolean {
        val released = barrier.releasePaused(serial)
        if (!released) {
            AppLogger.w(tag, "SeekBarrier: reject paused release serial=$serial snapshot=${barrier.snapshot()}")
        }
        return released
    }

    fun cancel(serial: Long, reason: String) {
        barrier.cancel(serial)
        AppLogger.d(tag, "SeekBarrier: cancel serial=$serial reason=$reason")
    }

    fun fail(serial: Long, reason: String) {
        barrier.fail(serial)
        AppLogger.w(tag, "SeekBarrier: fail serial=$serial reason=$reason")
    }

    fun isCurrent(serial: Long): Boolean = barrier.isCurrent(serial)

    fun snapshot(): SeekBarrierSnapshot = barrier.snapshot()

    override fun close() = barrier.close()
}
