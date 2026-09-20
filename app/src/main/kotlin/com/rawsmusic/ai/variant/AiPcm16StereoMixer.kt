package com.rawsmusic.ai.variant

import com.rawsmusic.ai.instrument.Pcm16WaveWriter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

internal data class AiRawMixInput(
    val pcmFile: File,
    val gainDb: Float,
)

internal data class AiPcmMixResult(
    val outputFile: File,
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val peakBeforeNormalization: Float,
    val normalizationGainDb: Float,
)

/**
 * Two-pass linear PCM mixer. Pass one measures the exact summed peak; pass two writes one
 * globally scaled output. This avoids a per-block limiter changing the musical envelope.
 */
internal object AiPcm16StereoMixer {
    const val MIXER_VERSION = "pcm16-stereo-v1"
    private const val CHANNELS = 2
    private const val BLOCK_FRAMES = 4_096

    fun mix(
        inputs: List<AiRawMixInput>,
        outputWav: File,
        sampleRate: Int,
        frameCount: Long,
        outputCeilingDb: Float,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): AiPcmMixResult {
        require(inputs.isNotEmpty()) { "AI mix requires at least one input" }
        require(sampleRate > 0 && frameCount > 0L)
        require(outputCeilingDb.isFinite() && outputCeilingDb < 0f)
        inputs.forEach { input ->
            require(input.pcmFile.isFile) { "AI mix PCM input is missing" }
            require(input.pcmFile.length() % (CHANNELS * 2L) == 0L) { "AI mix PCM is misaligned" }
            require(input.gainDb.isFinite())
        }

        val gains = inputs.map { dbToGain(it.gainDb) }
        val peak = scanPeak(inputs, gains, frameCount, isCancelled) { fraction ->
            onProgress(fraction * 0.45f)
        }
        val ceiling = dbToGain(outputCeilingDb)
        val normalization = if (peak > ceiling && peak > 0f) ceiling / peak else 1f
        val normalizationDb = if (normalization >= 0.999999f) 0f else gainToDb(normalization)

        outputWav.parentFile?.mkdirs()
        outputWav.delete()
        writeMix(
            inputs = inputs,
            gains = gains,
            outputWav = outputWav,
            sampleRate = sampleRate,
            frameCount = frameCount,
            normalization = normalization,
            isCancelled = isCancelled,
        ) { fraction ->
            onProgress(0.45f + fraction * 0.55f)
        }
        onProgress(1f)
        return AiPcmMixResult(
            outputFile = outputWav,
            sampleRate = sampleRate,
            channels = CHANNELS,
            frameCount = frameCount,
            peakBeforeNormalization = peak,
            normalizationGainDb = normalizationDb,
        )
    }

    private fun scanPeak(
        inputs: List<AiRawMixInput>,
        gains: List<Float>,
        frameCount: Long,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Float {
        openInputs(inputs).useAll { streams ->
            var processed = 0L
            var peak = 0f
            val buffers = Array(streams.size) { ByteArray(BLOCK_FRAMES * CHANNELS * 2) }
            while (processed < frameCount) {
                check(!isCancelled()) { "AI media variant generation cancelled" }
                val frames = minOf(BLOCK_FRAMES.toLong(), frameCount - processed).toInt()
                val bytes = frames * CHANNELS * 2
                readZeroPadded(streams, buffers, bytes)
                for (sample in 0 until frames * CHANNELS) {
                    var sum = 0f
                    for (index in streams.indices) {
                        sum += readS16(buffers[index], sample * 2) * gains[index]
                    }
                    peak = maxOf(peak, abs(sum))
                }
                processed += frames
                onProgress(processed.toFloat() / frameCount)
            }
            return peak
        }
    }

    private fun writeMix(
        inputs: List<AiRawMixInput>,
        gains: List<Float>,
        outputWav: File,
        sampleRate: Int,
        frameCount: Long,
        normalization: Float,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        openInputs(inputs).useAll { streams ->
            Pcm16WaveWriter(outputWav, sampleRate, CHANNELS, frameCount).use { writer ->
                var processed = 0L
                val buffers = Array(streams.size) { ByteArray(BLOCK_FRAMES * CHANNELS * 2) }
                val mixed = FloatArray(BLOCK_FRAMES * CHANNELS)
                while (processed < frameCount) {
                    check(!isCancelled()) { "AI media variant generation cancelled" }
                    val frames = minOf(BLOCK_FRAMES.toLong(), frameCount - processed).toInt()
                    val bytes = frames * CHANNELS * 2
                    readZeroPadded(streams, buffers, bytes)
                    val sampleCount = frames * CHANNELS
                    for (sample in 0 until sampleCount) {
                        var sum = 0f
                        for (index in streams.indices) {
                            sum += readS16(buffers[index], sample * 2) * gains[index]
                        }
                        mixed[sample] = (sum * normalization).coerceIn(-1f, 1f)
                    }
                    writer.writeInterleaved(mixed, frames)
                    processed += frames
                    onProgress(processed.toFloat() / frameCount)
                }
            }
        }
    }

    private fun openInputs(inputs: List<AiRawMixInput>): List<BufferedInputStream> =
        inputs.map { BufferedInputStream(FileInputStream(it.pcmFile), 128 * 1024) }

    private inline fun <T> List<BufferedInputStream>.useAll(block: (List<BufferedInputStream>) -> T): T {
        try {
            return block(this)
        } finally {
            forEach { runCatching { it.close() } }
        }
    }

    private fun readZeroPadded(
        streams: List<BufferedInputStream>,
        buffers: Array<ByteArray>,
        byteCount: Int,
    ) {
        streams.indices.forEach { index ->
            val buffer = buffers[index]
            var offset = 0
            while (offset < byteCount) {
                val read = streams[index].read(buffer, offset, byteCount - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset < byteCount) buffer.fill(0, offset, byteCount)
        }
    }

    private fun readS16(bytes: ByteArray, offset: Int): Float {
        val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        return value.toShort() / 32768f
    }

    private fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    private fun gainToDb(gain: Float): Float = (20.0 * log10(gain.toDouble())).toFloat()
}
