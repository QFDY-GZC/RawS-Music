package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/** JNI-backed manual two-slot short crossfade. */
internal class NativeManualCrossfadeProcessor private constructor(
    private val tag: String,
    @Volatile private var handle: Long,
) : ManualCrossfadeProcessor {

    companion object {
        private const val FORMAT_S16 = 1
        private const val FORMAT_S32 = 2
        private const val FORMAT_FLOAT = 3
        private const val FORMAT_S24_PACKED = 4

        private const val COMPLETED_FLAG = Long.MIN_VALUE
        private const val ACTIVE_FLAG = 1L shl 62
        private const val FRAME_MASK = ACTIVE_FLAG - 1L

        private val nativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawscoreservice")
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun createOrNull(tag: String): NativeManualCrossfadeProcessor? {
            if (!nativeAvailable) return null
            return try {
                val handle = nativeCreate()
                if (handle == 0L) null else NativeManualCrossfadeProcessor(tag, handle)
            } catch (t: Throwable) {
                AppLogger.w(tag, "Native manual crossfade unavailable: ${t.message}")
                null
            }
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeArm(handle: Long, durationMs: Int)
        @JvmStatic private external fun nativeClear(handle: Long)
        @JvmStatic private external fun nativeProcess(
            handle: Long,
            currentBuffer: ByteArray,
            pendingBuffer: ByteArray,
            offset: Int,
            length: Int,
            sampleRate: Int,
            frameSize: Int,
            format: Int,
        ): Long
        @JvmStatic private external fun nativeClose(handle: Long)
    }

    override val isNativeBacked: Boolean = true

    @Volatile
    private var armed = false

    override fun start(durationMs: Int, sampleRate: Int): Boolean {
        val h = handle
        if (h == 0L || durationMs <= 0 || sampleRate <= 0) return false
        return try {
            nativeArm(h, durationMs.coerceAtLeast(1))
            armed = true
            true
        } catch (t: Throwable) {
            armed = false
            AppLogger.w(tag, "Native manual crossfade arm failed", t)
            false
        }
    }

    override fun reset(reason: String) {
        val h = handle
        armed = false
        if (h == 0L) return
        try {
            nativeClear(h)
        } catch (t: Throwable) {
            AppLogger.w(tag, "Native manual crossfade clear failed reason=$reason", t)
        }
    }

    override fun mixInPlace(
        currentBuffer: ByteArray,
        pendingBuffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    ): ManualCrossfadeProcessor.Result {
        val h = handle
        if (h == 0L || !armed || length <= 0 || frameSize <= 0) {
            return ManualCrossfadeProcessor.Result(0L, completed = false, active = armed)
        }
        val aligned = PcmFrameAligner.alignDown(length, frameSize)
        if (aligned <= 0) {
            return ManualCrossfadeProcessor.Result(0L, completed = false, active = armed)
        }
        val format = when {
            outputIsFloat -> FORMAT_FLOAT
            outputIsPacked24 -> FORMAT_S24_PACKED
            bitsPerSample <= 16 -> FORMAT_S16
            else -> FORMAT_S32
        }
        val packed = nativeProcess(
            h,
            currentBuffer,
            pendingBuffer,
            offset,
            aligned,
            sampleRate,
            frameSize,
            format,
        )
        val completed = packed and COMPLETED_FLAG != 0L
        val active = packed and ACTIVE_FLAG != 0L
        val processedFrames = packed and FRAME_MASK
        armed = active
        return ManualCrossfadeProcessor.Result(processedFrames, completed, active)
    }

    override fun close() {
        val h = handle
        handle = 0L
        armed = false
        if (h != 0L) {
            try {
                nativeClose(h)
            } catch (_: Throwable) {
                // Idempotent close also supports an older native library.
            }
        }
    }
}
