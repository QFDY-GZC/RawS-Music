package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository

/**
 * 将 scanner 模块的新两阶段扫描链路接到现有 Room/MusicRepository。
 */
class MusicRepositoryAudioLibraryRepository : AudioLibraryRepository {
    override suspend fun getAllSongs(): List<AudioFile> {
        return MusicRepository.getAllSongsSuspend()
    }

    override suspend fun upsertSongs(songs: List<AudioFile>) {
        MusicRepository.upsertSongsSuspend(songs)
    }

    override suspend fun deleteSongs(songs: List<AudioFile>) {
        MusicRepository.deleteSongsSuspend(songs)
    }
}
