package com.rawsmusic.module.data.prefs

internal data class PreferenceRule(
    val type: BackupPreferenceSchema.ValueType,
    val section: BackupSection,
    val validateValue: (PreferenceValue) -> Unit = {},
    val dependency: PreferenceDependency? = null,
)

internal enum class PreferenceDependency { FILE_OR_URI, INSTALLED_PLUGIN, INSTALLED_MODEL }

/** Explicit admission policy shared by export and import. Unknown keys are never writable. */
internal object BackupPreferenceRegistry {
    private val listNamespaces = setOf("", "home_", "songs_", "folders_", "folder_hierarchy_",
        "albums_", "album_detail_songs_", "artists_", "artist_detail_songs_", "artist_all_songs_",
        "artist_biography_", "genres_", "genre_detail_songs_", "years_", "year_detail_songs_",
        "composers_", "composer_detail_songs_", "folder_detail_songs_", "playlists_", "queue_",
        "recently_added_", "library_analysis_root_", "library_analysis_detail_", "global_search_results_",
        "inline_queue_fullscreen_")

    fun classify(address: PreferenceAddress): PreferenceRule? {
        if (address.store != "mmkv") {
            sharedRules[address]?.let { return it }
            if (address.store == "power_list_prefs") {
                listNamespaces.forEach { prefix ->
                    if (address.key == prefix + "column_count") return PreferenceRule(BackupPreferenceSchema.ValueType.INT, BackupSection.PREFERENCES, intRange(1, 4))
                    if (address.key == prefix + "list_zoom_level") return PreferenceRule(BackupPreferenceSchema.ValueType.INT, BackupSection.PREFERENCES, intChoices(0, 3, 4))
                }
            }
            if (address.store == "lyrico_sources" && address.key.length <= 136 && address.key.matches(Regex("enabled:[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                return PreferenceRule(BackupPreferenceSchema.ValueType.BOOLEAN, BackupSection.PREFERENCES, dependency = PreferenceDependency.INSTALLED_PLUGIN)
            }
            return null
        }
        if (address.key.matches(Regex("sort_collection_detail_[0-9a-f]{32}"))) {
            return PreferenceRule(BackupPreferenceSchema.ValueType.STRING, BackupSection.PREFERENCES,
                choices(*com.rawsmusic.core.common.model.SortOrder.entries.map { it.name }.toTypedArray()))
        }
        if (address.key in BackupPreferenceSchema.excludedKeys || address.key in BackupPreferenceSchema.sensitiveKeys) return null
        val type = BackupPreferenceSchema.types[address.key] ?: return null
        val resource = address.key in BackupPreferenceSchema.deviceReferenceKeys
        return PreferenceRule(
            type, if (resource) BackupSection.DEVICE_REFERENCES else BackupSection.PREFERENCES,
            mmkvValidators[address.key] ?: {},
            if (resource) PreferenceDependency.FILE_OR_URI else null,
        )
    }

    fun validate(entry: PreferenceEntry) {
        val rule = requireNotNull(classify(entry.address)) { "Unknown preference: ${entry.address}" }
        require(entry.value.type == rule.type) { "Wrong preference type: ${entry.address}" }
        when (val value = entry.value) {
            is PreferenceValue.FloatValue -> require(value.value.isFinite()) { "Non-finite preference: ${entry.address}" }
            is PreferenceValue.DoubleValue -> require(value.value.isFinite()) { "Non-finite preference: ${entry.address}" }
            else -> Unit
        }
        rule.validateValue(entry.value)
    }

    /** Only this verified historical wire format is migrated; arbitrary obfuscated JSON is rejected. */
    fun normalizeLegacy(entry: PreferenceEntry): PreferenceEntry {
        if (entry.address != PreferenceAddress("mmkv", "peq_filters_json") || entry.value !is PreferenceValue.StringValue || entry.value.value.isBlank()) return entry
        BackupStrictJson.parse(entry.value.value) // Detect nested duplicate members before conversion.
        val filters = com.rawsmusic.module.player.dsp.PeqFilterJsonCodec.decode(entry.value.value)
        return entry.copy(value = PreferenceValue.StringValue(com.rawsmusic.module.player.dsp.PeqFilterJsonCodec.encode(filters)))
    }

    private fun intRange(min: Int, max: Int): (PreferenceValue) -> Unit = {
        require((it as PreferenceValue.IntValue).value in min..max) { "Preference outside $min..$max" }
    }
    private fun floatRange(min: Float, max: Float): (PreferenceValue) -> Unit = {
        require((it as PreferenceValue.FloatValue).value in min..max) { "Preference outside $min..$max" }
    }
    private fun choices(vararg values: String): (PreferenceValue) -> Unit = {
        require((it as PreferenceValue.StringValue).value in values) { "Invalid preference choice" }
    }
    private fun intChoices(vararg values: Int): (PreferenceValue) -> Unit = {
        require((it as PreferenceValue.IntValue).value in values) { "Invalid integer choice" }
    }

    private fun numericArray(value: PreferenceValue, integral: Boolean) {
        val text = (value as PreferenceValue.StringValue).value
        if (text.isEmpty()) return // Existing unset/default encoding.
        val tree = BackupStrictJson.parse(text)
        require(tree.isJsonArray && tree.asJsonArray.size() <= 128) { "Invalid EQ bands" }
        tree.asJsonArray.forEach {
            if (integral) require(BackupStrictJson.integer(it) in -1500..1500)
            else require(BackupStrictJson.number(it) in -24.0..24.0)
        }
    }

    private fun peqFilters(value: PreferenceValue) {
        val text = (value as PreferenceValue.StringValue).value
        if (text.isEmpty()) return
        val tree = BackupStrictJson.parse(text)
        require(tree.isJsonArray && tree.asJsonArray.size() <= 40) { "Invalid PEQ filters" }
        tree.asJsonArray.forEach { item ->
            require(item.isJsonObject) { "Invalid PEQ filter" }
            val filter = item.asJsonObject
            require(filter.keySet().containsAll(setOf("type", "frequency", "gainDB", "Q", "enabled"))) { "Incomplete PEQ filter" }
            require(filter["type"].isJsonPrimitive && filter["type"].asJsonPrimitive.isString)
            require(filter["type"].asString in setOf("PEAK", "PEAK_ANALOG", "LOW_SHELF", "HIGH_SHELF", "LOW_PASS", "HIGH_PASS", "BAND_PASS", "NOTCH"))
            require(BackupStrictJson.number(filter["frequency"]) in 20.0..20000.0)
            require(BackupStrictJson.number(filter["gainDB"]) in -24.0..24.0)
            require(BackupStrictJson.number(filter["Q"]) in 0.05..24.0)
            require(filter["enabled"].isJsonPrimitive && filter["enabled"].asJsonPrimitive.isBoolean)
        }
    }

    private fun eqPresets(value: PreferenceValue) {
        val text = (value as PreferenceValue.StringValue).value
        if (text.isEmpty()) return
        val tree = BackupStrictJson.parse(text)
        require(tree.isJsonArray) { "Invalid saved EQ presets" }
        val ids = HashSet<Long>()
        tree.asJsonArray.forEach { item ->
            require(item.isJsonObject) { "Invalid EQ preset" }
            val preset = item.asJsonObject
            require(preset.keySet().containsAll(setOf("id", "name", "bandLevels", "bassBoost", "virtualizer", "isBuiltIn"))) { "Incomplete EQ preset" }
            require(ids.add(BackupStrictJson.integer(preset["id"]))) { "Duplicate EQ preset ID" }
            require(preset["name"].isJsonPrimitive && preset["name"].asJsonPrimitive.isString)
            numericArray(PreferenceValue.StringValue(preset["bandLevels"].toString()), integral = true)
            require(BackupStrictJson.integer(preset["bassBoost"]) in 0..1000)
            require(BackupStrictJson.integer(preset["virtualizer"]) in 0..1000)
            require(preset["isBuiltIn"].isJsonPrimitive && preset["isBuiltIn"].asJsonPrimitive.isBoolean)
        }
    }

    private fun stringCollection(value: PreferenceValue, objectMap: Boolean = false) {
        val text = (value as PreferenceValue.StringValue).value
        if (text.isEmpty()) return
        val tree = BackupStrictJson.parse(text)
        val items = if (objectMap) {
            require(tree.isJsonObject) { "Expected resource map" }
            tree.asJsonObject.entrySet().map { it.value }
        } else {
            require(tree.isJsonArray) { "Expected resource list" }
            tree.asJsonArray.toList()
        }
        require(items.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Expected resource strings" }
    }

    private fun islandSettings(value: PreferenceValue) {
        val text = (value as PreferenceValue.StringValue).value
        if (text.isEmpty()) return
        val tree = BackupStrictJson.parse(text)
        require(tree.isJsonObject) { "Invalid island settings" }
        val booleans = setOf("leftCover", "scroll", "colorize", "progressColor", "share")
        val ranges = mapOf("text" to 0L..2L, "mode" to 0L..1L, "rightChars" to 6L..14L,
            "leftCoverChars" to 4L..10L, "leftChars" to 6L..14L, "colorSource" to 0L..1L,
            "customColor" to Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "actions" to 0L..1L,
            "notificationStyle" to 0L..1L, "buttonLayout" to 0L..1L, "click" to 0L..2L,
            "shareFormat" to 0L..2L, "xmsf" to 0L..3L, "xmsfDuration" to 100L..500L,
            "dismissDelay" to 0L..5000L)
        tree.asJsonObject.entrySet().forEach { (key, field) ->
            if (key in booleans) require(field.isJsonPrimitive && field.asJsonPrimitive.isBoolean) { "Invalid island boolean" }
            else {
                val range = requireNotNull(ranges[key]) { "Unknown island setting" }
                val number = BackupStrictJson.integer(field)
                require(number in range) { "Island setting out of range" }
                if (key == "xmsfDuration") require(number % 50L == 0L)
                if (key == "dismissDelay") require(number in setOf(0L, 1000L, 3000L, 5000L))
            }
        }
    }

    private val mmkvValidators = mapOf<String, (PreferenceValue) -> Unit>(
        "ui_scan_paths" to { stringCollection(it) },
        "ui_root_scan_paths" to { stringCollection(it) },
        "scanner_music_folder_uris" to { value ->
            val text = (value as PreferenceValue.StringValue).value
            val uris = if (text.isEmpty()) emptyList() else text.split('\n')
            require(uris.distinct().size == uris.size && uris.all { it.isNotBlank() && it.startsWith("content://") }) { "Invalid folder URI list" }
        },
        "lyrics_media_notification_buttons" to { value ->
            val text = (value as PreferenceValue.StringValue).value
            val actions = if (text.isEmpty()) emptyList() else text.split(',')
            require(actions.size <= 2 && actions.distinct().size == actions.size && actions.all { it in setOf("playback_mode", "desktop_lyric", "favorite") }) { "Invalid media notification buttons" }
        },
        "ui_video_cover_current_assignments" to { stringCollection(it, objectMap = true) },
        "lyrics_xiaomi_super_island_settings" to ::islandSettings,
        "personalization_bottom_bar_material_v1" to choices("solid", "acrylic", "liquid_glass"),
        "personalization_settings_surface_style_v1" to choices("solid", "acrylic", "liquid_glass"),
        "personalization_notice_surface_style_v1" to choices("solid", "acrylic", "liquid_glass"),
        "personalization_library_bottom_buttons_layout_v1" to choices("horizontal", "vertical"),
        "personalization_library_bottom_buttons_mode_v1" to choices("disabled", "semi_transparent", "enabled"),
        "personalization_library_bottom_buttons_surface_v1" to choices("solid", "frosted", "liquid_glass"),
        "eq_presets" to ::eqPresets,
        "eq_band_levels" to { numericArray(it, integral = true) },
        "graphic_eq_gains" to { numericArray(it, integral = false) },
        "peq_filters_json" to ::peqFilters,
        "ui_inline_player_lyric_font_size_sp" to intChoices(24, 28, 32, 36, 40),
        "ui_font_size_scale" to intRange(70, 160),
        "ui_font_weight_v2" to intRange(100, 900),
        "lyric_font_weight" to intRange(100, 900),
        "lyric_font_scale" to intRange(60, 180),
        "ui_font_letter_spacing_em_v1" to floatRange(-0.05f, 0.20f),
        "player_playback_speed" to floatRange(0.25f, 3f),
        "player_usb_software_volume_range_db" to intRange(40, 80),
        "hdr_window_headroom" to { value ->
            val headroom = (value as PreferenceValue.FloatValue).value
            require(headroom == 0f || headroom in 1f..8f) { "Invalid HDR headroom" }
        },
        "hdr_window_scenes" to { value ->
            require((value as PreferenceValue.StringSetValue).value.all { it in setOf("HOME", "LIBRARY", "PLAYER", "LYRICS", "SETTINGS") }) { "Invalid HDR scene" }
        },
        "lyrics_export_signature" to { value ->
            require((value as PreferenceValue.StringValue).value.length <= 40) { "Export signature too long" }
        },
    )

    private val sharedRules = buildMap<PreferenceAddress, PreferenceRule> {
        fun add(store: String, key: String, type: BackupPreferenceSchema.ValueType, validator: (PreferenceValue) -> Unit = {}) {
            put(PreferenceAddress(store, key), PreferenceRule(type, BackupSection.PREFERENCES, validator))
        }
        val bool = BackupPreferenceSchema.ValueType.BOOLEAN
        val int = BackupPreferenceSchema.ValueType.INT
        val float = BackupPreferenceSchema.ValueType.FLOAT
        val string = BackupPreferenceSchema.ValueType.STRING
        add("raws_locale_preferences", "application_language", string, choices("system", "zh-CN", "en", "ja", "ko"))
        listOf("weather_visible", "carousel_visible", "carousel_lyric_visible", "carousel_gesture_locked",
            "floating_glass_enabled", "floating_long_press_spring_enabled", "floating_press_displacement_enabled",
            "top_feather_enabled", "most_played_visible").forEach { add("home_header_preferences", it, bool) }
        add("home_header_preferences", "carousel_style", int, intChoices(0, 1))
        add("home_header_preferences", "top_feather_strength", int, intRange(0, 2))
        val libraryScenes = setOf("songs", "folders", "folder_hierarchy", "albums", "artists", "playlists", "queue", "recently_added", "genre", "year", "composer", "source_import")
        val toolScenes = setOf("analytics", "song_stats", "webdav", "log_viewer")
        listOf("library_order", "tool_order", "hidden_scenes").forEach { key ->
            add("home_card_layout_preferences", key, string) { value ->
                val scenes = (value as PreferenceValue.StringValue).value.takeIf { it.isNotEmpty() }?.split('|').orEmpty()
                val allowed = when (key) { "library_order" -> libraryScenes; "tool_order" -> toolScenes; else -> libraryScenes + toolScenes }
                require(scenes.distinct().size == scenes.size && scenes.all { it in allowed }) { "Invalid home card layout" }
            }
        }
        add("mini_player_prefs", "artwork_mode", string, choices("normal", "vinyl", "original"))
        add("raw_flow_background", "mode", string, choices("dark", "light", "universal", "off"))
        add("raw_flow_background", "background_style", string, choices("flow", "album_color", "static", "simple"))
        add("raw_flow_background", "motion_speed", float, floatRange(0.5f, 4f))
        add("raw_flow_background", "album_color_motion_speed_v2", float, floatRange(0.25f, 6f))
        add("raw_flow_background", "frame_rate_fps", int, intChoices(24, 30, 45, 60, 90, 120))
        add("raw_flow_background", "color_saturation", float, floatRange(0.5f, 1.6f))
        add("raw_flow_background", "color_brightness", float, floatRange(0.65f, 1.35f))
        add("raw_flow_background", "static_gradient", float, floatRange(0f, 10f))
        add("raw_flow_background", "static_blur", float, floatRange(0f, 15f))
        add("raw_flow_background", "static_detail", float, floatRange(0f, 10f))
        add("raw_flow_background", "static_brightness", float, floatRange(0f, 2.5f))
        add("raw_flow_background", "static_saturation", float, floatRange(0f, 3f))
        add("raw_flow_background", "static_gradient_color", int)
        listOf("uvr_denoise_double_inference", "uvr_live_stem_streaming").forEach { add("ai_separation_runtime_options", it, bool) }
        add("ai_separation_runtime_options", "uvr_live_stem", string, choices("VOCALS", "INSTRUMENTAL"))
        add("ai_separation_runtime_options", "uvr_live_strength", float, floatRange(0f, 1f))
        add("audio_transcode_ui_options", "format", string, choices("FLAC", "OGG_FLAC", "WAV", "AIFF", "ALAC", "WAVPACK", "TTA", "DSF", "AAC", "MP2", "MP3", "OPUS", "VORBIS", "WMA"))
        add("audio_transcode_ui_options", "dsd_rate", string, choices("DSD64", "DSD128", "DSD256", "DSD512", "DSD1024"))
        add("audio_transcode_ui_options", "flac_compression_level", int, intRange(0, 12))
        listOf("strict_metadata", "hardware_acceleration", "auto_scan_on_success").forEach { add("audio_transcode_ui_options", it, bool) }
        listOf("sample_rate_hz", "bit_depth", "bit_rate_kbps").forEach { key ->
            add("audio_transcode_ui_options", key, int) { value ->
                val number = (value as PreferenceValue.IntValue).value
                require(number == Int.MIN_VALUE || when (key) {
                    "sample_rate_hz" -> number in 8000..768000
                    "bit_depth" -> number in setOf(8, 16, 24, 32, 64)
                    else -> number in 6..1536
                }) { "Invalid conversion setting" }
            }
        }
        listOf("uri", "kind", "enabled", "show_player", "show_settings", "show_full_image").forEach { key ->
            val validator: (PreferenceValue) -> Unit = if (key == "kind") choices("image", "video") else { _ -> }
            put(PreferenceAddress("custom_media_background", key), PreferenceRule(
                if (key in setOf("uri", "kind")) string else bool, BackupSection.DEVICE_REFERENCES,
                validator, PreferenceDependency.FILE_OR_URI,
            ))
        }
        listOf("selected_model_id", "selected_model_version", "selected_realtime_model_id",
            "selected_realtime_model_version", "selected_lyric_alignment_id", "selected_lyric_alignment_version").forEach { key ->
            put(PreferenceAddress("ai_separation_models", key), PreferenceRule(string, BackupSection.PREFERENCES, dependency = PreferenceDependency.INSTALLED_MODEL))
        }
        put(PreferenceAddress("ai_performance_ui", "instrument_key"), PreferenceRule(string, BackupSection.PREFERENCES, dependency = PreferenceDependency.INSTALLED_MODEL))
        put(PreferenceAddress("lyrico_sources", "preferred_source_order_v1"), PreferenceRule(string, BackupSection.PREFERENCES, dependency = PreferenceDependency.INSTALLED_PLUGIN))
    }
}
