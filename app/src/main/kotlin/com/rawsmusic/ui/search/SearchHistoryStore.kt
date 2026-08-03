package com.rawsmusic.ui.search

import android.content.Context
import com.rawsmusic.module.data.repository.SearchHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

internal class SearchHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        "global_search_history",
        Context.MODE_PRIVATE
    )
    private val repository = SearchHistoryRepository(appContext)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load(): List<String> {
        migrateLegacyHistoryIfNeeded()
        return repository.getRecent(MAX_HISTORY_SIZE)
    }

    fun add(query: String, current: List<String>): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return current
        val updated = buildList {
            add(normalized)
            current.filterNot { it.equals(normalized, ignoreCase = true) }.forEach(::add)
        }.take(MAX_HISTORY_SIZE)
        writeScope.launch {
            repository.upsert(normalized, System.currentTimeMillis())
            repository.trimTo(MAX_HISTORY_SIZE)
        }
        return updated
    }

    fun clear() {
        writeScope.launch { repository.clear() }
    }

    private suspend fun migrateLegacyHistoryIfNeeded() {
        if (preferences.getBoolean(KEY_ROOM_MIGRATED, false)) return
        val legacy = loadLegacy()
        if (legacy.isNotEmpty()) {
            val now = System.currentTimeMillis()
            repository.upsertAll(legacy, now)
            repository.trimTo(MAX_HISTORY_SIZE)
        }
        preferences.edit()
            .remove(KEY_HISTORY)
            .putBoolean(KEY_ROOM_MIGRATED, true)
            .apply()
    }

    private fun loadLegacy(): List<String> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_HISTORY = "queries"
        const val KEY_ROOM_MIGRATED = "room_migrated_v1"
        const val MAX_HISTORY_SIZE = 20
    }
}
