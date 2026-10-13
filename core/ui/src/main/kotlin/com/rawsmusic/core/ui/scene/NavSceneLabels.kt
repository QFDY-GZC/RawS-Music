package com.rawsmusic.core.ui.scene

import androidx.annotation.StringRes
import com.rawsmusic.core.ui.R

@StringRes
fun NavScene.labelRes(): Int = when (this) {
    NavScene.HOME -> R.string.nav_scene_home
    NavScene.SONGS -> R.string.nav_scene_songs
    NavScene.FOLDERS -> R.string.nav_scene_folders
    NavScene.FOLDER_HIERARCHY -> R.string.nav_scene_folder_hierarchy
    NavScene.ALBUMS -> R.string.nav_scene_albums
    NavScene.ALBUM_DETAIL -> R.string.nav_scene_album_detail
    NavScene.ARTISTS -> R.string.nav_scene_artists
    NavScene.ARTIST_DETAIL -> R.string.nav_scene_artist_detail
    NavScene.PLAYLISTS -> R.string.nav_scene_playlists
    NavScene.PLAYLIST_DETAIL -> R.string.nav_scene_playlist_detail
    NavScene.QUEUE -> R.string.nav_scene_queue
    NavScene.RECENTLY_ADDED -> R.string.nav_scene_recently_added
    NavScene.WEBDAV -> R.string.nav_scene_webdav
    NavScene.ABOUT -> R.string.nav_scene_about
    NavScene.SONG_STATS -> R.string.nav_scene_song_stats
    NavScene.LOG_VIEWER -> R.string.nav_scene_log_viewer
    NavScene.ANALYTICS -> R.string.nav_scene_analytics
    NavScene.PLAYLIST_LIST -> R.string.nav_scene_playlist_list
    NavScene.PLAYLIST_DETAIL_PAGE -> R.string.nav_scene_playlist_detail_page
    NavScene.SEARCH -> R.string.nav_scene_search
    NavScene.SETTINGS -> R.string.nav_scene_settings
    NavScene.APPEARANCE -> R.string.nav_scene_appearance
    NavScene.AUDIO_EFFECTS -> R.string.nav_scene_audio_effects
    NavScene.AUDIO_SETTINGS -> R.string.nav_scene_audio_settings
    NavScene.ALBUM_ART_SETTINGS -> R.string.nav_scene_album_art_settings
    NavScene.BASS_TREBLE_BOOST -> R.string.nav_scene_bass_treble_boost
    NavScene.COMPRESSOR -> R.string.nav_scene_compressor
    NavScene.GLOBAL_FONT_SETTINGS -> R.string.nav_scene_global_font_settings
    NavScene.LYRIC_FONT_SETTINGS -> R.string.nav_scene_lyric_font_settings
    NavScene.LYRIC_MANAGEMENT -> R.string.nav_scene_lyric_management
    NavScene.PANORAMIC_360 -> R.string.nav_scene_panoramic_360
    NavScene.PEQ -> R.string.nav_scene_peq
    NavScene.PLAYER_INTERFACE -> R.string.nav_scene_player_interface
    NavScene.SPATIAL_SOUND -> R.string.nav_scene_spatial_sound
    NavScene.STATUS_BAR_LYRIC -> R.string.nav_scene_status_bar_lyric
    NavScene.SURROUND_360 -> R.string.nav_scene_surround_360
    NavScene.USB_DAC_SETTINGS -> R.string.nav_scene_usb_dac_settings
    NavScene.WEBDAV_BACKUP -> R.string.nav_scene_webdav_backup
    NavScene.DAILY_20 -> R.string.nav_scene_daily_20
    NavScene.GENRE -> R.string.nav_scene_genre
    NavScene.YEAR -> R.string.nav_scene_year
    NavScene.COMPOSER -> R.string.nav_scene_composer
    NavScene.GENRE_DETAIL -> R.string.nav_scene_genre_detail
    NavScene.YEAR_DETAIL -> R.string.nav_scene_year_detail
    NavScene.COMPOSER_DETAIL -> R.string.nav_scene_composer_detail
    NavScene.SCAN_SETTINGS -> R.string.nav_scene_scan_settings
    NavScene.TRANSITION_SETTINGS -> R.string.nav_scene_transition_settings
    NavScene.PERSONALIZATION_SETTINGS -> R.string.nav_scene_personalization_settings
    NavScene.SOURCE_IMPORT -> R.string.nav_scene_source_import
    NavScene.FOLDER_DETAIL -> R.string.nav_scene_folder_detail
    NavScene.LIBRARY_ANALYSIS_DETAIL -> R.string.nav_scene_library_analysis_detail
    NavScene.ARTIST_BIOGRAPHY -> R.string.nav_scene_artist_biography
    NavScene.ARTIST_ALL_SONGS -> R.string.nav_scene_artist_all_songs
    NavScene.HDR_SETTINGS -> R.string.settings_hdr_title
}
