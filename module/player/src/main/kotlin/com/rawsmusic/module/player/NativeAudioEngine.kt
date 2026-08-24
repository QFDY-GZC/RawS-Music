package com.rawsmusic.module.player

import android.media.AudioFormat
import android.os.Build
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

class NativeAudioEngine private constructor(
    @Volatile private var handle: Long,
    val requestedMode: AudioOutputMode,
    val actualMode: AudioOutputMode,
    val sampleRate: Int,
    val channels: Int,
    val encoding: Int,
    val spatializationBehavior: Int,
    val contentSpatialized: Boolean
) {

    companion object {
        private const val TAG = "NativeAudioEngine"

        private const val MODE_OPENSL_ES = 0
        private const val MODE_AAUDIO = 1
        private const val MODE_DIRECT = 2

        private const val FORMAT_PCM_I16 = 1
        private const val FORMAT_PCM_FLOAT = 2
        private const val FORMAT_PCM_I32 = 3

        /** Native sink paused before this PCM block was accepted; caller must retry the same block. */
        internal const val WRITE_PAUSED = -2

        private val nativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("rawscoreservice")
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
                AudioOutputMode.AUDIO_TRACK -> false
            }
        }

        fun create(
            requestedMode: AudioOutputMode,
            sampleRate: Int,
            channels: Int,
            encoding: Int,
            bufferFrames: Int,
            preferredDeviceId: Int = 0,
            spatializationBehavior: Int = AndroidSpatialAudio.AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER,
            contentSpatialized: Boolean = false
        ): NativeAudioEngine? {
            if (!nativeAvailable) return null
            val normalizedChannels = channels.coerceIn(1, 2)
            val format = nativeFormatFor(requestedMode, encoding)
            val modeChain = fallbackModeChain(requestedMode, spatializationBehavior)

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
                        preferredDeviceId.coerceAtLeast(0),
                        spatializationBehavior,
                        contentSpatialized
                    )
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "nativeCreate failed for $mode: ${t.message}")
                    0L
                }
                if (handle != 0L) {
                    AppLogger.i(TAG, "Created native engine: requested=$requestedMode actual=$mode rate=$sampleRate ch=$normalizedChannels format=$format bufferFrames=$bufferFrames preferredDeviceId=$preferredDeviceId spatializationBehavior=$spatializationBehavior contentSpatialized=$contentSpatialized")
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
                        },
                        spatializationBehavior = spatializationBehavior,
                        contentSpatialized = contentSpatialized
                    )
                }
            }
            AppLogger.w(TAG, "No native engine backend accepted requested=$requestedMode rate=$sampleRate ch=$normalizedChannels encoding=$encoding")
            return null
        }

        private fun fallbackModeChain(
            mode: AudioOutputMode,
            spatializationBehavior: Int
        ): List<AudioOutputMode> {
            return when (mode) {
                // DIRECT is only a real Direct path if AAudio grants an exclusive stream.
                // Do not silently fall back to AAudio shared/OpenSL here; FfmpegAudioPlayer
                // will then use the AudioTrack direct/preferred-device path instead.
                AudioOutputMode.DIRECT -> listOf(AudioOutputMode.DIRECT)
                AudioOutputMode.AAUDIO -> {
                    // OpenSL ES has no per-stream spatialization declaration. When platform
                    // spatialization is requested, fail the native attempt after AAudio so the
                    // existing caller falls back to AudioTrack, which can still request AUTO.
                    if (spatializationBehavior == AndroidSpatialAudio.AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO) {
                        listOf(AudioOutputMode.AAUDIO)
                    } else {
                        listOf(AudioOutputMode.AAUDIO, AudioOutputMode.OPENSL_ES)
                    }
                }
                AudioOutputMode.OPENSL_ES -> listOf(AudioOutputMode.OPENSL_ES)
                AudioOutputMode.AUDIO_TRACK -> emptyList()
            }
        }

        private fun nativeMode(mode: AudioOutputMode): Int {
            return when (mode) {
                AudioOutputMode.OPENSL_ES -> MODE_OPENSL_ES
                AudioOutputMode.AAUDIO -> MODE_AAUDIO
                AudioOutputMode.DIRECT -> MODE_DIRECT
                AudioOutputMode.AUDIO_TRACK -> MODE_OPENSL_ES
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
            preferredDeviceId: Int,
            spatializationBehavior: Int,
            contentSpatialized: Boolean
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

    // nativeWrite() is intentionally blocking: OpenSL waits for a free queue slot and AAudio can
    // wait inside AAudioStream_write(). A single synchronized monitor around write/start/pause used
    // to let the render thread hold the lifecycle lock while blocked in native code, preventing the
    // control thread from reaching nativePause()/nativeStart(). Use a read/write lifetime gate
    // instead: ordinary native calls may overlap, while close is the only exclusive operation.
    // The native backends own their own mutex/state machine and are responsible for coordinating
    // write vs pause/start.
    private val lifecycleLock = ReentrantReadWriteLock()
    private val closing = AtomicBoolean(false)

    private fun <T> withLiveHandle(default: T, block: (Long) -> T): T {
        if (closing.get()) return default
        val read = lifecycleLock.readLock()
        read.lock()
        return try {
            if (closing.get()) return default
            val h = handle
            if (h != 0L) block(h) else default
        } finally {
            read.unlock()
        }
    }

    private fun withLiveHandle(block: (Long) -> Unit) {
        withLiveHandle(Unit, block)
    }

    fun start(): Boolean = withLiveHandle(false) { nativeStart(it) }

    fun pause() = withLiveHandle { nativePause(it) }

    fun stop() = withLiveHandle { nativeStop(it) }

    fun flush() = withLiveHandle { nativeFlush(it) }

    fun write(buffer: ByteArray, offset: Int, length: Int): Int =
        withLiveHandle(-1) { nativeWrite(it, buffer, offset, length) }

    fun setVolume(volume: Float) = withLiveHandle {
        nativeSetVolume(it, volume.coerceIn(0f, 1f))
    }

    fun setOutputDevice(deviceId: Int): Boolean =
        withLiveHandle(false) { nativeSetOutputDevice(it, deviceId.coerceAtLeast(0)) }

    fun getFramesWritten(): Long = withLiveHandle(0L) { nativeGetFramesWritten(it) }

    fun close() {
        if (!closing.compareAndSet(false, true)) return

        val write = lifecycleLock.writeLock()
        // The normal owner already calls stop() before close(), so avoid an unnecessary second
        // native stop when no call is in flight. If a writer is still holding a read lease, wake it
        // first; otherwise waiting for the exclusive lease could deadlock on a full native queue.
        if (!write.tryLock()) {
            val read = lifecycleLock.readLock()
            read.lock()
            try {
                val h = handle
                if (h != 0L) nativeStop(h)
            } finally {
                read.unlock()
            }
            write.lock()
        }
        try {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        } finally {
            write.unlock()
        }
    }
}
