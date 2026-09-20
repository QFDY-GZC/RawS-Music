package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger

/**
 * JNI bridge for the fixed native PCM queues owned by current/pending track slots.
 *
 * Priming is a single-producer operation and prefix playback is a single-consumer
 * operation. The native implementation keeps both paths allocation-free.
 */
internal object NativePcmSlotQueue {
    private const val TAG = "NativePcmSlotQueue"
    private const val DEFAULT_MAXIMUM_SLOT_BYTES = 2 * 1024 * 1024

    private val libraryLoaded: Boolean by lazy {
        try {
            System.loadLibrary("rawscoreservice")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native PCM slot queue unavailable", t)
            false
        }
    }

    @Volatile
    private var bridgeDisabled: Boolean = false

    private fun isUsable(): Boolean = libraryLoaded && !bridgeDisabled

    private fun disableBridge(operation: String, error: Throwable) {
        bridgeDisabled = true
        AppLogger.w(TAG, "$operation failed; prepared PCM will use Kotlin fallback", error)
    }

    fun maximumSlotBytes(): Int {
        if (!isUsable()) return DEFAULT_MAXIMUM_SLOT_BYTES
        return runCatching { nativeMaximumSlotBytes() }
            .getOrElse {
                disableBridge("nativeMaximumSlotBytes", it)
                DEFAULT_MAXIMUM_SLOT_BYTES
            }
            .coerceAtLeast(1)
    }

    fun isConfigured(decoderSerial: Long, generation: Int): Boolean {
        if (!isUsable()) return false
        return runCatching { nativeIsConfigured(decoderSerial, generation) }
            .getOrElse {
                disableBridge("nativeIsConfigured", it)
                false
            }
    }

    fun appendPending(
        decoderSerial: Long,
        generation: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (!isUsable()) return -1
        return runCatching {
            nativeAppendPending(decoderSerial, generation, source, offset, length)
        }.getOrElse {
            disableBridge("nativeAppendPending", it)
            -1
        }
    }

    fun read(
        decoderSerial: Long,
        generation: Int,
        destination: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (!isUsable()) return -1
        return runCatching {
            nativeRead(decoderSerial, generation, destination, offset, maxLength)
        }.getOrElse {
            disableBridge("nativeRead", it)
            -1
        }
    }

    fun availableBytes(decoderSerial: Long, generation: Int): Int {
        if (!isUsable()) return 0
        return runCatching { nativeAvailableBytes(decoderSerial, generation) }
            .getOrElse {
                disableBridge("nativeAvailableBytes", it)
                0
            }
    }

    fun availableFrames(decoderSerial: Long, generation: Int): Long {
        if (!isUsable()) return 0L
        return runCatching { nativeAvailableFrames(decoderSerial, generation) }
            .getOrElse {
                disableBridge("nativeAvailableFrames", it)
                0L
            }
    }

    fun totalWrittenFrames(decoderSerial: Long, generation: Int): Long {
        if (!isUsable()) return 0L
        return runCatching { nativeTotalWrittenFrames(decoderSerial, generation) }
            .getOrElse {
                disableBridge("nativeTotalWrittenFrames", it)
                0L
            }
    }

    fun snapshot(): String {
        if (!isUsable()) return "native_pcm_slots_unavailable"
        return runCatching { nativeSnapshot() }.getOrElse {
            disableBridge("nativeSnapshot", it)
            "native_pcm_slots_error:${it.javaClass.simpleName}"
        }
    }

    @JvmStatic private external fun nativeMaximumSlotBytes(): Int
    @JvmStatic private external fun nativeIsConfigured(decoderSerial: Long, generation: Int): Boolean

    @JvmStatic
    private external fun nativeAppendPending(
        decoderSerial: Long,
        generation: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    @JvmStatic
    private external fun nativeRead(
        decoderSerial: Long,
        generation: Int,
        destination: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int

    @JvmStatic private external fun nativeAvailableBytes(decoderSerial: Long, generation: Int): Int
    @JvmStatic private external fun nativeAvailableFrames(decoderSerial: Long, generation: Int): Long
    @JvmStatic private external fun nativeTotalWrittenFrames(decoderSerial: Long, generation: Int): Long
    @JvmStatic private external fun nativeSnapshot(): String
}
