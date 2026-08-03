package com.rawsmusic.metadata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LibraryMetadataMatchPhase {
    IDLE,
    RUNNING,
    COMPLETED,
}

data class LibraryMetadataMatchProgress(
    val jobId: String = "",
    val phase: LibraryMetadataMatchPhase = LibraryMetadataMatchPhase.IDLE,
    val total: Int = 0,
    val processed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val completionToken: Long = 0L,
) {
    val remaining: Int
        get() = (total - processed).coerceAtLeast(0)

    val isRunning: Boolean
        get() = phase == LibraryMetadataMatchPhase.RUNNING
}

/**
 * In-process progress bridge between the foreground matcher and Compose chrome.
 * The service is not assigned to a remote process, so a StateFlow keeps updates cheap
 * and avoids sending one broadcast/notification update for every completed song.
 */
object LibraryMetadataMatchProgressBus {
    private val mutableState = MutableStateFlow(LibraryMetadataMatchProgress())
    val state: StateFlow<LibraryMetadataMatchProgress> = mutableState.asStateFlow()

    @Synchronized
    fun started(jobId: String, total: Int) {
        val completionToken = mutableState.value.completionToken
        mutableState.value = LibraryMetadataMatchProgress(
            jobId = jobId,
            phase = LibraryMetadataMatchPhase.RUNNING,
            total = total.coerceAtLeast(0),
            completionToken = completionToken,
        )
    }

    @Synchronized
    fun running(
        jobId: String,
        total: Int,
        processed: Int,
        succeeded: Int,
        failed: Int,
    ): LibraryMetadataMatchProgress {
        val current = mutableState.value
        if (current.jobId.isNotEmpty() && current.jobId != jobId) return current
        val safeTotal = total.coerceAtLeast(0)
        val safeSucceeded = maxOf(current.succeeded, succeeded).coerceAtLeast(0)
        val safeFailed = maxOf(current.failed, failed).coerceAtLeast(0)
        val safeProcessed = maxOf(
            current.processed,
            processed,
            safeSucceeded + safeFailed,
        ).coerceIn(0, safeTotal)
        val next = LibraryMetadataMatchProgress(
            jobId = jobId,
            phase = LibraryMetadataMatchPhase.RUNNING,
            total = safeTotal,
            processed = safeProcessed,
            succeeded = safeSucceeded,
            failed = safeFailed,
            completionToken = current.completionToken,
        )
        mutableState.value = next
        return next
    }

    @Synchronized
    fun completed(
        jobId: String,
        total: Int,
        succeeded: Int,
        failed: Int,
    ) {
        val current = mutableState.value
        if (current.jobId.isNotEmpty() && current.jobId != jobId) return
        mutableState.value = LibraryMetadataMatchProgress(
            jobId = jobId,
            phase = LibraryMetadataMatchPhase.COMPLETED,
            total = total.coerceAtLeast(0),
            processed = total.coerceAtLeast(0),
            succeeded = succeeded.coerceAtLeast(0),
            failed = failed.coerceAtLeast(0),
            completionToken = current.completionToken + 1L,
        )
    }
}
