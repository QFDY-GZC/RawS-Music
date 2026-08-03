package com.rawsmusic.core.common.spectrum

import android.content.Context
import android.os.SystemClock
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isDsdSourceFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Persistent offline spectrum analysis. It never shares the realtime visualizer cache. */
object AudioSpectrumAnalysisCache {
    private const val CACHE_VERSION = 4
    private const val PAYLOAD_VERSION = 2
    private const val MAGIC = 0x52534143 // RSAC
    private const val DIR = "audio_spectrum_v1"
    private const val MAX_PAYLOAD = 64 * 1024 * 1024
    private val mutex = Mutex()

    suspend fun loadOrAnalyze(
        context: Context,
        song: AudioFile,
        onProgress: (AudioSpectrumAnalysisProgress) -> Unit = {}
    ): AudioSpectrumAnalysis {
        require(song.path.isNotBlank()) { "Audio path is empty" }
        val identity = identityFor(song)
        read(cacheFile(context, identity), identity)?.let { return it }

        return mutex.withLock {
            read(cacheFile(context, identity), identity)?.let { return@withLock it }
            val result = analyze(song, onProgress)
            write(cacheFile(context, identity), identity, result)
            result
        }
    }

    private fun analyze(
        song: AudioFile,
        onProgress: (AudioSpectrumAnalysisProgress) -> Unit
    ): AudioSpectrumAnalysis {
        check(FFmpegBridge.isLoaded()) { "FFmpeg is not available" }
        check(AudioSpectrumNative.isAvailable()) { "Spectrum native library is not available" }

        val sourceRate = song.sampleRate.takeIf { it > 0 } ?: 0
        val analysisTargetRate = when {
            // Keep the complete range exposed by the spectrum view (0..384 kHz) while
            // avoiding the multi-megahertz PCM stream produced by native DSD decoding.
            song.isDsdSourceFile() -> 768_000
            sourceRate > 768_000 -> 768_000
            else -> 0
        }
        val decoder = FFmpegBridge.openDecoder(
            path = song.path,
            targetSampleRate = analysisTargetRate,
            bitsPerSample = 32,
            channels = 2
        )
        check(decoder != 0L) { "Unable to open audio decoder" }

        var nativeHandle = 0L
        try {
            val sampleRate = FFmpegBridge.getDecoderSampleRate(decoder).takeIf { it > 0 }
                ?: song.sampleRate.takeIf { it > 0 }
                ?: 44100
            val decoderChannels = FFmpegBridge.getDecoderChannels(decoder).takeIf { it > 0 } ?: 2
            val decoderDuration = FFmpegBridge.getDecoderDuration(decoder)
            val cueStartMs = song.cueOffsetMs.coerceAtLeast(0L)
            val durationMs = when {
                song.cueEndMs > cueStartMs -> song.cueEndMs - cueStartMs
                song.duration > 0L -> song.duration
                decoderDuration > cueStartMs -> decoderDuration - cueStartMs
                else -> 0L
            }
            if (cueStartMs > 0L) FFmpegBridge.seekDecoder(decoder, cueStartMs)

            // A 16K window is enough for the displayed 1024 bins. The native analyzer uses a
            // full-window hop for high-rate material, so DSD does not pay for overlapping FFTs.
            val fftSize = 16384
            android.util.Log.i(
                "AudioSpectrumAnalysis",
                "config sourceRate=$sourceRate targetRate=$analysisTargetRate " +
                    "decoderRate=$sampleRate fft=$fftSize dsd=${song.isDsdSourceFile()}",
            )
            nativeHandle = AudioSpectrumNative.create(
                sampleRate = sampleRate,
                channels = min(decoderChannels, 2),
                durationMs = durationMs.coerceAtLeast(1L),
                fftSize = fftSize
            )
            check(nativeHandle != 0L) { "Unable to create spectrum analyzer" }

            val frameBytes = max(1, min(decoderChannels, 2)) * 4
            val readBuffer = ByteArray(256 * 1024)
            var carry = ByteArray(0)
            var decodedSamples = 0L
            val targetSamples = if (durationMs > 0L) durationMs * sampleRate / 1000L else 0L
            var lastProgressAt = 0L
            var lastProgressFraction = -1f

            while (true) {
                val read = FFmpegBridge.decodeChunk(decoder, readBuffer, 0, readBuffer.size)
                if (read == -1) break
                check(read >= 0) { "Audio decoder failed: $read" }
                if (read == 0) continue

                val merged = ByteArray(carry.size + read)
                if (carry.isNotEmpty()) System.arraycopy(carry, 0, merged, 0, carry.size)
                System.arraycopy(readBuffer, 0, merged, carry.size, read)
                val aligned = merged.size - merged.size % frameBytes
                if (aligned > 0) {
                    val remaining = if (targetSamples > 0L) targetSamples - decodedSamples else Long.MAX_VALUE
                    val allowedBytes = min(aligned.toLong(), remaining * frameBytes).toInt()
                    val processBytes = allowedBytes - allowedBytes % frameBytes
                    if (processBytes > 0) {
                        val levels = AudioSpectrumNative.processS32Le(
                            nativeHandle,
                            merged,
                            0,
                            processBytes
                        )
                        decodedSamples += processBytes / frameBytes
                        val fraction = if (targetSamples > 0L) {
                            (decodedSamples.toFloat() / targetSamples).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (fraction >= 1f ||
                            now - lastProgressAt >= 80L ||
                            fraction - lastProgressFraction >= 0.01f
                        ) {
                            onProgress(
                                AudioSpectrumAnalysisProgress(
                                    fraction = fraction,
                                    leftDb = levels?.getOrNull(0) ?: -120f,
                                    rightDb = levels?.getOrNull(1) ?: -120f
                                )
                            )
                            lastProgressAt = now
                            lastProgressFraction = fraction
                        }
                    }
                }
                val remainder = aligned.coerceAtMost(merged.size)
                carry = merged.copyOfRange(remainder, merged.size)
                if (targetSamples > 0L && decodedSamples >= targetSamples) break
            }

            val payload = AudioSpectrumNative.finish(nativeHandle)
                ?: error("Spectrum analyzer returned no result")
            onProgress(AudioSpectrumAnalysisProgress(1f, -120f, -120f))
            return parsePayload(payload)
        } finally {
            AudioSpectrumNative.release(nativeHandle)
            FFmpegBridge.closeDecoder(decoder)
        }
    }

    private fun parsePayload(payload: ByteArray): AudioSpectrumAnalysis {
        require(payload.size >= 64) { "Spectrum payload is truncated" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x52534131 && buffer.int == PAYLOAD_VERSION) { "Unknown spectrum payload" }
        val sampleRate = buffer.int
        val channels = buffer.int
        val fftSize = buffer.int
        val averageBins = buffer.int
        val waterfallBins = buffer.int
        val waterfallFrames = buffer.int
        val durationMs = buffer.long
        require(sampleRate > 0 && fftSize >= 1024)
        require(averageBins in 1..8192)
        require(waterfallBins in 1..2048)
        require(waterfallFrames in 1..2400)

        val cutoffHz = buffer.float
        val cutoffConfidence = buffer.float
        val leftPeakDb = buffer.float
        val rightPeakDb = buffer.float
        val leftRmsDb = buffer.float
        val rightRmsDb = buffer.float
        val required = averageBins * 4L + waterfallFrames * 8L + waterfallFrames * waterfallBins * 2L
        require(required <= buffer.remaining().toLong()) { "Spectrum payload is truncated" }
        val average = FloatArray(averageBins) { buffer.float }
        val leftLevels = FloatArray(waterfallFrames) { buffer.float }
        val rightLevels = FloatArray(waterfallFrames) { buffer.float }
        val leftWaterfall = ByteArray(waterfallFrames * waterfallBins)
        val rightWaterfall = ByteArray(waterfallFrames * waterfallBins)
        buffer.get(leftWaterfall)
        buffer.get(rightWaterfall)
        return AudioSpectrumAnalysis(
            sampleRate = sampleRate,
            channels = channels,
            fftSize = fftSize,
            durationMs = durationMs,
            cutoffHz = cutoffHz,
            cutoffConfidence = cutoffConfidence,
            leftPeakDb = leftPeakDb,
            rightPeakDb = rightPeakDb,
            leftRmsDb = leftRmsDb,
            rightRmsDb = rightRmsDb,
            averageSpectrumDb = average,
            waterfallFrames = waterfallFrames,
            waterfallBins = waterfallBins,
            leftLevelsDb = leftLevels,
            rightLevelsDb = rightLevels,
            leftWaterfall = leftWaterfall,
            rightWaterfall = rightWaterfall
        )
    }

    private fun identityFor(song: AudioFile): String = buildString {
        append(song.path).append('|')
        append(song.fileSize).append('|')
        append(song.dateModified).append('|')
        append(song.sampleRate).append('|')
        append(song.duration).append('|')
        append(song.cueOffsetMs).append('|')
        append(song.cueEndMs).append('|')
        append(CACHE_VERSION)
    }

    private fun cacheFile(context: Context, identity: String): File {
        val directory = File(context.filesDir, DIR)
        if (!directory.exists()) directory.mkdirs()
        return File(directory, sha256(identity) + ".rsac")
    }

    private fun read(file: File, expectedIdentity: String): AudioSpectrumAnalysis? {
        if (!file.isFile || file.length() > MAX_PAYLOAD || file.length() < 32L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != CACHE_VERSION) return null
                if (input.readUTF() != expectedIdentity) return null
                val size = input.readInt()
                if (size <= 0 || size > MAX_PAYLOAD) return null
                parsePayload(ByteArray(size).also(input::readFully))
            }
        }.getOrNull()
    }

    private fun write(file: File, identity: String, analysis: AudioSpectrumAnalysis) {
        // Reuse the native binary payload layout by encoding the model on the Kotlin side.
        // This keeps the cache independent from Java serialization and easy to invalidate.
        val payload = encodePayload(analysis)
        runCatching {
            val temporary = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeUTF(identity)
                output.writeInt(payload.size)
                output.write(payload)
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun encodePayload(analysis: AudioSpectrumAnalysis): ByteArray {
        val size = 64 + analysis.averageSpectrumDb.size * 4 +
            analysis.waterfallFrames * 8 +
            analysis.leftWaterfall.size + analysis.rightWaterfall.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x52534131)
        buffer.putInt(PAYLOAD_VERSION)
        buffer.putInt(analysis.sampleRate)
        buffer.putInt(analysis.channels)
        buffer.putInt(analysis.fftSize)
        buffer.putInt(analysis.averageSpectrumDb.size)
        buffer.putInt(analysis.waterfallBins)
        buffer.putInt(analysis.waterfallFrames)
        buffer.putLong(analysis.durationMs)
        buffer.putFloat(analysis.cutoffHz)
        buffer.putFloat(analysis.cutoffConfidence)
        buffer.putFloat(analysis.leftPeakDb)
        buffer.putFloat(analysis.rightPeakDb)
        buffer.putFloat(analysis.leftRmsDb)
        buffer.putFloat(analysis.rightRmsDb)
        analysis.averageSpectrumDb.forEach(buffer::putFloat)
        analysis.leftLevelsDb.forEach(buffer::putFloat)
        analysis.rightLevelsDb.forEach(buffer::putFloat)
        buffer.put(analysis.leftWaterfall)
        buffer.put(analysis.rightWaterfall)
        return buffer.array()
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { append("%02x".format(it)) }
        }
    }
}
