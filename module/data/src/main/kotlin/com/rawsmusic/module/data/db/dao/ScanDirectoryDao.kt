package com.rawsmusic.module.data.db.dao

import androidx.room.*
import com.rawsmusic.module.data.db.entity.ScanDirectoryEntity

@Dao
interface ScanDirectoryDao {
    @Query("SELECT * FROM scan_directories")
    suspend fun getAll(): List<ScanDirectoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ScanDirectoryEntity>)
}
