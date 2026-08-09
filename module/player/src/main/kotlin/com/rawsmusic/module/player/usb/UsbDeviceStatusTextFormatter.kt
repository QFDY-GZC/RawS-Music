package com.rawsmusic.module.player.usb

import java.util.Locale

/** Pure text formatting for the USB status surface; it owns no engine or controller state. */
object UsbDeviceStatusTextFormatter {
    fun formatVendorProductId(vendorId: Int, productId: Int): String =
        if (vendorId > 0 || productId > 0) {
            "VID ${vendorId.toString(16).uppercase().padStart(4, '0')} / " +
                "PID ${productId.toString(16).uppercase().padStart(4, '0')}"
        } else {
            "未知"
        }

    fun formatDsdBitRateHz(rateHz: Int): String {
        if (rateHz <= 0) return "unknown"
        return if (rateHz >= 1_000_000) {
            String.format(Locale.US, "%.4f MHz", rateHz / 1_000_000.0)
        } else {
            "$rateHz Hz"
        }
    }

    fun buildDsdFormatText(
        multiplier: Int,
        rateHz: Int,
        channels: Int = 0,
        includeChannels: Boolean = true,
    ): String = buildString {
        append("DSD$multiplier")
        append(" / 1bit")
        val rateText = formatDsdBitRateHz(rateHz)
        if (rateText != "unknown") append(" / $rateText")
        if (includeChannels && channels > 0) append(" / ${channels}ch")
    }

    fun buildDsdInfoText(
        sourceIsDsd: Boolean,
        dsdMode: UsbDsdModeConfig?,
        dsdRateHz: Int,
    ): String {
        if (dsdMode == null) return "关闭"
        val modeText = buildDsdFormatText(
            multiplier = dsdMode.multiplier,
            rateHz = dsdRateHz,
            includeChannels = false,
        )
        return if (sourceIsDsd) "DSD源直通：$modeText" else "PCM→DSD：$modeText"
    }

    fun buildOutputChainText(
        sourceIsDsd: Boolean,
        dsdMode: UsbDsdModeConfig?,
        bitPerfect: Boolean,
        needsPcmAdapter: Boolean,
    ): String = when {
        sourceIsDsd && dsdMode != null -> "DSD源直通 → DAC"
        dsdMode != null -> "PCM → DSD${dsdMode.multiplier} → DAC"
        bitPerfect -> "PCM 直通 → DAC"
        sourceIsDsd -> "DSD → PCM 解码 → DAC"
        needsPcmAdapter -> "PCM → 重采样/格式适配 → DAC"
        else -> "PCM 直通 → DAC"
    }
}
