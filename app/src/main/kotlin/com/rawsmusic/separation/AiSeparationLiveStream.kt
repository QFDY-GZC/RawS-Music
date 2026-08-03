package com.rawsmusic.separation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class AiSeparationStem {
    VOCALS,
    INSTRUMENTAL,
}

data class AiSeparationLiveStreamState(
    val taskId: String = "",
    val sourceName: String = "",
    val sampleRate: Int = 0,
    val vocalsPath: String = "",
    val instrumentalPath: String = "",
    val availableFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val active: Boolean = false,
    val completed: Boolean = false,
    val error: String = "",
) {
    val ready: Boolean
        get() = sampleRate > 0 &&
            vocalsPath.isNotBlank() &&
            instrumentalPath.isNotBlank() &&
            availableFrames > 0L

    fun fileFor(stem: AiSeparationStem): File = File(
        if (stem == AiSeparationStem.VOCALS) vocalsPath else instrumentalPath
    )
}

/**
 * In-process handoff between the foreground separation service and the live stem player.
 *
 * Native publishes both growing float32 WAV files before reporting [availableFrames], so readers
 * never observe a progress value whose PCM bytes are still held in stdio buffers.
 */
object AiSeparationLiveStreamBus {
    private val lock = Any()
    private val mutable = MutableStateFlow(AiSeparationLiveStreamState())
    val state: StateFlow<AiSeparationLiveStreamState> = mutable.asStateFlow()

    fun begin(
        taskId: String,
        sourceName: String,
        sampleRate: Int,
        vocalsFile: File,
        instrumentalFile: File,
    ) = synchronized(lock) {
        mutable.value = AiSeparationLiveStreamState(
            taskId = taskId,
            sourceName = sourceName,
            sampleRate = sampleRate,
            vocalsPath = vocalsFile.absolutePath,
            instrumentalPath = instrumentalFile.absolutePath,
            active = true,
        )
    }

    fun publish(taskId: String, availableFrames: Long, totalFrames: Long) {
        synchronized(lock) {
            val current = mutable.value
            if (current.taskId != taskId) return
            mutable.value = current.copy(
                availableFrames = maxOf(current.availableFrames, availableFrames),
                totalFrames = maxOf(current.totalFrames, totalFrames),
            )
        }
    }

    fun complete(taskId: String, result: AiSeparationResult) {
        synchronized(lock) {
            val current = mutable.value
            if (current.taskId != taskId) return
            mutable.value = current.copy(
                vocalsPath = result.vocalsFile.absolutePath,
                instrumentalPath = result.instrumentalFile.absolutePath,
                availableFrames = result.totalFrames,
                totalFrames = result.totalFrames,
                active = false,
                completed = true,
                error = "",
            )
        }
    }

    fun fail(taskId: String, message: String) {
        synchronized(lock) {
            val current = mutable.value
            if (current.taskId != taskId) return
            mutable.value = current.copy(active = false, completed = false, error = message)
        }
    }

    fun clear(taskId: String = mutable.value.taskId) = synchronized(lock) {
        if (mutable.value.taskId == taskId) mutable.value = AiSeparationLiveStreamState()
    }
}
