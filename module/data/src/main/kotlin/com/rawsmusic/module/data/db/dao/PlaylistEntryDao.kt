package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.PlaylistEntryEntity

@Dao
interface PlaylistEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PlaylistEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(entries: List<PlaylistEntryEntity>): List<Long>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY sort ASC")
    suspend fun getByPlaylistId(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId AND folder_file_id = :fileId LIMIT 1")
    suspend fun getByPlaylistAndFile(playlistId: Long, fileId: Long): PlaylistEntryEntity?

    @Query("DELETE FROM playlist_entries WHERE _id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM playlist_entries WHERE playlist_id = :playlistId AND folder_file_id = :fileId")
    suspend fun deleteByPlaylistAndFile(playlistId: Long, fileId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylistId(playlistId: Long)

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun countByPlaylist(playlistId: Long): Int

    @Query("SELECT MAX(sort) FROM playlist_entries WHERE playlist_id = :playlistId")
    suspend fun maxSortByPlaylist(playlistId: Long): Int?

    @Query("UPDATE playlist_entries SET sort = :sort WHERE _id = :id")
    suspend fun updateSort(id: Long, sort: Int)

    @Query("UPDATE playlist_entries SET played_at = :playedAt WHERE _id = :id")
    suspend fun updatePlayedAt(id: Long, playedAt: Long)
}
