package com.rawsmusic.core.ui.scene.pages

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.core.ui.scene.NavScene

private const val HOME_CARD_PREFS = "home_card_layout_preferences"
private const val HOME_LIBRARY_ORDER_KEY = "library_order"
private const val HOME_TOOL_ORDER_KEY = "tool_order"
private const val HOME_HIDDEN_KEY = "hidden_scenes"
private const val HOME_CARD_SEPARATOR = "|"

/** Categories shown in the Music Library section of HOME. */
val HomeLibraryCardScenes: List<NavScene> = listOf(
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
    NavScene.SOURCE_IMPORT,
)

/** Utility cards are editable too, but never mix into the library category order. */
val HomeToolCardScenes: List<NavScene> = listOf(
    NavScene.ANALYTICS,
    NavScene.SONG_STATS,
    NavScene.WEBDAV,
    NavScene.LOG_VIEWER,
)

enum class HomeCardGroup { LIBRARY, TOOLS }

enum class HomeCardChooserSort { DEFAULT, NAME, CONTENT_COUNT }

@Stable
class HomeCardLayoutState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        HOME_CARD_PREFS,
        Context.MODE_PRIVATE,
    )

    var libraryOrder by mutableStateOf(loadOrder(HOME_LIBRARY_ORDER_KEY, HomeLibraryCardScenes))
        private set
    var toolOrder by mutableStateOf(loadOrder(HOME_TOOL_ORDER_KEY, HomeToolCardScenes))
        private set
    var hiddenScenes by mutableStateOf(loadHidden())
        private set

    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            HOME_LIBRARY_ORDER_KEY -> libraryOrder = loadOrder(HOME_LIBRARY_ORDER_KEY, HomeLibraryCardScenes)
            HOME_TOOL_ORDER_KEY -> toolOrder = loadOrder(HOME_TOOL_ORDER_KEY, HomeToolCardScenes)
            HOME_HIDDEN_KEY -> hiddenScenes = loadHidden()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun dispose() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun scenes(group: HomeCardGroup, visibleOnly: Boolean = true): List<NavScene> {
        val order = when (group) {
            HomeCardGroup.LIBRARY -> libraryOrder
            HomeCardGroup.TOOLS -> toolOrder
        }
        return if (visibleOnly) order.filterNot { it.tag in hiddenScenes } else order
    }

    fun hidden(group: HomeCardGroup): List<NavScene> = scenes(group, visibleOnly = false)
        .filter { it.tag in hiddenScenes }

    fun isVisible(scene: NavScene): Boolean = scene.tag !in hiddenScenes

    fun hide(scene: NavScene) {
        if (scene !in HomeLibraryCardScenes && scene !in HomeToolCardScenes) return
        if (scene.tag in hiddenScenes) return
        hiddenScenes = hiddenScenes + scene.tag
        persistHidden()
    }

    fun show(scene: NavScene) {
        if (scene !in HomeLibraryCardScenes && scene !in HomeToolCardScenes) return
        val group = groupOf(scene) ?: return
        hiddenScenes = hiddenScenes - scene.tag
        // Re-added cards land after the currently visible cards, which makes the result obvious
        // and avoids a card appearing in the middle of a layout the user was editing.
        val visible = scenes(group, visibleOnly = true).filterNot { it == scene }
        val hidden = scenes(group, visibleOnly = false).filter { it != scene && it.tag in hiddenScenes }
        setOrder(group, visible + scene + hidden)
        persistHidden()
    }

    fun setVisible(scene: NavScene, visible: Boolean) {
        if (visible) show(scene) else hide(scene)
    }

    fun moveBy(scene: NavScene, delta: Int) {
        if (delta == 0) return
        val group = groupOf(scene) ?: return
        val visible = scenes(group, visibleOnly = true).toMutableList()
        val from = visible.indexOf(scene)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, visible.lastIndex)
        if (to == from) return
        visible.removeAt(from)
        visible.add(to, scene)
        val hidden = scenes(group, visibleOnly = false).filter { it.tag in hiddenScenes }
        setOrder(group, visible + hidden)
    }

    fun moveVisibleTo(scene: NavScene, targetVisibleIndex: Int) {
        val group = groupOf(scene) ?: return
        val visible = scenes(group, visibleOnly = true).toMutableList()
        val from = visible.indexOf(scene)
        if (from < 0) return
        val target = targetVisibleIndex.coerceIn(0, visible.lastIndex)
        if (target == from) return
        visible.removeAt(from)
        visible.add(target, scene)
        val hidden = scenes(group, visibleOnly = false).filter { it.tag in hiddenScenes }
        setOrder(group, visible + hidden)
    }

    fun setVisibleOrder(group: HomeCardGroup, visibleOrder: List<NavScene>) {
        val allowedVisible = visibleOrder
            .filter { groupOf(it) == group && it.tag !in hiddenScenes }
            .distinct()
        val missingVisible = scenes(group, visibleOnly = true).filterNot { it in allowedVisible }
        val hidden = scenes(group, visibleOnly = false).filter { it.tag in hiddenScenes }
        setOrder(group, allowedVisible + missingVisible + hidden)
    }

    fun reset() {
        hiddenScenes = emptySet()
        libraryOrder = HomeLibraryCardScenes
        toolOrder = HomeToolCardScenes
        preferences.edit()
            .putString(HOME_LIBRARY_ORDER_KEY, encode(HomeLibraryCardScenes))
            .putString(HOME_TOOL_ORDER_KEY, encode(HomeToolCardScenes))
            .putString(HOME_HIDDEN_KEY, "")
            .apply()
    }

    fun groupOf(scene: NavScene): HomeCardGroup? = when (scene) {
        in HomeLibraryCardScenes -> HomeCardGroup.LIBRARY
        in HomeToolCardScenes -> HomeCardGroup.TOOLS
        else -> null
    }

    private fun setOrder(group: HomeCardGroup, next: List<NavScene>) {
        when (group) {
            HomeCardGroup.LIBRARY -> {
                libraryOrder = normalizeOrder(next, HomeLibraryCardScenes)
                preferences.edit().putString(HOME_LIBRARY_ORDER_KEY, encode(libraryOrder)).apply()
            }
            HomeCardGroup.TOOLS -> {
                toolOrder = normalizeOrder(next, HomeToolCardScenes)
                preferences.edit().putString(HOME_TOOL_ORDER_KEY, encode(toolOrder)).apply()
            }
        }
    }

    private fun loadOrder(key: String, defaults: List<NavScene>): List<NavScene> {
        val raw = preferences.getString(key, null).orEmpty()
        if (raw.isBlank()) return defaults
        val byTag = defaults.associateBy { it.tag }
        val parsed = raw.split(HOME_CARD_SEPARATOR)
            .mapNotNull(byTag::get)
        return normalizeOrder(parsed, defaults)
    }

    private fun normalizeOrder(candidate: List<NavScene>, defaults: List<NavScene>): List<NavScene> {
        val allowed = defaults.toSet()
        val seen = LinkedHashSet<NavScene>()
        candidate.forEach { if (it in allowed) seen.add(it) }
        defaults.forEach(seen::add)
        return seen.toList()
    }

    private fun loadHidden(): Set<String> {
        val allowed = (HomeLibraryCardScenes + HomeToolCardScenes).map { it.tag }.toSet()
        return preferences.getString(HOME_HIDDEN_KEY, "").orEmpty()
            .split(HOME_CARD_SEPARATOR)
            .filter { it.isNotBlank() && it in allowed }
            .toSet()
    }

    private fun persistHidden() {
        preferences.edit().putString(HOME_HIDDEN_KEY, hiddenScenes.sorted().joinToString(HOME_CARD_SEPARATOR)).apply()
    }

    private fun encode(scenes: List<NavScene>): String = scenes.joinToString(HOME_CARD_SEPARATOR) { it.tag }
}

@Composable
fun rememberHomeCardLayoutState(): HomeCardLayoutState {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { HomeCardLayoutState(context) }
    DisposableEffect(state) {
        onDispose(state::dispose)
    }
    return state
}
