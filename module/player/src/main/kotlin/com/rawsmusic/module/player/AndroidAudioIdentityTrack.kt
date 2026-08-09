package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger

/**
 * HiBy-compatible Android media carrier for native USB playback.
 *
 * The real program audio remains exclusively on the native USB UAC path. This helper mirrors the
 * "keep background alive" path found in HiBy Music 4.3.10:
 *
 * - 44.1 kHz, stereo, PCM16, STREAM_MUSIC / USAGE_MEDIA;
 * - AudioTrack.MODE_STREAM with exactly the platform minimum buffer (minimum 16 bytes);
 * - no preferred-device routing;
 * - non-Huawei devices fill every byte with 0x01; Huawei devices use 0x00;
 * - one blocking three-argument AudioTrack.write() of the whole buffer every 20 ms;
 * - lifecycle is owned by the native USB stream-start/stream-end callbacks in PlayerController.
 *
 * This intentionally does not use raw_android_audio_identity.cpp. Keeping the old native bridge
 * compiled but unused makes this a single-variable carrier experiment and avoids changing USB,
 * decoder, transfer, or event-thread ownership in the same step.
 */
internal class AndroidAudioIdentityTrack(context: Context) {
    @Suppress("UNUSED_PARAMETER")
    private val appContext = context.applicationContext

    private val lock = Any()

    private var generation = 0L
    private var running = false
    private var worker: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var startReason = ""
    private var totalWrittenBytes = 0L
    private var writeCalls = 0L
    private var writeErrors = 0L
    private var lastHeartbeatElapsedMs = 0L
    private var bufferSizeBytes = 0
    private var fillByte = 0

    fun start(reason: String) {
        val threadToStart = synchronized(lock) {
            val existing = worker
            if (running && existing?.isAlive == true) {
                startReason = reason
                AppLogger.i(TAG, "HiBy USB carrier already running: reason=$reason ${statsStringLocked()}")
                return
            }

            generation += 1L
            val myGeneration = generation
            running = true
            startReason = reason
            totalWrittenBytes = 0L
            writeCalls = 0L
            writeErrors = 0L
            lastHeartbeatElapsedMs = 0L
            bufferSizeBytes = 0
            fillByte = if (isHuaweiDevice()) 0x00 else 0x01

            Thread(
                { runCarrier(myGeneration, reason) },
                WORKER_NAME,
            ).also { worker = it }
        }

        threadToStart.start()
    }

    fun stop(reason: String) {
        val snapshot = synchronized(lock) {
            val active = running || worker != null || audioTrack != null
            if (!active) return

            running = false
            generation += 1L
            startReason = ""

            val track = audioTrack
            audioTrack = null
            val thread = worker
            worker = null
            StopSnapshot(track, thread)
        }

        // Release from the caller as well as the worker's finally block. Releasing the AudioTrack
        // is the reliable way to unblock a three-argument blocking write during stop/pause.
        releaseTrack(snapshot.track, "caller_stop:$reason")
        snapshot.thread?.interrupt()
        // HiBy disposes the periodic writer and releases AudioTrack without waiting for a worker
        // join on the caller/main thread. The generation token prevents a late old worker from
        // touching a newly started carrier.
        AppLogger.i(TAG, "HiBy USB carrier stopped: reason=$reason")
    }

    /** HiBy releases the mute-data AudioTrack on pause rather than pausing it in place. */
    fun pause(reason: String) = stop("pause:$reason")

    /** Resume is an explicit fresh start; PlayerController only calls it for a live USB stream. */
    fun resume(reason: String) = start("resume:$reason")

    fun isRunning(): Boolean = synchronized(lock) {
        running && worker?.isAlive == true && audioTrack?.state == AudioTrack.STATE_INITIALIZED
    }

    fun statsString(): String = synchronized(lock) { statsStringLocked() }

    private fun runCarrier(myGeneration: Long, reason: String) {
        var track: AudioTrack? = null
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                AppLogger.w(TAG, "HiBy USB carrier unavailable: minBuffer=$minBuffer")
                return
            }

            val actualBufferSize = minBuffer.coerceAtLeast(MIN_BUFFER_BYTES)
            val data = ByteArray(actualBufferSize) { fillByte.toByte() }
            val created = createTrack(actualBufferSize)
            track = created
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                AppLogger.w(TAG, "HiBy USB carrier AudioTrack not initialized: buffer=$actualBufferSize")
                return
            }

            val accepted = synchronized(lock) {
                if (!isGenerationActiveLocked(myGeneration)) {
                    false
                } else {
                    audioTrack = created
                    bufferSizeBytes = actualBufferSize
                    true
                }
            }
            if (!accepted) return

            created.play()
            AppLogger.i(
                TAG,
                "HiBy USB carrier started: reason=$reason sr=$SAMPLE_RATE ch=2 bits=16 " +
                    "buffer=$actualBufferSize fill=0x${fillByte.toString(16).padStart(2, '0')} " +
                    "periodMs=$WRITE_PERIOD_MS preferredDevice=none",
            )

            var nextWriteNanos = System.nanoTime() + WRITE_PERIOD_NANOS
            while (isGenerationActive(myGeneration)) {
                sleepUntil(nextWriteNanos)
                if (!isGenerationActive(myGeneration)) break

                val written = try {
                    // This is deliberately the legacy three-argument overload used by HiBy. For a
                    // streaming AudioTrack it is a blocking write; there is no WRITE_NON_BLOCKING.
                    @Suppress("DEPRECATION")
                    created.write(data, 0, data.size)
                } catch (t: Throwable) {
                    synchronized(lock) { writeErrors += 1L }
                    if (isGenerationActive(myGeneration)) {
                        AppLogger.w(TAG, "HiBy USB carrier write failed", t)
                    }
                    break
                }

                synchronized(lock) {
                    writeCalls += 1L
                    if (written > 0) {
                        totalWrittenBytes += written.toLong()
                    } else {
                        writeErrors += 1L
                    }
                    maybeLogHeartbeatLocked(created, written)
                }

                nextWriteNanos += WRITE_PERIOD_NANOS
                val now = System.nanoTime()
                if (nextWriteNanos < now - MAX_LATE_NANOS) {
                    // Do not perform a burst of queued writes after a long process suspension.
                    nextWriteNanos = now + WRITE_PERIOD_NANOS
                }
            }
        } catch (t: Throwable) {
            if (isGenerationActive(myGeneration)) {
                AppLogger.w(TAG, "HiBy USB carrier worker failed: reason=$reason", t)
            }
        } finally {
            synchronized(lock) {
                if (audioTrack === track) audioTrack = null
                if (worker === Thread.currentThread()) worker = null
                if (generation == myGeneration) running = false
            }
            releaseTrack(track, "worker_finally:$reason")
        }
    }

    private fun createTrack(bufferSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    private fun releaseTrack(track: AudioTrack?, reason: String) {
        if (track == null) return
        runCatching { track.stop() }
        runCatching { track.flush() }
        runCatching { track.release() }
        AppLogger.i(TAG, "HiBy USB carrier AudioTrack released: reason=$reason")
    }

    private fun isGenerationActive(myGeneration: Long): Boolean = synchronized(lock) {
        isGenerationActiveLocked(myGeneration)
    }

    private fun isGenerationActiveLocked(myGeneration: Long): Boolean =
        running && generation == myGeneration && worker === Thread.currentThread()

    private fun sleepUntil(targetNanos: Long) {
        while (true) {
            val remaining = targetNanos - System.nanoTime()
            if (remaining <= 0L) return
            val millis = remaining / 1_000_000L
            val nanos = (remaining % 1_000_000L).toInt()
            try {
                Thread.sleep(millis, nanos)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun maybeLogHeartbeatLocked(track: AudioTrack, written: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatElapsedMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatElapsedMs = now
        AppLogger.i(
            TAG,
            "HiBy USB carrier heartbeat: playState=${track.playState} written=$written ${statsStringLocked()}",
        )
    }

    private fun statsStringLocked(): String =
        "running=$running workerAlive=${worker?.isAlive == true} buffer=$bufferSizeBytes " +
            "fill=0x${fillByte.toString(16).padStart(2, '0')} calls=$writeCalls " +
            "bytes=$totalWrittenBytes errors=$writeErrors reason=$startReason"

    private fun isHuaweiDevice(): Boolean =
        Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true)

    private data class StopSnapshot(
        val track: AudioTrack?,
        val thread: Thread?,
    )

    private companion object {
        private const val TAG = "AndroidAudioIdentity"
        private const val WORKER_NAME = "HiByMuteData"
        private const val SAMPLE_RATE = 44_100
        private const val MIN_BUFFER_BYTES = 16
        private const val WRITE_PERIOD_MS = 20L
        private const val WRITE_PERIOD_NANOS = WRITE_PERIOD_MS * 1_000_000L
        private const val MAX_LATE_NANOS = WRITE_PERIOD_NANOS * 5L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
    }
}
