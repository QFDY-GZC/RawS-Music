package com.rawsmusic.ai.melody

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal data class RmvpeFeatureChunk(
    /** RMVPE input layout [mel, frame], flattened in row-major order. */
    val mel: FloatArray,
    val frameCount: Int,
    val rms: FloatArray,
)

/** Exact mobile-side counterpart of the RMVPE log-mel frontend used by the reference graph. */
internal class RmvpeMelFeatureExtractor(
    private val contract: AiMelodyModelDescriptor,
) {
    private val window = FloatArray(contract.fftSize) { index ->
        // torch.hann_window(periodic=true)
        (0.5 - 0.5 * cos(2.0 * PI * index / contract.fftSize)).toFloat()
    }
    private val filters = buildHtkSlaneyMelFilters(contract)
    private val real = DoubleArray(contract.fftSize)
    private val imaginary = DoubleArray(contract.fftSize)
    private val magnitude = DoubleArray(contract.fftSize / 2 + 1)

    fun extract(
        pcm16Mono: File,
        totalSamples: Long,
        startFrame: Int,
        frameCount: Int,
    ): RmvpeFeatureChunk {
        require(pcm16Mono.isFile && pcm16Mono.length() >= totalSamples * 2L)
        require(totalSamples > 1L && totalSamples <= Int.MAX_VALUE.toLong()) {
            "RMVPE PCM 样本数量超出当前实现范围"
        }
        require(startFrame >= 0 && frameCount > 0)
        val total = totalSamples.toInt()
        val firstSample = startFrame.toLong() * contract.hopLength - contract.fftSize / 2L
        val lastFrame = startFrame + frameCount - 1L
        val lastExclusive = lastFrame * contract.hopLength - contract.fftSize / 2L + contract.fftSize
        val readStart = max(0L, firstSample).coerceAtMost(totalSamples - 1L).toInt()
        val readEndExclusive = min(totalSamples, max(1L, lastExclusive)).toInt().coerceAtLeast(readStart + 1)
        val source = readPcm16(pcm16Mono, readStart, readEndExclusive)
        val mel = FloatArray(contract.melBins * frameCount)
        val rms = FloatArray(frameCount)

        repeat(frameCount) { localFrame ->
            val globalFrame = startFrame + localFrame
            val center = globalFrame * contract.hopLength
            val frameStart = center - contract.fftSize / 2
            java.util.Arrays.fill(real, 0.0)
            java.util.Arrays.fill(imaginary, 0.0)
            var energy = 0.0
            repeat(contract.fftSize) { index ->
                val globalSample = reflectIndex(frameStart + index, total)
                val sample = source[globalSample - readStart].toDouble()
                energy += sample * sample
                real[index] = sample * window[index]
            }
            rms[localFrame] = sqrt(energy / contract.fftSize).toFloat()
            fft(real, imaginary)
            repeat(magnitude.size) { bin ->
                magnitude[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
            repeat(contract.melBins) { melBin ->
                var value = 0.0
                val filter = filters[melBin]
                var cursor = 0
                while (cursor < filter.indices.size) {
                    value += magnitude[filter.indices[cursor]] * filter.weights[cursor]
                    cursor++
                }
                mel[melBin * frameCount + localFrame] = ln(max(value, LOG_CLAMP)).toFloat()
            }
        }
        return RmvpeFeatureChunk(mel = mel, frameCount = frameCount, rms = rms)
    }


    fun extract(
        pcmMono: FloatArray,
        startFrame: Int,
        frameCount: Int,
    ): RmvpeFeatureChunk {
        require(pcmMono.size > 1) { "RMVPE PCM 样本数量不足" }
        require(startFrame >= 0 && frameCount > 0)
        val total = pcmMono.size
        val mel = FloatArray(contract.melBins * frameCount)
        val rms = FloatArray(frameCount)
        repeat(frameCount) { localFrame ->
            val globalFrame = startFrame + localFrame
            val center = globalFrame * contract.hopLength
            val frameStart = center - contract.fftSize / 2
            java.util.Arrays.fill(real, 0.0)
            java.util.Arrays.fill(imaginary, 0.0)
            var energy = 0.0
            repeat(contract.fftSize) { index ->
                val globalSample = reflectIndex(frameStart + index, total)
                val sample = pcmMono[globalSample].toDouble()
                energy += sample * sample
                real[index] = sample * window[index]
            }
            rms[localFrame] = sqrt(energy / contract.fftSize).toFloat()
            fft(real, imaginary)
            repeat(magnitude.size) { bin ->
                magnitude[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
            repeat(contract.melBins) { melBin ->
                var value = 0.0
                val filter = filters[melBin]
                var cursor = 0
                while (cursor < filter.indices.size) {
                    value += magnitude[filter.indices[cursor]] * filter.weights[cursor]
                    cursor++
                }
                mel[melBin * frameCount + localFrame] = ln(max(value, LOG_CLAMP)).toFloat()
            }
        }
        return RmvpeFeatureChunk(mel = mel, frameCount = frameCount, rms = rms)
    }

    private fun readPcm16(file: File, startSample: Int, endSampleExclusive: Int): FloatArray {
        val sampleCount = endSampleExclusive - startSample
        val bytes = ByteArray(sampleCount * 2)
        RandomAccessFile(file, "r").use { input ->
            input.seek(startSample.toLong() * 2L)
            input.readFully(bytes)
        }
        return FloatArray(sampleCount) { index ->
            val byte = index * 2
            val value = (bytes[byte].toInt() and 0xff) or (bytes[byte + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
    }

    private fun reflectIndex(index: Int, length: Int): Int {
        if (length <= 1) return 0
        var reflected = index
        while (reflected < 0 || reflected >= length) {
            reflected = if (reflected < 0) -reflected else 2 * length - 2 - reflected
        }
        return reflected
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var j = 0
        for (i in 1 until real.size) {
            var bit = real.size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = real[i]
                real[i] = real[j]
                real[j] = r
            }
        }
        var length = 2
        while (length <= real.size) {
            val angle = -2.0 * PI / length
            val phaseReal = cos(angle)
            val phaseImaginary = kotlin.math.sin(angle)
            var block = 0
            while (block < real.size) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                repeat(length / 2) { offset ->
                    val even = block + offset
                    val odd = even + length / 2
                    val transformedReal = currentReal * real[odd] - currentImaginary * imaginary[odd]
                    val transformedImaginary = currentReal * imaginary[odd] + currentImaginary * real[odd]
                    real[odd] = real[even] - transformedReal
                    imaginary[odd] = imaginary[even] - transformedImaginary
                    real[even] += transformedReal
                    imaginary[even] += transformedImaginary
                    val nextReal = currentReal * phaseReal - currentImaginary * phaseImaginary
                    currentImaginary = currentReal * phaseImaginary + currentImaginary * phaseReal
                    currentReal = nextReal
                }
                block += length
            }
            length = length shl 1
        }
    }

    private data class SparseFilter(
        val indices: IntArray,
        val weights: DoubleArray,
    )

    private fun buildHtkSlaneyMelFilters(contract: AiMelodyModelDescriptor): Array<SparseFilter> {
        val minMel = hzToHtkMel(contract.melMinHz.toDouble())
        val maxMel = hzToHtkMel(contract.melMaxHz.toDouble())
        val melPoints = DoubleArray(contract.melBins + 2) { index ->
            minMel + (maxMel - minMel) * index / (contract.melBins + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHtkHz(melPoints[it]) }
        val fftFreqs = DoubleArray(contract.fftSize / 2 + 1) { bin ->
            bin.toDouble() * contract.sampleRate / contract.fftSize
        }
        return Array(contract.melBins) { mel ->
            val lower = hzPoints[mel]
            val center = hzPoints[mel + 1]
            val upper = hzPoints[mel + 2]
            val normalization = 2.0 / (upper - lower).coerceAtLeast(1.0e-12)
            val indexList = ArrayList<Int>()
            val weightList = ArrayList<Double>()
            fftFreqs.forEachIndexed { bin, frequency ->
                val lowerSlope = (frequency - lower) / (center - lower).coerceAtLeast(1.0e-12)
                val upperSlope = (upper - frequency) / (upper - center).coerceAtLeast(1.0e-12)
                val weight = max(0.0, min(lowerSlope, upperSlope)) * normalization
                if (weight > 0.0) {
                    indexList += bin
                    weightList += weight
                }
            }
            SparseFilter(indexList.toIntArray(), weightList.toDoubleArray())
        }
    }

    private fun hzToHtkMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHtkHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    companion object {
        private const val LOG_CLAMP = 1.0e-5
    }
}
