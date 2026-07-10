package com.rawsmusic.core.common.utils

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 码率统一归一化工具。
 *
 * 项目内部统一使用 bps（bits per second）。
 * 外部输入可能来自 MediaStore/FFmpeg(bps) 或 TagLib(kbps)，这里统一清洗。
 * UI 显示统一使用 formatKbps()，避免把 kbps/bps 或异常估算值直接显示出来。
 */
object BitrateNormalizer {
    private const val MIN_AUDIO_BPS = 1_000L
    private const val MAX_GENERIC_AUDIO_BPS = 96_000_000L
    private const val MAX_LOSSY_AUDIO_BPS = 1_500_000L
    private const val MAX_MP3_AUDIO_BPS = 640_000L
    private const val MAX_TRUSTED_DURATION_MS = 24L * 60L * 60L * 1000L

    /**
     * 将可能是 kbps 或 bps 的原始码率归一化为 bps。
     *
     * 策略：
     * 1. 先用 raw 与 raw*1000 判断单位；
     * 2. 文件大小 + 时长能得到合理平均码率时，用它纠正明显偏离的 TagLib/MediaStore 值；
     * 3. 对 MP3/AAC/Opus/Vorbis/WMA 等有损格式使用更窄的上限，避免把异常 format bit_rate
     *    或损坏缓存显示成 78787 kbps 这类不可能的值；
     * 4. DSD/PCM/无损格式保留更高上限，不影响 DSD256/512 或高采样率 PCM 显示。
     */
    fun toBps(
        rawBitrate: Int,
        durationMs: Long = 0L,
        fileSizeBytes: Long = 0L,
        codecName: String = "",
        formatName: String = "",
        filePath: String = ""
    ): Int {
        val maxAllowed = maxAllowedBps(codecName, formatName, filePath)
        val estimated = estimateFromFileLong(durationMs, fileSizeBytes, maxAllowed)
        if (rawBitrate <= 0) return sanitize(estimated, maxAllowed)

        val raw = rawBitrate.toLong()
        val asBps = raw
        val asKbpsToBps = raw * 1000L

        val candidates = longArrayOf(asBps, asKbpsToBps)
            .filter { it in MIN_AUDIO_BPS..maxAllowed }

        val unitNormalized = when {
            estimated > 0L && candidates.isNotEmpty() -> candidates.minBy { distanceRatio(it, estimated) }
            estimated > 0L -> estimated
            candidates.isNotEmpty() -> {
                if (raw < 10_000L && asKbpsToBps in MIN_AUDIO_BPS..maxAllowed) asKbpsToBps
                else candidates.first()
            }
            else -> 0L
        }

        val corrected = if (estimated > 0L && unitNormalized > 0L) {
            val drift = distanceRatio(unitNormalized, estimated)
            // TagLib on lossy files often exposes nominal/low core bitrate (56/112 kbps),
            // and some platform parsers expose an absurd container bit_rate.  When the
            // full-file average is sane and the stored value is far away, prefer the average.
            if (drift > 0.38) estimated else unitNormalized
        } else {
            unitNormalized
        }

        return sanitize(corrected, maxAllowed)
    }

    /** 格式化为 "xxx kbps" 字符串。 */
    fun formatKbps(
        rawBitrate: Int,
        durationMs: Long = 0L,
        fileSizeBytes: Long = 0L,
        codecName: String = "",
        formatName: String = "",
        filePath: String = ""
    ): String {
        val bps = toBps(
            rawBitrate = rawBitrate,
            durationMs = durationMs,
            fileSizeBytes = fileSizeBytes,
            codecName = codecName,
            formatName = formatName,
            filePath = filePath
        )
        if (bps <= 0) return "未知"
        return "${(bps / 1000.0).roundToInt()} kbps"
    }

    fun isSaneForAudio(
        rawBitrate: Int,
        durationMs: Long = 0L,
        fileSizeBytes: Long = 0L,
        codecName: String = "",
        formatName: String = "",
        filePath: String = ""
    ): Boolean = toBps(rawBitrate, durationMs, fileSizeBytes, codecName, formatName, filePath) > 0

    private fun estimateFromFileLong(durationMs: Long, fileSizeBytes: Long, maxAllowed: Long): Long {
        if (durationMs <= 0L || durationMs > MAX_TRUSTED_DURATION_MS || fileSizeBytes <= 0L) return 0L
        val seconds = durationMs / 1000.0
        if (seconds <= 0.0) return 0L
        val estimated = (fileSizeBytes * 8.0 / seconds).toLong()
        return if (estimated in MIN_AUDIO_BPS..maxAllowed) estimated else 0L
    }

    private fun distanceRatio(value: Long, target: Long): Double {
        if (value <= 0L || target <= 0L) return Double.MAX_VALUE
        return abs(value - target).toDouble() / target.toDouble()
    }

    private fun sanitize(value: Long, maxAllowed: Long): Int {
        if (value <= 0L) return 0
        if (value !in MIN_AUDIO_BPS..maxAllowed) return 0
        return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun maxAllowedBps(codecName: String, formatName: String, filePath: String): Long {
        val text = listOf(codecName, formatName, filePath.substringAfterLast('.', ""))
            .joinToString(" ")
            .lowercase(Locale.US)
        return when {
            text.contains("dsd") || text.contains("dsf") || text.contains("dff") -> MAX_GENERIC_AUDIO_BPS
            text.contains("pcm") || text.contains("wav") || text.contains("aiff") ||
                text.contains("flac") || text.contains("alac") || text.contains("ape") ||
                text.contains("tak") || text.contains("tta") || text.contains("wv") -> MAX_GENERIC_AUDIO_BPS
            text.contains("mp3") || text.contains("mpeg audio") -> MAX_MP3_AUDIO_BPS
            text.contains("aac") || text.contains("m4a") || text.contains("mp4") ||
                text.contains("opus") || text.contains("vorbis") || text.contains("ogg") ||
                text.contains("wma") || text.contains("amr") -> MAX_LOSSY_AUDIO_BPS
            else -> MAX_GENERIC_AUDIO_BPS
        }
    }
}
