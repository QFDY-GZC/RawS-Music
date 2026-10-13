package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.rawsmusic.R
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.scene.pages.CouiSettingsScaffold
import com.rawsmusic.core.ui.scene.pages.ProgressiveTopBlur
import com.rawsmusic.core.ui.scene.pages.settingsTopBlurSource
import com.rawsmusic.core.ui.scene.pages.settingsBlurViewportHeight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import com.rawsmusic.core.ui.scene.pages.WithoutPlatformOverscroll
import com.rawsmusic.core.ui.scene.pages.couiOverscrollOffset
import com.rawsmusic.core.ui.scene.pages.rememberCouiSpringOverscrollState
import com.rawsmusic.core.ui.scene.pages.rememberCouiFlingBehavior
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.SettingsSurfaceStyle
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.core.ui.theme.rawGlobalFontFamily
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal val LocalSettingsBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun themeColors(): ThemeColors {
    val context = LocalContext.current
    val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)
    return if (isDark) ThemeColors(
        background = Color(0xFF2A2624),
        surface = Color(0xFF353130),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xCCFFFFFF),
        outline = Color(0xFF9F8D80),
        primary = Color.White,
        onPrimary = Color(0xFF3E2D1A),
        primaryContainer = Color(0xFF57432E),
        onPrimaryContainer = Color.White,
        secondaryText = Color(0xCCFFFFFF)
    ) else ThemeColors(
        background = Color(0xFFF8F7FC),
        surface = Color(0xFFE4E6F2),
        onSurface = Color.Black,
        onSurfaceVariant = Color(0x8A000000),
        outline = Color(0xFF8A8E9C),
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9EEF8),
        onPrimaryContainer = Color.Black,
        secondaryText = Color(0x8A000000)
    )
}

internal data class ThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryText: Color
)

@Composable
internal fun appFontFamily(): FontFamily {
    val runtimeVersion = RawThemeRuntimeState.version
    val path = AppPreferences.UI.customFontPath
    val globalWeight = AppPreferences.UI.fontWeight.coerceIn(100, 900)
    return remember(path, runtimeVersion, globalWeight) {
        runCatching {
            rawGlobalFontFamily(
                path = path,
                globalWeight = globalWeight,
                allowOutOfRange = AppPreferences.UI.experimentalFontWeightEnabled,
            )
        }
            .getOrDefault(FontFamily.Default)
    }
}

/**
 * 设置页面模板。
 * 顶栏对齐 ColorOS 设置：透明 AppBar、内容滚入顶栏下方、渐变模糊层随滚动淡入。
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)
    val surfaceStyle by PersonalizationPreferences.settingsSurfaceStyle.collectAsState()
    val context = LocalContext.current
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val customBackgroundRevision = CustomMediaBackgroundState.revision
    val customSettingsBackground = CustomMediaBackgroundState.enabled &&
        CustomMediaBackgroundState.showOnSettings
    val resolvedPageBackground = if (
        customSettingsBackground || surfaceStyle != SettingsSurfaceStyle.SOLID
    ) Color.Transparent else pageBackground
    CouiSettingsScaffold(
        title = title,
        onBack = onBack,
        background = resolvedPageBackground,
        titleColor = MiuixTheme.colorScheme.onBackground,
        // COUI would fall back to the window colour here; transparent pages (custom media / glass
        // surfaces) keep only the blur so the custom background stays visible under the bar.
        rampColor = resolvedPageBackground,
        backgroundBackdrop = LocalSettingsBackdrop.current,
        content = content,
    )
}

@Composable
internal fun SettingsActionRow(
    title: String,
    description: String? = null,
    iconRes: Int? = null,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = description.orEmpty(),
        onClick = onClick,
        startAction = iconRes?.let { resId ->
            { SettingsSubpageEntryIcon(resId) }
        }
    )
}

/**
 * 设置主页面。
 * 使用 Miuix 组件。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LiquidGlassSettingsScreen(
    onNavigateToLyrics: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToPersonalization: () -> Unit,
    onNavigateToAudioSettings: () -> Unit,
    onNavigateToAudioTranscode: () -> Unit,
    onNavigateToAudioEffects: () -> Unit,
    onNavigateToAiSeparation: () -> Unit,
    onNavigateToTransitionSettings: () -> Unit,
    onNavigateToShuffleSettings: () -> Unit,
    onNavigateToPlayerInterface: () -> Unit,
    onNavigateToUsbDac: () -> Unit,
    onNavigateToHardwareDeviceControl: () -> Unit,
    onNavigateToGlobalFont: () -> Unit,
    onNavigateToAlbumArt: () -> Unit,
    onWebDavBackup: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
    onNavigateToScanSettings: () -> Unit = {},
    onNavigateToHdr: () -> Unit = {}
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)
    val context = LocalContext.current
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val customBackgroundRevision = CustomMediaBackgroundState.revision
    val customSettingsBackground = CustomMediaBackgroundState.enabled &&
        CustomMediaBackgroundState.showOnSettings
    val surfaceStyle by PersonalizationPreferences.settingsSurfaceStyle.collectAsState()
    val resolvedPageBackground = if (
        customSettingsBackground || surfaceStyle != SettingsSurfaceStyle.SOLID
    ) Color.Transparent else pageBackground
    // SettingsCollapsableAppBarLayout saves MODE_STATE_KEY: it is the search mode here, so the
    // query (and with it the collapsed chrome + result panel) survives a configuration change.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val searchProgress by animateFloatAsState(
        if (searchActive) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = SettingsSearchMotion.stiffness),
        label = "SettingsSearchMode",
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val searchPrefs = remember(context) { context.getSharedPreferences("settings_search", android.content.Context.MODE_PRIVATE) }
    var recentQueries by remember {
        mutableStateOf(runCatching {
            val array = org.json.JSONArray(searchPrefs.getString("recent", "[]"))
            List(array.length().coerceAtMost(6)) { array.getString(it) }
        }.getOrDefault(emptyList()))
    }
    fun recordSearch() {
        recentQueries = updateSettingsSearchHistory(recentQueries, searchQuery)
        searchPrefs.edit().putString("recent", org.json.JSONArray(recentQueries).toString()).apply()
    }
    fun leaveSearch() {
        recordSearch()
        searchActive = false
        searchQuery = ""
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    fun removeHistory(query: String? = null) {
        recentQueries = if (query == null) emptyList() else recentQueries.filterNot { it == query }
        searchPrefs.edit().putString("recent", org.json.JSONArray(recentQueries).toString()).apply()
    }
    BackHandler(enabled = searchActive) { leaveSearch() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }

    val playbackSectionTitle = stringResource(R.string.settings_section_playback_audio)
    val interfaceSectionTitle = stringResource(R.string.settings_section_interface_display)
    val lyricsSectionTitle = stringResource(R.string.settings_section_lyrics_extensions)
    val librarySectionTitle = stringResource(R.string.settings_section_library_data)
    val diagnosticsSectionTitle = stringResource(R.string.settings_section_help_diagnostics)

    val openChildSettings: (Class<*>) -> Unit = { activityClass ->
        val host = context as? BaseSettingsActivity
        if (host != null) {
            host.navigateToSettings(activityClass)
        } else {
            context.startActivity(
                Intent(context, activityClass).apply { setPackage(context.packageName) }
            )
        }
    }

    // Root sections and search use the same model. This prevents entries from
    // being searchable but missing from the normal list (or the reverse).
    val sections = listOf(
        SettingsRootSection(
            title = playbackSectionTitle,
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_audio_quality_title),
                    summary = stringResource(R.string.settings_audio_quality_summary),
                    keywords = stringResource(R.string.settings_audio_quality_keywords),
                    iconRes = R.drawable.ic_settings_audio_output,
                    tone = SettingsRootIconTone.Audio,
                    onClick = onNavigateToAudioSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_audio_transcode_title),
                    summary = stringResource(R.string.settings_audio_transcode_summary),
                    keywords = stringResource(R.string.settings_audio_transcode_keywords),
                    iconRes = R.drawable.ic_settings_transcode,
                    tone = SettingsRootIconTone.Transcode,
                    onClick = onNavigateToAudioTranscode
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_transition_title),
                    summary = stringResource(R.string.settings_transition_summary),
                    keywords = stringResource(R.string.settings_transition_keywords),
                    iconRes = R.drawable.ic_settings_transition,
                    tone = SettingsRootIconTone.Transition,
                    onClick = onNavigateToTransitionSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_shuffle_title),
                    summary = stringResource(R.string.settings_shuffle_summary),
                    keywords = stringResource(R.string.settings_shuffle_keywords),
                    iconRes = R.drawable.ic_settings_shuffle,
                    tone = SettingsRootIconTone.Shuffle,
                    onClick = onNavigateToShuffleSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_audio_effects_title),
                    summary = stringResource(R.string.settings_audio_effects_summary),
                    keywords = stringResource(R.string.settings_audio_effects_keywords),
                    iconRes = R.drawable.ic_settings_effects,
                    tone = SettingsRootIconTone.Effects,
                    onClick = onNavigateToAudioEffects
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_ai_separation_title),
                    summary = stringResource(R.string.settings_ai_separation_summary),
                    keywords = stringResource(R.string.settings_ai_separation_keywords),
                    iconRes = R.drawable.ic_settings_ai,
                    tone = SettingsRootIconTone.Ai,
                    onClick = onNavigateToAiSeparation
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_usb_dac_title),
                    summary = stringResource(R.string.settings_usb_dac_summary),
                    keywords = stringResource(R.string.settings_usb_dac_keywords),
                    iconRes = R.drawable.ic_settings_usb_ali,
                    tone = SettingsRootIconTone.Usb,
                    onClick = onNavigateToUsbDac
                ),
                SettingsRootItem(
                    title = stringResource(R.string.hardware_device_control_title),
                    summary = stringResource(R.string.hardware_device_control_summary),
                    keywords = stringResource(R.string.hardware_device_control_keywords),
                    iconRes = R.drawable.ic_settings_chip,
                    tone = SettingsRootIconTone.Hardware,
                    onClick = onNavigateToHardwareDeviceControl
                )
            )
        ),
        SettingsRootSection(
            title = interfaceSectionTitle,
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_appearance_title),
                    summary = stringResource(R.string.settings_appearance_summary),
                    keywords = stringResource(R.string.settings_appearance_keywords),
                    iconRes = R.drawable.ic_settings_appearance,
                    tone = SettingsRootIconTone.Appearance,
                    onClick = onNavigateToAppearance
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_player_interface_title),
                    summary = stringResource(R.string.settings_player_interface_summary),
                    keywords = stringResource(R.string.settings_player_interface_keywords),
                    iconRes = R.drawable.ic_settings_player_ui_ali,
                    tone = SettingsRootIconTone.Player,
                    onClick = onNavigateToPlayerInterface
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_album_art_title),
                    summary = stringResource(R.string.settings_album_art_summary),
                    keywords = stringResource(R.string.settings_album_art_keywords),
                    iconRes = R.drawable.ic_settings_album_art,
                    tone = SettingsRootIconTone.AlbumArt,
                    onClick = onNavigateToAlbumArt
                ),
                SettingsRootItem(
                    title = stringResource(com.rawsmusic.core.ui.R.string.settings_hdr_title),
                    summary = stringResource(com.rawsmusic.core.ui.R.string.settings_hdr_summary),
                    keywords = "HDR 高动态范围 display highlight",
                    iconRes = R.drawable.ic_settings_appearance,
                    tone = SettingsRootIconTone.Appearance,
                    onClick = onNavigateToHdr
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_font_settings_title),
                    summary = stringResource(R.string.settings_font_settings_summary),
                    keywords = stringResource(R.string.settings_global_font_keywords),
                    iconRes = R.drawable.ic_settings_font,
                    tone = SettingsRootIconTone.Font,
                    onClick = onNavigateToGlobalFont
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_personalization_title),
                    summary = stringResource(R.string.settings_personalization_summary),
                    keywords = stringResource(R.string.settings_personalization_keywords),
                    iconRes = R.drawable.ic_settings_personalization,
                    tone = SettingsRootIconTone.Personalization,
                    onClick = onNavigateToPersonalization
                )
            )
        ),
        SettingsRootSection(
            title = lyricsSectionTitle,
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_lyrics_title),
                    summary = stringResource(R.string.settings_lyrics_summary),
                    keywords = stringResource(R.string.settings_lyrics_keywords),
                    iconRes = R.drawable.ic_settings_lyrics_ali,
                    tone = SettingsRootIconTone.Lyrics,
                    onClick = onNavigateToLyrics
                )
            )
        ),
        SettingsRootSection(
            title = librarySectionTitle,
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_scan_settings_title),
                    summary = stringResource(R.string.settings_scan_settings_summary),
                    keywords = stringResource(R.string.settings_scan_settings_keywords),
                    iconRes = R.drawable.ic_settings_scan_ali,
                    tone = SettingsRootIconTone.Scan,
                    onClick = onNavigateToScanSettings
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_webdav_backup_title),
                    summary = stringResource(R.string.settings_webdav_backup_summary),
                    keywords = stringResource(R.string.settings_webdav_backup_keywords),
                    iconRes = R.drawable.ic_settings_cloud_backup,
                    tone = SettingsRootIconTone.Backup,
                    onClick = onWebDavBackup
                )
            )
        ),
        SettingsRootSection(
            title = diagnosticsSectionTitle,
            items = listOf(
                SettingsRootItem(
                    title = stringResource(R.string.settings_log_viewer_title),
                    summary = stringResource(R.string.settings_log_viewer_summary),
                    keywords = stringResource(R.string.settings_log_viewer_keywords),
                    iconRes = R.drawable.ic_settings_log,
                    tone = SettingsRootIconTone.Log,
                    onClick = onNavigateToLogViewer
                ),
                SettingsRootItem(
                    title = stringResource(R.string.settings_about_raws_music_title),
                    summary = stringResource(R.string.settings_about_raws_music_summary),
                    keywords = stringResource(R.string.settings_about_keywords),
                    iconRes = R.drawable.ic_settings_about,
                    tone = SettingsRootIconTone.About,
                    onClick = onNavigateToAbout
                )
            )
        )
    )

    val rootItems = sections.flatMap { section ->
        section.items.map { item ->
            SettingsSearchItem(
                sectionTitle = section.title,
                parentTitle = null,
                title = item.title,
                summary = item.summary,
                keywords = item.keywords,
                iconRes = item.iconRes,
                iconVector = item.iconVector,
                tone = item.tone,
                depth = 0,
                destination = if (item.title == context.getString(com.rawsmusic.core.ui.R.string.settings_hdr_title))
                    HdrSettingsActivity::class.java.name else item.tone.destination.name,
                onClick = item.onClick,
            )
        }
    }
    // HDR shares the appearance colour, but must not replace the appearance owning route.
    val rootsByTone = rootItems.groupBy(SettingsSearchItem::tone).mapValues { it.value.first() }
    fun childSearchItem(
        tone: SettingsRootIconTone,
        title: String,
        summary: String? = null,
        keywords: String = "",
        destination: Class<*>? = null,
        onClick: (() -> Unit)? = null,
    ): SettingsSearchItem {
        val root = requireNotNull(rootsByTone[tone])
        return SettingsSearchItem(
            sectionTitle = root.sectionTitle,
            parentTitle = root.title,
            title = title,
            summary = summary.orEmpty(),
            keywords = keywords,
            iconRes = root.iconRes,
            iconVector = root.iconVector,
            tone = root.tone,
            depth = 1,
            destination = (destination ?: tone.destination).name,
            onClick = onClick ?: root.onClick,
        )
    }

    // Search is deliberately broader than the root navigation model. A child entry either opens
    // its dedicated Activity or the owning page when the control lives inline on that page.
    val childItems = listOf(
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_focus_title),
            stringResource(R.string.settings_audio_focus_summary),
            "audio focus 音频焦点 抢占 duck",
            destination = AudioFocusSettingsActivity::class.java,
            onClick = { openChildSettings(AudioFocusSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_dvc),
            stringResource(R.string.settings_audio_dvc_desc),
            "DVC direct volume control 直接音量",
            destination = DvcSettingsActivity::class.java,
            onClick = { openChildSettings(DvcSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_volume_normalization),
            stringResource(R.string.settings_audio_volume_normalization_desc),
            "ReplayGain normalization 音量标准化 增益",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_gapless),
            stringResource(R.string.settings_audio_gapless_desc),
            "gapless 无缝播放 连播",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_speed_title),
            stringResource(R.string.settings_audio_speed_summary),
            "speed tempo 播放速度 倍速",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_speed_change_pitch_title),
            stringResource(R.string.settings_audio_speed_change_pitch_summary),
            "pitch 变调 音高 WSOLA",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_mono_output_title),
            stringResource(R.string.settings_audio_mono_output_summary),
            "mono 单声道",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_dither_title),
            stringResource(R.string.settings_audio_dither_summary),
            "dither 抖动 TPDF Shibata",
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_bluetooth_sco),
            stringResource(R.string.settings_audio_bluetooth_sco_desc),
            "bluetooth SCO 蓝牙 通话",
        ),

        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_exclusive_mode),
            stringResource(R.string.usb_dac_exclusive_desc),
            "USB exclusive 独占",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_volume_mode),
            stringResource(R.string.usb_dac_volume_mode_summary),
            "USB volume 软件音量 硬件音量 Feature Unit",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_software_volume_fineness),
            stringResource(R.string.usb_dac_software_volume_fineness_desc),
            "software volume fineness range dB 软件音量 精细度",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_output_mode),
            stringResource(R.string.usb_dac_output_mode_summary),
            "PCM DSD DoP native output 输出模式",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_bit_perfect_strict),
            stringResource(R.string.usb_dac_bit_perfect_strict_desc),
            "bit perfect strict 位完美",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_pcm_to_dsd),
            stringResource(R.string.usb_dac_pcm_to_dsd_desc),
            "PCM DSD conversion 转换",
        ),
        childSearchItem(
            SettingsRootIconTone.Usb,
            stringResource(R.string.usb_dac_device_chain),
            keywords = "device chain USB DAC 设备链路",
        ),

        childSearchItem(
            SettingsRootIconTone.Effects,
            stringResource(R.string.settings_effects_peq_title),
            stringResource(R.string.settings_effects_peq_desc),
            "PEQ parametric EQ 参数均衡器",
            destination = PEQActivity::class.java,
            onClick = { openChildSettings(PEQActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Effects,
            stringResource(R.string.settings_effects_convolution),
            keywords = "convolution FIR 卷积",
        ),

        childSearchItem(
            SettingsRootIconTone.Transition,
            stringResource(R.string.transition_manual_title),
            stringResource(R.string.transition_manual_summary),
            "manual switch crossfade 手动切歌 淡入淡出",
        ),
        childSearchItem(
            SettingsRootIconTone.Transition,
            stringResource(R.string.transition_seek_title),
            stringResource(R.string.transition_seek_summary),
            "seek 拖动进度 淡入淡出",
        ),
        childSearchItem(
            SettingsRootIconTone.Transition,
            stringResource(R.string.transition_transport_title),
            stringResource(R.string.transition_transport_summary),
            "play pause stop transport 播放 暂停 停止",
        ),

        childSearchItem(
            SettingsRootIconTone.Shuffle,
            stringResource(R.string.settings_shuffle_randomization_title),
            stringResource(R.string.settings_shuffle_randomization_summary),
            "shuffle random 随机化",
        ),
        childSearchItem(
            SettingsRootIconTone.Shuffle,
            stringResource(R.string.settings_shuffle_categories_only),
            stringResource(R.string.settings_shuffle_categories_only_summary),
            "category 分类 随机",
        ),
        childSearchItem(
            SettingsRootIconTone.Shuffle,
            stringResource(R.string.settings_shuffle_hierarchy_title),
            stringResource(R.string.settings_shuffle_hierarchy_summary),
            "folder hierarchy 文件夹 层次结构",
        ),
        childSearchItem(
            SettingsRootIconTone.Shuffle,
            stringResource(R.string.settings_shuffle_large_title),
            stringResource(R.string.settings_shuffle_large_summary),
            "large list 大型列表",
        ),

        childSearchItem(
            SettingsRootIconTone.Appearance,
            stringResource(R.string.settings_appearance_theme_mode),
            stringResource(R.string.settings_appearance_theme_mode_desc),
            "theme light dark system 主题 浅色 深色",
        ),
        childSearchItem(
            SettingsRootIconTone.Appearance,
            stringResource(R.string.settings_appearance_color_scheme),
            stringResource(R.string.settings_appearance_color_scheme_desc),
            "color scheme Monet Miuix 配色",
        ),
        childSearchItem(
            SettingsRootIconTone.Appearance,
            stringResource(R.string.settings_custom_background_title),
            stringResource(R.string.settings_custom_background_summary),
            "custom background 自定义背景",
        ),
        childSearchItem(
            SettingsRootIconTone.Appearance,
            stringResource(R.string.settings_background_style),
            stringResource(R.string.settings_background_style_desc),
            "RawFlow flow static simple 背景样式 流光 静态",
        ),
        childSearchItem(
            SettingsRootIconTone.Appearance,
            stringResource(R.string.settings_language_title),
            stringResource(R.string.settings_language_summary),
            "language 中文 English 系统语言",
        ),

        childSearchItem(
            SettingsRootIconTone.Player,
            stringResource(R.string.settings_player_default_bg_rule_title),
            stringResource(R.string.settings_player_default_bg_rule_desc),
            "player background 播放页 背景",
        ),
        childSearchItem(
            SettingsRootIconTone.Player,
            stringResource(R.string.settings_player_immersive),
            stringResource(R.string.settings_player_immersive_desc),
            "immersive 沉浸",
        ),
        childSearchItem(
            SettingsRootIconTone.Player,
            stringResource(R.string.settings_player_visualizer),
            stringResource(R.string.settings_player_visualizer_desc),
            "visualizer waveform 可视化 波形",
        ),
        childSearchItem(
            SettingsRootIconTone.Player,
            stringResource(R.string.settings_player_artwork_animation_title),
            stringResource(R.string.settings_player_artwork_animation_summary),
            "artwork animation 专辑图 动画 carousel perspective slide",
        ),
        childSearchItem(
            SettingsRootIconTone.Player,
            stringResource(R.string.settings_player_page_memory),
            stringResource(R.string.settings_player_page_memory_desc),
            "page memory 页面记忆",
        ),

        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_album_art_quality),
            keywords = "cover quality 封面 画质",
        ),
        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_album_art_default_artwork_title),
            stringResource(R.string.settings_album_art_default_artwork_desc),
            "default artwork 默认封面",
        ),
        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_album_art_high_res_title),
            stringResource(R.string.settings_album_art_high_res_desc),
            "high resolution 高清 封面",
        ),
        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_album_art_clear_cache),
            stringResource(R.string.settings_album_art_clear_cache_desc),
            "cache 缓存 清理",
        ),
        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_artist_biography_switch),
            stringResource(R.string.settings_artist_biography_switch_summary),
            "artist biography 传记 艺术家 简介",
            onClick = { openChildSettings(AlbumArtActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.AlbumArt,
            stringResource(R.string.settings_artist_artwork_viewer_switch),
            stringResource(R.string.settings_artist_artwork_viewer_switch_summary),
            "artist image avatar artwork viewer 艺术家 头像 长按 查看",
            onClick = { openChildSettings(AlbumArtActivity::class.java) },
        ),

        childSearchItem(
            SettingsRootIconTone.Font,
            stringResource(R.string.settings_font_size),
            keywords = "font size 字体大小",
        ),
        childSearchItem(
            SettingsRootIconTone.Font,
            stringResource(R.string.settings_font_weight),
            keywords = "font weight 字重",
        ),
        childSearchItem(
            SettingsRootIconTone.Font,
            stringResource(R.string.settings_font_italic),
            keywords = "italic 斜体",
        ),

        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_predictive_back_title),
            stringResource(R.string.settings_predictive_back_summary),
            "predictive back 预测性返回 返回动画",
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_performance_mode_title),
            stringResource(R.string.settings_performance_mode_summary),
            "performance 性能模式",
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_bottom_navigation_title),
            stringResource(R.string.settings_bottom_navigation_summary),
            "bottom navigation 底部导航 导航栏",
            destination = BottomNavigationSettingsActivity::class.java,
            onClick = { openChildSettings(BottomNavigationSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_mini_player_karaoke_effect),
            stringResource(R.string.settings_mini_player_karaoke_effect_desc),
            "mini player karaoke word lyrics 播放栏 逐字 歌词 特效",
            destination = BottomNavigationSettingsActivity::class.java,
            onClick = { openChildSettings(BottomNavigationSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(com.rawsmusic.core.ui.R.string.home_header_carousel_gesture_lock_title),
            stringResource(com.rawsmusic.core.ui.R.string.home_header_carousel_gesture_lock_summary),
            "home carousel gesture lock 主界面 轮播 手势 锁定 相邻 专辑图",
            onClick = { openChildSettings(PersonalizationSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(com.rawsmusic.core.ui.R.string.home_most_played_disable_title),
            stringResource(com.rawsmusic.core.ui.R.string.home_most_played_disable_summary),
            "home most played hide 听过最多 歌曲 隐藏 主界面",
            onClick = { openChildSettings(PersonalizationSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(com.rawsmusic.core.ui.R.string.home_card_settings_title),
            stringResource(com.rawsmusic.core.ui.R.string.home_card_settings_entry_summary),
            "home card 主页卡片",
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_top_chrome_style_title),
            stringResource(R.string.settings_top_chrome_style_summary),
            "top chrome 顶部 工具栏 floating haze transparent",
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_library_bottom_buttons_title),
            stringResource(R.string.settings_library_bottom_buttons_summary),
            "library bottom buttons 库 底部按钮 液态玻璃",
        ),

        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_lyric_management_title),
            stringResource(R.string.settings_lyric_management_summary),
            "lyrics source plugin 歌词源 插件",
            destination = LyricManagementActivity::class.java,
            onClick = { openChildSettings(LyricManagementActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_lyric_color_section),
            stringResource(R.string.settings_lyric_color_scope_summary),
            "lyric color rainbow album artwork preset custom 歌词 颜色 彩虹 专辑图 预设 自定义",
            destination = LyricManagementActivity::class.java,
            onClick = { openChildSettings(LyricManagementActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_status_bar_lyric_title),
            stringResource(R.string.settings_status_bar_lyric_summary),
            "status bar desktop lyric 状态栏 悬浮歌词",
            destination = StatusBarLyricActivity::class.java,
            onClick = { openChildSettings(StatusBarLyricActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_system_lyric_delivery_title),
            stringResource(R.string.settings_system_lyric_delivery_summary),
            "system lyric delivery Flyme Xiaomi 三星 歌词输出",
            destination = SystemLyricDeliveryActivity::class.java,
            onClick = { openChildSettings(SystemLyricDeliveryActivity::class.java) },
        ),

        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_choose_folder_title),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_choose_folder_summary),
            "scan folder 音乐文件夹 扫描目录",
        ),
        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_rescan_title),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_rescan_summary),
            "rescan 重新扫描 媒体库",
        ),
        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_min_duration_title),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_min_duration_summary),
            "minimum duration 最短时长 短曲过滤",
        ),
        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_cold_start_auto_title),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_cold_start_auto_summary),
            "cold start auto scan 冷启动 自动扫描",
        ),
        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_tag_ignore_case),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_tag_ignore_case_summary),
            "tag ignore case 标签 大小写",
        ),
        childSearchItem(
            SettingsRootIconTone.Scan,
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_artist_separators),
            stringResource(com.rawsmusic.core.ui.R.string.scan_settings_artist_separators_summary),
            "artist separator 艺术家 分隔符",
        ),

        childSearchItem(
            SettingsRootIconTone.Backup,
            stringResource(R.string.settings_webdav_backup_action),
            stringResource(R.string.settings_webdav_backup_restore_desc),
            "WebDAV backup 备份 云端",
        ),
        childSearchItem(
            SettingsRootIconTone.Backup,
            stringResource(R.string.settings_webdav_restore_action),
            stringResource(R.string.settings_webdav_backup_restore_desc),
            "WebDAV restore 恢复",
        ),

        childSearchItem(
            SettingsRootIconTone.Ai,
            stringResource(R.string.settings_ai_runtime_section),
            keywords = "ONNX ORT runtime AI 运行库",
        ),
        childSearchItem(
            SettingsRootIconTone.Ai,
            stringResource(R.string.settings_ai_repository_import),
            keywords = "AI repository model 模型仓库 导入",
        ),
    )

    val searchLocale = LocalConfiguration.current.locales.toLanguageTags()
    val indexedLabels = remember(context, searchLocale) {
        settingsSearchIndex.map { it to context.getString(it.titleRes) }
    }
    val indexedItems = remember(context, searchLocale) { indexedLabels
        .filterNot { (entry, label) -> rootItems.any { it.title == label && it.destination == entry.destination.name } }
        .map { (entry, label) ->
        val tone = SettingsRootIconTone.valueOf(entry.tone)
        val item = childSearchItem(tone, label, summary = "", destination = entry.destination,
            onClick = { openChildSettings(entry.destination) })
        if (entry.destination == HdrSettingsActivity::class.java) {
            item.copy(parentTitle = context.getString(com.rawsmusic.core.ui.R.string.settings_hdr_title), keywords = "HDR")
        } else item
    } }
    val searchItems = remember(context, searchLocale) { (rootItems + childItems + indexedItems)
        .distinctBy { it.destination to normalizeSettingsSearchText(it.title) }
    }
    val filteredItems = remember(searchQuery, searchItems) { if (searchQuery.isBlank()) {
        emptyList()
    } else {
        searchItems
            .filter { result -> matchesSettingsSearchQuery(searchQuery, result) }
            .sortedWith(
                compareBy<SettingsSearchItem> { settingsSearchScore(searchQuery, it) }
                    .thenByDescending(SettingsSearchItem::depth)
                    .thenBy(SettingsSearchItem::title)
            )
    } }
    val resultGroups = remember(filteredItems) { filteredItems.groupBy(SettingsSearchItem::destination).entries.toList() }
    var expandedGroups by remember(searchQuery) { mutableStateOf(emptySet<String>()) }
    // Record settled successful searches as well as explicit submit/result/cancel. Avoid
    // writing every keystroke, and don't fill history with incomplete intermediate queries.
    LaunchedEffect(searchActive, searchQuery) {
        if (searchActive && searchQuery.isNotBlank()) {
            kotlinx.coroutines.delay(600)
            recordSearch()
        }
    }

    val homeScrollState = rememberScrollState()
    val searchResultScrollState = rememberLazyListState()
    LaunchedEffect(searchActive) {
        if (searchActive) homeScrollState.stopScroll()
    }
    LaunchedEffect(searchActive, searchQuery) {
        if (searchActive) searchResultScrollState.scrollToItem(0)
    }
    val topBlurState = rememberHazeState()
    val searchPageBackdrop = rememberLayerBackdrop()
    val windowBackdrop = LocalSettingsBackdrop.current
    val density = LocalDensity.current
    val title = stringResource(R.string.settings_main_title)
    val searchHint = stringResource(R.string.settings_search_hint)
    val statusBarTop = rawStableStatusBarTopPadding()
    val homeOverscroll = rememberCouiSpringOverscrollState()
    val homeCollapsePx = with(density) { SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP.dp.toPx() }
    // --- AppBar offset: nested-scroll consumption + COUISpringForce ---
    // COUIAppBarLayout.BaseBehavior splits the two directions, and that split is what the gesture
    // feels like in the reference (see SettingsHomeHeaderMotion.pre/postScrollConsumption):
    //  * collapsing is entirely the AppBar's, in the *pre*-scroll phase, clamped to the 116dp range —
    //    a small drag only moves the AppBar, the list never leaves the top, so releasing below the
    //    snap threshold animates the AppBar back and the whole screen ends up where it started;
    //  * expanding is only what the list could not consume, i.e. the *post*-scroll leftover once the
    //    list is back at its top. Inside a scrolled list a downward drag therefore scrolls the list
    //    and the "设置" title only fades in when the list is home.
    // Logical AppBar offset (px, <= 0), saved/restored like OFFSET_STATE_KEY.
    val headerTarget = rememberSaveable { mutableFloatStateOf(0f) }
    // Displayed offset: the spring below follows the target; starts where the target was restored.
    val headerDisplay = remember { mutableFloatStateOf(headerTarget.floatValue) }
    // True while the release snap drives the bar with the attach spring (animateOffsetWithDuration).
    val headerSnapping = remember { mutableStateOf(false) }
    // COUISpringForce velocity (px/s), kept while the spring is retargeted. A plain array: it is
    // never read during composition.
    val headerVelocity = remember { floatArrayOf(0f) }
    val homeRangePx = homeCollapsePx.roundToInt()
    val headerConnection = remember(homeCollapsePx) {
        object : NestedScrollConnection {
            /** Moves the bar by [consumed] px (negative = collapse) and reports it as consumed. */
            private fun move(consumed: Float): Offset {
                if (consumed == 0f) return Offset.Zero
                headerTarget.floatValue += consumed
                // A new gesture owns the bar again (scroll(...) picks the follow spring).
                headerSnapping.value = false
                return Offset(0f, consumed)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = move(
                SettingsHomeHeaderMotion.preScrollConsumption(
                    availableY = available.y,
                    offsetPx = headerTarget.floatValue,
                    rangePx = homeCollapsePx,
                )
            )

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = move(
                SettingsHomeHeaderMotion.postScrollConsumption(
                    availableY = available.y,
                    offsetPx = headerTarget.floatValue,
                    rangePx = homeCollapsePx,
                )
            )
        }
    }
    LaunchedEffect(homeScrollState, searchActive) {
        if (searchActive) return@LaunchedEffect
        // onStopNestedScroll → snapToChildIfNeeded, evaluated on the *AppBar's own* offset:
        //  * contentInScreen(list) while mAutoExpand is on -> mShouldSnapToBottom, i.e. the base
        //    offset reports 0 once and the snap always resolves to "expanded";
        //  * otherwise calculateSnapOffset uses the AppBar's child edges (0 / -116dp) with the
        //    mid-point rule (integer division) — the "gesture threshold" — and moves only the AppBar
        //    (the list keeps the position the finger left), driven by the attach spring (0.25/0).
        snapshotFlow { homeScrollState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) return@collectLatest
            val offset = headerTarget.floatValue
            val snapOffset = when {
                homeScrollState.maxValue <= 0 -> 0f
                offset <= -homeRangePx || offset >= 0f -> return@collectLatest
                offset < -homeRangePx / 2 -> -homeRangePx.toFloat()
                else -> 0f
            }
            if (snapOffset == offset && abs(headerDisplay.floatValue - snapOffset) < 0.01f) {
                return@collectLatest
            }
            headerSnapping.value = true
            headerTarget.floatValue = snapOffset
        }
    }
    // One spring for the follow and the release snap (COUISpringForce.getAcceleration:
    // a = -omega^2 * (x - target) - 2 * zeta * omega * v, and zeta = 1 because every
    // COUICollapsableAppBarLayoutParams bounce is 0). Animatable.animateTo cannot be retargeted
    // per frame: a new call cancels the running animation and Compose resets its velocity to 0
    // (Animatable.kt: "once the animation ends, its velocity will be reset to 0"), which restarted
    // the bar from rest on every scroll frame and made the title lag the gesture by tens of dp.
    LaunchedEffect(searchActive) {
        if (searchActive) return@LaunchedEffect
        var last = 0L
        var idle = true
        while (true) {
            if (idle) {
                snapshotFlow { headerTarget.floatValue to headerDisplay.floatValue }
                    .first { (target, x) -> abs(target - x) > 0.01f }
                idle = false
                last = 0L
            } else {
                val now = withFrameNanos { it }
                val x = headerDisplay.floatValue
                val target = headerTarget.floatValue
                var v = headerVelocity[0]
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.032f)
                    val stiffness = when {
                        headerSnapping.value -> SettingsHomeHeaderMotion.SnapStiffness
                        target < x -> SettingsHomeHeaderMotion.CollapseFollowStiffness
                        else -> SettingsHomeHeaderMotion.ExpandFollowStiffness
                    }
                    // RK4, like COUI's own CouiPhysicsState.rk4 / CouiSpringForce: the explicit Euler
                    // step is only marginally stable for the stiff expand spring (omega ~ 63 rad/s at
                    // a 32 ms frame), which would ring or overshoot the target.
                    val h = dt.toDouble()
                    val kd = stiffness.toDouble()
                    val c = 2.0 * sqrt(kd)                     // zeta = 1, every COUI bounce is 0
                    val xd = x.toDouble()
                    val vd = v.toDouble()
                    val td = target.toDouble()
                    val a1 = -kd * (xd - td) - c * vd
                    val x2 = xd + vd * h * 0.5
                    val v2 = vd + a1 * h * 0.5
                    val a2 = -kd * (x2 - td) - c * v2
                    val x3 = xd + v2 * h * 0.5
                    val v3 = vd + a2 * h * 0.5
                    val a3 = -kd * (x3 - td) - c * v3
                    val x4 = xd + v3 * h
                    val v4 = vd + a3 * h
                    val a4 = -kd * (x4 - td) - c * v4
                    val rk4Weight = 1.0 / 6.0
                    // Clamped to the AppBar's own range: the reference can never report an offset
                    // outside [0, -116dp], and a display that went positive would render the large
                    // title (its alpha is 1 - |offset| / 64dp) with the chrome pushed down.
                    val next = (xd + (vd + 2.0 * (v2 + v3) + v4) * rk4Weight * h)
                        .toFloat()
                        .coerceIn(-homeCollapsePx, 0f)
                    v = (vd + (a1 + 2.0 * (a2 + a3) + a4) * rk4Weight * h).toFloat()
                    headerDisplay.floatValue = next
                    headerVelocity[0] = v
                    if (abs(target - next) < 0.05f && abs(v) < 1.5f) {
                        headerDisplay.floatValue = target
                        headerVelocity[0] = 0f
                        headerSnapping.value = false
                        idle = true
                    }
                }
                last = now
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(resolvedPageBackground)
    ) {
        // Capture a fixed, full-screen viewport, including its fill. Recording inside the
        // scrolling column made the sampling origin scroll and excluded the status-bar area.
        Box(Modifier.fillMaxSize()
            .then(if (Build.VERSION.SDK_INT >= 33 && isBackdropSupported)
                Modifier.layerBackdrop(searchPageBackdrop) else Modifier)
            .then(if (isBackdropSupported) Modifier.settingsTopBlurSource(topBlurState, windowBackdrop) else Modifier)
            .background(resolvedPageBackground)) {
        // Keep the homepage mounted: exiting search must not restore its scroll on a later frame.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = SettingsSearchMotion.at(searchProgress, searchActive).homeAlpha
        }) {
            WithoutPlatformOverscroll {
                Column(Modifier.fillMaxSize()
                    .nestedScroll(homeOverscroll.connection)
                    .nestedScroll(headerConnection)
                    .couiOverscrollOffset(homeOverscroll)
                    .verticalScroll(homeScrollState, enabled = !searchActive,
                        flingBehavior = rememberCouiFlingBehavior(homeOverscroll))) {
                    Column(Modifier.offset { IntOffset(0, headerDisplay.floatValue.roundToInt()) }) {
                        Spacer(Modifier.height(statusBarTop))
                        Spacer(Modifier.height(SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP.dp))
                        Spacer(Modifier.height(SettingsHomeHeaderMotion.SEARCH_BAR_HEIGHT_DP.dp))
                        sections.forEach { section ->
                            Column(Modifier.padding(horizontal = 12.dp)) {
                                SmallTitle(text = section.title)
                                SettingsCardGroup {
                                    Column {
                                        section.items.forEach { item -> SettingsRootEntry(item, isDark) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }
        }
        if (searchActive || searchProgress > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = searchProgress
            translationY = (with(density) { statusBarTop.toPx() } + homeCollapsePx + headerDisplay.floatValue) * (1f - searchProgress)
        }.background(resolvedPageBackground).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { keyboard?.hide() }) {
        if (searchQuery.isBlank() || filteredItems.isNotEmpty()) {
            LazyColumn(
                state = searchResultScrollState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp)
            ) {
                item(key = "search-top") { Spacer(modifier = Modifier.height(64.dp)) }
                if (searchQuery.isBlank()) {
                    if (recentQueries.isNotEmpty()) {
                        item(key = "search-history") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                        SmallTitle(text = stringResource(R.string.settings_search_recent_title))
                            }
                            Text(stringResource(R.string.settings_search_clear_history),
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.clickable { removeHistory() }.padding(16.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)) {
                            recentQueries.forEach { query ->
                                Row(Modifier.clip(RoundedCornerShape(50))
                                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(query, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = MiuixTheme.colorScheme.onBackground,
                                        modifier = Modifier.weight(1f, fill = false).clickable {
                                            searchQuery = query
                                        }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp))
                                    Icon(Icons.Default.Close,
                                        contentDescription = stringResource(R.string.settings_search_remove_history, query),
                                        tint = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        modifier = Modifier.clickable { removeHistory(query) }
                                            .padding(end = 12.dp, start = 4.dp).size(18.dp))
                                }
                            }
                        }
                        }
                    }
                } else {
                    resultGroups.forEach { (destination, results) ->
                        val owner = (rootItems + childItems).firstOrNull { it.destination == destination }
                        item(key = "heading:$destination") {
                            SmallTitle(text = "${owner?.title ?: results.first().parentTitle ?: results.first().title} · ${results.size}")
                        }
                        val expanded = destination in expandedGroups
                        items(if (expanded) results else results.take(3),
                            key = { "$destination:${normalizeSettingsSearchText(it.title)}" }) { result ->
                            SettingsCardGroup {
                            SettingsSearchResultEntry(
                                result = result,
                                isDark = isDark,
                                onClick = {
                                    recordSearch()
                                    keyboard?.hide()
                                    result.onClick()
                                },
                            )
                            }
                        }
                        if (results.size > 3) {
                            item(key = "expand:$destination") {
                                Text(text = if (expanded) stringResource(R.string.settings_search_collapse_group)
                                    else stringResource(R.string.settings_search_expand_group, results.size - 3),
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        expandedGroups = if (expanded) expandedGroups - destination else expandedGroups + destination
                                    }.padding(horizontal = 20.dp, vertical = 12.dp))
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        }
        }

        }
        }
        SettingsHomeSearchChrome(
            title = title,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            hint = searchHint,
            headerOffsetPx = { headerDisplay.floatValue },
            contentScrollPx = { homeScrollState.value.toFloat() + homeOverscroll.scrollY },
            searchActive = searchActive,
            searchProgress = searchProgress,
            onEnterSearch = { searchActive = true },
            onLeaveSearch = { leaveSearch() },
            onSubmitSearch = { recordSearch(); keyboard?.hide() },
            searchFocus = searchFocus,
            isDark = isDark,
            hazeState = topBlurState,
            materialBackdrop = searchPageBackdrop,
            statusBarTop = statusBarTop,
        )
    }
}

/**
 * SettingsCollapsingToolbarLayout large title. [headerOffsetPx] is the displayed AppBar offset
 * (≤ 0, spring-followed); the collapsing toolbar sits right below the status-bar padding.
 */
@Composable
private fun SettingsHomeTitle(
    title: String,
    headerOffsetPx: () -> Float,
    statusBarTopPx: Float,
    searchProgress: Float,
    searchActive: Boolean,
) {
    val density = LocalDensity.current
    var textHeightPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .offset { IntOffset(0, (statusBarTopPx + headerOffsetPx()).roundToInt()) }
            .fillMaxWidth()
            .height(SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP.dp)
            .padding(start = 24.dp, bottom = SettingsHomeHeaderMotion.TITLE_MARGIN_BOTTOM_DP.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = title,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = appFontFamily(),
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier
                .onSizeChanged { textHeightPx = it.height }
                .graphicsLayer {
                    val scrollPx = -headerOffsetPx()
                    val motion = SettingsHomeHeaderMotion.at(scrollPx / density.density)
                    // COUICollapsingTextHelper, in collapsing-toolbar coordinates: the expanded
                    // anchor sits at the bottom (margin 18dp). The collapsed anchor is *constant*:
                    // COUICollapsingToolbarLayout.updateCollapsedBounds bakes it once per layout as the
                    // pinned toolbar's slot + getMaxOffsetForPinChild, which reads the toolbar's
                    // ViewOffsetHelper.getLayoutTop() (the layout position, not the live pin offset),
                    // and the offset listener never requests layout — so it must not follow the scroll.
                    // Both text sizes are 40sp, so the anchor uses the unscaled height; Settings then
                    // scales the drawn text separately.
                    val h = textHeightPx.toFloat()
                    val expandedTop = (SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP -
                        SettingsHomeHeaderMotion.TITLE_MARGIN_BOTTOM_DP) * density.density - h
                    val pinPx = SettingsHomeHeaderMotion.TITLE_RANGE_DP * density.density
                    val collapsedTop = pinPx +
                        (SettingsHomeHeaderMotion.TOOLBAR_HEIGHT_DP * density.density - h) / 2f +
                        SettingsHomeHeaderMotion.TITLE_MARGIN_CENTER_OFFSET_DP * density.density
                    // TitleTransform belongs to the text, not an alpha layer around the
                    // AppBar. An outer offscreen layer clips the offset title at its bounds.
                    val search = SettingsSearchMotion.at(searchProgress, searchActive)
                    translationY = (collapsedTop - expandedTop) * motion.titlePositionFraction + search.titleOffsetPx
                    alpha = motion.titleAlpha * search.homeAlpha
                    scaleX = motion.titleScale * search.titleScale
                    scaleY = scaleX
                    // canvas.scale(scale, scale, drawX, drawY): pivot at the text's top-left.
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        )
    }
}

@Composable
private fun SettingsHomeSearchChrome(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    headerOffsetPx: () -> Float,
    contentScrollPx: () -> Float,
    searchActive: Boolean,
    searchProgress: Float,
    onEnterSearch: () -> Unit,
    onLeaveSearch: () -> Unit,
    onSubmitSearch: () -> Unit,
    searchFocus: FocusRequester,
    isDark: Boolean,
    hazeState: HazeState,
    materialBackdrop: Backdrop,
    statusBarTop: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val collapsePx = with(density) { SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP.dp.toPx() }
    val statusTopPx = with(density) { statusBarTop.toPx() }
    // Displayed AppBar offset (the header view chases the list with a spring). COUIAppBarLayout
    // dispatches offsets to *every* listener from that same per-frame spring animation
    // (COUIViewOffsetHelper.OnAnimateUpdateListener -> BaseBehavior -> appBar.onOffsetChanged(frame)),
    // so the overlay alpha, the extra height and the search-bar shadow all follow the displayed
    // offset — the logical scroll target is only what the nested scroll consumes against.
    val headerPx = SettingsSearchMotion.headerOffset(headerOffsetPx(), collapsePx, searchProgress)
    val displayedPx = -headerPx
    val motion = SettingsHomeHeaderMotion.at(displayedPx / density.density)
    // AppBarBlurOverlayBehavior.resolveScrollDistancePx: the |AppBar offset| while it is non-zero,
    // otherwise the content scroll plus the rubber band (mAccumulatedContentScrollPx +
    // COUIRecyclerView.getScrollY()). A list that cannot move the bar therefore still fades the
    // overlay in while it is being overscrolled.
    val barOffsetPx = displayedPx
    val contentPx = contentScrollPx()
    val blurDistancePx = if (barOffsetPx > 0f) barOffsetPx else contentPx
    val normalBlurAlpha = SettingsHomeHeaderMotion.blurAlpha(blurDistancePx / density.density)
    // HomepageImpl.applySearchEditBlur forces alpha on entry; animToNormal releases it on exit.
    val blurAlpha = if (searchActive) 1f else normalBlurAlpha
    val barProgress by animateFloatAsState(
        if (searchActive) 1f else 0f,
        spring(dampingRatio = SettingsSearchMotion.barDampingRatio,
            stiffness = SettingsSearchMotion.barStiffness, visibilityThreshold = 0.000001f),
        label = "SettingsSearchCapsule",
    )
    val cancelProgress by animateFloatAsState(
        if (searchActive) 1f else 0f,
        spring(dampingRatio = SettingsSearchMotion.barDampingRatio,
            stiffness = SettingsSearchMotion.barStiffness, visibilityThreshold = 0.000001f),
        label = "SettingsSearchCancel",
    )
    // AppBarBlurOverlayView spans the whole AppBar — status-bar padding + 116dp collapsing toolbar
    // + 52dp search bar — moved by the AppBar offset, plus homepage_appbar_blur_extra_height while
    // the AppBar reports isCollapsed() (the *title* collapsed, |offset| >= 64dp, not 116dp).
    // Background RenderEffect operates over the visible sampling band. Keep that band's
    // origin at the window top: moving a full-height Compose mask offscreen instead leaves
    // the search row in its sharp tail once the large title has collapsed.
    val overlayHeight = statusBarTop +
        (SettingsHomeHeaderMotion.COLLAPSE_RANGE_DP + SettingsHomeHeaderMotion.SEARCH_BAR_HEIGHT_DP +
            if (motion.collapsed) SettingsHomeHeaderMotion.BLUR_COLLAPSED_EXTRA_DP else 0f).dp
    // Only the already-composited header pixels are recorded here. Never record the
    // search material itself, which would feed its own tint back into the next frame.
    val searchBlurBackdrop = rememberLayerBackdrop()
    val searchBackdrop = rememberCombinedBackdrop(materialBackdrop,searchBlurBackdrop)
    Box(modifier = modifier.fillMaxWidth()) {
        if (isBackdropSupported) {
            ProgressiveTopBlur(
                hazeState = hazeState,
                radius = 30.dp,
                tintColor = MiuixTheme.colorScheme.background,
                tintAlpha = if (isDark) 0.20f else 0.24f,
                modifier = Modifier
                    .fillMaxWidth()
                    .layerBackdrop(searchBlurBackdrop)
                    .height(with(density) {
                        settingsBlurViewportHeight(overlayHeight.toPx(), headerPx).toDp()
                    })
                    .graphicsLayer { alpha = blurAlpha },
            )
        }

        if (searchProgress < 1f) {
            // SettingsCollapsingToolbarLayout: part of the AppBar, moved by the AppBar offset
            // (clamped to the 116dp scroll range) and never by list overscroll.
            SettingsHomeTitle(
                title = title,
                headerOffsetPx = headerOffsetPx,
                statusBarTopPx = statusTopPx,
                searchProgress = searchProgress,
                searchActive = searchActive,
            )
        }

        // SearchViewAnimate (exitUntilCollapsed): top = statusBar + 116dp + offset, so it ends
        // pinned right under the status bar. 52dp row, 40dp capsule, 16dp horizontal padding.
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (statusTopPx + collapsePx + headerPx).roundToInt()) }
                .fillMaxWidth()
                .height(SettingsHomeHeaderMotion.SEARCH_BAR_HEIGHT_DP.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(50)
            // COUISearchBar hint / icon colour: couiColorLabelSecondary / couiColorSecondNeutral.
            val secondaryColor = if (isDark) Color(0x8AFFFFFF) else Color(0x8A000000)
            val contentColor = MiuixTheme.colorScheme.onBackground
            val context = androidx.compose.ui.platform.LocalContext.current
            val frameworkSurface = remember(context) { CouiSearchBarSurface.resolve(context) }
            val materialColor = if (isBackdropSupported) {
                // The fixed progressive layer already draws the pixels behind this capsule.
                // Do not redraw the sharp page or sample/blur the header a second time.
                if (isDark) Color(0xFF262626).copy(alpha = 0.65f)
                else Color(0xFFF5F5F5).copy(alpha = 0.32f)
            } else {
                // Material version without blur: solid ?attr/couiColorBar.
                if (isDark) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
            }
            val cancelWidth = remember { mutableIntStateOf(0) }
            val cancelTranslationDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
            val sharedMaterial = searchMaterialModifier(cancelWidth.intValue,barProgress,cancelProgress,
                materialColor,if (isBackdropSupported) searchBackdrop else null,isDark,
                decorateSearch = !frameworkSurface.stroke)
            // The backdrop's offscreen rectangular clip belongs to its sampling layer only.
            // Wrapping the Layout also clips the sibling View's capsule drop shadow to the
            // row bounds, leaving a straight-edged grey rectangle on light backgrounds.
            Box(Modifier.matchParentSize().then(sharedMaterial))
            // measureFunctionalButton: text-sized, capped at 152dp. The capsule reserves the
            // measured width + 12dp; cancel is a sibling, never part of EditText's decoration.
            Layout(content = {
            Box {
            if (frameworkSurface.available) {
                CouiSearchBarBackground(
                    surface = frameworkSurface,
                    // HomepageImpl's AppBar offset listener drives COUISearchBar.updateDropShadow with
                    // the displayed offset (per spring frame), not with the scroll target.
                    fraction = { displayedPx / collapsePx },
                    isDark = isDark,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = contentColor,
                    fontSize = 15.sp,
                    fontFamily = appFontFamily(),
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(contentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .focusRequester(searchFocus)
                    .onFocusChanged { if (it.isFocused && !searchActive) onEnterSearch() }
                    .clip(shape),
                decorationBox = { innerTextField ->
                    Row(
                        // Inner search icon: 4dp start gap + 36dp slot (8dp padding, 20dp glyph),
                        // so the text starts at coui_search_view_background_start_gap (40dp).
                        modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = secondaryColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = hint,
                                    fontSize = 15.sp,
                                    fontFamily = appFontFamily(),
                                    color = secondaryColor,
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        }
                        if (query.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable { onQueryChange("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = secondaryColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                },
            )
            }
            Box(modifier = Modifier.graphicsLayer {
                // Keep the spring's subpixel motion in the layer, not integer placement.
                // The shared surface uses exactly the same displacement.
                translationX = cancelTranslationDirection * SettingsSearchMaterial.cancelTranslation(cancelProgress)
                alpha = SettingsSearchMotion.cancelAlpha(cancelProgress)
                scaleX = SettingsSearchMotion.cancelScale(cancelProgress)
                scaleY = scaleX
                // COUISearchBar.updateBlurWithAnimationProgress: physical px, DECAL, not dp.
                val radius = 30f * (1f - cancelProgress).coerceAtLeast(0f)
                renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > 0.01f)
                    android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.DECAL)
                        .asComposeRenderEffect() else null
            }.clickable(enabled = searchActive, onClick = onLeaveSearch)) {
            // The functional cancel button is not SearchBarBackgroundView: its surface is
            // already drawn by sharedMaterial. Do not attach the search bar's scroll shadow.
            Text(
                text = stringResource(android.R.string.cancel),
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                fontFamily = appFontFamily(),
                color = contentColor.copy(alpha = contentColor.alpha * cancelProgress.coerceIn(0f, 1f)),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp).padding(horizontal = 16.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
            }
            }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
                val button = measurables[1].measure(Constraints(
                    maxWidth = minOf(152.dp.roundToPx(), constraints.maxWidth),
                    maxHeight = constraints.maxHeight,
                ))
                if (cancelWidth.intValue != button.width) cancelWidth.intValue = button.width
                val capsuleWidth = SettingsSearchMaterial.searchWidth(
                    constraints.maxWidth, button.width, 12.dp.roundToPx(), barProgress,
                )
                val capsule = measurables[0].measure(Constraints.fixed(capsuleWidth, 40.dp.roundToPx()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Material-version branch of m9779e: translate from capsule edge to final
                    // button position. placeRelative also mirrors the same geometry in RTL.
                    button.placeRelative(
                        constraints.maxWidth - button.width,
                        (constraints.maxHeight - button.height) / 2,
                    )
                    capsule.placeRelative(0, (constraints.maxHeight - capsule.height) / 2)
                }
            }
        }
    }
}

/**
 * Which parts of SearchBarBackgroundView the platform renders on this device.
 * - blur: COUIMaterialEffect.canApplyBackgroundRenderEffect() (OplusBuild SDK 33.34+, animation
 *   level not low) and COUIMaterialStateManager.MATERIAL_BLUR_ENABLE
 *   (Settings.System system_material_blur_enable == 1, not in low power / super power save).
 * - stroke: MATERIAL_STROKE_ENABLE (materialVersionCheck() 40.2+ and
 *   Settings.System system_material_stroke_enable == 1).
 * - caustic: MATERIAL_CAUSTIC_SHADOW_ENABLE (stroke and the app feature
 *   com.oplus.appcompat.texture_effect.Enable) → COUISearchBar.isCausticShadowEnable().
 * - elevation: ShadowUtils.checkOPlusViewElevationSDK() (34.2+).
 * - smoothCorner: RoundCornerUtil.getSmoothStyleType() == 1.
 */
private class CouiSearchBarSurface(
    val blur: Boolean,
    val stroke: Boolean,
    val caustic: Boolean,
    val elevation: Boolean,
    val smoothCorner: Boolean,
) {
    val available: Boolean get() = blur || stroke || caustic || elevation

    companion object {
        fun resolve(context: android.content.Context): CouiSearchBarSurface {
            if (!CouiOplus.present) return CouiSearchBarSurface(false, false, false, false, false)
            val resolver = context.contentResolver
            val animLevel = CouiOplus.systemPropInt("persist.sys.oplus.anim_level", 3)
            // COUIMaterialStateManager.isLowAnimationLevel(): !(1 <= level < 3).
            val lowAnimation = animLevel !in 1 until 3
            val lowPower = runCatching {
                android.provider.Settings.Global.getInt(resolver, "low_power", 0) == 1
            }.getOrDefault(false)
            val superPowerSave = runCatching {
                android.provider.Settings.System.getInt(resolver, "super_powersave_mode_state", 0) == 1
            }.getOrDefault(false)
            val blurSetting = runCatching {
                android.provider.Settings.System.getInt(resolver, "system_material_blur_enable", 0) == 1
            }.getOrDefault(false)
            val strokeSetting = runCatching {
                android.provider.Settings.System.getInt(resolver, "system_material_stroke_enable", 0) == 1
            }.getOrDefault(false)
            val stroke = CouiOplus.checkSubSdk(40, 2) && strokeSetting
            val feature = CouiOplus.appFeatureBoolean(resolver, "com.oplus.appcompat.texture_effect.Enable")
            // getSmoothStyleType() == 1: 37.1+ and isSmoothRoundRectOn() (version 34+, anim_level < 3
            // or upgrade_anim_level < 3, default_smooth_weight / 100 != 2).
            val upgradeLevel = CouiOplus.systemPropInt("persist.sys.oplus.upgrade_anim_level", 3)
            val weight = CouiOplus.systemPropInt("persist.sys.oplus.default_smooth_weight", 170) / 100f
            val smooth = CouiOplus.checkSubSdk(37, 1) && CouiOplus.sdk.first >= 34 &&
                (animLevel < 3 || upgradeLevel < 3) && weight != 2f
            return CouiSearchBarSurface(
                blur = CouiOplus.checkSubSdk(33, 34) && !lowAnimation && blurSetting &&
                    !lowPower && !superPowerSave,
                stroke = stroke,
                caustic = stroke && feature,
                elevation = CouiOplus.checkSubSdk(34, 2),
                smoothCorner = smooth,
            )
        }
    }
}

/**
 * HomepageImpl$2 offset listener driving COUISearchBar.updateDropShadow. Per offset change (skipped
 * when |offset| / totalScrollRange equals mScrollFraction):
 * fraction > 0.5 → updateDropShadow(fraction); setNoShadowInNormalState(false)
 * otherwise      → updateDropShadow(0);        setNoShadowInNormalState(true)
 * updateDropShadow clamps to [0, 1] and, while mNoShadowInNormalState is still set, remaps with
 * remapShadowProgress (p ≤ 0.8 → 0, else (p − 0.8) / 0.2). HomepageImpl sets the flag to true
 * when the search bar is set up.
 */
private class SearchBarDropShadow {
    private var scrollFraction = 0f
    private var noShadowInNormalState = true
    var progress = 0f
        private set

    fun onOffsetChanged(fraction: Float) {
        if (scrollFraction == fraction) return
        scrollFraction = fraction
        if (fraction > 0.5f) {
            updateDropShadow(fraction)
            noShadowInNormalState = false
        } else {
            updateDropShadow(0f)
            noShadowInNormalState = true
        }
    }

    private fun updateDropShadow(value: Float) {
        val p = value.coerceIn(0f, 1f)
        progress = if (noShadowInNormalState) {
            if (p <= 0.8f) 0f else (p - 0.8f) / 0.19999999f
        } else {
            p
        }
    }
}

/**
 * SearchBarBackgroundView's platform decorations (normal state, material type 0).
 * The TOP_BAR background colour layers are drawn in Compose over the AppBar recording;
 * this View supplies TYPE_FRAMEWORK_CAPSULE_1 stroke:
 *   OplusMaterialEdgeParams(1, 2.2dp, 0.2 / night 0.6, 0) and OplusMaterialShadowParams(1,
 *   1.15 / night 1.0, 0.1 / night 0.01, 1.0); clipToOutline off; OplusMaterialCornerParams(60, 1)
 *   with radius h / 2. Outline: the smooth round rect path, or a round rect of radius h / 2.
 * - updateDropShadow: caustic → setCausticShadow (ShadowUtils level 6 + caustic colour / layout /
 *   params), otherwise clearCausticShadow + updateViewShadow (ShadowUtils level 3a).
 */
@Composable
private fun CouiSearchBarBackground(
    surface: CouiSearchBarSurface,
    fraction: () -> Float,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val dropShadow = remember { SearchBarDropShadow() }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context -> CouiSearchBarBackgroundView(context, surface) },
        modifier = modifier,
        update = { view ->
            view.setNight(isDark)
            dropShadow.onOffsetChanged(fraction())
            view.updateDropShadow(dropShadow.progress)
        },
)
}

private class CouiSearchBarBackgroundView(
    context: android.content.Context,
    private val surface: CouiSearchBarSurface,
) : android.view.View(context) {
    private val smoothPath = android.graphics.Path()
    private val smoothRect = android.graphics.RectF()
    private var night: Boolean? = null
    private var shadowProgress = -1f

    init {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                if (surface.blur && !smoothPath.isEmpty) {
                    outline.setPath(smoothPath)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
        }
        clipToOutline = !surface.blur
        // UIUtil.setViewShadowClippingEnabled(this, true), OplusBuild SDK 40.2+.
        if (CouiOplus.checkSubSdk(40, 2)) CouiOplus.setShadowClippingEnabled(this, true)
        // The platform draws this view's shadow while its parent draws it; the interop holder is
        // exactly the capsule's size, so it must not clip it.
        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                (v.parent as? android.view.ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
            }

            override fun onViewDetachedFromWindow(v: android.view.View) = Unit
        })
    }

    fun setNight(isNight: Boolean) {
        if (night == isNight) return
        night = isNight
        applyMaterialSurface()
        if (shadowProgress >= 0f) applyDropShadow(shadowProgress)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // updateSmoothRoundPath: radius = height / 2 over the background bounds.
        smoothPath.reset()
        smoothRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val radius = h / 2f
        if (!(surface.smoothCorner && CouiOplus.addSmoothRoundRect(smoothPath, smoothRect, radius))) {
            smoothPath.reset()
            smoothPath.addRoundRect(smoothRect, radius, radius, android.graphics.Path.Direction.CW)
        }
        invalidateOutline()
        applyMaterialSurface()
    }

    private fun applyMaterialSurface() {
        val isNight = night ?: return
        if (width == 0 || height == 0) return
        // Compose draws the background material from the composited AppBar scene. This View
        // owns only the platform edge/corner/shadow, avoiding a second raw background sample.
        if (surface.stroke) {
            CouiOplus.setEdgeParams(
                this,
                type = 1,
                width = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 2.2f, resources.displayMetrics,
                ),
                alpha = if (isNight) 0.6f else 0.2f,
                angle = 0f,
            )
            CouiOplus.setMaterialShadowParams(
                this,
                type = 1,
                fadeScale = if (isNight) 1.0f else 1.15f,
                fadeIn = if (isNight) 0.01f else 0.1f,
                fadeOut = 1.0f,
            )
        }
        if (surface.blur) CouiOplus.setCornerParams(this, height / 2f)
    }

    fun updateDropShadow(progress: Float) {
        if (progress == shadowProgress) return
        shadowProgress = progress
        applyDropShadow(progress)
    }

    private fun applyDropShadow(progress: Float) {
        val isNight = night ?: return
        val metrics = resources.displayMetrics
        if (surface.caustic) {
            // setCausticShadow(view, p).
            if (surface.elevation) {
                // ShadowUtils level 6: coui_shadow_*_caustic_upper.
                setElevationFromOplusView(
                    elevation = dimensionPixelSize(16f, metrics) * progress,
                    alpha = 30,
                    lightY = dimensionPixelSize(-160f, metrics),
                    lightZ = dimensionPixelSize(0f, metrics),
                    lightR = dimensionPixelSize(800f, metrics),
                    blurR = dimensionPixelSize(0f, metrics),
                )
            }
            if (CouiOplus.checkSubSdk(40, 12)) {
                // coui_caustic_color_1: #2EFFFFFF, night #33FFFFFF; alpha scaled by p.
                val caustic = if (isNight) 0x33FFFFFF else 0x2EFFFFFF
                val a = (android.graphics.Color.alpha(caustic) * progress).toInt()
                CouiOplus.setOutlineCausticShadowColor(this, android.graphics.Color.argb(a, 255, 255, 255))
                // coui_caustic_layout_{l,t,r,b}_level2 = 0, 0, 0, 4.9dp (getDimensionPixelOffset).
                CouiOplus.setOutlineCausticShadowLayout(this, 0, 0, 0, (4.9f * metrics.density).toInt())
                // coui_caustic_layout_sdf_level2 = 9.23dp; the other arguments are the defaults
                // of setOutlineCausticShadowParams (0.6, 0.6, 18, 0.45, 0.6).
                CouiOplus.setOutlineCausticShadowParams(
                    this, (9.23f * metrics.density).toInt().toFloat(), 0.6f, 0.6f, 18f, 0.45f, 0.6f,
                )
            }
        } else {
            if (CouiOplus.checkSubSdk(40, 12)) CouiOplus.setOutlineCausticShadowColor(this, 0)
            if (surface.elevation) {
                // ShadowUtils level 3a.
                setElevationFromOplusView(
                    elevation = dimensionPixelSize(100f, metrics) * progress,
                    alpha = if (isNight) 68 else 34,
                    lightY = dimensionPixelSize(if (isNight) -240f else -200f, metrics),
                    lightZ = dimensionPixelSize(10000f, metrics),
                    lightR = dimensionPixelSize(2000f, metrics),
                    blurR = dimensionPixelSize(0f, metrics),
                )
            }
        }
    }

    /** ShadowUtils.setElevationToViewFromOPlusView. */
    private fun setElevationFromOplusView(elevation: Float, alpha: Int, lightY: Int, lightZ: Int, lightR: Int, blurR: Int) {
        val color = android.graphics.Color.argb(alpha, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = color
            outlineSpotShadowColor = color
        }
        this.elevation = elevation
        CouiOplus.setOverrideLightSourceGeometry(
            this, -1f, lightY.toFloat(), lightZ.toFloat(), lightR.toFloat(), blurR.toFloat(),
        )
    }

    /** TypedValue.complexToDimensionPixelSize for a dp value. */
    private fun dimensionPixelSize(dp: Float, metrics: android.util.DisplayMetrics): Int {
        val f = dp * metrics.density
        val res = if (f >= 0f) (f + 0.5f).toInt() else (f - 0.5f).toInt()
        if (res != 0) return res
        if (dp == 0f) return 0
        return if (dp > 0f) 1 else -1
    }
}

/** Reflection bridge to the app-visible (whitelisted) oplus framework APIs that COUI calls. */
private object CouiOplus {
    private fun cls(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
    private val float = Float::class.javaPrimitiveType!!
    private val int = Int::class.javaPrimitiveType!!
    private val view = android.view.View::class.java

    val present: Boolean by lazy { cls("com.oplus.os.OplusBuild") != null }

    /** OplusBuild.VERSION.SDK_VERSION / SDK_SUB_VERSION. */
    val sdk: Pair<Int, Int> by lazy {
        val version = cls("com.oplus.os.OplusBuild\$VERSION")
        val major = runCatching { version!!.getField("SDK_VERSION").getInt(null) }.getOrDefault(0)
        val sub = runCatching { version!!.getField("SDK_SUB_VERSION").getInt(null) }.getOrDefault(0)
        major to sub
    }

    /** COUIVersionUtil.checkOPlusViewSubSDK. */
    fun checkSubSdk(major: Int, sub: Int): Boolean {
        val (version, subVersion) = sdk
        return version > major || (version == major && subVersion >= sub)
    }

    fun systemPropInt(key: String, default: Int): Int = runCatching {
        val value = cls("android.os.SystemProperties")!!.getMethod("get", String::class.java)
            .invoke(null, key) as String
        if (value.isEmpty()) default else value.trim().toInt()
    }.getOrDefault(default)

    /** AppFeatureProviderUtils.getBoolean(resolver, name, false). */
    fun appFeatureBoolean(resolver: android.content.ContentResolver, name: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(
            "content://com.oplus.customize.coreapp.configmanager.configprovider.AppFeatureProvider/app_feature",
        )
        resolver.query(uri, null, "featurename=?", arrayOf(name), null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val value = cursor.getString(cursor.getColumnIndexOrThrow("parameters")) ?: return@use false
            value.substringAfter("boolean:", "").trim().equals("true", ignoreCase = true)
        } ?: false
    }.getOrDefault(false)

    private val oplusView by lazy { cls("com.oplus.view.OplusView") }
    private val oplusViewCtor by lazy { runCatching { oplusView!!.getConstructor(view) }.getOrNull() }
    private val overrideLight by lazy {
        runCatching {
            oplusView!!.getMethod("setOverrideLightSourceGeometry", float, float, float, float, float)
        }.getOrNull()
    }
    private val shadowClipping by lazy {
        runCatching {
            oplusView!!.getMethod("setShadowClippingEnabled", view, Boolean::class.javaPrimitiveType)
        }.getOrNull()
    }
    private val renderEffect by lazy {
        runCatching {
            cls("com.oplus.view.OplusViewBackgroundRenderEffect")!!
                .getMethod("setBackgroundRenderEffect", android.graphics.RenderEffect::class.java, view)
        }.getOrNull()
    }
    private val materialUtil by lazy { cls("com.oplus.view.material.OplusMaterialUtil") }
    private val edgeParams by lazy { cls("com.oplus.view.material.OplusMaterialEdgeParams") }
    private val shadowParams by lazy { cls("com.oplus.view.material.OplusMaterialShadowParams") }
    private val cornerParams by lazy { cls("com.oplus.view.material.OplusMaterialCornerParams") }
    private val pathAdapter by lazy { cls("com.oplus.graphics.OplusPathAdapter") }

    fun setOverrideLightSourceGeometry(target: android.view.View, x: Float, y: Float, z: Float, r: Float, blur: Float) {
        runCatching { overrideLight!!.invoke(oplusViewCtor!!.newInstance(target), x, y, z, r, blur) }
    }

    fun setShadowClippingEnabled(target: android.view.View, enabled: Boolean) {
        runCatching { shadowClipping!!.invoke(null, target, enabled) }
    }

    fun setBackgroundRenderEffect(effect: Any?, target: android.view.View) {
        runCatching { renderEffect!!.invoke(null, effect, target) }
    }

    fun setEdgeParams(target: android.view.View, type: Int, width: Float, alpha: Float, angle: Float) {
        runCatching {
            val params = edgeParams!!.getConstructor(int, float, float, float).newInstance(type, width, alpha, angle)
            materialUtil!!.getMethod("setEdgeParams", view, edgeParams).invoke(null, target, params)
        }
    }

    fun setMaterialShadowParams(target: android.view.View, type: Int, fadeScale: Float, fadeIn: Float, fadeOut: Float) {
        runCatching {
            val params = shadowParams!!.getConstructor(int, float, float, float)
                .newInstance(type, fadeScale, fadeIn, fadeOut)
            materialUtil!!.getMethod("setShadowParams", view, shadowParams).invoke(null, target, params)
        }
    }

    fun setCornerParams(target: android.view.View, radius: Float) {
        runCatching {
            val params = cornerParams!!.getConstructor(float, float).newInstance(60f, 1f)
            cornerParams!!.getMethod("setRadius", float).invoke(params, radius)
            materialUtil!!.getMethod("setCornerParams", view, cornerParams).invoke(null, target, params)
        }
    }

    fun setOutlineCausticShadowColor(target: android.view.View, color: Int) {
        runCatching {
            materialUtil!!.getMethod("setOutlineCausticShadowColor", view, int).invoke(null, target, color)
        }
    }

    fun setOutlineCausticShadowLayout(target: android.view.View, l: Int, t: Int, r: Int, b: Int) {
        runCatching {
            materialUtil!!.getMethod("setOutlineCausticShadowLayout", view, int, int, int, int)
                .invoke(null, target, l, t, r, b)
        }
    }

    fun setOutlineCausticShadowParams(target: android.view.View, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        runCatching {
            materialUtil!!.getMethod("setOutlineCausticShadowParams", view, float, float, float, float, float, float)
                .invoke(null, target, a, b, c, d, e, f)
        }
    }

    /** new OplusPathAdapter(path, 1).addSmoothRoundRect(rect, r, r, 1f, CCW). */
    fun addSmoothRoundRect(path: android.graphics.Path, rect: android.graphics.RectF, radius: Float): Boolean = runCatching {
        val adapter = pathAdapter!!.getConstructor(android.graphics.Path::class.java, int).newInstance(path, 1)
        pathAdapter!!.getMethod(
            "addSmoothRoundRect", android.graphics.RectF::class.java, float, float, float,
            android.graphics.Path.Direction::class.java,
        ).invoke(adapter, rect, radius, radius, 1f, android.graphics.Path.Direction.CCW)
        true
    }.getOrDefault(false)
}

private data class SettingsRootSection(
    val title: String,
    val items: List<SettingsRootItem>
)

private data class SettingsRootItem(
    val title: String,
    val summary: String,
    val keywords: String,
    val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
    val tone: SettingsRootIconTone,
    val onClick: () -> Unit
)

private enum class SettingsRootIconTone(
    val light: Color,
    val dark: Color,
) {
    Audio(Color(0xFF4F6FD6), Color(0xFF93A8F5)),
    Transcode(Color(0xFF2D7F9D), Color(0xFF78BED6)),
    Transition(Color(0xFFB15B5B), Color(0xFFE09A96)),
    Shuffle(Color(0xFF7562C8), Color(0xFFB1A2EC)),
    Effects(Color(0xFFB06F2E), Color(0xFFE6AC72)),
    Ai(Color(0xFF9A5DB8), Color(0xFFCF98E6)),
    Usb(Color(0xFF277F92), Color(0xFF74C2CF)),
    Hardware(Color(0xFF397E74), Color(0xFF7CC2B8)),
    Appearance(Color(0xFFB36A3C), Color(0xFFE5A477)),
    Player(Color(0xFF586AC0), Color(0xFF9DA9EA)),
    AlbumArt(Color(0xFFB65E7D), Color(0xFFE29AB2)),
    Font(Color(0xFF8A6A3E), Color(0xFFC7A573)),
    Personalization(Color(0xFF8D5EB0), Color(0xFFC6A0DE)),
    Lyrics(Color(0xFFB4546F), Color(0xFFE29AAF)),
    Scan(Color(0xFF3F8363), Color(0xFF7DC39D)),
    Backup(Color(0xFF3D759F), Color(0xFF7EB4D8)),
    Log(Color(0xFF64758C), Color(0xFFA7B5C7)),
    About(Color(0xFF6E7180), Color(0xFFB3B5C0));

    val destination: Class<*> get() = when (this) {
        Audio -> AudioSettingsActivity::class.java
        Transcode -> com.rawsmusic.ui.transcode.AudioTranscodeActivity::class.java
        Transition -> TransitionSettingsActivity::class.java
        Shuffle -> ShuffleSettingsActivity::class.java
        Effects -> AudioEffectsActivity::class.java
        Ai -> AiSeparationActivity::class.java
        Usb -> UsbDacSettingsActivity::class.java
        Hardware -> HardwareDeviceControlActivity::class.java
        Appearance -> AppearanceActivity::class.java
        Player -> PlayerInterfaceActivity::class.java
        AlbumArt -> AlbumArtActivity::class.java
        Font -> GlobalFontSettingsActivity::class.java
        Personalization -> PersonalizationSettingsActivity::class.java
        Lyrics -> LyricSettingsActivity::class.java
        Scan -> ScanSettingsActivity::class.java
        Backup -> WebDavBackupActivity::class.java
        Log -> LogViewerActivity::class.java
        About -> AboutActivity::class.java
    }

    fun resolve(isDark: Boolean): Color = if (isDark) dark else light
}

@Composable
private fun SettingsRootEntry(
    item: SettingsRootItem,
    isDark: Boolean,
    summaryOverride: String? = null,
    onClick: () -> Unit = item.onClick,
) {
    ArrowPreference(
        title = item.title,
        summary = summaryOverride ?: item.summary,
        onClick = onClick,
        startAction = {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                val iconVector = item.iconVector
                if (iconVector != null) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = item.tone.resolve(isDark),
                        modifier = Modifier.size(25.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(requireNotNull(item.iconRes)),
                        contentDescription = null,
                        tint = item.tone.resolve(isDark),
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        },
    )
}

private data class SettingsSearchItem(
    val sectionTitle: String,
    val parentTitle: String?,
    val title: String,
    val summary: String,
    val keywords: String,
    val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
    val tone: SettingsRootIconTone,
    val depth: Int,
    val destination: String,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsSearchResultEntry(
    result: SettingsSearchItem,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val location = buildString {
        append(result.sectionTitle)
        result.parentTitle?.takeIf { it != result.title }?.let {
            append(" · ")
            append(it)
        }
    }
    ArrowPreference(
        title = result.title,
        summary = buildString {
            append(location)
            if (result.summary.isNotBlank()) {
                append(" · ")
                append(result.summary)
            }
        },
        onClick = onClick,
        startAction = {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                val vector = result.iconVector
                if (vector != null) {
                    Icon(
                        imageVector = vector,
                        contentDescription = null,
                        tint = result.tone.resolve(isDark),
                        modifier = Modifier.size(25.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(requireNotNull(result.iconRes)),
                        contentDescription = null,
                        tint = result.tone.resolve(isDark),
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun MainSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    SmallTitle(text = title)
    SettingsCardGroup { Column(content = content) }
}

@Composable
private fun Modifier.settingsCardMaterial(
    cardColor: Color,
    isDark: Boolean,
): Modifier {
    val surfaceStyle by PersonalizationPreferences.settingsSurfaceStyle.collectAsState()
    val backdrop = LocalSettingsBackdrop.current
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val edgeColor = scheme.onBackground.copy(alpha = if (isDark) 0.12f else 0.10f)

    return when {
        surfaceStyle == SettingsSurfaceStyle.SOLID || backdrop == null || !isBackdropSupported ->
            this
                .clip(shape)
                .background(cardColor)

        surfaceStyle == SettingsSurfaceStyle.ACRYLIC ->
            this
                .shadow(
                    elevation = if (isDark) 5.dp else 7.dp,
                    shape = shape,
                    ambientColor = scheme.onBackground.copy(alpha = if (isDark) 0.16f else 0.11f),
                    spotColor = scheme.onBackground.copy(alpha = if (isDark) 0.12f else 0.09f),
                )
                .clip(shape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy(0.55f * glassSettings.vibrancyStrength)
                        blur((glassSettings.blurRadiusDp * 1.35f).dp.toPx())
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = (0.12f * glassSettings.highlightStrength).coerceIn(0f, 0.35f)
                        )
                    },
                    shadow = {
                        Shadow(
                            color = scheme.onBackground.copy(
                                alpha = ((if (isDark) 0.16f else 0.11f) * glassSettings.shadowStrength)
                                    .coerceIn(0f, 0.32f)
                            )
                        )
                    },
                    onDrawSurface = {
                        drawRect(
                            scheme.surfaceContainer.copy(alpha = if (isDark) 0.66f else 0.80f)
                        )
                        drawRect(scheme.primary.copy(alpha = if (isDark) 0.035f else 0.022f))
                    },
                )
                .border(0.75.dp, edgeColor, shape)

        else ->
            this
                .shadow(
                    elevation = if (isDark) 4.dp else 6.dp,
                    shape = shape,
                    ambientColor = scheme.onBackground.copy(alpha = if (isDark) 0.14f else 0.09f),
                    spotColor = scheme.onBackground.copy(alpha = if (isDark) 0.10f else 0.075f),
                )
                .clip(shape)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        val minDimension = size.minDimension
                        val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f).coerceAtMost(8f)
                        vibrancy(1.15f * glassSettings.vibrancyStrength)
                        blur(liquidBlurDp.dp.toPx())
                        lens(
                            refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.30f,
                            refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.15f,
                            depthEffect = true,
                            chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = (0.25f * glassSettings.highlightStrength).coerceIn(0f, 0.75f)
                        )
                    },
                    shadow = {
                        Shadow(
                            color = scheme.onBackground.copy(
                                alpha = ((if (isDark) 0.15f else 0.10f) * glassSettings.shadowStrength)
                                    .coerceIn(0f, 0.32f)
                            )
                        )
                    },
                    onDrawSurface = {
                        drawRect(
                            scheme.surfaceContainer.copy(alpha = if (isDark) 0.10f else 0.14f)
                        )
                        drawRect(scheme.primary.copy(alpha = if (isDark) 0.018f else 0.012f))
                    },
                )
                .border(0.75.dp, edgeColor, shape)
    }
}

@Composable
internal fun SettingsCardGroup(
    content: @Composable () -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White

    Column(
        modifier = Modifier
            .settingsCardMaterial(cardColor = cardColor, isDark = isDark)
            .padding(vertical = 4.dp)
    ) {
        content()
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    MainSettingsSection(title = title, content = content)
}

@Composable
internal fun SettingsNavigationEntry(
    title: String,
    description: String,
    iconRes: Int? = null,
    onClick: () -> Unit
) {
    ArrowPreference(
        title = title,
        summary = description,
        onClick = onClick,
        startAction = iconRes?.let { resId ->
            { SettingsSubpageEntryIcon(resId) }
        }
    )
}

@Composable
private fun SettingsSubpageEntryIcon(iconRes: Int) {
    Box(
        modifier = Modifier.size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(23.dp),
        )
    }
}

private fun matchesSettingsSearchQuery(
    query: String,
    item: SettingsSearchItem,
): Boolean {
    return SettingsSearchText("${item.sectionTitle} ${item.parentTitle.orEmpty()}",
        item.title, item.summary, item.keywords).matches(query)
}

private fun settingsSearchScore(query: String, item: SettingsSearchItem): Int {
    return SettingsSearchText("${item.sectionTitle} ${item.parentTitle.orEmpty()}",
        item.title, item.summary, item.keywords).score(query)
}

@Composable
internal fun SettingsInfoEntry(
    title: String,
    description: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = MiuixTheme.colorScheme.onBackground
        )
        Text(
            description,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
internal fun SectionHeader(title: String) {
    SmallTitle(text = title)
}

@Composable
internal fun Divider() {
}

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    Column(
        modifier
            .fillMaxWidth()
            .settingsCardMaterial(cardColor = cardColor, isDark = isDark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content
    )
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
internal fun ExpandableEffectContent(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(enabled) }
    var previousEnabled by rememberSaveable { mutableStateOf(enabled) }

    LaunchedEffect(enabled) {
        if (enabled && !previousEnabled) {
            expanded = true
        } else if (!enabled) {
            expanded = false
        }
        previousEnabled = enabled
    }

    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = content
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = stringResource(
                        if (expanded) R.string.settings_effect_card_collapse
                        else R.string.settings_effect_card_expand
                    ),
                    onClick = { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchPreference(
        title = label,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange
    )
}

// ==================== 液态玻璃组件（保留供后续扩展） ====================

private val isBackdropSupported: Boolean by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        android.util.Log.d("BackdropCompat", "Not supported: API ${Build.VERSION.SDK_INT} < S")
        return@lazy false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            android.graphics.RuntimeShader("half4 main(float2 c) { return half4(1.0); }")
            android.util.Log.d("BackdropCompat", "Supported: RuntimeShader test passed on API ${Build.VERSION.SDK_INT}")
            true
        } catch (e: Throwable) {
            android.util.Log.e("BackdropCompat", "Not supported: RuntimeShader test failed", e)
            false
        }
    } else {
        android.util.Log.d("BackdropCompat", "Supported: API ${Build.VERSION.SDK_INT} between S and TIRAMISU")
        true
    }
}

private const val USE_FALLBACK_CARDS = false

@Composable
fun LiquidGlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    lightweight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (USE_FALLBACK_CARDS || !isBackdropSupported) {
        LiquidGlassCardFallback(modifier, content)
        return
    }

    if (lightweight) {
        LiquidGlassCardLightweight(backdrop, modifier, content)
        return
    }

    var isPressed by remember { mutableStateOf(false) }
    var pressOffsetX by remember { mutableStateOf(0f) }
    var pressOffsetY by remember { mutableStateOf(0f) }

    val rotationX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) -pressOffsetY * 6f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "rotationX"
    )
    val rotationY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) pressOffsetX * 6f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "rotationY"
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "scale"
    )
    val context = LocalContext.current
    val cameraDistance by remember { mutableStateOf(12f * context.resources.displayMetrics.density) }
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()

    Column(
        modifier
            .graphicsLayer {
                this.cameraDistance = cameraDistance
                scaleX = scale
                scaleY = scale
                this.rotationX = rotationX
                this.rotationY = rotationY
            }
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        pressOffsetX = (offset.x - centerX) / centerX
                        pressOffsetY = (offset.y - centerY) / centerY
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
                effects = {
                    val minDimension = size.minDimension
                    vibrancy(1.5f * glassSettings.vibrancyStrength)
                    blur(glassSettings.blurRadiusDp.dp.toPx())
                    lens(
                        refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.5f,
                        refractionAmount = glassSettings.refractionAmountFraction * minDimension,
                        depthEffect = true,
                        chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                    )
                },
                highlight = { Highlight.Default.copy(alpha = (0.30f * glassSettings.highlightStrength).coerceIn(0f, 1f)) },
                shadow = { Shadow(alpha = (0.10f * glassSettings.shadowStrength).coerceIn(0f, 1f)) }
            )
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun LiquidGlassCardLightweight(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(24.dp) },
                effects = {
                    vibrancy(1.2f * glassSettings.vibrancyStrength)
                    blur(glassSettings.blurRadiusDp.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = (0.26f * glassSettings.highlightStrength).coerceIn(0f, 1f)) },
                shadow = { Shadow(alpha = (0.08f * glassSettings.shadowStrength).coerceIn(0f, 1f)) }
            )
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun LiquidGlassCardFallback(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF353130) else Color(0xFFE4E6F2)
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x1A000000))
            .clip(RoundedCornerShape(24.dp))
            .background(cardColor.copy(alpha = 0.85f))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
