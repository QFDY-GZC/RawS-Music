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

    // A search result session is intentionally transient. Only the dedicated history store
    // persists entered queries; reopening search starts with a clean result set.
    var query by mutableStateOf("")
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
    var outerListIndex by mutableIntStateOf(0)
    var outerListOffset by mutableIntStateOf(0)
    private val focusedIndices = mutableMapOf<GlobalSearchScope, Int>()
    private val focusedKeys = mutableMapOf<GlobalSearchScope, String>()
    var sortMode by mutableStateOf(
        GlobalSearchSortMode.fromToken(preferences.getString(prefix + "sort_mode", null))
    )
        private set
    var sortDescending by mutableStateOf(preferences.getBoolean(prefix + "sort_descending", false))
        private set

    fun updateQuery(value: String) {
        query = value
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
    }

    fun focusedIndex(scope: GlobalSearchScope): Int = focusedIndices[scope] ?: -1

    fun focusedKey(scope: GlobalSearchScope): String? = focusedKeys[scope]

    fun saveFocusedIndex(scope: GlobalSearchScope, index: Int) {
        focusedIndices[scope] = index
    }

    fun saveFocusedEntry(scope: GlobalSearchScope, key: String, index: Int) {
        focusedKeys[scope] = key
        focusedIndices[scope] = index
    }

    fun updateSort(mode: GlobalSearchSortMode) {
        sortMode = mode
        preferences.edit().putString(prefix + "sort_mode", mode.token).apply()
    }

    fun updateSort(mode: GlobalSearchSortMode, descending: Boolean) {
        sortMode = mode
        sortDescending = descending
        preferences.edit()
            .putString(prefix + "sort_mode", mode.token)
            .putBoolean(prefix + "sort_descending", descending)
            .apply()
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
    fun get(context: Context, initialScope: GlobalSearchScope?): GlobalSearchSessionState {
        val key = initialScope?.token ?: "all"
        return GlobalSearchSessionState(context, key, initialScope)
    }
}
