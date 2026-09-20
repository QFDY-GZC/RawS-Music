package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/** JNI wrapper for the lock-free native seek phase barrier. */
internal class NativeSeekBarrier private constructor(
    private val tag: String,
    @Volatile private var handle: Long,
) : SeekBarrierBackend {

    companion object {
        private val nativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawscoreservice")
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun createOrNull(tag: String): NativeSeekBarrier? {
            if (!nativeAvailable) return null
            return try {
                val handle = nativeCreate()
                if (handle == 0L) null else NativeSeekBarrier(tag, handle)
            } catch (t: Throwable) {
                AppLogger.w(tag, "NativeSeekBarrier unavailable: ${t.message}")
                null
            }
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeArm(
            handle: Long,
            serial: Long,
            targetMs: Long,
            keepPaused: Boolean,
            requireFadeOut: Boolean,
        ): Boolean
        @JvmStatic private external fun nativeMarkFadeOutComplete(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeIsCurrent(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeCanStartDecoder(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeMarkDecoderCommitted(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeMarkOutputCommitted(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeReleasePaused(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeCancel(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeFail(handle: Long, serial: Long): Boolean
        @JvmStatic private external fun nativeSnapshot(handle: Long): LongArray
        @JvmStatic private external fun nativeClose(handle: Long)
    }

    override val isNativeBacked: Boolean = true

    override fun arm(
        serial: Long,
        targetMs: Long,
        keepPaused: Boolean,
        requireFadeOut: Boolean,
    ): Boolean = call(false) {
        nativeArm(it, serial, targetMs, keepPaused, requireFadeOut)
    }

    override fun markFadeOutComplete(serial: Long): Boolean =
        call(false) { nativeMarkFadeOutComplete(it, serial) }

    override fun isCurrent(serial: Long): Boolean =
        call(false) { nativeIsCurrent(it, serial) }

    override fun canStartDecoder(serial: Long): Boolean =
        call(false) { nativeCanStartDecoder(it, serial) }

    override fun markDecoderCommitted(serial: Long): Boolean =
        call(false) { nativeMarkDecoderCommitted(it, serial) }

    override fun markOutputCommitted(serial: Long): Boolean =
        call(false) { nativeMarkOutputCommitted(it, serial) }

    override fun releasePaused(serial: Long): Boolean =
        call(false) { nativeReleasePaused(it, serial) }

    override fun cancel(serial: Long): Boolean = call(false) { nativeCancel(it, serial) }

    override fun fail(serial: Long): Boolean = call(false) { nativeFail(it, serial) }

    override fun snapshot(): SeekBarrierSnapshot = call(
        SeekBarrierSnapshot(0L, -1L, SeekBarrierPhase.IDLE, false),
    ) { h ->
        val values = nativeSnapshot(h)
        if (values.size < 4) {
            SeekBarrierSnapshot(0L, -1L, SeekBarrierPhase.IDLE, false)
        } else {
            SeekBarrierSnapshot(
                serial = values[0],
                targetMs = values[1],
                phase = SeekBarrierPhase.fromNative(values[2].toInt()),
                keepPaused = values[3] != 0L,
            )
        }
    }

    private inline fun <T> call(fallback: T, block: (Long) -> T): T {
        val h = handle
        if (h == 0L) return fallback
        return try {
            block(h)
        } catch (t: Throwable) {
            AppLogger.w(tag, "NativeSeekBarrier call failed: ${t.message}")
            fallback
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeClose(h) }
    }
}
