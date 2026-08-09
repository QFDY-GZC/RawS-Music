package com.rawsmusic.separation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiSeparationDownloadPhase {
    IDLE,
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AiSeparationDownloadProgress(
    val modelId: String = "",
    val modelVersion: String = "",
    val modelName: String = "",
    val phase: AiSeparationDownloadPhase = AiSeparationDownloadPhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String = "",
) {
    val active: Boolean
        get() = phase in setOf(
            AiSeparationDownloadPhase.PREPARING,
            AiSeparationDownloadPhase.DOWNLOADING,
            AiSeparationDownloadPhase.VERIFYING,
            AiSeparationDownloadPhase.INSTALLING,
        )
}

object AiSeparationProgressBus {
    private val mutable = MutableStateFlow(AiSeparationDownloadProgress())
    val state: StateFlow<AiSeparationDownloadProgress> = mutable.asStateFlow()

    fun publish(progress: AiSeparationDownloadProgress) {
        mutable.value = progress
    }

    fun clearCompleted() {
        if (!mutable.value.active) mutable.value = AiSeparationDownloadProgress()
    }
}
