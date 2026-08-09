package com.rawsmusic.core.ui.scene

/** Maps detail/sub-scenes back to the tab that owns them. */
fun NavScene.bottomNavigationRoot(): NavScene = when (this) {
    NavScene.HOME,
    NavScene.SOURCE_IMPORT -> NavScene.HOME

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
