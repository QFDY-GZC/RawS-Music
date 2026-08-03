package com.rawsmusic

import com.rawsmusic.core.ui.scene.NavScene

/**
 * Static navigation decisions shared by MainActivity entry points.
 *
 * Keeping resource-id mapping out of the Activity makes the Activity a host for
 * navigation state instead of the owner of every destination table.
 */
internal object MainActivityNavigationPolicy {

    fun settingsSceneForDestination(destinationId: Int): NavScene = when (destinationId) {
        R.id.nav_lyric_management -> NavScene.LYRIC_MANAGEMENT
        R.id.nav_status_bar_lyric -> NavScene.STATUS_BAR_LYRIC
        R.id.nav_appearance -> NavScene.APPEARANCE
        R.id.nav_audio_settings -> NavScene.AUDIO_SETTINGS
        R.id.nav_audio_effects -> NavScene.AUDIO_EFFECTS
        R.id.nav_player_interface -> NavScene.PLAYER_INTERFACE
        R.id.nav_usb_dac_settings -> NavScene.USB_DAC_SETTINGS
        R.id.nav_peq -> NavScene.PEQ
        R.id.nav_compressor -> NavScene.COMPRESSOR
        R.id.nav_bass_treble_boost -> NavScene.BASS_TREBLE_BOOST
        R.id.nav_spatial_sound -> NavScene.SPATIAL_SOUND
        R.id.nav_surround_360 -> NavScene.SURROUND_360
        R.id.nav_panoramic_360 -> NavScene.PANORAMIC_360
        R.id.nav_lyric_font_settings -> NavScene.LYRIC_FONT_SETTINGS
        R.id.nav_global_font_settings -> NavScene.GLOBAL_FONT_SETTINGS
        R.id.nav_webdav_backup -> NavScene.WEBDAV_BACKUP
        R.id.nav_log_viewer -> NavScene.LOG_VIEWER
        R.id.nav_album_art_settings -> NavScene.ALBUM_ART_SETTINGS
        else -> NavScene.SETTINGS
    }
}
