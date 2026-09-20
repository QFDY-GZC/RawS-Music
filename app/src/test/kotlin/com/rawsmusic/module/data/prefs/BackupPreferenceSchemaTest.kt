package com.rawsmusic.module.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceSchemaTest {
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
