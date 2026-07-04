package com.rawsmusic.core.common.utils

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 码率统一归一化工具。
 *
 * 项目内部统一使用 bps（bits per second）。
 * 所有外部输入（MediaStore、TagLib、FFmpeg）都经过此工具归一化。
 * UI 显示统一使用 formatKbps()。
 */
object BitrateNormalizer {

    /**
     * 将可能是 kbps 或 bps 的原始码率归一化为 bps。
     *
     * 策略：用 文件大小 + 时长 估算真实 bps，再比较 raw 和 raw*1000 哪个更接近。
     */
    fun toBps(
        rawBitrate: Int,
        durationMs: Long = 0L,
        fileSizeBytes: Long = 0L
    ): Int {
        if (rawBitrate <= 0) return estimateFromFile(durationMs, fileSizeBytes)

        val raw = rawBitrate.toLong()
        val asBps = raw
        val asKbpsToBps = raw * 1000L
        val estimated = estimateFromFileLong(durationMs, fileSizeBytes)

        if (estimated > 0L) {
            val bpsScore = distanceRatio(asBps, estimated)
            val kbpsScore = distanceRatio(asKbpsToBps, estimated)
            return sanitize(if (kbpsScore < bpsScore) asKbpsToBps else asBps)
        }

        // 没有文件大小/时长可参考时，用保守启发式
        return sanitize(if (raw < 10_000L) raw * 1000L else raw)
    }

    /**
     * 格式化为 "xxx kbps" 字符串。
     */
    fun formatKbps(
        rawBitrate: Int,
        durationMs: Long = 0L,
        fileSizeBytes: Long = 0L
    ): String {
        val bps = toBps(rawBitrate, durationMs, fileSizeBytes)
        if (bps <= 0) return "未知"
        return "${(bps / 1000.0).roundToInt()} kbps"
    }

    private fun estimateFromFile(durationMs: Long, fileSizeBytes: Long): Int {
        return sanitize(estimateFromFileLong(durationMs, fileSizeBytes))
    }

    private fun estimateFromFileLong(durationMs: Long, fileSizeBytes: Long): Long {
        if (durationMs <= 0L || fileSizeBytes <= 0L) return 0L
        val seconds = durationMs / 1000.0
        if (seconds <= 0.0) return 0L
        return (fileSizeBytes * 8.0 / seconds).toLong()
    }

    private fun distanceRatio(value: Long, target: Long): Double {
        if (value <= 0L || target <= 0L) return Double.MAX_VALUE
        return abs(value - target).toDouble() / target.toDouble()
    }

    private fun sanitize(value: Long): Int {
        if (value <= 0L) return 0
        return value.coerceIn(1_000L, 100_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
