package com.rawsmusic.module.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceSchemaTest {
    @Test
    fun recentlyAddedUserChoicesAreNotLostOnBackup() {
        mapOf(
            "hdr_window_enabled" to BackupPreferenceSchema.ValueType.BOOLEAN,
            "hdr_window_scenes" to BackupPreferenceSchema.ValueType.STRING_SET,
            "hdr_window_headroom" to BackupPreferenceSchema.ValueType.FLOAT,
            "lyrics_export_signature" to BackupPreferenceSchema.ValueType.STRING,
            "ui_player_swipe_to_change_track_disabled" to BackupPreferenceSchema.ValueType.BOOLEAN,
        ).forEach { (key, type) -> assertEquals(key, type, BackupPreferenceSchema.typeForBackup(key)) }
    }

    @Test
    fun deviceResourcesAreNotInDefaultPortableBackup() {
        listOf(
            "scanner_music_folder_uris", "ui_scan_paths", "ui_root_scan_paths",
            "ui_custom_font_path", "lyric_font_path", "fft_convolver_ir_uri",
            "ui_video_cover_permanent_uri", "ui_video_cover_current_assignments",
            "personalization_player_hires_badge_custom_path_v1",
        ).forEach { assertNull(it, BackupPreferenceSchema.typeForBackup(it)) }
    }

    @Test
    fun playerMoreFavoriteVisibilityIsPortableAsBoolean() {
        assertEquals(
            BackupPreferenceSchema.ValueType.BOOLEAN,
            BackupPreferenceSchema.typeForBackup("ui_player_more_favorite_visible"),
        )
    }

    @Test
    fun portableSettingsKeepUserConfiguration() {
        assertEquals(
            BackupPreferenceSchema.ValueType.BOOLEAN,
            BackupPreferenceSchema.typeForBackup("aa_higher_res"),
        )
        assertEquals(
            BackupPreferenceSchema.ValueType.FLOAT,
            BackupPreferenceSchema.typeForBackup("player_playback_speed"),
        )
        assertEquals(
            BackupPreferenceSchema.ValueType.STRING,
            BackupPreferenceSchema.typeForBackup("peq_filters_json"),
        )
    }

    @Test
    fun runtimeAndFileInventoryStateIsNotPortable() {
        assertNull(BackupPreferenceSchema.typeForBackup("player_queue_json"))
        assertNull(BackupPreferenceSchema.typeForBackup("scan_cold_directory_fingerprints_v1"))
        assertNull(BackupPreferenceSchema.typeForBackup("usb_hw_volume_active_boot_id"))
        assertNull(BackupPreferenceSchema.typeForBackup("music_source_plugins_v1"))
        assertTrue("webdav_password" in BackupPreferenceSchema.sensitiveKeys)
    }
}
