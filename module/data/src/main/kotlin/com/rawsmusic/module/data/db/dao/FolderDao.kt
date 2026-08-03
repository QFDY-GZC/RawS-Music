package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.FolderEntity

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(folders: List<FolderEntity>): List<Long>

    @Query("SELECT * FROM folders WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY path ASC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE is_cue = 0 ORDER BY path ASC")
    suspend fun getAllNonCue(): List<FolderEntity>

    @Query("DELETE FROM folders WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int
}
