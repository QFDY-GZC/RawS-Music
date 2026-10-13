package com.rawsmusic.module.data.prefs

import org.junit.Assert.*
import org.junit.Test

class BackupPreferenceRegistryTest {
    @Test fun materialAndLibraryButtonChoicesAreNotOmitted() {
        listOf("personalization_bottom_bar_material_v1", "personalization_settings_surface_style_v1",
            "personalization_notice_surface_style_v1", "personalization_library_bottom_buttons_mode_v1",
            "personalization_library_bottom_buttons_layout_v1", "personalization_library_bottom_buttons_surface_v1")
            .forEach { assertEquals(it, BackupPreferenceSchema.ValueType.STRING, rule("mmkv", it)?.type) }
        assertEquals(BackupPreferenceSchema.ValueType.FLOAT, rule("mmkv", "personalization_bottom_nav_style_bottom_lift_v1")?.type)
        validate("mmkv", "personalization_bottom_bar_material_v1", PreferenceValue.StringValue("liquid_glass"))
        rejected { validate("mmkv", "personalization_bottom_bar_material_v1", PreferenceValue.StringValue("bad_material")) }
    }

    @Test fun mmkvSourceContractsHaveExplicitClassificationAndCorrectTypes() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "module/data/src/main/kotlin").isDirectory }
        val sourceFiles = java.io.File(root, "module/data/src/main/kotlin/com/rawsmusic/module/data/prefs")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        val constants = Regex("const val (\\w+) = \"([^\"]+)\"")
        val reads = Regex("decode(Bool|Int|Long|Float|Double|StringSet|String)\\(\\s*(\\w+|\"[^\"]+\")")
        val types = mapOf("Bool" to BackupPreferenceSchema.ValueType.BOOLEAN,
            "Int" to BackupPreferenceSchema.ValueType.INT, "Long" to BackupPreferenceSchema.ValueType.LONG,
            "Float" to BackupPreferenceSchema.ValueType.FLOAT, "Double" to BackupPreferenceSchema.ValueType.DOUBLE,
            "String" to BackupPreferenceSchema.ValueType.STRING, "StringSet" to BackupPreferenceSchema.ValueType.STRING_SET)
        var checked = 0
        sourceFiles.forEach { file ->
            val source = file.readText()
            val names = constants.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
            reads.findAll(source).forEach { match ->
                val token = match.groupValues[2]
                val key = if (token.startsWith('"')) token.trim('"') else names[token]
                if (key != null) {
                    checked++
                    if (key !in BackupPreferenceSchema.excludedKeys && key !in BackupPreferenceSchema.sensitiveKeys) {
                        assertEquals("${file.name}: $key", types[match.groupValues[1]], rule("mmkv", key)?.type)
                    }
                }
            }
        }
        assertTrue("Audit fixture did not discover any preferences", checked > 300)
    }

    @Test fun nestedDspDataRejectsMalformedAndWronglyTypedValues() {
        validate("mmkv", "eq_band_levels", PreferenceValue.StringValue("[0,1,-1,2,0]"))
        validate("mmkv", "graphic_eq_gains", PreferenceValue.StringValue("[0.0,1.5,-2.0]"))
        rejected { validate("mmkv", "eq_band_levels", PreferenceValue.StringValue("not-json")) }
        rejected { validate("mmkv", "eq_band_levels", PreferenceValue.StringValue("[\"0\"]")) }
        rejected { validate("mmkv", "peq_filters_json", PreferenceValue.StringValue("[{}]")) }
        rejected { validate("mmkv", "peq_filters_json", PreferenceValue.StringValue("[{\"frequency\":1000,\"frequency\":2000}]")) }
    }

    @Test fun savedEqPresetsMustRemainCompleteAndUniquelyIdentified() {
        validate("mmkv", "eq_presets", PreferenceValue.StringValue("[{\"id\":1,\"name\":\"Normal\",\"bandLevels\":[0,0,0,0,0],\"bassBoost\":0,\"virtualizer\":0,\"isBuiltIn\":true}]"))
        rejected { validate("mmkv", "eq_presets", PreferenceValue.StringValue("[{\"id\":1}]")) }
        rejected { validate("mmkv", "eq_presets", PreferenceValue.StringValue("{}")) }
    }

    @Test fun sharedEnumsAndDynamicPluginKeysAreRestricted() {
        assertNotNull(rule("lyrico_sources", "enabled:org.example.lyrics"))
        assertNull(rule("lyrico_sources", "enabled:../secret"))
        rejected { validate("home_header_preferences", "carousel_style", PreferenceValue.IntValue(2)) }
        rejected { validate("raw_flow_background", "frame_rate_fps", PreferenceValue.IntValue(144)) }
        rejected { validate("home_card_layout_preferences", "library_order", PreferenceValue.StringValue("songs|fake")) }
    }

    @Test fun libraryDisplayChoicesArePortableButScrollPositionsAreNot() {
        validate("power_list_prefs", "artists_column_count", PreferenceValue.IntValue(4))
        validate("power_list_prefs", "album_detail_songs_list_zoom_level", PreferenceValue.IntValue(0))
        assertNull(rule("power_list_prefs", "unknown_column_count"))
        assertNull(rule("power_list_prefs", "artists_scroll_position"))
        val detailSort = "sort_collection_detail_0123456789abcdef0123456789abcdef"
        validate("mmkv", detailSort, PreferenceValue.StringValue("TITLE_ASC"))
        rejected { validate("mmkv", detailSort, PreferenceValue.StringValue("FAKE_SORT")) }
        assertNull(rule("mmkv", "sort_collection_detail_anything"))
    }

    @Test fun nestedNotificationAndResourceDataCannotBeSilentlyReset() {
        validate("mmkv", "scanner_music_folder_uris", PreferenceValue.StringValue("content://com.android.externalstorage.documents/tree/primary%3AMusic\ncontent://example/tree/1"))
        validate("mmkv", "ui_scan_paths", PreferenceValue.StringValue("[\"/storage/emulated/0/Music\"]"))
        rejected { validate("mmkv", "ui_scan_paths", PreferenceValue.StringValue("[1]")) }
        rejected { validate("mmkv", "ui_video_cover_current_assignments", PreferenceValue.StringValue("[]")) }
        rejected { validate("mmkv", "lyrics_xiaomi_super_island_settings", PreferenceValue.StringValue("{\"scroll\":\"true\"}")) }
        validate("mmkv", "lyrics_media_notification_buttons", PreferenceValue.StringValue("playback_mode,favorite"))
        rejected { validate("mmkv", "lyrics_media_notification_buttons", PreferenceValue.StringValue("playback_mode,fake")) }
    }

    @Test fun resourcesRequireSeparateOptIn() {
        listOf("scanner_music_folder_uris", "ui_custom_font_path", "lyric_font_path", "fft_convolver_ir_uri")
            .forEach { key ->
                assertEquals(key, BackupSection.DEVICE_REFERENCES, rule("mmkv", key)?.section)
                assertFalse(BackupSelection().sections.contains(rule("mmkv", key)?.section))
            }
    }

    @Test fun runtimeAndDestructiveOptionsCannotBeImported() {
        listOf("player_queue_json", "player_volume", "usb_hw_volume_unclean_active", "scan_cold_directory_fingerprints_v1")
            .forEach { assertNull(rule("mmkv", it)) }
        assertNull(rule("audio_transcode_ui_options", "delete_source_on_success"))
        assertNull(rule("power_list_prefs", "SONGS_position"))
        assertNull(rule("home_header_preferences", "arbitrary_new_key"))
    }

    @Test fun conversionChoicesKeepActualStoredTypesAndNullSentinel() {
        assertEquals(BackupPreferenceSchema.ValueType.INT, rule("audio_transcode_ui_options", "sample_rate_hz")?.type)
        validate("audio_transcode_ui_options", "sample_rate_hz", PreferenceValue.IntValue(Int.MIN_VALUE))
        validate("audio_transcode_ui_options", "format", PreferenceValue.StringValue("AAC"))
        rejected { validate("audio_transcode_ui_options", "format", PreferenceValue.StringValue("FAKE")) }
        rejected { validate("audio_transcode_ui_options", "flac_compression_level", PreferenceValue.IntValue(13)) }
        rejected { validate("audio_transcode_ui_options", "strict_metadata", PreferenceValue.StringValue("true")) }
    }

    @Test fun numericValuesAreFiniteAndRespectUserSettingBounds() {
        validate("mmkv", "ui_font_size_scale", PreferenceValue.IntValue(160))
        rejected { validate("mmkv", "ui_font_size_scale", PreferenceValue.IntValue(161)) }
        rejected { validate("mmkv", "player_playback_speed", PreferenceValue.FloatValue(Float.NaN)) }
        rejected { validate("mmkv", "player_playback_speed", PreferenceValue.FloatValue(Float.POSITIVE_INFINITY)) }
        rejected { validate("mmkv", "hdr_window_headroom", PreferenceValue.FloatValue(9f)) }
    }

    @Test fun hdrScenesAndSignatureCannotContainInvalidValues() {
        validate("mmkv", "hdr_window_scenes", PreferenceValue.StringSetValue(setOf("PLAYER", "SETTINGS")))
        rejected { validate("mmkv", "hdr_window_scenes", PreferenceValue.StringSetValue(setOf("NO_SCENE"))) }
        rejected { validate("mmkv", "lyrics_export_signature", PreferenceValue.StringValue("x".repeat(41))) }
    }

    private fun rule(store: String, key: String) = BackupPreferenceRegistry.classify(PreferenceAddress(store, key))
    private fun validate(store: String, key: String, value: PreferenceValue) =
        BackupPreferenceRegistry.validate(PreferenceEntry(PreferenceAddress(store, key), value))
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("Invalid preference was accepted")
    }
}
