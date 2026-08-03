package com.rawsmusic.module.scanner

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

object ScanManager {

    fun startScan(
        context: Context,
        customPaths: List<String> = emptyList(),
        useMediaStore: Boolean = !AppPreferences.Scanner.legacyFileAccessEnabled,
        quickScan: Boolean = false
    ): Flow<ScanProgress> = flow {
        val startTime = System.currentTimeMillis()

        if (useMediaStore) {
            MediaStoreScanner.scan(context, customPaths, quickScan, options = MediaStoreScanner.ScanOptions.fromPreferences()).collect { progress ->
                when (progress) {
                    is ScanProgress.Completed -> {
                        val mediaStoreSongs = progress.songs.toMutableList()

                        // 合并 SAF 用户选择文件夹的扫描结果
                        val safUris = AppPreferences.Scanner.musicFolderUris
                        if (safUris.isNotEmpty()) {
                            try {
                                val safSongs = SafMusicScanner.scanSelectedFolders(context)
                                mediaStoreSongs.addAll(safSongs)
                                Log.d("ScanManager", "SAF scan: ${safSongs.size} songs from ${safUris.size} folders")
                            } catch (e: Exception) {
                                Log.w("ScanManager", "SAF scan failed: ${e.message}")
                            }
                        }

                        // 合并传统文件系统递归扫描结果（兜底 Download 等非 Music 目录）
                        if (!quickScan && customPaths.isNotEmpty() && AppPreferences.Scanner.legacyFileAccessEnabled) {
                            try {
                                val legacySongs = MediaStoreScanner.scanCustomPathsByFileSystem(context, customPaths)
                                mediaStoreSongs.addAll(legacySongs)
                                Log.d("ScanManager", "legacy scan: ${legacySongs.size} songs from customPaths")
                            } catch (e: Exception) {
                                Log.w("ScanManager", "legacy scan failed: ${e.message}")
                            }
                        }

                        val deduplicated = deduplicate(mediaStoreSongs)
                        Log.d("ScanManager", "scan merged: mediaStore=${progress.songs.size}, total=${mediaStoreSongs.size}, deduplicated=${deduplicated.size}")
                        val inserted = MusicRepository.insertSongs(deduplicated)
                        AppPreferences.UI.lastScanTime = System.currentTimeMillis()
                        emit(ScanProgress.Completed(deduplicated, inserted, progress.timeMs))
                    }
                    else -> emit(progress)
                }
            }
        } else {
            if (!LegacyFileAccess.hasPermission(context)) {
                emit(ScanProgress.Error(LegacyFileAccess.unavailableMessage()))
                return@flow
            }
            val safUris = AppPreferences.Scanner.musicFolderUris
            // 空路径且没有 SAF 文件夹时直接返回错误，不扫描外部存储根目录。
            if (customPaths.isEmpty() && safUris.isEmpty()) {
                Log.w("ScanManager", "startScan: no custom paths or SAF folders and MediaStore disabled")
                emit(ScanProgress.Error("请先选择音乐文件夹"))
                return@flow
            }
            val paths = customPaths
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

            if (safUris.isNotEmpty()) {
                runCatching { SafMusicScanner.scanSelectedFolders(context) }
                    .onSuccess { allSongs.addAll(it) }
                    .onFailure { Log.w("ScanManager", "legacy SAF scan failed", it) }
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
            val key = if (song.cueOffsetMs > 0 || song.cueTrackIndex > 0) {
                "${song.path.lowercase()}@cue${song.cueOffsetMs}_${song.cueTrackIndex}"
            } else {
                song.path.lowercase()
            }
            if (key in seen) false
            else {
                seen.add(key)
                true
            }
        }
    }

    fun incrementalScan(context: Context): Flow<ScanProgress> = flow {
        if (AppPreferences.Scanner.legacyFileAccessEnabled) {
            startScan(
                context = context,
                customPaths = AppPreferences.UI.scanPaths,
                useMediaStore = false,
                quickScan = true
            ).collect { emit(it) }
            return@flow
        }

        val existingKeys = MusicRepository.getAllSongs().map { song ->
            if (song.cueOffsetMs > 0 || song.cueTrackIndex > 0) {
                "${song.path}@cue${song.cueOffsetMs}_${song.cueTrackIndex}"
            } else {
                song.path
            }
        }.toSet()

        MediaStoreScanner.scan(context, options = MediaStoreScanner.ScanOptions.fromPreferences()).collect { progress ->
            when (progress) {
                is ScanProgress.Completed -> {
                    val newSongs = progress.songs.filter { song ->
                        val key = if (song.cueOffsetMs > 0 || song.cueTrackIndex > 0) {
                            "${song.path}@cue${song.cueOffsetMs}_${song.cueTrackIndex}"
                        } else {
                            song.path
                        }
                        key !in existingKeys
                    }
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
