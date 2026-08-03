package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    indices = [Index(value = ["path"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long = 0,

    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "short_name")
    val shortName: String = "",

    @ColumnInfo(name = "parent_name")
    val parentName: String = "",

    @ColumnInfo(name = "is_cue")
    val isCue: Int = 0
)
