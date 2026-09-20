package com.rawsmusic.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.PlayerHiResBadgeCorner
import com.rawsmusic.module.data.prefs.PlayerProgressPreferences
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.ui.AppNoticeBus
import androidx.core.content.ContextCompat
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkAnimationStyle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LiquidGlassPlayerInterfaceScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var defaultBackgroundEnabled by remember { mutableStateOf(AppPreferences.UI.isDefaultBackgroundEnabled) }
    var immersiveEnabled by remember { mutableStateOf(AppPreferences.UI.isImmersiveEnabled) }
    var audioVisualizerEnabled by remember { mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled) }
    var audioVisualizerHapticsEnabled by remember {
        mutableStateOf(AppPreferences.UI.audioVisualizerHapticsEnabled)
    }
    var audioVisualizerParticleColorMode by remember {
        mutableStateOf(AppPreferences.UI.audioVisualizerParticleColorMode)
    }
    var statusBarHidden by remember { mutableStateOf(AppPreferences.UI.isStatusBarHidden) }
    var miniCoverEnabled by remember { mutableStateOf(AppPreferences.UI.isMiniCoverEnabled) }
    var playPageMemoryEnabled by remember { mutableStateOf(AppPreferences.UI.isPlayPageMemoryEnabled) }
    var playerTitleAlignment by remember { mutableStateOf(AppPreferences.UI.playerTitleAlignment) }
    var miniLyricAlignment by remember { mutableStateOf(AppPreferences.UI.miniLyricAlignment) }
    val playerArtworkCornerRadiusDp by PersonalizationPreferences.playerArtworkCornerRadiusDp.collectAsState()
    val playerArtworkAnimationStyleValue by PersonalizationPreferences.playerArtworkAnimationStyle.collectAsState()
    val playerArtworkAnimationStyle = PlayerArtworkAnimationStyle.from(playerArtworkAnimationStyleValue)
    val hiResBadgeSettings by PersonalizationPreferences.playerHiResBadgeSettings.collectAsState()
    var waveformColorTarget by remember { mutableStateOf<WaveformColorTarget?>(null) }
    var waveformEditingColor by remember { mutableStateOf(Color.White) }
    val waveformBarCount by PlayerProgressPreferences.waveformBarCount.collectAsState()
    val waveformColorMode by PlayerProgressPreferences.waveformColorMode.collectAsState()
    val climaxEnabled by PlayerProgressPreferences.climaxEnabled.collectAsState()
    val waveformHoldWhenPaused by PlayerProgressPreferences.holdWhenPaused.collectAsState()
    val waveformRemainingColorInt by PlayerProgressPreferences.remainingColor.collectAsState()
    val waveformPlayedColorInt by PlayerProgressPreferences.playedColor.collectAsState()
    val waveformClimaxColorInt by PlayerProgressPreferences.climaxColor.collectAsState()
    fun persistAudioVisualizer(enabled: Boolean) {
        audioVisualizerEnabled = enabled
        AppPreferences.UI.isAudioVisualizerEnabled = enabled
        android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
            it.setPackage(context.packageName)
            context.sendBroadcast(it)
        }
    }
    fun persistAudioVisualizerHaptics(enabled: Boolean) {
        audioVisualizerHapticsEnabled = enabled
        AppPreferences.UI.audioVisualizerHapticsEnabled = enabled
        android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
            it.setPackage(context.packageName)
            context.sendBroadcast(it)
        }
    }
    fun persistAudioVisualizerParticleColorMode(mode: Int) {
        audioVisualizerParticleColorMode = mode.coerceIn(0, 1)
        AppPreferences.UI.audioVisualizerParticleColorMode = audioVisualizerParticleColorMode
        android.content.Intent("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED").also {
            it.setPackage(context.packageName)
            context.sendBroadcast(it)
        }
    }
    val alignmentItems = listOf(
        0 to R.string.desktop_lyric_left,
        1 to R.string.desktop_lyric_center,
        2 to R.string.desktop_lyric_right,
    )
    val playerTitleAlignmentEntry = DropdownEntry(
        items = alignmentItems.map { (value, labelRes) ->
            DropdownItem(
                text = stringResource(labelRes),
                selected = playerTitleAlignment == value,
                onClick = {
                    playerTitleAlignment = value
                    AppPreferences.UI.playerTitleAlignment = value
                }
            )
        }
    )
    val miniLyricAlignmentEntry = DropdownEntry(
        items = alignmentItems.map { (value, labelRes) ->
            DropdownItem(
                text = stringResource(labelRes),
                selected = miniLyricAlignment == value,
                onClick = {
                    miniLyricAlignment = value
                    AppPreferences.UI.miniLyricAlignment = value
                }
            )
        }
    )

    val artworkPerspectiveLabel = stringResource(R.string.settings_player_artwork_animation_perspective)
    val artworkCarouselLabel = stringResource(R.string.settings_player_artwork_animation_carousel)
    val artworkSlideLabel = stringResource(R.string.settings_player_artwork_animation_slide)
    val artworkAnimationEntry = DropdownEntry(
        items = listOf(
            PlayerArtworkAnimationStyle.PerspectiveDepth to artworkPerspectiveLabel,
            PlayerArtworkAnimationStyle.InwardCarousel to artworkCarouselLabel,
            PlayerArtworkAnimationStyle.Slide to artworkSlideLabel,
        ).map { (style, label) ->
            DropdownItem(
                text = label,
                selected = playerArtworkAnimationStyle == style,
                onClick = { PersonalizationPreferences.playerArtworkAnimationStyleValue = style.value },
            )
        },
    )
    val selectedArtworkAnimationLabel = when (playerArtworkAnimationStyle) {
        PlayerArtworkAnimationStyle.PerspectiveDepth -> artworkPerspectiveLabel
        PlayerArtworkAnimationStyle.InwardCarousel -> artworkCarouselLabel
        PlayerArtworkAnimationStyle.Slide -> artworkSlideLabel
    }

    val particleWhiteLabel =
        stringResource(R.string.settings_player_visualizer_particle_color_white)
    val particleRainbowLabel =
        stringResource(R.string.settings_player_visualizer_particle_color_rainbow)
    val particleColorEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = particleWhiteLabel,
                selected = audioVisualizerParticleColorMode == 0,
                onClick = { persistAudioVisualizerParticleColorMode(0) },
            ),
            DropdownItem(
                text = particleRainbowLabel,
                selected = audioVisualizerParticleColorMode == 1,
                onClick = { persistAudioVisualizerParticleColorMode(1) },
            ),
        ),
    )

    val hiResCornerLabels = mapOf(
        PlayerHiResBadgeCorner.TOP_LEFT to stringResource(R.string.settings_player_hires_badge_top_left),
        PlayerHiResBadgeCorner.TOP_RIGHT to stringResource(R.string.settings_player_hires_badge_top_right),
        PlayerHiResBadgeCorner.BOTTOM_LEFT to stringResource(R.string.settings_player_hires_badge_bottom_left),
        PlayerHiResBadgeCorner.BOTTOM_RIGHT to stringResource(R.string.settings_player_hires_badge_bottom_right),
    )
    val hiResCornerEntry = DropdownEntry(
        items = PlayerHiResBadgeCorner.entries.map { corner ->
            DropdownItem(
                text = hiResCornerLabels.getValue(corner),
                selected = hiResBadgeSettings.corner == corner,
                onClick = {
                    PersonalizationPreferences.updatePlayerHiResBadgeSettings { it.copy(corner = corner) }
                },
            )
        },
    )
    val hiResBadgeImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val importedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "player_hires_badge").apply { mkdirs() }
                    val destination = File(dir, "custom_badge_${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    destination.absolutePath
                }.getOrNull()
            }
            if (!importedPath.isNullOrBlank()) {
                PersonalizationPreferences.updatePlayerHiResBadgeSettings {
                    it.copy(customPath = importedPath)
                }
            } else {
                AppNoticeBus.error(
                    context.getString(R.string.settings_player_hires_badge_import_failed)
                )
            }
        }
    }

    val densityLabels = mapOf(
        100 to stringResource(R.string.settings_waveform_density_sparse),
        160 to stringResource(R.string.settings_waveform_density_near_dense),
        200 to stringResource(R.string.settings_waveform_density_dense),
        280 to stringResource(R.string.settings_waveform_density_crowded),
    )
    val waveformDensityEntry = DropdownEntry(
        items = PlayerProgressPreferences.supportedWaveformBarCounts.map { count ->
            DropdownItem(
                text = densityLabels[count] ?: count.toString(),
                selected = waveformBarCount == count,
                onClick = { PlayerProgressPreferences.waveformBarCountValue = count },
            )
        },
    )
    val miuixPaletteLabel = stringResource(R.string.settings_waveform_color_mode_miuix)
    val albumPaletteLabel = stringResource(R.string.settings_waveform_color_mode_album)
    val waveformColorModeEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = miuixPaletteLabel,
                selected = waveformColorMode == PlayerProgressPreferences.COLOR_MODE_MIUIX,
                onClick = { PlayerProgressPreferences.waveformColorModeValue = PlayerProgressPreferences.COLOR_MODE_MIUIX },
            ),
            DropdownItem(
                text = albumPaletteLabel,
                selected = waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART,
                onClick = { PlayerProgressPreferences.waveformColorModeValue = PlayerProgressPreferences.COLOR_MODE_ALBUM_ART },
            ),
        ),
    )

    val visualizerPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        persistAudioVisualizer(granted)
        if (!granted) {
            AppNoticeBus.error(context.getString(R.string.permission_denied))
        }
    }

    SettingsPage(title = stringResource(R.string.settings_player_interface_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_player_bg_section)) {
            SwitchRow(stringResource(R.string.settings_player_default_bg), defaultBackgroundEnabled) { checked ->
                defaultBackgroundEnabled = checked
                AppPreferences.UI.isDefaultBackgroundEnabled = checked
                android.content.Intent("com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_default_bg_rule_title),
                description = stringResource(R.string.settings_player_default_bg_rule_desc)
            )
        }

        SettingsSection(stringResource(R.string.settings_player_play_page_section)) {
            SwitchRow(stringResource(R.string.settings_player_immersive), immersiveEnabled) { checked ->
                immersiveEnabled = checked
                AppPreferences.UI.isImmersiveEnabled = checked
                android.content.Intent("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_immersive),
                description = stringResource(R.string.settings_player_immersive_desc)
            )
            PlayerArtworkCornerRadiusSlider(
                value = playerArtworkCornerRadiusDp,
                onValueChange = { PersonalizationPreferences.playerArtworkCornerRadiusDpValue = it },
            )
            RawWindowDropdownPreference(
                entry = artworkAnimationEntry,
                title = stringResource(R.string.settings_player_artwork_animation_title),
                summary = selectedArtworkAnimationLabel,
                showValue = true,
                maxHeight = 320.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_artwork_animation_title),
                description = stringResource(R.string.settings_player_artwork_animation_summary),
            )
            SwitchRow(
                stringResource(R.string.settings_player_hires_badge_title),
                hiResBadgeSettings.enabled,
            ) { checked ->
                PersonalizationPreferences.updatePlayerHiResBadgeSettings { it.copy(enabled = checked) }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_hires_badge_title),
                description = stringResource(R.string.settings_player_hires_badge_summary),
            )
            if (hiResBadgeSettings.enabled) {
                RawWindowDropdownPreference(
                    entry = hiResCornerEntry,
                    title = stringResource(R.string.settings_player_hires_badge_position),
                    summary = hiResCornerLabels.getValue(hiResBadgeSettings.corner),
                    showValue = true,
                    maxHeight = 260.dp,
                    collapseOnSelection = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { hiResBadgeImportLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(
                                if (hiResBadgeSettings.customPath.isBlank()) {
                                    R.string.settings_player_hires_badge_import
                                } else {
                                    R.string.settings_player_hires_badge_replace
                                }
                            )
                        )
                    }
                    if (hiResBadgeSettings.customPath.isNotBlank()) {
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Button(
                            onClick = {
                                PersonalizationPreferences.updatePlayerHiResBadgeSettings {
                                    it.copy(customPath = "")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.settings_player_hires_badge_builtin))
                        }
                    }
                }
            }
            SwitchRow(stringResource(R.string.settings_player_hide_status_bar), statusBarHidden) { checked ->
                statusBarHidden = checked
                AppPreferences.UI.isStatusBarHidden = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_hide_status_bar),
                description = stringResource(R.string.settings_player_hide_status_bar_desc)
            )
            SwitchRow(stringResource(R.string.settings_player_visualizer), audioVisualizerEnabled) { checked ->
                if (checked && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    visualizerPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    persistAudioVisualizer(checked)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_visualizer),
                description = stringResource(R.string.settings_player_visualizer_desc)
            )
            RawWindowDropdownPreference(
                entry = particleColorEntry,
                title = stringResource(R.string.settings_player_visualizer_particle_color),
                summary = if (audioVisualizerParticleColorMode == 1) {
                    particleRainbowLabel
                } else {
                    particleWhiteLabel
                },
                showValue = true,
                maxHeight = 220.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_visualizer_particle_color),
                description = stringResource(R.string.settings_player_visualizer_particle_color_desc),
            )
            SwitchRow(
                stringResource(R.string.settings_player_visualizer_haptics),
                audioVisualizerHapticsEnabled,
            ) { checked ->
                persistAudioVisualizerHaptics(checked)
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_visualizer_haptics),
                description = stringResource(R.string.settings_player_visualizer_haptics_desc),
            )
            RawWindowDropdownPreference(
                entry = playerTitleAlignmentEntry,
                title = stringResource(R.string.settings_player_title_alignment),
                summary = stringResource(
                    when (playerTitleAlignment) {
                        1 -> R.string.desktop_lyric_center
                        2 -> R.string.desktop_lyric_right
                        else -> R.string.desktop_lyric_left
                    }
                ),
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_title_alignment),
                description = stringResource(R.string.settings_player_title_alignment_desc)
            )
            RawWindowDropdownPreference(
                entry = miniLyricAlignmentEntry,
                title = stringResource(R.string.settings_mini_lyric_alignment),
                summary = stringResource(
                    when (miniLyricAlignment) {
                        1 -> R.string.desktop_lyric_center
                        2 -> R.string.desktop_lyric_right
                        else -> R.string.desktop_lyric_left
                    }
                ),
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_mini_lyric_alignment),
                description = stringResource(R.string.settings_mini_lyric_alignment_desc)
            )
        }

        SettingsSection(stringResource(R.string.settings_waveform_section)) {
            RawWindowDropdownPreference(
                entry = waveformDensityEntry,
                title = stringResource(R.string.settings_waveform_density_title),
                summary = densityLabels[waveformBarCount] ?: waveformBarCount.toString(),
                showValue = true,
                maxHeight = 300.dp,
                collapseOnSelection = true,
            )
            RawWindowDropdownPreference(
                entry = waveformColorModeEntry,
                title = stringResource(R.string.settings_waveform_color_mode_title),
                summary = if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_ALBUM_ART) albumPaletteLabel else miuixPaletteLabel,
                showValue = true,
                maxHeight = 260.dp,
                collapseOnSelection = true,
            )
            if (waveformColorMode == PlayerProgressPreferences.COLOR_MODE_MIUIX) {
                SettingsActionRow(
                    title = stringResource(R.string.immersive_waveform_remaining_color),
                    description = waveformColorSummary(Color(waveformRemainingColorInt)),
                    onClick = {
                        waveformColorTarget = WaveformColorTarget.Remaining
                        waveformEditingColor = Color(waveformRemainingColorInt)
                    },
                )
                SettingsActionRow(
                    title = stringResource(R.string.immersive_waveform_played_color),
                    description = waveformColorSummary(Color(waveformPlayedColorInt)),
                    onClick = {
                        waveformColorTarget = WaveformColorTarget.Played
                        waveformEditingColor = Color(waveformPlayedColorInt)
                    },
                )
            }
            SwitchRow(stringResource(R.string.immersive_climax_point), climaxEnabled) { checked ->
                PlayerProgressPreferences.climaxEnabledValue = checked
            }
            SwitchRow(
                stringResource(R.string.settings_waveform_hold_when_paused_title),
                waveformHoldWhenPaused,
            ) { checked ->
                PlayerProgressPreferences.holdWhenPausedValue = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_waveform_hold_when_paused_title),
                description = stringResource(R.string.settings_waveform_hold_when_paused_desc),
            )
            SettingsActionRow(
                title = stringResource(R.string.immersive_waveform_climax_color),
                description = waveformColorSummary(Color(waveformClimaxColorInt)),
                onClick = {
                    waveformColorTarget = WaveformColorTarget.Climax
                    waveformEditingColor = Color(waveformClimaxColorInt)
                },
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_waveform_climax_pending_title),
                description = stringResource(R.string.settings_waveform_climax_pending_summary),
            )
        }

        AudioInfoCapsuleSettingsSection()

        SettingsSection(stringResource(R.string.settings_player_main_section)) {
            SwitchRow(stringResource(R.string.settings_player_mini_cover), miniCoverEnabled) { checked ->
                miniCoverEnabled = checked
                AppPreferences.UI.isMiniCoverEnabled = checked
                android.content.Intent("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED").also {
                    it.setPackage(context.packageName)
                    context.sendBroadcast(it)
                }
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_mini_cover),
                description = stringResource(R.string.settings_player_mini_cover_desc)
            )
            SwitchRow(stringResource(R.string.settings_player_page_memory), playPageMemoryEnabled) { checked ->
                playPageMemoryEnabled = checked
                AppPreferences.UI.isPlayPageMemoryEnabled = checked
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_player_page_memory),
                description = stringResource(R.string.settings_player_page_memory_desc)
            )
        }
    }

    val activeColorTarget = waveformColorTarget
    if (activeColorTarget != null) {
        val pickerTitle = when (activeColorTarget) {
            WaveformColorTarget.Remaining -> stringResource(R.string.immersive_waveform_remaining_color)
            WaveformColorTarget.Played -> stringResource(R.string.immersive_waveform_played_color)
            WaveformColorTarget.Climax -> stringResource(R.string.immersive_waveform_climax_color)
        }
        Dialog(onDismissRequest = { waveformColorTarget = null }) {
            SettingsCard {
                Text(text = pickerTitle)
                Spacer(modifier = Modifier.height(12.dp))
                ColorPicker(
                    color = waveformEditingColor,
                    onColorChanged = { waveformEditingColor = it },
                    colorSpace = ColorSpace.HSV,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        val argb = waveformEditingColor.toArgb()
                        when (activeColorTarget) {
                            WaveformColorTarget.Remaining -> PlayerProgressPreferences.remainingColorValue = argb
                            WaveformColorTarget.Played -> PlayerProgressPreferences.playedColorValue = argb
                            WaveformColorTarget.Climax -> PlayerProgressPreferences.climaxColorValue = argb
                        }
                        waveformColorTarget = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            }
        }
    }
}

private enum class WaveformColorTarget {
    Remaining,
    Played,
    Climax,
}

private fun waveformColorSummary(color: Color): String =
    "#%08X".format(color.toArgb())

@Composable
private fun PlayerArtworkCornerRadiusSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_player_artwork_corner_radius),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${value.toInt()}dp",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = value.coerceIn(0f, 48f),
            onValueChange = onValueChange,
            valueRange = 0f..48f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.settings_player_artwork_corner_radius_desc),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
