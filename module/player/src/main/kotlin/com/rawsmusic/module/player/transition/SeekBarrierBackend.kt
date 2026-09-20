package com.rawsmusic.module.player.transition

internal enum class SeekBarrierPhase(val nativeValue: Int) {
    IDLE(0),
    FADE_OUT_PENDING(1),
    DECODER_PENDING(2),
    OUTPUT_PENDING(3),
    PAUSED_COMMITTED(4),
    COMPLETED(5),
    CANCELLED(6),
    FAILED(7);

    companion object {
        fun fromNative(value: Int): SeekBarrierPhase =
            entries.firstOrNull { it.nativeValue == value } ?: IDLE
    }
}

internal data class SeekBarrierSnapshot(
    val serial: Long,
    val targetMs: Long,
    val phase: SeekBarrierPhase,
    val keepPaused: Boolean,
)

internal interface SeekBarrierBackend : AutoCloseable {
    val isNativeBacked: Boolean

    fun arm(serial: Long, targetMs: Long, keepPaused: Boolean, requireFadeOut: Boolean): Boolean
    fun markFadeOutComplete(serial: Long): Boolean
    fun isCurrent(serial: Long): Boolean
    fun canStartDecoder(serial: Long): Boolean
    fun markDecoderCommitted(serial: Long): Boolean
    fun markOutputCommitted(serial: Long): Boolean
    fun releasePaused(serial: Long): Boolean
    fun cancel(serial: Long): Boolean
    fun fail(serial: Long): Boolean
    fun snapshot(): SeekBarrierSnapshot

    override fun close() = Unit
}

/** JVM/old-native fallback with the same transition contract as the native barrier. */
internal class KotlinSeekBarrierBackend : SeekBarrierBackend {
    override val isNativeBacked: Boolean = false

    private val lock = Any()
    private var state = SeekBarrierSnapshot(0L, -1L, SeekBarrierPhase.IDLE, false)

    override fun arm(
        serial: Long,
        targetMs: Long,
        keepPaused: Boolean,
        requireFadeOut: Boolean,
    ): Boolean = synchronized(lock) {
        if (serial <= 0L) return@synchronized false
        state = SeekBarrierSnapshot(
            serial = serial,
            targetMs = targetMs.coerceAtLeast(0L),
            phase = if (requireFadeOut && !keepPaused) {
                SeekBarrierPhase.FADE_OUT_PENDING
            } else {
                SeekBarrierPhase.DECODER_PENDING
            },
            keepPaused = keepPaused,
        )
        true
    }

    override fun markFadeOutComplete(serial: Long): Boolean = synchronized(lock) {
        transition(serial, SeekBarrierPhase.FADE_OUT_PENDING, SeekBarrierPhase.DECODER_PENDING)
    }

    override fun isCurrent(serial: Long): Boolean = synchronized(lock) {
        state.serial == serial && when (state.phase) {
            SeekBarrierPhase.FADE_OUT_PENDING,
            SeekBarrierPhase.DECODER_PENDING,
            SeekBarrierPhase.OUTPUT_PENDING,
            SeekBarrierPhase.PAUSED_COMMITTED -> true
            else -> false
        }
    }

    override fun canStartDecoder(serial: Long): Boolean = synchronized(lock) {
        state.serial == serial && state.phase == SeekBarrierPhase.DECODER_PENDING
    }

    override fun markDecoderCommitted(serial: Long): Boolean = synchronized(lock) {
        if (state.serial != serial || state.phase != SeekBarrierPhase.DECODER_PENDING) return@synchronized false
        state = state.copy(
            phase = if (state.keepPaused) SeekBarrierPhase.PAUSED_COMMITTED else SeekBarrierPhase.OUTPUT_PENDING,
        )
        true
    }

    override fun markOutputCommitted(serial: Long): Boolean = synchronized(lock) {
        transition(serial, SeekBarrierPhase.OUTPUT_PENDING, SeekBarrierPhase.COMPLETED)
    }

    override fun releasePaused(serial: Long): Boolean = synchronized(lock) {
        transition(serial, SeekBarrierPhase.PAUSED_COMMITTED, SeekBarrierPhase.COMPLETED)
    }

    override fun cancel(serial: Long): Boolean = synchronized(lock) {
        if (state.serial != serial) return@synchronized false
        state = state.copy(phase = SeekBarrierPhase.CANCELLED)
        true
    }

    override fun fail(serial: Long): Boolean = synchronized(lock) {
        if (state.serial != serial) return@synchronized false
        state = state.copy(phase = SeekBarrierPhase.FAILED)
        true
    }

    override fun snapshot(): SeekBarrierSnapshot = synchronized(lock) { state }

    private fun transition(
        serial: Long,
        expected: SeekBarrierPhase,
        next: SeekBarrierPhase,
    ): Boolean {
        if (state.serial != serial || state.phase != expected) return false
        state = state.copy(phase = next)
        return true
    }
}
