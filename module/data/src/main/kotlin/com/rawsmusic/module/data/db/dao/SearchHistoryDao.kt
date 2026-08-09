package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rawsmusic.module.data.db.entity.SearchHistoryEntity

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY used_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SearchHistoryEntity>)

    @Query(
        "DELETE FROM search_history WHERE normalized_query NOT IN " +
            "(SELECT normalized_query FROM search_history ORDER BY used_at DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
