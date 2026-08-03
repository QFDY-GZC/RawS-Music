package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playback_stats")
data class PlaybackStatEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "play_count") val playCount: Int,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long
)

@Entity(
    tableName = "playback_history",
    indices = [Index(value = ["played_at"]), Index(value = ["song_id"])]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "played_at") val playedAt: Long
)

@Entity(tableName = "daily_listen_stats")
data class DailyListenEntity(
    @PrimaryKey val date: String,
    @ColumnInfo(name = "listened_ms") val listenedMs: Long
)
