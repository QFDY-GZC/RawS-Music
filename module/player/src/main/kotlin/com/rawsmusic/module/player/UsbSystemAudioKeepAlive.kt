package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine

internal class UsbSystemAudioKeepAlive(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var track: AudioTrack? = null
    private var keepAliveBuffer: ByteArray = ByteArray(0)
    private var mode: Mode = Mode.StreamWrite
    private var running = false
    private var startReason: String = ""
    private var lastHeartbeatElapsedMs = 0L

    private val writeTick = object : Runnable {
        override fun run() {
            val nextHandler = synchronized(lock) {
                if (!running) return@synchronized null
                val activeTrack = track
                if (activeTrack == null) {
                    stopLocked("track_missing")
                    return@synchronized null
                }
                try {
                    val playState = activeTrack.playState
                    if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                        AppLogger.w(
                            TAG,
                            "AudioTrack keepalive playState repaired: mode=${mode.logName} playState=$playState"
                        )
                        activeTrack.play()
                    }
                    if (mode == Mode.StreamWrite) {
                        activeTrack.write(keepAliveBuffer, 0, keepAliveBuffer.size, AudioTrack.WRITE_BLOCKING)
                    }
                    UsbAudioEngine.nativePumpUsbEventsFromKeepAlive()
                    maybeLogHeartbeatLocked(activeTrack)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "AudioTrack mute keepalive write failed", t)
                    stopLocked("write_failed")
                    return@synchronized null
                }
                handler
            }
            nextHandler?.postDelayed(this, WRITE_INTERVAL_MS)
        }
    }

    fun start(reason: String) {
        synchronized(lock) {
            if (running) return
            val staticTrack = createStaticLoopTrackLocked()
            val created = staticTrack ?: createStreamTrackLocked()
            if (created == null) {
                AppLogger.w(TAG, "USB system AudioTrack keepalive unavailable: reason=$reason")
                return
            }
            mode = if (staticTrack != null) Mode.StaticLoop else Mode.StreamWrite
            track = created
            startReason = reason
            try {
                created.play()
                running = true
                ensureHandlerLocked().post(writeTick)
                AppLogger.i(
                    TAG,
                    "USB system AudioTrack keepalive started: reason=$reason mode=${mode.logName} bytes=${keepAliveBuffer.size}"
                )
            } catch (t: Throwable) {
                AppLogger.w(TAG, "AudioTrack mute keepalive start failed: reason=$reason", t)
                releaseTrackLocked()
            }
        }
    }

    fun stop(reason: String) {
        synchronized(lock) {
            if (!running && track == null && thread == null) return
            stopLocked(reason)
        }
    }

    private fun createStaticLoopTrackLocked(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuffer
            .coerceAtLeast(STATIC_LOOP_BYTES)
            .coerceAtLeast(16)
        if (bufferSize <= 0) {
            AppLogger.w(TAG, "AudioTrack static keepalive unavailable: minBuffer=$minBuffer")
            return null
        }
        val buffer = ByteArray(bufferSize)
        fillKeepAliveBuffer(buffer)
        keepAliveBuffer = buffer
        val created = createAudioTrackLocked(AudioTrack.MODE_STATIC, bufferSize) ?: return null
        return try {
            val written = created.write(buffer, 0, buffer.size)
            val frameCount = written / BYTES_PER_MONO_16BIT_FRAME
            val loopResult = if (frameCount > 0) created.setLoopPoints(0, frameCount, -1) else -1
            if (written == buffer.size && loopResult == AudioTrack.SUCCESS) {
                created
            } else {
                AppLogger.w(
                    TAG,
                    "AudioTrack static keepalive rejected: written=$written expected=${buffer.size} frames=$frameCount loopResult=$loopResult"
                )
                runCatching { created.release() }
                null
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "AudioTrack static keepalive prime failed", t)
            runCatching { created.release() }
            null
        }
    }

    private fun createStreamTrackLocked(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuffer.coerceAtLeast(16)
        if (bufferSize <= 0) {
            AppLogger.w(TAG, "AudioTrack mute keepalive unavailable: minBuffer=$minBuffer")
            return null
        }
        keepAliveBuffer = ByteArray(bufferSize)
        fillKeepAliveBuffer(keepAliveBuffer)
        return createAudioTrackLocked(AudioTrack.MODE_STREAM, bufferSize)
    }

    private fun createAudioTrackLocked(transferMode: Int, bufferSize: Int): AudioTrack? {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(transferMode)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                    .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                    ?: run {
                        AppLogger.w(TAG, "AudioTrack mute keepalive builder returned uninitialized track")
                        null
                    }
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    android.media.AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    transferMode
                ).takeIf { it.state == AudioTrack.STATE_INITIALIZED }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "AudioTrack mute keepalive create failed", t)
            null
        }
    }

    private fun fillKeepAliveBuffer(buffer: ByteArray) {
        var positive = true
        var index = 0
        while (index + 1 < buffer.size) {
            val sample = if (positive) 1 else -1
            buffer[index] = (sample and 0xff).toByte()
            buffer[index + 1] = ((sample shr 8) and 0xff).toByte()
            positive = !positive
            index += BYTES_PER_MONO_16BIT_FRAME
        }
    }

    private fun maybeLogHeartbeatLocked(activeTrack: AudioTrack) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatElapsedMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatElapsedMs = now
        AppLogger.i(
            TAG,
            "USB system AudioTrack keepalive heartbeat: mode=${mode.logName} playState=${activeTrack.playState} bytes=${keepAliveBuffer.size}"
        )
    }

    private fun ensureHandlerLocked(): Handler {
        val existing = handler
        if (existing != null) return existing
        val newThread = HandlerThread(
            "UsbSystemAudioKeepAlive",
            Process.THREAD_PRIORITY_URGENT_AUDIO
        ).also { it.start() }
        thread = newThread
        return Handler(newThread.looper).also { handler = it }
    }

    private fun stopLocked(reason: String) {
        running = false
        handler?.removeCallbacksAndMessages(null)
        releaseTrackLocked()
        thread?.quitSafely()
        thread = null
        handler = null
        mode = Mode.StreamWrite
        lastHeartbeatElapsedMs = 0L
        val previousReason = startReason
        startReason = ""
        AppLogger.i(TAG, "USB system AudioTrack mute keepalive stopped: reason=$reason startReason=$previousReason")
    }

    private fun releaseTrackLocked() {
        val oldTrack = track ?: return
        track = null
        runCatching { oldTrack.pause() }
        runCatching { oldTrack.flush() }
        runCatching { oldTrack.release() }
    }

    private enum class Mode(val logName: String) {
        StaticLoop("static_loop_lsb"),
        StreamWrite("stream_write_lsb")
    }

    private companion object {
        private const val TAG = "UsbSystemAudioKeepAlive"
        private const val SAMPLE_RATE = 44_100
        private const val WRITE_INTERVAL_MS = 8L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val BYTES_PER_MONO_16BIT_FRAME = 2
        private const val STATIC_LOOP_BYTES = SAMPLE_RATE / 10 * BYTES_PER_MONO_16BIT_FRAME
    }
}
