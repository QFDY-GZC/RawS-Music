package com.rawsmusic.module.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "normalized_query")
    val normalizedQuery: String,

    @ColumnInfo(name = "query")
    val query: String,

    @ColumnInfo(name = "used_at")
    val usedAt: Long
)
