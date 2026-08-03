package com.rawsmusic.module.data.repository

import android.content.Context
import com.rawsmusic.module.data.db.MusicDatabase
import com.rawsmusic.module.data.db.entity.SearchHistoryEntity
import java.util.Locale

class SearchHistoryRepository(context: Context) {
    private val dao = MusicDatabase.getInstance(context.applicationContext).searchHistoryDao()

    suspend fun getRecent(limit: Int): List<String> =
        dao.getRecent(limit).map { it.query }

    suspend fun upsert(query: String, usedAt: Long) {
        dao.upsert(query.toEntity(usedAt))
    }

    suspend fun upsertAll(queries: List<String>, firstUsedAt: Long) {
        dao.upsertAll(
            queries.mapIndexed { index, query -> query.toEntity(firstUsedAt - index) }
        )
    }

    suspend fun trimTo(limit: Int) {
        dao.trimTo(limit)
    }

    suspend fun clear() {
        dao.clear()
    }

    private fun String.toEntity(usedAt: Long) = SearchHistoryEntity(
        normalizedQuery = lowercase(Locale.ROOT),
        query = this,
        usedAt = usedAt
    )
}
