package com.rawsmusic.ui.search

import android.content.Context
import org.json.JSONArray

internal class SearchHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "global_search_history",
        Context.MODE_PRIVATE
    )

    fun load(): List<String> {
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

    fun add(query: String): List<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return load()
        val updated = buildList {
            add(normalized)
            load().filterNot { it.equals(normalized, ignoreCase = true) }.forEach(::add)
        }.take(MAX_HISTORY_SIZE)
        save(updated)
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(history: List<String>) {
        val array = JSONArray()
        history.forEach(array::put)
        preferences.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val KEY_HISTORY = "queries"
        const val MAX_HISTORY_SIZE = 20
    }
}
