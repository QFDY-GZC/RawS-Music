package com.rawsmusic.ui.search

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.core.ui.scene.GlobalSearchScope

@Stable
internal class GlobalSearchSessionState(
    context: Context,
    val sessionKey: String,
    initialScope: GlobalSearchScope?
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "global_search_sessions",
        Context.MODE_PRIVATE
    )
    private val prefix = "${sessionKey}_"

    var query by mutableStateOf(preferences.getString(prefix + "query", "").orEmpty())
        private set
    var entryScopeMode by mutableStateOf(
        preferences.getBoolean(prefix + "entry_scope_mode", initialScope != null)
    )
        private set
    var activeScopes by mutableStateOf(
        preferences.getStringSet(prefix + "active_scopes", null)
            ?.mapNotNullTo(linkedSetOf()) { GlobalSearchScope.fromToken(it) }
            ?: initialScope?.let { linkedSetOf(it) }.orEmpty()
    )
        private set
    var outerListIndex by mutableIntStateOf(preferences.getInt(prefix + "outer_index", 0))
    var outerListOffset by mutableIntStateOf(preferences.getInt(prefix + "outer_offset", 0))
    var sortMode by mutableStateOf(
        GlobalSearchSortMode.fromToken(preferences.getString(prefix + "sort_mode", null))
    )
        private set
    var sortDescending by mutableStateOf(preferences.getBoolean(prefix + "sort_descending", false))
        private set

    fun updateQuery(value: String) {
        query = value
        preferences.edit().putString(prefix + "query", value).apply()
    }

    fun updateFilters(scopes: Set<GlobalSearchScope>, entryMode: Boolean) {
        activeScopes = scopes
        entryScopeMode = entryMode
        preferences.edit()
            .putStringSet(prefix + "active_scopes", scopes.mapTo(linkedSetOf()) { it.token })
            .putBoolean(prefix + "entry_scope_mode", entryMode)
            .apply()
    }

    fun saveOuterScroll(index: Int, offset: Int) {
        outerListIndex = index
        outerListOffset = offset
        preferences.edit()
            .putInt(prefix + "outer_index", index)
            .putInt(prefix + "outer_offset", offset)
            .apply()
    }

    fun focusedIndex(scope: GlobalSearchScope): Int {
        return preferences.getInt(prefix + "focus_${scope.token}", -1)
    }

    fun focusedKey(scope: GlobalSearchScope): String? {
        return preferences.getString(prefix + "focus_key_${scope.token}", null)
    }

    fun saveFocusedIndex(scope: GlobalSearchScope, index: Int) {
        preferences.edit().putInt(prefix + "focus_${scope.token}", index).apply()
    }

    fun saveFocusedEntry(scope: GlobalSearchScope, key: String, index: Int) {
        preferences.edit()
            .putString(prefix + "focus_key_${scope.token}", key)
            .putInt(prefix + "focus_${scope.token}", index)
            .apply()
    }

    fun updateSort(mode: GlobalSearchSortMode) {
        sortMode = mode
        preferences.edit().putString(prefix + "sort_mode", mode.token).apply()
    }

    fun toggleSortDirection() {
        sortDescending = !sortDescending
        preferences.edit().putBoolean(prefix + "sort_descending", sortDescending).apply()
    }
}

internal enum class GlobalSearchSortMode(val token: String) {
    RELEVANCE("relevance"),
    NAME("name"),
    SONG_COUNT("song_count");

    companion object {
        fun fromToken(token: String?): GlobalSearchSortMode {
            return entries.firstOrNull { it.token == token } ?: RELEVANCE
        }
    }
}

internal object GlobalSearchSessionStore {
    private val sessions = linkedMapOf<String, GlobalSearchSessionState>()

    fun get(context: Context, initialScope: GlobalSearchScope?): GlobalSearchSessionState {
        val key = initialScope?.token ?: "all"
        return synchronized(sessions) {
            sessions.getOrPut(key) {
                GlobalSearchSessionState(context, key, initialScope)
            }
        }
    }
}
