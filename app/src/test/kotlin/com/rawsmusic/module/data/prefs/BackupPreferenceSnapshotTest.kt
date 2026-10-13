package com.rawsmusic.module.data.prefs

import org.junit.Assert.*
import org.junit.Test

class BackupPreferenceSnapshotTest {
    @Test fun roundTripKeepsMissingKeysAndOriginalTypesInsteadOfDefaults() {
        val original = linkedMapOf(
            PreferenceAddress("home_header_preferences", "carousel_style") to PreferenceValue.LongValue(9),
            PreferenceAddress("mmkv", "hdr_window_enabled") to null,
            PreferenceAddress("mmkv", "player_playback_speed") to PreferenceValue.FloatValue(1.5f),
            PreferenceAddress("raw_flow_background", "custom") to PreferenceValue.StringSetValue(setOf("", "one")),
        )
        val snapshot = BackupPreferenceSnapshot.decode(BackupPreferenceSnapshot.encode(original))
        assertEquals(original, snapshot)
        assertTrue(snapshot.containsKey(PreferenceAddress("mmkv", "hdr_window_enabled")))
        assertNull(snapshot[PreferenceAddress("mmkv", "hdr_window_enabled")])
        assertEquals(PreferenceValue.LongValue(9), snapshot[PreferenceAddress("home_header_preferences", "carousel_style")])
    }

    @Test fun duplicateSnapshotAddressesFailBeforeRollbackWrites() {
        val entry = """{"store":"mmkv","key":"hdr_window_enabled","present":false}"""
        assertTrue(runCatching { BackupPreferenceSnapshot.decode("[$entry,$entry]".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun presentKeyRequiresAnExactTypedValue() {
        for (bad in listOf(
            """{"store":"mmkv","key":"x","present":true}""",
            """{"store":"mmkv","key":"x","present":true,"type":"INT","value":1.2}""",
            """{"store":"mmkv","key":"x","present":true,"type":"BOOLEAN","value":"false"}""",
            """{"store":"mmkv","key":"x","present":false,"value":true}""",
        )) assertTrue(bad, runCatching { BackupPreferenceSnapshot.decode("[$bad]".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
