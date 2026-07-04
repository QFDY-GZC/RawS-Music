package com.rawsmusic.module.player

import android.media.AudioFormat
import android.os.Build
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger

class NativeAudioEngine private constructor(
    @Volatile private var handle: Long,
    val requestedMode: AudioOutputMode,
    val actualMode: AudioOutputMode,
    val sampleRate: Int,
    val channels: Int,
    val encoding: Int
) {

    companion object {
        private const val TAG = "NativeAudioEngine"

        private const val MODE_OPENSL_ES = 0
        private const val MODE_AAUDIO = 1
        private const val MODE_DIRECT = 2

        private const val FORMAT_PCM_I16 = 1
        private const val FORMAT_PCM_FLOAT = 2
        private const val FORMAT_PCM_I32 = 3

        private val nativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawsmusic_native_audio")
                true
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Failed to load rawsmusic_native_audio", t)
                false
            }
        }

        fun isSupported(mode: AudioOutputMode): Boolean {
            if (!nativeAvailable) return false
            return when (mode) {
                AudioOutputMode.OPENSL_ES -> true
                AudioOutputMode.AAUDIO,
                AudioOutputMode.DIRECT -> Build.VERSION.SDK_INT >= 27
            }
        }

        fun create(
            requestedMode: AudioOutputMode,
            sampleRate: Int,
            channels: Int,
            encoding: Int,
            bufferFrames: Int,
            preferredDeviceId: Int = 0
        ): NativeAudioEngine? {
            if (!nativeAvailable) return null
            val normalizedChannels = channels.coerceIn(1, 2)
            val format = nativeFormatFor(requestedMode, encoding)
            val modeChain = fallbackModeChain(requestedMode)

            for (mode in modeChain) {
                if (!isSupported(mode)) continue
                val nativeMode = nativeMode(mode)
                val handle = try {
                    nativeCreate(
                        nativeMode,
                        sampleRate,
                        normalizedChannels,
                        format,
                        bufferFrames.coerceAtLeast(256),
                        preferredDeviceId.coerceAtLeast(0)
                    )
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "nativeCreate failed for $mode: ${t.message}")
                    0L
                }
                if (handle != 0L) {
                    AppLogger.i(TAG, "Created native engine: requested=$requestedMode actual=$mode rate=$sampleRate ch=$normalizedChannels format=$format bufferFrames=$bufferFrames preferredDeviceId=$preferredDeviceId")
                    return NativeAudioEngine(
                        handle = handle,
                        requestedMode = requestedMode,
                        actualMode = mode,
                        sampleRate = sampleRate,
                        channels = normalizedChannels,
                        encoding = when (format) {
                            FORMAT_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                            FORMAT_PCM_I32 -> pcm32EncodingOrNull() ?: AudioFormat.ENCODING_PCM_16BIT
                            else -> AudioFormat.ENCODING_PCM_16BIT
                        }
                    )
                }
            }
            AppLogger.w(TAG, "No native engine backend accepted requested=$requestedMode rate=$sampleRate ch=$normalizedChannels encoding=$encoding")
            return null
        }

        private fun fallbackModeChain(mode: AudioOutputMode): List<AudioOutputMode> {
            return when (mode) {
                // DIRECT is only a real Direct path if AAudio grants an exclusive stream.
                // Do not silently fall back to AAudio shared/OpenSL here; FfmpegAudioPlayer
                // will then use the AudioTrack direct/preferred-device path instead.
                AudioOutputMode.DIRECT -> listOf(AudioOutputMode.DIRECT)
                AudioOutputMode.AAUDIO -> listOf(AudioOutputMode.AAUDIO, AudioOutputMode.OPENSL_ES)
                AudioOutputMode.OPENSL_ES -> listOf(AudioOutputMode.OPENSL_ES)
            }
        }

        private fun nativeMode(mode: AudioOutputMode): Int {
            return when (mode) {
                AudioOutputMode.OPENSL_ES -> MODE_OPENSL_ES
                AudioOutputMode.AAUDIO -> MODE_AAUDIO
                AudioOutputMode.DIRECT -> MODE_DIRECT
            }
        }

        private fun pcm32EncodingOrNull(): Int? {
            if (Build.VERSION.SDK_INT < 31) return null
            return try {
                AudioFormat::class.java.getField("ENCODING_PCM_32BIT").getInt(null)
            } catch (_: Throwable) {
                null
            }
        }

        private fun nativeFormatFor(mode: AudioOutputMode, encoding: Int): Int {
            if (mode == AudioOutputMode.OPENSL_ES) return FORMAT_PCM_I16
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return FORMAT_PCM_FLOAT
            val pcm32 = pcm32EncodingOrNull()
            if (pcm32 != null && encoding == pcm32) return FORMAT_PCM_I32
            return FORMAT_PCM_I16
        }

        @JvmStatic private external fun nativeCreate(
            mode: Int,
            sampleRate: Int,
            channels: Int,
            format: Int,
            bufferFrames: Int,
            preferredDeviceId: Int
        ): Long

        @JvmStatic private external fun nativeStart(handle: Long): Boolean
        @JvmStatic private external fun nativePause(handle: Long)
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeFlush(handle: Long)
        @JvmStatic private external fun nativeWrite(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
        @JvmStatic private external fun nativeSetVolume(handle: Long, volume: Float)
        @JvmStatic private external fun nativeSetOutputDevice(handle: Long, deviceId: Int): Boolean
        @JvmStatic private external fun nativeGetFramesWritten(handle: Long): Long
        @JvmStatic private external fun nativeClose(handle: Long)
    }

    private val nativeLock = Any()

    fun start(): Boolean = synchronized(nativeLock) {
        val h = handle
        h != 0L && nativeStart(h)
    }

    fun pause() = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativePause(h)
    }

    fun stop() = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeStop(h)
    }

    fun flush() = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeFlush(h)
    }

    fun write(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeWrite(h, buffer, offset, length) else -1
    }

    fun setVolume(volume: Float) = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeSetVolume(h, volume.coerceIn(0f, 1f))
    }

    fun setOutputDevice(deviceId: Int): Boolean = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeSetOutputDevice(h, deviceId.coerceAtLeast(0)) else false
    }

    fun getFramesWritten(): Long = synchronized(nativeLock) {
        val h = handle
        if (h != 0L) nativeGetFramesWritten(h) else 0L
    }

    fun close() = synchronized(nativeLock) {
        val h = handle
        handle = 0L
        if (h != 0L) nativeClose(h)
    }
}
