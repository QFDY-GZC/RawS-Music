package com.rawsmusic.core.ui.scene

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * Main bottom-navigation entries that can be selected from Personalization settings.
 * HOME is mandatory so the user always has a stable way back to the main screen.
 */
val customizableBottomNavigationScenes: List<NavScene> = NavScene.bottomNavigationEntries

val defaultBottomNavigationScenes: List<NavScene> = listOf(
    NavScene.HOME,
    NavScene.SONGS,
    NavScene.AUDIO_EFFECTS,
    NavScene.SEARCH,
    NavScene.SETTINGS,
)

fun resolveBottomNavigationScenes(tags: List<String>): List<NavScene> {
    val allowed = customizableBottomNavigationScenes.associateBy { it.tag }
    val resolved = tags
        .mapNotNull(allowed::get)
        .distinct()
        .toMutableList()

    if (NavScene.HOME !in resolved) resolved.add(0, NavScene.HOME)
    if (resolved.size < 2) {
        defaultBottomNavigationScenes.firstOrNull { it !in resolved }?.let(resolved::add)
    }
    return resolved.take(MAX_BOTTOM_NAVIGATION_ITEMS)
}

const val MAX_BOTTOM_NAVIGATION_ITEMS = 5
const val MIN_BOTTOM_NAVIGATION_ITEMS = 2

@Composable
fun NavScene.bottomNavigationLabel(): String = when (this) {
    NavScene.HOME -> stringResource(R.string.bottom_nav_home)
    NavScene.SONGS -> stringResource(R.string.bottom_nav_library)
    NavScene.FOLDERS -> stringResource(R.string.library_title_folders)
    NavScene.ALBUMS -> stringResource(R.string.library_title_albums)
    NavScene.ARTISTS -> stringResource(R.string.library_title_artists)
    NavScene.PLAYLISTS -> stringResource(R.string.library_title_playlists)
    NavScene.QUEUE -> stringResource(R.string.library_title_queue)
    NavScene.RECENTLY_ADDED -> stringResource(R.string.library_title_recently_added)
    NavScene.GENRE -> stringResource(R.string.library_title_genres)
    NavScene.AUDIO_EFFECTS -> stringResource(R.string.bottom_nav_effects)
    NavScene.SEARCH -> stringResource(R.string.bottom_nav_search)
    NavScene.SETTINGS -> stringResource(R.string.bottom_nav_settings)
    else -> label
}

@DrawableRes
private fun NavScene.customBottomNavigationIconResOrNull(): Int? = when (this) {
    NavScene.HOME -> R.drawable.ic_home_3_fill
    NavScene.AUDIO_EFFECTS -> R.drawable.ic_audio_effects_custom
    NavScene.FOLDERS -> R.drawable.ic_nav_custom_folders
    NavScene.ALBUMS -> R.drawable.ic_nav_custom_albums
    NavScene.ARTISTS -> R.drawable.ic_nav_custom_artists
    NavScene.PLAYLISTS -> R.drawable.ic_nav_custom_playlists
    NavScene.QUEUE -> R.drawable.ic_nav_custom_queue
    NavScene.RECENTLY_ADDED -> R.drawable.ic_nav_custom_recent
    NavScene.GENRE -> R.drawable.ic_nav_custom_genre
    else -> null
}

private fun NavScene.miuixBottomNavigationIcon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    NavScene.SONGS -> MiuixIcons.Regular.Music
    NavScene.SEARCH -> MiuixIcons.Regular.Search
    NavScene.SETTINGS -> MiuixIcons.Regular.Settings
    else -> MiuixIcons.Regular.Music
}

@Composable
fun BottomNavigationEntryIcon(
    scene: NavScene,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val resolvedContentDescription = contentDescription ?: scene.bottomNavigationLabel()
    val customIconRes = scene.customBottomNavigationIconResOrNull()
    if (customIconRes != null) {
        Image(
            painter = painterResource(customIconRes),
            contentDescription = resolvedContentDescription,
            modifier = modifier,
            colorFilter = ColorFilter.tint(tint),
        )
    } else {
        Icon(
            imageVector = scene.miuixBottomNavigationIcon(),
            contentDescription = resolvedContentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}
