package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PersonalizationPreferences {
    private const val KEY_PREDICTIVE_BACK_ANIMATION = "personalization_predictive_back_animation"
    private const val KEY_PERFORMANCE_MODE = "personalization_performance_mode"
    private const val KEY_PERFORMANCE_MODE_DEFAULT_V2 = "personalization_performance_mode_default_v2"
    private const val KEY_BOTTOM_NAVIGATION_SCENES = "personalization_bottom_navigation_scenes_v1"
    private const val KEY_BOTTOM_BAR_STYLE = "personalization_bottom_bar_style_v1"
    private const val KEY_BOTTOM_BAR_MATERIAL = "personalization_bottom_bar_material_v1"
    private const val KEY_BOTTOM_CHROME_SCROLL_BEHAVIOR = "personalization_bottom_chrome_scroll_behavior_v1"
    private const val KEY_BOTTOM_NAV_STYLE_HEIGHT = "personalization_bottom_nav_style_height_v1"
    private const val KEY_BOTTOM_NAV_STYLE_BOTTOM_LIFT = "personalization_bottom_nav_style_bottom_lift_v1"
    private const val KEY_BOTTOM_NAV_STYLE_RADIUS = "personalization_bottom_nav_style_radius_v1"
    private const val KEY_BOTTOM_NAV_STYLE_LEADING_MARGIN = "personalization_bottom_nav_style_leading_margin_v1"
    private const val KEY_BOTTOM_NAV_STYLE_TRAILING_MARGIN = "personalization_bottom_nav_style_trailing_margin_v1"
    private const val KEY_BOTTOM_NAV_STYLE_CURVE = "personalization_bottom_nav_style_curve_v1"
    private const val KEY_MINI_PROGRESS_DIRECTION = "personalization_mini_progress_direction_v1"
    private const val KEY_MINI_BUBBLE_COUNT = "personalization_mini_bubble_count_v1"
    private const val KEY_MINI_BUBBLE_MOTION = "personalization_mini_bubble_motion_v1"
    private const val KEY_MINI_BUBBLE_SPEED = "personalization_mini_bubble_speed_v1"
    private const val KEY_MINI_HIGH_ENERGY = "personalization_mini_high_energy_v1"
    private const val KEY_MINI_BUBBLE_ORIGIN = "personalization_mini_bubble_origin_v1"
    private const val KEY_MINI_STYLE_HEIGHT = "personalization_mini_style_height_v1"
    private const val KEY_MINI_STYLE_COMPACT_HEIGHT = "personalization_mini_style_compact_height_v1"
    private const val KEY_MINI_STYLE_RADIUS = "personalization_mini_style_radius_v1"
    private const val KEY_MINI_STYLE_LEADING_MARGIN = "personalization_mini_style_leading_margin_v1"
    private const val KEY_MINI_STYLE_TRAILING_MARGIN = "personalization_mini_style_trailing_margin_v1"
    private const val KEY_MINI_STYLE_CURVE = "personalization_mini_style_curve_v1"
    private const val KEY_MINI_STYLE_ARTWORK_SIZE = "personalization_mini_style_artwork_size_v1"
    private const val KEY_MINI_STYLE_ORIGINAL_ARTWORK_RADIUS = "personalization_mini_style_original_artwork_radius_v1"
    private const val KEY_MINI_STYLE_ARTWORK_GAP = "personalization_mini_style_artwork_gap_v1"
    private const val KEY_MINI_STYLE_CONTROL_GAP = "personalization_mini_style_control_gap_v1"
    private const val KEY_MINI_CONTROL_SHOW_PREVIOUS = "personalization_mini_control_show_previous_v1"
    private const val KEY_MINI_CONTROL_PLAY_POSITION = "personalization_mini_control_play_position_v1"
    private const val KEY_MINI_CONTROL_SECONDARY_ACTION = "personalization_mini_control_secondary_action_v1"
    private const val KEY_TOP_CHROME_STYLE = "personalization_top_chrome_style_v1"
    private const val KEY_LIBRARY_HEADER_BUTTONS_ENABLED = "personalization_library_header_buttons_enabled_v1"
    private const val KEY_LIBRARY_BOTTOM_BUTTONS_MODE = "personalization_library_bottom_buttons_mode_v1"
    private const val KEY_LIBRARY_BOTTOM_BUTTONS_LAYOUT = "personalization_library_bottom_buttons_layout_v1"
    private const val KEY_LIBRARY_BOTTOM_BUTTONS_SURFACE = "personalization_library_bottom_buttons_surface_v1"
    private const val KEY_ALPHABET_INDEX_HIDDEN = "personalization_alphabet_index_hidden_v1"
    private const val KEY_PLAYER_ARTWORK_CORNER_RADIUS = "personalization_player_artwork_corner_radius_v1"
    private const val KEY_PLAYER_ARTWORK_ANIMATION_STYLE = "personalization_player_artwork_animation_style_v1"
    private const val KEY_PLAYER_HIRES_BADGE_ENABLED = "personalization_player_hires_badge_enabled_v1"
    private const val KEY_PLAYER_HIRES_BADGE_CORNER = "personalization_player_hires_badge_corner_v1"
    private const val KEY_PLAYER_HIRES_BADGE_CUSTOM_PATH = "personalization_player_hires_badge_custom_path_v1"
    private const val KEY_GLASS_BLUR_RADIUS = "personalization_glass_blur_radius_v1"
    private const val KEY_GLASS_REFRACTION_HEIGHT = "personalization_glass_refraction_height_v1"
    private const val KEY_GLASS_REFRACTION_AMOUNT = "personalization_glass_refraction_amount_v1"
    private const val KEY_GLASS_CHROMATIC_ABERRATION = "personalization_glass_chromatic_aberration_v1"
    private const val KEY_GLASS_VIBRANCY = "personalization_glass_vibrancy_v1"
    private const val KEY_GLASS_HIGHLIGHT = "personalization_glass_highlight_v1"
    private const val KEY_GLASS_SHADOW = "personalization_glass_shadow_v1"
    private const val KEY_SETTINGS_SURFACE_STYLE = "personalization_settings_surface_style_v1"
    private const val KEY_NOTICE_SURFACE_STYLE = "personalization_notice_surface_style_v1"

    private val allowedBottomNavigationTags = setOf(
        "home",
        "songs",
        "folders",
        "albums",
        "artists",
        "playlists",
        "queue",
        "recently_added",
        "genre",
        "audio_effects",
        "search",
        "settings",
    )

    val defaultBottomNavigationSceneTags: List<String> = listOf(
        "home",
        "songs",
        "audio_effects",
        "search",
        "settings",
    )

    var predictiveBackAnimationEnabled: Boolean
        get() = AppPreferences.storage.decodeBool(KEY_PREDICTIVE_BACK_ANIMATION, true)
        set(value) {
            AppPreferences.storage.encode(KEY_PREDICTIVE_BACK_ANIMATION, value)
        }

    private val _performanceModeEnabled = MutableStateFlow(initialPerformanceMode())
    val performanceMode = _performanceModeEnabled.asStateFlow()

    var performanceModeEnabled: Boolean
        get() = _performanceModeEnabled.value
        set(value) {
            _performanceModeEnabled.value = value
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE, value)
        }

    private val _bottomNavigationEnabled = MutableStateFlow(AppPreferences.UI.isBottomBarEnabled)
    val bottomNavigationEnabled = _bottomNavigationEnabled.asStateFlow()

    private val persistedBottomBarStyle = AppPreferences.storage.decodeString(
        KEY_BOTTOM_BAR_STYLE,
        BottomBarStyle.FLOATING.prefValue,
    )

    private val _bottomBarStyle = MutableStateFlow(BottomBarStyle.fromPref(persistedBottomBarStyle))
    val bottomBarStyle = _bottomBarStyle.asStateFlow()

    var bottomBarStyleValue: BottomBarStyle
        get() = _bottomBarStyle.value
        set(value) {
            if (_bottomBarStyle.value == value) return
            _bottomBarStyle.value = value
            AppPreferences.storage.encode(KEY_BOTTOM_BAR_STYLE, value.prefValue)
        }

    private val _bottomBarMaterial = MutableStateFlow(
        BottomBarMaterial.fromPref(
            AppPreferences.storage.decodeString(
                KEY_BOTTOM_BAR_MATERIAL,
                BottomBarMaterial.fromLegacyStylePref(persistedBottomBarStyle).prefValue,
            )
        )
    )
    val bottomBarMaterial = _bottomBarMaterial.asStateFlow()

    var bottomBarMaterialValue: BottomBarMaterial
        get() = _bottomBarMaterial.value
        set(value) {
            if (_bottomBarMaterial.value == value) return
            _bottomBarMaterial.value = value
            AppPreferences.storage.encode(KEY_BOTTOM_BAR_MATERIAL, value.prefValue)
        }

    private val _bottomChromeScrollBehavior = MutableStateFlow(
        BottomChromeScrollBehavior.fromPref(
            AppPreferences.storage.decodeString(
                KEY_BOTTOM_CHROME_SCROLL_BEHAVIOR,
                BottomChromeScrollBehavior.DEFAULT.prefValue,
            )
        )
    )
    val bottomChromeScrollBehavior = _bottomChromeScrollBehavior.asStateFlow()

    var bottomChromeScrollBehaviorValue: BottomChromeScrollBehavior
        get() = _bottomChromeScrollBehavior.value
        set(value) {
            if (_bottomChromeScrollBehavior.value == value) return
            _bottomChromeScrollBehavior.value = value
            AppPreferences.storage.encode(KEY_BOTTOM_CHROME_SCROLL_BEHAVIOR, value.prefValue)
        }

    private val _bottomNavigationStyle = MutableStateFlow(loadBottomNavigationStyle())
    val bottomNavigationStyle = _bottomNavigationStyle.asStateFlow()

    fun updateBottomNavigationStyle(
        transform: (BottomNavigationStyleSettings) -> BottomNavigationStyleSettings,
    ) {
        val next = transform(_bottomNavigationStyle.value).normalized()
        if (next == _bottomNavigationStyle.value) return
        _bottomNavigationStyle.value = next
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_HEIGHT, next.heightDp)
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_BOTTOM_LIFT, next.bottomLiftDp)
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_RADIUS, next.cornerRadiusDp)
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_LEADING_MARGIN, next.leadingMarginDp)
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_TRAILING_MARGIN, next.trailingMarginDp)
        AppPreferences.storage.encode(KEY_BOTTOM_NAV_STYLE_CURVE, next.cornerCurve)
    }

    fun resetBottomNavigationStyle() {
        updateBottomNavigationStyle { BottomNavigationStyleSettings.Default }
    }

    private val _miniPlayerProgressEffects = MutableStateFlow(loadMiniPlayerProgressEffects())
    val miniPlayerProgressEffects = _miniPlayerProgressEffects.asStateFlow()

    fun updateMiniPlayerProgressEffects(
        transform: (MiniPlayerProgressEffects) -> MiniPlayerProgressEffects,
    ) {
        val next = transform(_miniPlayerProgressEffects.value).normalized()
        if (next == _miniPlayerProgressEffects.value) return
        _miniPlayerProgressEffects.value = next
        AppPreferences.storage.encode(KEY_MINI_PROGRESS_DIRECTION, next.direction.prefValue)
        AppPreferences.storage.encode(KEY_MINI_BUBBLE_COUNT, next.bubbleCount)
        AppPreferences.storage.encode(KEY_MINI_BUBBLE_MOTION, next.bubbleMotion.prefValue)
        AppPreferences.storage.encode(KEY_MINI_BUBBLE_SPEED, next.bubbleSpeed)
        AppPreferences.storage.encode(KEY_MINI_HIGH_ENERGY, next.highEnergyHighlight)
        AppPreferences.storage.encode(KEY_MINI_BUBBLE_ORIGIN, next.bubbleOrigin.prefValue)
    }

    private val _miniPlayerStyle = MutableStateFlow(loadMiniPlayerStyle())
    val miniPlayerStyle = _miniPlayerStyle.asStateFlow()

    fun updateMiniPlayerStyle(transform: (MiniPlayerStyleSettings) -> MiniPlayerStyleSettings) {
        val next = transform(_miniPlayerStyle.value).normalized()
        if (next == _miniPlayerStyle.value) return
        _miniPlayerStyle.value = next
        AppPreferences.storage.encode(KEY_MINI_STYLE_HEIGHT, next.expandedHeightDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_COMPACT_HEIGHT, next.compactHeightDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_RADIUS, next.cornerRadiusDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_LEADING_MARGIN, next.leadingMarginDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_TRAILING_MARGIN, next.trailingMarginDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_CURVE, next.cornerCurve)
        AppPreferences.storage.encode(KEY_MINI_STYLE_ARTWORK_SIZE, next.artworkSizeDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_ORIGINAL_ARTWORK_RADIUS, next.originalArtworkCornerRadiusDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_ARTWORK_GAP, next.artworkTextGapDp)
        AppPreferences.storage.encode(KEY_MINI_STYLE_CONTROL_GAP, next.controlGapDp)
    }

    fun resetMiniPlayerStyle() {
        updateMiniPlayerStyle { MiniPlayerStyleSettings.Default }
    }

    private val _miniPlayerControls = MutableStateFlow(loadMiniPlayerControlSettings())
    val miniPlayerControls = _miniPlayerControls.asStateFlow()

    fun updateMiniPlayerControls(transform: (MiniPlayerControlSettings) -> MiniPlayerControlSettings) {
        val next = transform(_miniPlayerControls.value)
        if (next == _miniPlayerControls.value) return
        _miniPlayerControls.value = next
        AppPreferences.storage.encode(KEY_MINI_CONTROL_SHOW_PREVIOUS, next.showPrevious)
        AppPreferences.storage.encode(KEY_MINI_CONTROL_PLAY_POSITION, next.playPausePosition.prefValue)
        AppPreferences.storage.encode(KEY_MINI_CONTROL_SECONDARY_ACTION, next.secondaryAction.prefValue)
    }

    private val _alphabetIndexHidden = MutableStateFlow(
        AppPreferences.storage.decodeBool(KEY_ALPHABET_INDEX_HIDDEN, false)
    )
    val alphabetIndexHidden = _alphabetIndexHidden.asStateFlow()

    var isAlphabetIndexHidden: Boolean
        get() = _alphabetIndexHidden.value
        set(value) {
            if (_alphabetIndexHidden.value == value) return
            _alphabetIndexHidden.value = value
            AppPreferences.storage.encode(KEY_ALPHABET_INDEX_HIDDEN, value)
        }

    private val _playerArtworkCornerRadiusDp = MutableStateFlow(
        AppPreferences.storage.decodeFloat(KEY_PLAYER_ARTWORK_CORNER_RADIUS, 12f).coerceIn(0f, 48f)
    )
    val playerArtworkCornerRadiusDp = _playerArtworkCornerRadiusDp.asStateFlow()

    var playerArtworkCornerRadiusDpValue: Float
        get() = _playerArtworkCornerRadiusDp.value
        set(value) {
            val normalized = value.coerceIn(0f, 48f)
            if (_playerArtworkCornerRadiusDp.value == normalized) return
            _playerArtworkCornerRadiusDp.value = normalized
            AppPreferences.storage.encode(KEY_PLAYER_ARTWORK_CORNER_RADIUS, normalized)
        }

    private val _playerArtworkAnimationStyle = MutableStateFlow(
        AppPreferences.storage.decodeInt(
            KEY_PLAYER_ARTWORK_ANIMATION_STYLE,
            AppPreferences.UI.playerArtworkAnimationStyle,
        ).coerceIn(0, 2)
    )
    val playerArtworkAnimationStyle = _playerArtworkAnimationStyle.asStateFlow()

    var playerArtworkAnimationStyleValue: Int
        get() = _playerArtworkAnimationStyle.value
        set(value) {
            val normalized = value.coerceIn(0, 2)
            if (_playerArtworkAnimationStyle.value == normalized) return
            _playerArtworkAnimationStyle.value = normalized
            AppPreferences.storage.encode(KEY_PLAYER_ARTWORK_ANIMATION_STYLE, normalized)
            AppPreferences.UI.playerArtworkAnimationStyle = normalized
        }

    private val _playerHiResBadgeSettings = MutableStateFlow(
        PlayerHiResBadgeSettings(
            enabled = AppPreferences.storage.decodeBool(KEY_PLAYER_HIRES_BADGE_ENABLED, false),
            corner = PlayerHiResBadgeCorner.fromPref(
                AppPreferences.storage.decodeInt(KEY_PLAYER_HIRES_BADGE_CORNER, PlayerHiResBadgeCorner.TOP_LEFT.prefValue)
            ),
            customPath = AppPreferences.storage.decodeString(KEY_PLAYER_HIRES_BADGE_CUSTOM_PATH, "").orEmpty(),
        )
    )
    val playerHiResBadgeSettings = _playerHiResBadgeSettings.asStateFlow()

    fun updatePlayerHiResBadgeSettings(
        transform: (PlayerHiResBadgeSettings) -> PlayerHiResBadgeSettings,
    ) {
        val next = transform(_playerHiResBadgeSettings.value)
        if (next == _playerHiResBadgeSettings.value) return
        _playerHiResBadgeSettings.value = next
        AppPreferences.storage.encode(KEY_PLAYER_HIRES_BADGE_ENABLED, next.enabled)
        AppPreferences.storage.encode(KEY_PLAYER_HIRES_BADGE_CORNER, next.corner.prefValue)
        AppPreferences.storage.encode(KEY_PLAYER_HIRES_BADGE_CUSTOM_PATH, next.customPath)
    }

    private val _globalLiquidGlassSettings = MutableStateFlow(loadGlobalLiquidGlassSettings())
    val globalLiquidGlassSettings = _globalLiquidGlassSettings.asStateFlow()

    private val _settingsSurfaceStyle = MutableStateFlow(
        SettingsSurfaceStyle.fromPref(
            AppPreferences.storage.decodeString(
                KEY_SETTINGS_SURFACE_STYLE,
                SettingsSurfaceStyle.SOLID.prefValue,
            )
        )
    )
    val settingsSurfaceStyle = _settingsSurfaceStyle.asStateFlow()

    var settingsSurfaceStyleValue: SettingsSurfaceStyle
        get() = _settingsSurfaceStyle.value
        set(value) {
            if (_settingsSurfaceStyle.value == value) return
            _settingsSurfaceStyle.value = value
            AppPreferences.storage.encode(KEY_SETTINGS_SURFACE_STYLE, value.prefValue)
        }

    private val _noticeSurfaceStyle = MutableStateFlow(
        SettingsSurfaceStyle.fromPref(
            AppPreferences.storage.decodeString(
                KEY_NOTICE_SURFACE_STYLE,
                SettingsSurfaceStyle.ACRYLIC.prefValue,
            )
        )
    )
    val noticeSurfaceStyle = _noticeSurfaceStyle.asStateFlow()

    var noticeSurfaceStyleValue: SettingsSurfaceStyle
        get() = _noticeSurfaceStyle.value
        set(value) {
            if (_noticeSurfaceStyle.value == value) return
            _noticeSurfaceStyle.value = value
            AppPreferences.storage.encode(KEY_NOTICE_SURFACE_STYLE, value.prefValue)
        }

    fun updateGlobalLiquidGlassSettings(
        transform: (GlobalLiquidGlassSettings) -> GlobalLiquidGlassSettings,
    ) {
        val next = transform(_globalLiquidGlassSettings.value).normalized()
        if (next == _globalLiquidGlassSettings.value) return
        _globalLiquidGlassSettings.value = next
        AppPreferences.storage.encode(KEY_GLASS_BLUR_RADIUS, next.blurRadiusDp)
        AppPreferences.storage.encode(KEY_GLASS_REFRACTION_HEIGHT, next.refractionHeightFraction)
        AppPreferences.storage.encode(KEY_GLASS_REFRACTION_AMOUNT, next.refractionAmountFraction)
        AppPreferences.storage.encode(KEY_GLASS_CHROMATIC_ABERRATION, next.chromaticAberration)
        AppPreferences.storage.encode(KEY_GLASS_VIBRANCY, next.vibrancyStrength)
        AppPreferences.storage.encode(KEY_GLASS_HIGHLIGHT, next.highlightStrength)
        AppPreferences.storage.encode(KEY_GLASS_SHADOW, next.shadowStrength)
    }

    fun resetGlobalLiquidGlassSettings() {
        updateGlobalLiquidGlassSettings { GlobalLiquidGlassSettings.Default }
    }

    private val _topChromeStyle = MutableStateFlow(
        TopChromeStyle.fromPref(
            AppPreferences.storage.decodeString(KEY_TOP_CHROME_STYLE, TopChromeStyle.HAZE.prefValue)
        )
    )
    val topChromeStyle = _topChromeStyle.asStateFlow()

    var topChromeStyleValue: TopChromeStyle
        get() = _topChromeStyle.value
        set(value) {
            if (_topChromeStyle.value == value) return
            _topChromeStyle.value = value
            AppPreferences.storage.encode(KEY_TOP_CHROME_STYLE, value.prefValue)
        }

    private val _libraryHeaderButtonsEnabled = MutableStateFlow(
        AppPreferences.storage.decodeBool(KEY_LIBRARY_HEADER_BUTTONS_ENABLED, true)
    )
    val libraryHeaderButtonsEnabled = _libraryHeaderButtonsEnabled.asStateFlow()

    var libraryHeaderButtonsEnabledValue: Boolean
        get() = _libraryHeaderButtonsEnabled.value
        set(value) {
            if (_libraryHeaderButtonsEnabled.value == value) return
            _libraryHeaderButtonsEnabled.value = value
            AppPreferences.storage.encode(KEY_LIBRARY_HEADER_BUTTONS_ENABLED, value)
        }

    private val _libraryBottomButtonsMode = MutableStateFlow(
        LibraryBottomButtonsMode.fromPref(
            AppPreferences.storage.decodeString(
                KEY_LIBRARY_BOTTOM_BUTTONS_MODE,
                LibraryBottomButtonsMode.ENABLED.prefValue,
            )
        )
    )
    val libraryBottomButtonsMode = _libraryBottomButtonsMode.asStateFlow()

    var libraryBottomButtonsModeValue: LibraryBottomButtonsMode
        get() = _libraryBottomButtonsMode.value
        set(value) {
            if (_libraryBottomButtonsMode.value == value) return
            _libraryBottomButtonsMode.value = value
            AppPreferences.storage.encode(KEY_LIBRARY_BOTTOM_BUTTONS_MODE, value.prefValue)
        }

    private val _libraryBottomButtonsLayout = MutableStateFlow(
        LibraryBottomButtonsLayout.fromPref(
            AppPreferences.storage.decodeString(
                KEY_LIBRARY_BOTTOM_BUTTONS_LAYOUT,
                if (
                    TopChromeStyle.fromPref(
                        AppPreferences.storage.decodeString(KEY_TOP_CHROME_STYLE, TopChromeStyle.HAZE.prefValue)
                    ) == TopChromeStyle.FLOATING_VERTICAL
                ) {
                    LibraryBottomButtonsLayout.VERTICAL.prefValue
                } else {
                    LibraryBottomButtonsLayout.HORIZONTAL.prefValue
                },
            )
        )
    )
    val libraryBottomButtonsLayout = _libraryBottomButtonsLayout.asStateFlow()

    var libraryBottomButtonsLayoutValue: LibraryBottomButtonsLayout
        get() = _libraryBottomButtonsLayout.value
        set(value) {
            if (_libraryBottomButtonsLayout.value == value) return
            _libraryBottomButtonsLayout.value = value
            AppPreferences.storage.encode(KEY_LIBRARY_BOTTOM_BUTTONS_LAYOUT, value.prefValue)
        }

    private val _libraryBottomButtonsSurface = MutableStateFlow(
        LibraryBottomButtonsSurface.fromPref(
            AppPreferences.storage.decodeString(
                KEY_LIBRARY_BOTTOM_BUTTONS_SURFACE,
                LibraryBottomButtonsSurface.SOLID.prefValue,
            )
        )
    )
    val libraryBottomButtonsSurface = _libraryBottomButtonsSurface.asStateFlow()

    var libraryBottomButtonsSurfaceValue: LibraryBottomButtonsSurface
        get() = _libraryBottomButtonsSurface.value
        set(value) {
            if (_libraryBottomButtonsSurface.value == value) return
            _libraryBottomButtonsSurface.value = value
            AppPreferences.storage.encode(KEY_LIBRARY_BOTTOM_BUTTONS_SURFACE, value.prefValue)
        }

    var isBottomNavigationEnabled: Boolean
        get() = _bottomNavigationEnabled.value
        set(value) {
            if (_bottomNavigationEnabled.value == value) return
            _bottomNavigationEnabled.value = value
            AppPreferences.UI.isBottomBarEnabled = value
        }

    private val _bottomNavigationSceneTags = MutableStateFlow(loadBottomNavigationSceneTags())
    val bottomNavigationSceneTags = _bottomNavigationSceneTags.asStateFlow()

    var bottomNavigationScenes: List<String>
        get() = _bottomNavigationSceneTags.value
        set(value) {
            val normalized = normalizeBottomNavigationSceneTags(value)
            _bottomNavigationSceneTags.value = normalized
            AppPreferences.storage.encode(KEY_BOTTOM_NAVIGATION_SCENES, normalized.joinToString(","))
        }

    fun resetBottomNavigationScenes() {
        bottomNavigationScenes = defaultBottomNavigationSceneTags
    }

    private fun loadMiniPlayerProgressEffects(): MiniPlayerProgressEffects =
        MiniPlayerProgressEffects(
            direction = MiniPlayerProgressDirection.fromPref(
                AppPreferences.storage.decodeString(
                    KEY_MINI_PROGRESS_DIRECTION,
                    MiniPlayerProgressDirection.LEFT_TO_RIGHT.prefValue,
                )
            ),
            bubbleCount = AppPreferences.storage.decodeInt(KEY_MINI_BUBBLE_COUNT, 14),
            bubbleMotion = MiniPlayerBubbleMotion.fromPref(
                AppPreferences.storage.decodeString(
                    KEY_MINI_BUBBLE_MOTION,
                    MiniPlayerBubbleMotion.FLOAT.prefValue,
                )
            ),
            bubbleSpeed = AppPreferences.storage.decodeFloat(KEY_MINI_BUBBLE_SPEED, 1f),
            highEnergyHighlight = AppPreferences.storage.decodeBool(KEY_MINI_HIGH_ENERGY, false),
            bubbleOrigin = MiniPlayerBubbleOrigin.fromPref(
                AppPreferences.storage.decodeString(
                    KEY_MINI_BUBBLE_ORIGIN,
                    MiniPlayerBubbleOrigin.START_EDGE.prefValue,
                )
            ),
        ).normalized()

    private fun loadBottomNavigationStyle(): BottomNavigationStyleSettings =
        BottomNavigationStyleSettings(
            heightDp = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_HEIGHT, 64f),
            bottomLiftDp = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_BOTTOM_LIFT, 0f),
            cornerRadiusDp = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_RADIUS, 32f),
            leadingMarginDp = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_LEADING_MARGIN, 16f),
            trailingMarginDp = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_TRAILING_MARGIN, 20f),
            cornerCurve = AppPreferences.storage.decodeFloat(KEY_BOTTOM_NAV_STYLE_CURVE, 2f),
        ).normalized()

    private fun loadMiniPlayerControlSettings(): MiniPlayerControlSettings =
        MiniPlayerControlSettings(
            showPrevious = AppPreferences.storage.decodeBool(KEY_MINI_CONTROL_SHOW_PREVIOUS, false),
            playPausePosition = MiniPlayerPlayPausePosition.fromPref(
                AppPreferences.storage.decodeString(
                    KEY_MINI_CONTROL_PLAY_POSITION,
                    MiniPlayerPlayPausePosition.TRAILING.prefValue,
                )
            ),
            secondaryAction = MiniPlayerSecondaryAction.fromPref(
                AppPreferences.storage.decodeString(
                    KEY_MINI_CONTROL_SECONDARY_ACTION,
                    MiniPlayerSecondaryAction.DEFAULT.prefValue,
                )
            ),
        )

    private fun loadMiniPlayerStyle(): MiniPlayerStyleSettings =
        MiniPlayerStyleSettings(
            expandedHeightDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_HEIGHT, 62f),
            compactHeightDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_COMPACT_HEIGHT, 54f),
            cornerRadiusDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_RADIUS, 22f),
            leadingMarginDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_LEADING_MARGIN, 16f),
            trailingMarginDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_TRAILING_MARGIN, 20f),
            cornerCurve = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_CURVE, 2f),
            artworkSizeDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_ARTWORK_SIZE, 44f),
            originalArtworkCornerRadiusDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_ORIGINAL_ARTWORK_RADIUS, 8f),
            artworkTextGapDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_ARTWORK_GAP, 8f),
            controlGapDp = AppPreferences.storage.decodeFloat(KEY_MINI_STYLE_CONTROL_GAP, 0f),
        ).normalized()

    private fun loadGlobalLiquidGlassSettings(): GlobalLiquidGlassSettings =
        GlobalLiquidGlassSettings(
            blurRadiusDp = AppPreferences.storage.decodeFloat(KEY_GLASS_BLUR_RADIUS, 8f),
            refractionHeightFraction = AppPreferences.storage.decodeFloat(KEY_GLASS_REFRACTION_HEIGHT, 0.75f),
            refractionAmountFraction = AppPreferences.storage.decodeFloat(KEY_GLASS_REFRACTION_AMOUNT, 0.375f),
            chromaticAberration = AppPreferences.storage.decodeFloat(KEY_GLASS_CHROMATIC_ABERRATION, 1f),
            vibrancyStrength = AppPreferences.storage.decodeFloat(KEY_GLASS_VIBRANCY, 1f),
            highlightStrength = AppPreferences.storage.decodeFloat(KEY_GLASS_HIGHLIGHT, 1f),
            shadowStrength = AppPreferences.storage.decodeFloat(KEY_GLASS_SHADOW, 1f),
        ).normalized()

    private fun initialPerformanceMode(): Boolean {
        if (!AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE_DEFAULT_V2, false)) {
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE_DEFAULT_V2, true)
            AppPreferences.storage.encode(KEY_PERFORMANCE_MODE, true)
            return true
        }
        return AppPreferences.storage.decodeBool(KEY_PERFORMANCE_MODE, true)
    }

    private fun loadBottomNavigationSceneTags(): List<String> {
        val stored = AppPreferences.storage.decodeString(KEY_BOTTOM_NAVIGATION_SCENES, "")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (stored.isEmpty()) return defaultBottomNavigationSceneTags
        return normalizeBottomNavigationSceneTags(stored)
    }

    private fun normalizeBottomNavigationSceneTags(raw: List<String>): List<String> {
        val result = raw
            .filter { it in allowedBottomNavigationTags }
            .distinct()
            .toMutableList()

        if ("home" !in result) result.add(0, "home")
        if (result.size < 2) {
            defaultBottomNavigationSceneTags.firstOrNull { it !in result }?.let(result::add)
        }
        return result.take(5)
    }
}

enum class PlayerHiResBadgeCorner(val prefValue: Int) {
    TOP_LEFT(0),
    TOP_RIGHT(1),
    BOTTOM_LEFT(2),
    BOTTOM_RIGHT(3);

    companion object {
        fun fromPref(value: Int): PlayerHiResBadgeCorner =
            entries.firstOrNull { it.prefValue == value } ?: TOP_LEFT
    }
}

data class PlayerHiResBadgeSettings(
    val enabled: Boolean = false,
    val corner: PlayerHiResBadgeCorner = PlayerHiResBadgeCorner.TOP_LEFT,
    val customPath: String = "",
)
