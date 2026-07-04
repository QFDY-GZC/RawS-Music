package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FolderFileEntity::class,
            parentColumns = ["_id"],
            childColumns = ["folder_file_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["folder_file_id"])
    ]
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "folder_file_id")
    val folderFileId: Long,

    @ColumnInfo(name = "sort")
    val sort: Int = 0,

    @ColumnInfo(name = "cue_offset_ms")
    val cueOffsetMs: Long = 0,

    @ColumnInfo(name = "folder_path")
    val folderPath: String = "",

    @ColumnInfo(name = "file_name")
    val fileName: String = "",

    @ColumnInfo(name = "played_at")
    val playedAt: Long = 0,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long = 0
)
