package com.rawsmusic.module.scanner

data class ScanUiState(
    val isScanning: Boolean = false,
    val canCancel: Boolean = false,
    val pendingScan: Boolean = false,
    val reason: String = "",
    val stage: String = "",
    val message: String = "",
    val scanned: Int = 0,
    val total: Int = 0,
    val progress: Float = 0f,
    val cacheHits: Int = 0,
    val enrichedCount: Int = 0,
    val dbUpserted: Int = 0,
    val dbDeleted: Int = 0,
    val dbUnchanged: Int = 0,
    val found: Int = 0,
    val timeMs: Long = 0L,
    val error: String? = null
) {
    val progressPercent: Int get() = (progress * 100f).toInt().coerceIn(0, 100)

    companion object {
        fun idle() = ScanUiState()
        fun starting(
            reason: String,
            stage: String,
            message: String,
        ) = ScanUiState(
            isScanning = true, canCancel = true, reason = reason,
            stage = stage, message = message
        )
        fun cancelled(
            stage: String,
            message: String,
        ) = ScanUiState(
            isScanning = false, canCancel = false,
            stage = stage, message = message
        )
    }
}
