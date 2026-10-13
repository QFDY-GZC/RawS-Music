package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository

/**
 * 将 scanner 模块的新两阶段扫描链路接到现有 Room/MusicRepository。
 */
class MusicRepositoryAudioLibraryRepository : AudioLibraryRepository {
    override suspend fun getScanBaseline(): ScanBaseline = ScanBaseline(
        songs = MusicRepository.getAllSongsUnsortedSuspend(),
        pendingKeys = MusicRepository.getPendingScanSongs().mapTo(HashSet(), ::scanRowKey),
        directories = MusicRepository.getScanDirectories(),
    )

    override suspend fun updateScanMetadataStatus(outcomes: List<MetadataScanOutcome>) {
        MusicRepository.updateScanTagStatuses(outcomes.map { it.song to it.completed })
    }
    override suspend fun commitScanDirectories(snapshots: Map<String, Long>) {
        MusicRepository.commitScanDirectories(snapshots)
    }
    override suspend fun getAllSongs(): List<AudioFile> {
        return MusicRepository.getAllSongsSuspend()
    }

    override suspend fun getAllSongsForSync(): List<AudioFile> {
        return MusicRepository.getAllSongsUnsortedSuspend()
    }

    override suspend fun upsertSongs(songs: List<AudioFile>) {
        MusicRepository.upsertSongsSuspend(songs)
    }

    override suspend fun deleteSongs(songs: List<AudioFile>) {
        MusicRepository.deleteSongsSuspend(songs)
    }

    override suspend fun upsertSongsForScan(songs: List<AudioFile>, refreshLibrary: Boolean) {
        MusicRepository.upsertSongsSuspend(songs, refreshLibrary = refreshLibrary, strictScanCommit = true)
    }

    override suspend fun deleteSongsForScan(songs: List<AudioFile>, refreshLibrary: Boolean) {
        MusicRepository.deleteSongsForScanSuspend(songs, refreshLibrary = refreshLibrary)
    }

    override suspend fun refreshAfterScanSync() {
        MusicRepository.refreshAfterBulkScanSyncSuspend()
    }
}
