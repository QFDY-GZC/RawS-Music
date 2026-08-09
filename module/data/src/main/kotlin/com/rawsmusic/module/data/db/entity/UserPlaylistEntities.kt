package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "user_playlists",
    indices = [Index(value = ["name"], unique = true)],
    primaryKeys = ["playlist_id"]
)
data class UserPlaylistEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int
)

@Entity(
    tableName = "user_playlist_songs",
    primaryKeys = ["playlist_id", "song_key"],
    foreignKeys = [
        ForeignKey(
            entity = UserPlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "sort_order"], unique = true)
    ]
)
data class UserPlaylistSongEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    @ColumnInfo(name = "song_key")
    val songKey: String,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    val duration: Long,
    val path: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    val format: String,
    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int,
    @ColumnInfo(name = "bit_rate")
    val bitRate: Int,
    @ColumnInfo(name = "bits_per_sample")
    val bitsPerSample: Int,
    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int
)
