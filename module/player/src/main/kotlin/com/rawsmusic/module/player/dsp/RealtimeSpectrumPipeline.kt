package com.rawsmusic.module.player.dsp

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Latest-frame realtime mono spectrum pipeline.
 *
 * The audio writer only copies one bounded PCM window into a reusable slot. Mono downmix, FFT, resampling, smoothing and UI delivery run on a dedicated thread at a stable
 * display cadence. No independent left/right transform is performed.
 */
class RealtimeSpectrumPipeline(
    private val onSpectrum: (FloatArray) -> Unit
) : Closeable {
    private data class FrameMeta(
        val byteCount: Int = 0,
        val channels: Int = 0,
        val sampleRate: Int = 0,
        val sampleEncoding: Int = 0,
        val validBitsPerSample: Int = 0
    )

    companion object {
        private const val FRAME_PERIOD_MS = 16L
        private const val MIN_SUBMIT_INTERVAL_NS = 8_000_000L
        private const val MAX_CAPTURE_BYTES = 160 * 1024
        private const val INITIAL_BUFFER_BYTES = 32 * 1024
    }

    private val frameLock = ReentrantLock()
    private val lifecycleLock = Any()
    private val executor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable ->
            Thread(runnable, "RawSMusic-Spectrum").apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
    ).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    private var writeBuffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var readBuffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var pendingMeta = FrameMeta()
    private var hasPendingFrame = false
    private val output = FloatArray(NativeStereoSpectrumAnalyzer.OUTPUT_SIZE)

    @Volatile
    private var active = false
    @Volatile
    private var playing = false
    @Volatile
    private var closed = false
    @Volatile
    private var lastSubmitNs = 0L
    private var ticker: ScheduledFuture<*>? = null

    fun setActive(value: Boolean) {
        if (closed) return
        synchronized(lifecycleLock) {
            if (active == value) return
            active = value
            if (value) {
                ticker?.cancel(false)
                frameLock.withLock {
                    hasPendingFrame = false
                    pendingMeta = FrameMeta()
                }
                lastSubmitNs = 0L
                executor.execute {
                    NativeStereoSpectrumAnalyzer.reset()
                    output.fill(0f)
                    onSpectrum(output.copyOf())
                }
                ticker = executor.scheduleAtFixedRate(
                    { tickOnce() },
                    FRAME_PERIOD_MS,
                    FRAME_PERIOD_MS,
                    TimeUnit.MILLISECONDS
                )
            } else {
                ticker?.cancel(false)
                ticker = null
                frameLock.withLock {
                    hasPendingFrame = false
                    pendingMeta = FrameMeta()
                }
                executor.execute {
                    NativeStereoSpectrumAnalyzer.reset()
                    output.fill(0f)
                    onSpectrum(output.copyOf())
                }
            }
        }
    }

    fun setPlaying(value: Boolean) {
        playing = value
    }

    fun submit(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        sampleEncoding: Int,
        validBitsPerSample: Int
    ) {
        if (!active || closed || read <= 0 || channels <= 0 || sampleRate <= 0) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastSubmitNs < MIN_SUBMIT_INTERVAL_NS) return
        if (!frameLock.tryLock()) return
        try {
            if (!active || closed) return
            val bytesPerSample = when (sampleEncoding) {
                NativeStereoSpectrumAnalyzer.PCM_S16_LE -> 2
                NativeStereoSpectrumAnalyzer.PCM_S24_PACKED_LE -> 3
                NativeStereoSpectrumAnalyzer.PCM_S32_LE,
                NativeStereoSpectrumAnalyzer.PCM_FLOAT32_LE -> 4
                else -> return
            }
            val frameBytes = (bytesPerSample * channels).coerceAtLeast(1)
            val safeRead = read.coerceAtMost(buffer.size)
            val alignedRead = safeRead - (safeRead % frameBytes)
            var copyBytes = alignedRead.coerceAtMost(MAX_CAPTURE_BYTES)
            copyBytes -= copyBytes % frameBytes
            if (copyBytes <= 0) return
            if (writeBuffer.size < copyBytes) {
                writeBuffer = ByteArray(copyBytes.coerceAtMost(MAX_CAPTURE_BYTES))
            }
            val sourceOffset = (alignedRead - copyBytes).coerceAtLeast(0)
            System.arraycopy(buffer, sourceOffset, writeBuffer, 0, copyBytes)
            pendingMeta = FrameMeta(
                byteCount = copyBytes,
                channels = channels,
                sampleRate = sampleRate,
                sampleEncoding = sampleEncoding,
                validBitsPerSample = validBitsPerSample
            )
            hasPendingFrame = true
            lastSubmitNs = now
        } finally {
            frameLock.unlock()
        }
    }

    private fun tickOnce() {
        if (!active || closed) return

        var meta = FrameMeta()
        var frameAvailable = false
        frameLock.withLock {
            if (hasPendingFrame) {
                val swap = readBuffer
                readBuffer = writeBuffer
                writeBuffer = swap
                meta = pendingMeta
                pendingMeta = FrameMeta()
                hasPendingFrame = false
                frameAvailable = true
            }
        }

        val updated = if (frameAvailable) {
            NativeStereoSpectrumAnalyzer.analyze(
                buffer = readBuffer,
                read = meta.byteCount,
                channels = meta.channels,
                sampleRate = meta.sampleRate,
                sampleEncoding = meta.sampleEncoding,
                validBitsPerSample = meta.validBitsPerSample,
                output = output
            )
        } else {
            NativeStereoSpectrumAnalyzer.tick(paused = !playing, output = output)
        }
        if (updated && active && !closed) {
            onSpectrum(output.copyOf())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lifecycleLock) {
            active = false
            ticker?.cancel(false)
            ticker = null
        }
        executor.shutdownNow()
        NativeStereoSpectrumAnalyzer.reset()
    }
}
