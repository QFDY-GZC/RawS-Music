package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI-backed transport fade. The native processor owns the render-clock state;
 * Kotlin only publishes commands and submits complete PCM blocks.
 */
internal class NativeTransportFadeProcessor private constructor(
    private val tag: String,
    @Volatile private var handle: Long,
) : TransportFadeProcessor {

    companion object {
        private const val DIRECTION_IN = 1
        private const val DIRECTION_OUT = 2

        private const val FORMAT_S16 = 1
        private const val FORMAT_S32 = 2
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_S24_PACKED = 4

        private val nativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawscoreservice")
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun createOrNull(tag: String): NativeTransportFadeProcessor? {
            if (!nativeAvailable) return null
            return try {
                val handle = nativeCreate()
                if (handle == 0L) null else NativeTransportFadeProcessor(tag, handle)
            } catch (t: Throwable) {
                AppLogger.w(tag, "NativeTransportFade unavailable: ${t.message}")
                null
            }
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeArm(handle: Long, direction: Int, durationMs: Int)
        @JvmStatic private external fun nativeClear(handle: Long)
        @JvmStatic private external fun nativeIsActive(handle: Long): Boolean
        @JvmStatic private external fun nativeCurrentGain(handle: Long): Float
        @JvmStatic private external fun nativeCompletionSerial(handle: Long): Long
        @JvmStatic private external fun nativeProcess(
            handle: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            sampleRate: Int,
            frameSize: Int,
            format: Int,
        ): Boolean
        @JvmStatic private external fun nativeClose(handle: Long)
    }

    override val isNativeBacked: Boolean = true

    @Volatile
    private var armed = false
    private val commandGeneration = AtomicLong(0L)

    override val isActive: Boolean
        get() {
            val h = handle
            if (h == 0L || !armed) return false
            val generationBefore = commandGeneration.get()
            val active = try {
                nativeIsActive(h)
            } catch (_: Throwable) {
                false
            }
            if (!active && commandGeneration.get() == generationBefore) armed = false
            return active || commandGeneration.get() != generationBefore
        }

    override val currentGain: Float
        get() {
            val h = handle
            return if (h == 0L) 1f else try {
                nativeCurrentGain(h).coerceIn(0f, 1f)
            } catch (_: Throwable) {
                1f
            }
        }

    override fun startFadeIn(durationMs: Int, reason: String) {
        arm(DIRECTION_IN, durationMs, "IN", reason)
    }

    override fun startFadeOut(durationMs: Int, reason: String) {
        arm(DIRECTION_OUT, durationMs, "OUT", reason)
    }

    private fun arm(direction: Int, durationMs: Int, label: String, reason: String) {
        val h = handle
        if (h == 0L) return
        val safeDuration = durationMs.coerceAtLeast(1)
        nativeArm(h, direction, safeDuration)
        commandGeneration.incrementAndGet()
        armed = true
        AppLogger.d(
            tag,
            "NativeTransportFade: arm direction=$label durationMs=$safeDuration " +
                "gain=${"%.4f".format(currentGain)} reason=$reason",
        )
    }

    override fun clear(reason: String) {
        val h = handle
        if (h == 0L) return
        nativeClear(h)
        commandGeneration.incrementAndGet()
        armed = false
        AppLogger.d(tag, "NativeTransportFade: clear reason=$reason")
    }

    override fun processInPlace(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    ) {
        val h = handle
        if (h == 0L || !armed || length <= 0 || sampleRate <= 0 || frameSize <= 0) return
        val format = when {
            outputIsFloat -> FORMAT_FLOAT
            outputIsPacked24 -> FORMAT_S24_PACKED
            bitsPerSample <= 16 -> FORMAT_S16
            else -> FORMAT_S32
        }
        val generationBefore = commandGeneration.get()
        val stillActive = nativeProcess(
            h,
            buffer,
            offset,
            PcmFrameAligner.alignDown(length, frameSize),
            sampleRate,
            frameSize,
            format,
        )
        armed = stillActive || commandGeneration.get() != generationBefore
    }

    internal fun completionSerial(): Long {
        val h = handle
        return if (h == 0L) 0L else try {
            nativeCompletionSerial(h)
        } catch (_: Throwable) {
            0L
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        commandGeneration.incrementAndGet()
        armed = false
        if (h != 0L) {
            try {
                nativeClose(h)
            } catch (_: Throwable) {
                // Release must remain idempotent even with a stale/older native library.
            }
        }
    }
}
