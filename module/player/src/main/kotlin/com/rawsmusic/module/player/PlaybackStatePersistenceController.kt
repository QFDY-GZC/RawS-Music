package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope

/**
 * Coordinates runtime playback snapshots around [PlayerStatePersistence].
 *
 * Serialization format and queue hydration remain owned by PlayerStatePersistence; this class
 * owns the conflated single-writer lane and complete memory-termination flush.
 */
internal class PlaybackStatePersistenceController(
    private val scope: CoroutineScope,
    private val persistence: PlayerStatePersistence,
    private val currentSong: () -> AudioFile?,
    private val shouldPersistPlayback: () -> Boolean = { true },
    private val playStateOrdinal: () -> Int,
    private val keepUsbExclusive: () -> Boolean,
    private val positionMs: () -> Long,
    private val queue: () -> PlayQueue,
    private val priorityQueue: () -> List<AudioFile> = { emptyList() },
    /** False until cold restore or an explicit user play owns the in-memory queue. */
    private val shouldPersistQueue: () -> Boolean = { true },
) {
    private data class SaveRequest(val queue: PlayQueue, val priority: List<AudioFile>)
    private val snapshotWriter = LatestSnapshotWriter<SaveRequest>(scope) { request ->
        runCatching {
            persistence.saveSnapshot(
                songs = request.queue.songs,
                currentIndex = request.queue.currentIndex,
                entryIds = request.queue.entryIds,
                prioritySongs = request.priority,
            )
        }.onFailure { error -> AppLogger.w(TAG, "async queue snapshot failed", error) }
    }

    fun saveState() {
        if (!shouldPersistPlayback()) return
        currentSong()?.let(persistence::saveSongSnapshot)
        persistence.saveRuntime(
            playStateOrdinal = playStateOrdinal(),
            keepUsbExclusive = keepUsbExclusive(),
        )
        savePosition()

        val queueSnapshot = queue()
        val prioritySnapshot = priorityQueue().toList()
        val persistQueue = shouldPersistQueue()
        snapshotWriter.submit(if (persistQueue) SaveRequest(queueSnapshot, prioritySnapshot) else null)
    }

    fun persistForMemoryTermination(): Boolean {
        if (!shouldPersistPlayback()) {
            snapshotWriter.flushFinal { AppPreferences.sync() }
            return true
        }
        return runCatching {
            val queueSnapshot = queue()
            val persistQueue = shouldPersistQueue()
            snapshotWriter.flushFinal {
                currentSong()?.let(persistence::saveSongSnapshot)
                persistence.saveRuntime(
                    playStateOrdinal = playStateOrdinal(),
                    keepUsbExclusive = keepUsbExclusive(),
                )
                persistence.savePosition(positionMs())
                if (persistQueue) {
                    persistence.saveSnapshot(
                        songs = queueSnapshot.songs,
                        currentIndex = queueSnapshot.currentIndex,
                        entryIds = queueSnapshot.entryIds,
                        prioritySongs = priorityQueue().toList(),
                    )
                }
                AppPreferences.sync()
            }
            true
        }.getOrElse { error ->
            AppLogger.e(TAG, "FAIR_MEMORY persist failed", error)
            false
        }
    }

    fun savePosition() {
        if (!shouldPersistPlayback()) return
        runCatching {
            persistence.savePosition(positionMs())
        }
    }

    fun restore(): RestoredPlayerState? = persistence.restore()

    private companion object {
        const val TAG = "PlaybackStatePersistence"
    }
}
