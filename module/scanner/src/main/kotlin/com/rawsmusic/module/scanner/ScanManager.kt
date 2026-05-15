package com.rawsmusic.module.scanner

import android.content.Context
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object ScanManager {

    fun startScan(
        context: Context,
        customPaths: List<String> = emptyList(),
        useMediaStore: Boolean = true,
        quickScan: Boolean = false
    ): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()

        if (useMediaStore) {
            MediaStoreScanner.scan(context, customPaths, quickScan).collect { progress ->
                when (progress) {
                    is ScanProgress.Completed -> {
                        val deduplicated = deduplicate(progress.songs)
                        val inserted = MusicRepository.insertSongs(deduplicated)
                        AppPreferences.UI.lastScanTime = System.currentTimeMillis()
                        emit(ScanProgress.Completed(deduplicated, inserted, progress.timeMs))
                    }
                    else -> emit(progress)
                }
            }
        } else {
            val paths = customPaths.ifEmpty {
                listOf(
                    android.os.Environment.getExternalStorageDirectory().absolutePath
                )
            }
            val allSongs = mutableListOf<AudioFile>()
            var totalEstimated = 0

            emit(ScanProgress.Started(0))

            paths.forEach { path ->
                MetadataParser.scanDirectory(java.io.File(path)).collect { progress ->
                    when (progress) {
                        is ScanProgress.Completed -> {
                            allSongs.addAll(progress.songs)
                        }
                        is ScanProgress.Progress -> {
                            emit(ScanProgress.Progress(allSongs.size + progress.scanned, totalEstimated))
                        }
                        else -> {}
                    }
                }
            }

            val deduplicated = deduplicate(allSongs)
            val inserted = MusicRepository.insertSongs(deduplicated)
            AppPreferences.UI.lastScanTime = System.currentTimeMillis()
            val elapsed = System.currentTimeMillis() - startTime
            emit(ScanProgress.Completed(deduplicated, inserted, elapsed))
        }
    }

    private fun deduplicate(songs: List<AudioFile>): List<AudioFile> {
        val seen = mutableSetOf<String>()
        return songs.filter { song ->
            val key = song.path.lowercase()
            if (key in seen) false
            else {
                seen.add(key)
                true
            }
        }
    }

    fun incrementalScan(context: Context): Flow<ScanProgress> = flow {
        val existingPaths = MusicRepository.getAllSongs().map { it.path }.toSet()

        MediaStoreScanner.scan(context).collect { progress ->
            when (progress) {
                is ScanProgress.Completed -> {
                    val newSongs = progress.songs.filter { it.path !in existingPaths }
                    if (newSongs.isNotEmpty()) {
                        MusicRepository.insertSongs(newSongs)
                    }
                    AppPreferences.UI.lastScanTime = System.currentTimeMillis()
                    emit(ScanProgress.Completed(newSongs, newSongs.size, progress.timeMs))
                }
                else -> emit(progress)
            }
        }
    }
}
