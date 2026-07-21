package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Coordinates runtime playback snapshots around [PlayerStatePersistence].
 *
 * Serialization format and queue hydration remain owned by PlayerStatePersistence; this class
 * owns the asynchronous save job, synchronization lock, and complete memory-termination flush.
 */
internal class PlaybackStatePersistenceController(
    private val scope: CoroutineScope,
    private val persistence: PlayerStatePersistence,
    private val currentSong: () -> AudioFile?,
    private val playStateOrdinal: () -> Int,
    private val keepUsbExclusive: () -> Boolean,
    private val positionMs: () -> Long,
    private val queue: () -> PlayQueue,
) {
    private var saveStateJob: Job? = null
    private val persistenceLock = Any()

    fun saveState() {
        currentSong()?.let(persistence::saveSongSnapshot)
        persistence.saveRuntime(
            playStateOrdinal = playStateOrdinal(),
            keepUsbExclusive = keepUsbExclusive(),
        )
        savePosition()

        val queueSnapshot = queue()
        val songsSnapshot = queueSnapshot.songs.toList()
        val currentIndex = queueSnapshot.currentIndex
        saveStateJob?.cancel()
        saveStateJob = scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(persistenceLock) {
                    persistence.saveQueue(songsSnapshot, currentIndex)
                }
            }.onFailure { error ->
                AppLogger.w(TAG, "async queue snapshot failed", error)
            }
        }
    }

    fun persistForMemoryTermination(): Boolean {
        saveStateJob?.cancel()
        return runCatching {
            val queueSnapshot = queue()
            synchronized(persistenceLock) {
                currentSong()?.let(persistence::saveSongSnapshot)
                persistence.saveRuntime(
                    playStateOrdinal = playStateOrdinal(),
                    keepUsbExclusive = keepUsbExclusive(),
                )
                persistence.savePosition(positionMs())
                persistence.saveQueue(queueSnapshot.songs.toList(), queueSnapshot.currentIndex)
                AppPreferences.sync()
            }
            true
        }.getOrElse { error ->
            AppLogger.e(TAG, "FAIR_MEMORY persist failed", error)
            false
        }
    }

    fun savePosition() {
        runCatching {
            persistence.savePosition(positionMs())
        }
    }

    fun restore(): RestoredPlayerState? = persistence.restore()

    private companion object {
        const val TAG = "PlaybackStatePersistence"
    }
}
