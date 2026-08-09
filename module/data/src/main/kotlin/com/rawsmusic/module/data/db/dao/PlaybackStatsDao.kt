package com.rawsmusic.module.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rawsmusic.module.data.db.entity.DailyListenEntity
import com.rawsmusic.module.data.db.entity.PlaybackHistoryEntity
import com.rawsmusic.module.data.db.entity.PlaybackStatEntity

@Dao
interface PlaybackStatsDao {
    @Query("SELECT * FROM playback_stats ORDER BY play_count DESC, last_played_at DESC")
    suspend fun getStats(): List<PlaybackStatEntity>

    @Query("SELECT * FROM playback_history ORDER BY played_at DESC, id DESC LIMIT :limit")
    suspend fun getHistory(limit: Int): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM daily_listen_stats ORDER BY date ASC")
    suspend fun getDaily(): List<DailyListenEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStat(entity: PlaybackStatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(entity: DailyListenEntity)

    @Insert
    suspend fun insertHistory(entity: PlaybackHistoryEntity)

    @Query(
        "DELETE FROM playback_history WHERE id NOT IN " +
            "(SELECT id FROM playback_history ORDER BY played_at DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimHistory(limit: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(entities: List<PlaybackStatEntity>)

    @Insert
    suspend fun insertHistoryEntries(entities: List<PlaybackHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDaily(entities: List<DailyListenEntity>)

    @Query("DELETE FROM playback_stats")
    suspend fun clearStats()

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()

    @Query("DELETE FROM daily_listen_stats")
    suspend fun clearDaily()

    @Transaction
    suspend fun replaceAll(
        stats: List<PlaybackStatEntity>,
        history: List<PlaybackHistoryEntity>,
        daily: List<DailyListenEntity>
    ) {
        clearStats()
        clearHistory()
        clearDaily()
        insertStats(stats)
        insertHistoryEntries(history)
        insertDaily(daily)
    }
}
