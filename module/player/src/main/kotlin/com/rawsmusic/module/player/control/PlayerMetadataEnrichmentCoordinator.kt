package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.scanner.MediaStoreScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns asynchronous metadata enrichment after a playback selection is committed.
 *
 * The coordinator never touches a decoder, output route or Android lifecycle. It only enriches
 * repository rows and publishes identity-checked song/queue snapshots through callbacks.
 */
internal class PlayerMetadataEnrichmentCoordinator(
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val isUsbCriticalStartup: () -> Boolean,
        val currentSong: () -> AudioFile?,
        val setCurrentSong: (AudioFile) -> Unit,
        val currentQueue: () -> PlayQueue,
        val setQueue: (PlayQueue) -> Unit,
        val enrichRemoteCachedSong: (AudioFile) -> AudioFile?,
        val logInfo: (String) -> Unit,
    )

    private var enrichJob: Job? = null
    private var queueSweepJob: Job? = null

    fun start(song: AudioFile, remoteUrl: Boolean) {
        cancel()
        if (remoteUrl) {
            enrichJob = scope.launch(Dispatchers.IO) {
                var attempt = 0
                while (attempt < REMOTE_CACHE_MAX_ATTEMPTS) {
                    val enriched = runCatching { callbacks.enrichRemoteCachedSong(song) }.getOrNull()
                    if (enriched != null) {
                        withContext(Dispatchers.Main) {
                            if (sameItem(callbacks.currentSong(), song)) {
                                callbacks.setCurrentSong(enriched)
                            }
                            replaceQueueItem(song, enriched)
                        }
                        callbacks.logInfo(
                            "remote metadata enrichment applied path=${song.path.substringAfterLast('/').take(96)} " +
                                "art=${enriched.albumArtPath.isNotBlank()} sr=${enriched.sampleRate} bits=${enriched.bitsPerSample}"
                        )
                        return@launch
                    }
                    attempt++
                    if (attempt < REMOTE_CACHE_MAX_ATTEMPTS) {
                        delay(remoteRetryDelayMs(attempt))
                    }
                }
                callbacks.logInfo("remote metadata enrichment gave up waiting for local transport")
            }
            return
        }
        enrichJob = scope.launch(Dispatchers.IO) {
            if (callbacks.isUsbCriticalStartup()) {
                callbacks.logInfo("metadata enrichment delayed by USB critical startup")
                delay(2_500L)
            }
            runCatching {
                val enriched = preserveCueIdentity(
                    original = song,
                    enriched = MediaStoreScanner.enrichSong(song),
                )
                if (enriched != song) {
                    MusicRepository.updateSong(enriched)
                    withContext(Dispatchers.Main) {
                        if (sameItem(callbacks.currentSong(), song)) {
                            callbacks.setCurrentSong(enriched)
                        }
                        replaceQueueItem(song, enriched)
                    }
                }

                val queue = callbacks.currentQueue()
                val nextIndex = queue.currentIndex + 1
                val nextSong = queue.songs.getOrNull(nextIndex)
                if (nextSong != null && needsAudioFormatEnrichment(nextSong)) {
                    enrichQueueItem(nextSong)
                }

                queueSweepJob = scope.launch(Dispatchers.IO) {
                    if (callbacks.isUsbCriticalStartup()) delay(2_500L)
                    val snapshot = callbacks.currentQueue()
                    snapshot.songs.forEachIndexed { index, candidate ->
                        if (index == snapshot.currentIndex || index == nextIndex) return@forEachIndexed
                        if (!needsAudioFormatEnrichment(candidate)) return@forEachIndexed
                        enrichQueueItem(candidate)
                    }
                }
            }
        }
    }

    fun cancel() {
        enrichJob?.cancel()
        enrichJob = null
        queueSweepJob?.cancel()
        queueSweepJob = null
    }

    private suspend fun enrichQueueItem(song: AudioFile) {
        runCatching {
            val enriched = MediaStoreScanner.enrichSong(song)
            if (
                enriched.sampleRate != song.sampleRate ||
                enriched.bitsPerSample != song.bitsPerSample
            ) {
                MusicRepository.updateSong(enriched)
                withContext(Dispatchers.Main) {
                    replaceQueueItem(song, enriched)
                }
            }
        }
    }

    private fun replaceQueueItem(original: AudioFile, enriched: AudioFile) {
        val queue = callbacks.currentQueue()
        val index = queue.songs.indexOfFirst { sameItem(it, original) }
        if (index < 0) return
        val songs = queue.songs.toMutableList()
        songs[index] = enriched
        callbacks.setQueue(queue.copy(songs = songs))
    }

    private fun preserveCueIdentity(original: AudioFile, enriched: AudioFile): AudioFile {
        val cueTrack = original.cueEndMs > 0 || original.cueTrackIndex > 0
        if (!cueTrack) return enriched
        return enriched.copy(
            title = original.title,
            artist = original.artist,
            album = original.album,
            albumArtist = original.albumArtist,
            duration = original.duration,
            trackNumber = original.trackNumber,
            cueOffsetMs = original.cueOffsetMs,
            cueEndMs = original.cueEndMs,
            cueTrackIndex = original.cueTrackIndex,
        )
    }

    private fun needsAudioFormatEnrichment(song: AudioFile): Boolean =
        song.sampleRate == 0 || song.bitsPerSample == 0

    private fun sameItem(left: AudioFile?, right: AudioFile): Boolean =
        left != null &&
            left.path == right.path &&
            left.cueOffsetMs == right.cueOffsetMs &&
            left.cueTrackIndex == right.cueTrackIndex

    private fun remoteRetryDelayMs(attempt: Int): Long = when {
        attempt <= 1 -> 100L
        attempt == 2 -> 250L
        attempt == 3 -> 500L
        attempt == 4 -> 1_000L
        attempt <= 6 -> 2_000L
        else -> 5_000L
    }

    companion object {
        // Allows a large first-time WebDAV cache download to finish while the selected song is still current.
        private const val REMOTE_CACHE_MAX_ATTEMPTS = 64
    }
}
