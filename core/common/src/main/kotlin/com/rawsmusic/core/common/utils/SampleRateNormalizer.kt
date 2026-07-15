package com.rawsmusic.core.common.utils

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Source sample-rate normalization for UI and scanner metadata.
 *
 * FFmpeg/TagLib normally expose the real source rate, but HE-AAC/SBR files may
 * report the AAC core rate (22.05/24 kHz) while the decoder actually outputs
 * 44.1/48 kHz. Keep the raw value when it is clearly valid, but prefer the
 * decoder-effective rate when available and use a narrow AAC-family fallback for
 * the common SBR half-rate case.
 */
object SampleRateNormalizer {
    private val commonRates = intArrayOf(
        8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000,
        44_100, 48_000, 64_000, 88_200, 96_000, 176_400, 192_000,
        352_800, 384_000, 705_600, 768_000
    )

    fun normalize(
        rawSampleRate: Int,
        codecName: String = "",
        formatName: String = "",
        filePath: String = "",
        effectiveSampleRate: Int = 0
    ): Int {
        val raw = sanitize(rawSampleRate)
        val effective = sanitize(effectiveSampleRate)
        if (effective > 0 && (raw <= 0 || effective >= raw)) return effective
        if (raw <= 0) return 0

        // HE-AAC/SBR often stores a 22.05/24 kHz AAC core but decodes at 44.1/48 kHz.
        // Do not apply this to MP3/Vorbis/Opus: 22.05 kHz can be the real source rate there.
        if (isAacFamily(codecName, formatName, filePath)) {
            when (raw) {
                22_050 -> return 44_100
                24_000 -> return 48_000
            }
        }
        return raw
    }

    fun formatKhz(
        sampleRate: Int,
        codecName: String = "",
        formatName: String = "",
        filePath: String = "",
        uppercase: Boolean = false
    ): String {
        val normalized = normalize(
            rawSampleRate = sampleRate,
            codecName = codecName,
            formatName = formatName,
            filePath = filePath
        )
        if (normalized <= 0) return ""
        val khz = normalized / 1000.0
        val value = if (khz == khz.toLong().toDouble()) {
            khz.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", khz)
        }
        return if (uppercase) "$value KHZ" else "$value kHz"
    }

    private fun sanitize(value: Int): Int {
        if (value <= 0) return 0
        if (value in 7_000..768_000) return value
        // Some parsers accidentally return kHz as an integer. Keep the recovery narrow.
        if (value in 8..768) {
            val hz = value * 1000
            if (hz in 7_000..768_000) return hz
        }
        return 0
    }

    private fun isAacFamily(codecName: String, formatName: String, filePath: String): Boolean {
        val text = listOf(codecName, formatName, filePath.substringAfterLast('.', ""))
            .joinToString(" ")
            .lowercase(Locale.US)
        return text.contains("aac") || text.contains("mp4") || text.contains("m4a") || text.contains("m4b")
    }

    @Suppress("unused")
    private fun nearestCommonRate(value: Int): Int {
        if (value <= 0) return 0
        var best = value
        var bestDistance = Int.MAX_VALUE
        for (rate in commonRates) {
            val distance = kotlin.math.abs(rate - value)
            if (distance < bestDistance) {
                best = rate
                bestDistance = distance
            }
        }
        return if (bestDistance <= (best * 0.006f).roundToInt().coerceAtLeast(1)) best else value
    }
}
