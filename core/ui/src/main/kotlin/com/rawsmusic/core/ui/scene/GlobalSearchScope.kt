package com.rawsmusic.core.ui.scene

/** Search dimensions shared by navigation entry points and the app search page. */
enum class GlobalSearchScope(val token: String) {
    ALBUM("album"),
    ARTIST("artist"),
    FOLDER("folder"),
    GENRE("genre"),
    YEAR("year"),
    COMPOSER("composer"),
    SONG("song");

    companion object {
        fun fromToken(token: String?): GlobalSearchScope? {
            return entries.firstOrNull { it.token == token }
        }

        fun fromScene(scene: NavScene): GlobalSearchScope? {
            return when (scene) {
                NavScene.SONGS -> SONG
                NavScene.ALBUMS, NavScene.ALBUM_DETAIL -> ALBUM
                NavScene.ARTISTS, NavScene.ARTIST_DETAIL -> ARTIST
                NavScene.FOLDERS, NavScene.FOLDER_HIERARCHY -> FOLDER
                NavScene.GENRE, NavScene.GENRE_DETAIL -> GENRE
                NavScene.YEAR, NavScene.YEAR_DETAIL -> YEAR
                NavScene.COMPOSER, NavScene.COMPOSER_DETAIL -> COMPOSER
                else -> null
            }
        }
    }
}
