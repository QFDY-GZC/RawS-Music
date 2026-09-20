package com.rawsmusic.module.player.devicecontrol.usb

import org.json.JSONObject

sealed interface UsbStandardControlWriteResult {
    data class Applied(val current: Double?) : UsbStandardControlWriteResult
    data class Rejected(val reason: String) : UsbStandardControlWriteResult
    data class Failed(val reason: String, val transportCode: Int? = null) : UsbStandardControlWriteResult
}

internal object UsbStandardControlWriteParser {
    fun parse(json: String?): UsbStandardControlWriteResult {
        if (json.isNullOrBlank()) return UsbStandardControlWriteResult.Failed("empty_native_write_result")
        return runCatching {
            val root = JSONObject(json)
            val status = root.optString("status")
            val reason = root.optString("reason").ifBlank { status.ifBlank { "unknown" } }
            val current = if (root.isNull("current")) null else root.optDouble("current").takeIf { it.isFinite() }
            val transportCode = root.optInt("transportCode")
            when (status) {
                "applied" -> UsbStandardControlWriteResult.Applied(current)
                "read_only", "invalid_address", "unsupported_control", "invalid_value" ->
                    UsbStandardControlWriteResult.Rejected(reason)
                else -> UsbStandardControlWriteResult.Failed(reason, transportCode)
            }
        }.getOrElse {
            UsbStandardControlWriteResult.Failed("invalid_native_write_result:${it.javaClass.simpleName}")
        }
    }
}
