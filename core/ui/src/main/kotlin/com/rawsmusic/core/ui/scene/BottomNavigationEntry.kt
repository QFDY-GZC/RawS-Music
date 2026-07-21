package com.rawsmusic.core.ui.scene

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
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
val customizableBottomNavigationScenes: List<NavScene> = listOf(
    NavScene.HOME,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.AUDIO_EFFECTS,
    NavScene.SEARCH,
    NavScene.SETTINGS,
)

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

fun NavScene.bottomNavigationLabel(): String = when (this) {
    NavScene.HOME -> "主界面"
    NavScene.SONGS -> "音乐库"
    NavScene.FOLDERS -> "文件夹"
    NavScene.ALBUMS -> "专辑"
    NavScene.ARTISTS -> "艺术家"
    NavScene.PLAYLISTS -> "歌单"
    NavScene.QUEUE -> "播放队列"
    NavScene.RECENTLY_ADDED -> "最近添加"
    NavScene.GENRE -> "流派"
    NavScene.AUDIO_EFFECTS -> "音效"
    NavScene.SEARCH -> "搜索"
    NavScene.SETTINGS -> "设置"
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

/** Maps detail/sub-scenes back to the tab that owns them. */
fun NavScene.bottomNavigationRoot(): NavScene = when (this) {
    NavScene.HOME -> NavScene.HOME

    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY -> NavScene.FOLDERS

    NavScene.ALBUMS,
    NavScene.ALBUM_DETAIL -> NavScene.ALBUMS

    NavScene.ARTISTS,
    NavScene.ARTIST_DETAIL -> NavScene.ARTISTS

    NavScene.PLAYLISTS,
    NavScene.PLAYLIST_DETAIL,
    NavScene.PLAYLIST_LIST,
    NavScene.PLAYLIST_DETAIL_PAGE -> NavScene.PLAYLISTS

    NavScene.QUEUE -> NavScene.QUEUE
    NavScene.RECENTLY_ADDED -> NavScene.RECENTLY_ADDED
    NavScene.GENRE,
    NavScene.GENRE_DETAIL -> NavScene.GENRE

    NavScene.AUDIO_EFFECTS,
    NavScene.BASS_TREBLE_BOOST,
    NavScene.COMPRESSOR,
    NavScene.PANORAMIC_360,
    NavScene.PEQ,
    NavScene.SPATIAL_SOUND,
    NavScene.SURROUND_360 -> NavScene.AUDIO_EFFECTS

    NavScene.SEARCH -> NavScene.SEARCH

    NavScene.SETTINGS,
    NavScene.APPEARANCE,
    NavScene.PERSONALIZATION_SETTINGS,
    NavScene.AUDIO_SETTINGS,
    NavScene.ALBUM_ART_SETTINGS,
    NavScene.GLOBAL_FONT_SETTINGS,
    NavScene.LYRIC_FONT_SETTINGS,
    NavScene.LYRIC_MANAGEMENT,
    NavScene.PLAYER_INTERFACE,
    NavScene.STATUS_BAR_LYRIC,
    NavScene.USB_DAC_SETTINGS,
    NavScene.WEBDAV_BACKUP,
    NavScene.SCAN_SETTINGS,
    NavScene.TRANSITION_SETTINGS,
    NavScene.ABOUT -> NavScene.SETTINGS

    else -> NavScene.SONGS
}

@Composable
fun BottomNavigationEntryIcon(
    scene: NavScene,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = scene.bottomNavigationLabel(),
) {
    val customIconRes = scene.customBottomNavigationIconResOrNull()
    if (customIconRes != null) {
        Image(
            painter = painterResource(customIconRes),
            contentDescription = contentDescription,
            modifier = modifier,
            colorFilter = ColorFilter.tint(tint),
        )
    } else {
        Icon(
            imageVector = scene.miuixBottomNavigationIcon(),
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}
