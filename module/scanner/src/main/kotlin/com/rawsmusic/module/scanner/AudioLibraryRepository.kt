package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile

interface AudioLibraryRepository {
    suspend fun getAllSongs(): List<AudioFile>
    suspend fun upsertSongs(songs: List<AudioFile>)
    suspend fun deleteSongs(songs: List<AudioFile>)
}
