package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rawsmusic.module.data.db.entity.UserPlaylistEntity
import com.rawsmusic.module.data.db.entity.UserPlaylistSongEntity

@Dao
interface UserPlaylistDao {
    @Query("SELECT * FROM user_playlists ORDER BY sort_order ASC")
    suspend fun getPlaylists(): List<UserPlaylistEntity>

    @Query("SELECT * FROM user_playlist_songs ORDER BY playlist_id ASC, sort_order ASC")
    suspend fun getSongs(): List<UserPlaylistSongEntity>

    @Query("SELECT COUNT(*) FROM user_playlists")
    suspend fun countPlaylists(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: UserPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(playlists: List<UserPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<UserPlaylistSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<UserPlaylistSongEntity>)

    @Query("DELETE FROM user_playlists WHERE playlist_id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query(
        "DELETE FROM user_playlist_songs " +
            "WHERE playlist_id = :playlistId AND song_key = :songKey"
    )
    suspend fun deleteSong(playlistId: String, songKey: String)

    @Query("DELETE FROM user_playlist_songs WHERE playlist_id = :playlistId")
    suspend fun deleteSongsForPlaylist(playlistId: String)

    @Query(
        "UPDATE user_playlists SET name = :name, updated_at = :updatedAt " +
            "WHERE playlist_id = :playlistId"
    )
    suspend fun renamePlaylist(playlistId: String, name: String, updatedAt: Long)

    @Query("UPDATE user_playlists SET updated_at = :updatedAt WHERE playlist_id = :playlistId")
    suspend fun touchPlaylist(playlistId: String, updatedAt: Long)

    @Query("DELETE FROM user_playlist_songs")
    suspend fun clearSongs()

    @Query("DELETE FROM user_playlists")
    suspend fun clearPlaylists()

    @Transaction
    suspend fun replaceAll(
        playlists: List<UserPlaylistEntity>,
        songs: List<UserPlaylistSongEntity>
    ) {
        clearSongs()
        clearPlaylists()
        upsertPlaylists(playlists)
        upsertSongs(songs)
    }
}
