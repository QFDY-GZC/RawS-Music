package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rawsmusic.module.data.db.entity.PlaylistEntity

@Dao
interface PlaylistRoomDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE _id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE _id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE _id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}
