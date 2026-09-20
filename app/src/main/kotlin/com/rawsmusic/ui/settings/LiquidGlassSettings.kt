package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
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
import com.rawsmusic.core.ui.widget.background.CustomMediaBackgroundState
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.SettingsSurfaceStyle
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import java.io.File

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
    return remember(path, runtimeVersion) {
        if (path.isBlank()) {
            FontFamily.Default
        } else {
            runCatching {
                // Keep the file registered as its source face. A LoadedFontFamily(Typeface)
                // bypasses normal weight matching on a number of Compose paths; a file-backed
                // Normal face lets the theme/requested FontWeight synthesize the actual weight.
                FontFamily(Font(File(path), FontWeight.Normal))
            }.getOrDefault(FontFamily.Default)
        }
    }
}

/**
 * 设置页面模板。
 * 使用 Miuix SmallTopAppBar。
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
    Column(
        Modifier
            .fillMaxSize()
            .background(resolvedPageBackground)
    ) {
        SmallTopAppBar(
            title = title,
            color = resolvedPageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {
                if (onBack != null) {
                    top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) {
                        top.yukonga.miuix.kmp.basic.Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.settings_back),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            content()
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
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
    onNavigateToScanSettings: () -> Unit = {}
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
    var searchQuery by remember { mutableStateOf("") }

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
                    title = stringResource(R.string.settings_global_font_title),
                    summary = stringResource(R.string.settings_global_font_summary),
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
                onClick = item.onClick,
            )
        }
    }
    val rootsByTone = rootItems.associateBy(SettingsSearchItem::tone)
    fun childSearchItem(
        tone: SettingsRootIconTone,
        title: String,
        summary: String? = null,
        keywords: String = "",
        onClick: (() -> Unit)? = null,
    ): SettingsSearchItem {
        val root = requireNotNull(rootsByTone[tone])
        return SettingsSearchItem(
            sectionTitle = root.sectionTitle,
            parentTitle = root.title,
            title = title,
            summary = summary ?: root.summary,
            keywords = keywords,
            iconRes = root.iconRes,
            iconVector = root.iconVector,
            tone = root.tone,
            depth = 1,
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
            onClick = { openChildSettings(AudioFocusSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Audio,
            stringResource(R.string.settings_audio_dvc),
            stringResource(R.string.settings_audio_dvc_desc),
            "DVC direct volume control 直接音量",
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
            onClick = { openChildSettings(BottomNavigationSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Personalization,
            stringResource(R.string.settings_mini_player_karaoke_effect),
            stringResource(R.string.settings_mini_player_karaoke_effect_desc),
            "mini player karaoke word lyrics 播放栏 逐字 歌词 特效",
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
            onClick = { openChildSettings(LyricManagementActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_lyric_color_section),
            stringResource(R.string.settings_lyric_color_scope_summary),
            "lyric color rainbow album artwork preset custom 歌词 颜色 彩虹 专辑图 预设 自定义",
            onClick = { openChildSettings(LyricManagementActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_lyric_font_title),
            stringResource(R.string.settings_lyric_font_summary),
            "lyric font 歌词字体 字重",
            onClick = { openChildSettings(LyricFontSettingsActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_status_bar_lyric_title),
            stringResource(R.string.settings_status_bar_lyric_summary),
            "status bar desktop lyric 状态栏 悬浮歌词",
            onClick = { openChildSettings(StatusBarLyricActivity::class.java) },
        ),
        childSearchItem(
            SettingsRootIconTone.Lyrics,
            stringResource(R.string.settings_system_lyric_delivery_title),
            stringResource(R.string.settings_system_lyric_delivery_summary),
            "system lyric delivery Flyme Xiaomi 三星 歌词输出",
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

    val searchItems = rootItems + childItems
    val filteredItems = if (searchQuery.isBlank()) {
        emptyList()
    } else {
        searchItems
            .filter { result -> matchesSettingsSearchQuery(searchQuery, result) }
            .sortedWith(
                compareBy<SettingsSearchItem> { settingsSearchScore(searchQuery, it) }
                    .thenByDescending(SettingsSearchItem::depth)
                    .thenBy(SettingsSearchItem::title)
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(resolvedPageBackground)
    ) {
        SmallTopAppBar(
            title = stringResource(R.string.settings_main_title),
            color = resolvedPageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {}
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.settings_search_hint)
            )
        }

        if (searchQuery.isNotBlank() && filteredItems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                SmallTitle(text = stringResource(R.string.settings_search_results))
                SettingsCardGroup {
                    Column {
                        filteredItems.forEach { result ->
                            SettingsSearchResultEntry(
                                result = result,
                                isDark = isDark,
                                onClick = {
                                    result.onClick()
                                    searchQuery = ""
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else if (searchQuery.isNotBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                sections.forEach { section ->
                    SmallTitle(text = section.title)
                    SettingsCardGroup {
                        Column {
                            section.items.forEach { item ->
                                SettingsRootEntry(item = item, isDark = isDark)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }
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
    val tokens = normalizeSettingsSearchText(query)
        .split(' ')
        .filter(String::isNotBlank)
    if (tokens.isEmpty()) return false

    val searchableText = normalizeSettingsSearchText(
        buildString {
            append(item.sectionTitle)
            append(' ')
            append(item.parentTitle.orEmpty())
            append(' ')
            append(item.title)
            append(' ')
            append(item.summary)
            append(' ')
            append(item.keywords)
        }
    )
    return tokens.all(searchableText::contains)
}

private fun settingsSearchScore(query: String, item: SettingsSearchItem): Int {
    val normalizedQuery = normalizeSettingsSearchText(query)
    val normalizedTitle = normalizeSettingsSearchText(item.title)
    val normalizedParent = normalizeSettingsSearchText(item.parentTitle.orEmpty())
    val normalizedKeywords = normalizeSettingsSearchText(item.keywords)
    return when {
        normalizedTitle == normalizedQuery -> 0
        normalizedTitle.startsWith(normalizedQuery) -> 1
        normalizedTitle.contains(normalizedQuery) -> 2
        normalizedKeywords.contains(normalizedQuery) -> 3
        normalizedParent.contains(normalizedQuery) -> 4
        else -> 5
    }
}

private fun normalizeSettingsSearchText(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[\\s,，。;；:/\\\\|·_\\-]+"), " ")
        .trim()
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
