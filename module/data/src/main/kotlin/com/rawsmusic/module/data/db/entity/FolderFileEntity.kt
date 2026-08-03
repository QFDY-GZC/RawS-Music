package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folder_files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["name"]),
        Index(value = ["artist_tag"]),
        Index(value = ["album_tag"]),
        Index(value = ["album_artist_tag"]),
        Index(value = ["genre_tag"]),
        Index(value = ["tag_status"]),
        Index(value = ["file_path", "cue_offset_ms", "cue_track_index"], unique = true)
    ]
)
data class FolderFileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "title_tag")
    val titleTag: String = "",

    @ColumnInfo(name = "artist_tag")
    val artistTag: String = "",

    @ColumnInfo(name = "album_tag")
    val albumTag: String = "",

    @ColumnInfo(name = "album_artist_tag")
    val albumArtistTag: String = "",

    @ColumnInfo(name = "genre_tag")
    val genreTag: String = "",

    @ColumnInfo(name = "composer_tag")
    val composerTag: String = "",

    @ColumnInfo(name = "duration")
    val duration: Long = 0,

    @ColumnInfo(name = "file_type")
    val fileType: Int = 0,

    @ColumnInfo(name = "file_created_at")
    val fileCreatedAt: Long = 0,

    @ColumnInfo(name = "file_modified_at", defaultValue = "0")
    val fileModifiedAt: Long = 0,

    @ColumnInfo(name = "tag_status")
    val tagStatus: Int = 0,

    @ColumnInfo(name = "track_number")
    val trackNumber: Int = 0,

    @ColumnInfo(name = "year")
    val year: Int = 0,

    @ColumnInfo(name = "rating")
    val rating: Int = 0,

    @ColumnInfo(name = "folder_id")
    val folderId: Long = 0,

    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int = 0,

    @ColumnInfo(name = "bit_rate")
    val bitRate: Int = 0,

    @ColumnInfo(name = "bits_per_sample")
    val bitsPerSample: Int = 0,

    @ColumnInfo(name = "channels")
    val channels: Int = 0,

    @ColumnInfo(name = "encoding_format")
    val encodingFormat: String = "",

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String = "",

    @ColumnInfo(name = "has_lyrics")
    val hasLyrics: Int = 0,

    @ColumnInfo(name = "bpm")
    val bpm: Int = 0,

    @ColumnInfo(name = "disc_number")
    val discNumber: Int = 0,

    @ColumnInfo(name = "cue_offset_ms")
    val cueOffsetMs: Long = 0,

    @ColumnInfo(name = "cue_end_ms")
    val cueEndMs: Long = 0,

    @ColumnInfo(name = "cue_track_index")
    val cueTrackIndex: Int = 0,

    @ColumnInfo(name = "track_gain")
    val trackGain: Float = 0f,

    @ColumnInfo(name = "track_peak")
    val trackPeak: Float = 1.0f,

    @ColumnInfo(name = "album_gain")
    val albumGain: Float = 0f,

    @ColumnInfo(name = "album_peak")
    val albumPeak: Float = 1.0f,

    @ColumnInfo(name = "played_times")
    val playedTimes: Int = 0,

    @ColumnInfo(name = "last_pos")
    val lastPos: Long = 0,

    @ColumnInfo(name = "played_at")
    val playedAt: Long = 0,

    @ColumnInfo(name = "played_fully_at")
    val playedFullyAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "meta")
    val meta: String = ""
)
