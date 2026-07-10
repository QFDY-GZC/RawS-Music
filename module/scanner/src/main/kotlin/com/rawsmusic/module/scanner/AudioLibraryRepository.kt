package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile

interface AudioLibraryRepository {
    suspend fun getAllSongs(): List<AudioFile>

    /**
     * 扫描同步专用：不要求 UI 排序。对齐设计思路，delta 计算只需要稳定 key，
     * 不应该在同步前做一次 CJK 排序。
     */
    suspend fun getAllSongsForSync(): List<AudioFile> = getAllSongs()
    suspend fun upsertSongs(songs: List<AudioFile>)
    suspend fun deleteSongs(songs: List<AudioFile>)

    /** 扫描惰性同步专用：允许批量写入时暂不刷新 UI/聚合索引。 */
    suspend fun upsertSongsForScan(songs: List<AudioFile>, refreshLibrary: Boolean = true) {
        upsertSongs(songs)
    }

    /** 扫描惰性同步专用：允许最终删除阶段暂不刷新，最后统一刷新。 */
    suspend fun deleteSongsForScan(songs: List<AudioFile>, refreshLibrary: Boolean = true) {
        deleteSongs(songs)
    }

    suspend fun refreshAfterScanSync() = Unit
}
