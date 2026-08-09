package com.rawsmusic.separation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiSeparationJobPhase {
    IDLE,
    PREPARING,
    DECODING,
    LOADING_MODEL,
    SEPARATING,
    COMMITTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class AiSeparationJobProgress(
    val taskId: String = "",
    val modelId: String = "",
    val modelVersion: String = "",
    val modelName: String = "",
    val sourceName: String = "",
    val phase: AiSeparationJobPhase = AiSeparationJobPhase.IDLE,
    val processedFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val processedSegments: Int = 0,
    val totalSegments: Int = 0,
    val elapsedMs: Long = 0L,
    val realtimeFactor: Double = 0.0,
    val message: String = "",
    val resultId: String = "",
    val cancelRequested: Boolean = false,
) {
    val active: Boolean
        get() = phase in setOf(
            AiSeparationJobPhase.PREPARING,
            AiSeparationJobPhase.DECODING,
            AiSeparationJobPhase.LOADING_MODEL,
            AiSeparationJobPhase.SEPARATING,
            AiSeparationJobPhase.COMMITTING,
        )

    val fraction: Float
        get() = if (totalFrames > 0L) {
            (processedFrames.toDouble() / totalFrames).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

object AiSeparationJobProgressBus {
    private val lock = Any()
    private val mutable = MutableStateFlow(AiSeparationJobProgress())
    val state: StateFlow<AiSeparationJobProgress> = mutable.asStateFlow()

    fun begin(progress: AiSeparationJobProgress): Boolean = synchronized(lock) {
        if (mutable.value.active) return false
        mutable.value = progress.copy(cancelRequested = false)
        true
    }

    fun update(taskId: String, transform: (AiSeparationJobProgress) -> AiSeparationJobProgress) {
        synchronized(lock) {
            val current = mutable.value
            if (current.taskId != taskId) return
            val updated = transform(current)
            mutable.value = updated.copy(
                processedFrames = maxOf(current.processedFrames, updated.processedFrames),
                processedSegments = maxOf(current.processedSegments, updated.processedSegments),
                cancelRequested = current.cancelRequested || updated.cancelRequested,
            )
        }
    }

    fun requestCancel(taskId: String = mutable.value.taskId) {
        synchronized(lock) {
            val current = mutable.value
            if (current.active && current.taskId == taskId) {
                mutable.value = current.copy(cancelRequested = true, message = "正在取消 AI 分离")
            }
        }
    }

    fun isCancelRequested(taskId: String): Boolean = synchronized(lock) {
        val current = mutable.value
        current.taskId == taskId && current.cancelRequested
    }

    fun isModelActive(id: String, version: String): Boolean = synchronized(lock) {
        val current = mutable.value
        current.active && current.modelId == id && current.modelVersion == version
    }

    fun hasActiveTask(): Boolean = synchronized(lock) { mutable.value.active }

    fun clearCompleted() {
        synchronized(lock) {
            if (!mutable.value.active) mutable.value = AiSeparationJobProgress()
        }
    }
}
