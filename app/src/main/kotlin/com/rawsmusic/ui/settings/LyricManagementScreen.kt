package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricColorPreferences
import com.rawsmusic.module.data.prefs.LyricColorPreset
import com.rawsmusic.module.data.prefs.LyricColorSource
import com.rawsmusic.module.data.prefs.LyricLayoutPreferences
import com.rawsmusic.module.data.prefs.LyricTopLayoutStyle
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.helper.LyricoIntegration
import com.rawsmusic.lyrico.InstalledLyricoPlugin
import com.rawsmusic.lyrico.LyricoPluginStore
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import android.widget.Toast
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference

@Composable
fun LiquidGlassLyricManagementScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lyricoPluginStore = remember(context) { LyricoPluginStore.get(context) }
    val lyriconPrefs = AppPreferences.Lyricon
    var lyriconEnabled by remember { mutableStateOf(lyriconPrefs.enabled) }
    var lyriconTranslation by remember { mutableStateOf(lyriconPrefs.displayTranslation) }
    var lyriconRoma by remember { mutableStateOf(lyriconPrefs.displayRoma) }
    var lyriconOriginalColorCompat by remember {
        mutableStateOf(lyriconPrefs.originalTextColorCompatibility)
    }
    var karaokeGlowEnabled by remember { mutableStateOf(AppPreferences.UI.lyricKaraokeGlowEnabled) }
    var karaokeLiftEnabled by remember { mutableStateOf(AppPreferences.UI.lyricKaraokeLiftEnabled) }
    val lyricColorSettings by LyricColorPreferences.settings.collectAsState()
    var showLyricColorPicker by remember { mutableStateOf(false) }
    var lyricEditingColor by remember(lyricColorSettings.solidColorArgb) {
        mutableStateOf(Color(lyricColorSettings.solidColorArgb))
    }
    val lyricBottomPaddingDp by LyricLayoutPreferences.bottomPaddingDp.collectAsState()
    val lyricTopLayoutStyle by LyricLayoutPreferences.topLayoutStyle.collectAsState()
    var lyricoInstalled by remember { mutableStateOf(LyricoIntegration.isInstalled(context)) }
    var lyricoRuntimeReady by remember { mutableStateOf<Boolean?>(null) }
    var lyricoPlugins by remember { mutableStateOf<List<InstalledLyricoPlugin>>(emptyList()) }
    val pluginImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { lyricoPluginStore.import(uri) }
            if (result.isSuccess) {
                lyricoPlugins = lyricoPluginStore.listInstalled()
                val message = when {
                    result.failures.isNotEmpty() -> context.getString(
                        R.string.settings_lyrico_plugins_imported_partial,
                        result.plugins.size,
                        result.failures.size,
                        result.failures.joinToString("; ")
                    )
                    result.plugins.size == 1 -> context.getString(
                        R.string.settings_lyrico_plugin_imported,
                        result.plugins.single().manifest.name
                    )
                    else -> context.getString(
                        R.string.settings_lyrico_plugins_imported,
                        result.plugins.size
                    )
                }
                if (result.failures.isEmpty()) {
                    AppNoticeBus.post(
                        message = message,
                        icon = AppNoticeIcon.LYRICS,
                    )
                } else {
                    AppNoticeBus.error(message)
                }
            } else {
                AppNoticeBus.error(
                    context.getString(
                        R.string.settings_lyrico_plugin_import_failed,
                        result.error.orEmpty(),
                    )
                )
            }
        }
    }
    var lyriconStatus by remember {
        mutableStateOf(
            if (!lyriconEnabled) context.getString(R.string.settings_status_disabled)
            else if (LyriconProviderManager.isConnected()) context.getString(R.string.settings_status_connected)
            else context.getString(R.string.settings_status_disconnected)
        )
    }

    LaunchedEffect(lyriconEnabled) {
        if (lyriconEnabled) {
            LyriconProviderManager.onConnectionStatusChanged = { status ->
                lyriconStatus = when {
                    !AppPreferences.Lyricon.enabled -> context.getString(R.string.settings_status_disabled)
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTED -> context.getString(R.string.settings_status_connected)
                    status == io.github.proify.lyricon.provider.ConnectionStatus.CONNECTING -> context.getString(R.string.settings_status_connecting)
                    else -> context.getString(R.string.settings_status_disconnected)
                }
            }
        } else {
            LyriconProviderManager.onConnectionStatusChanged = null
        }
    }

    LaunchedEffect(Unit) {
        lyricoInstalled = LyricoIntegration.isInstalled(context)
        lyricoPlugins = withContext(Dispatchers.IO) { lyricoPluginStore.listInstalled() }
        lyricoRuntimeReady = withContext(Dispatchers.IO) { lyricoPluginStore.runtimeHealthCheck().isSuccess }
    }

    val lyricTopLayoutEntry = DropdownEntry(
        items = LyricTopLayoutStyle.entries.map { style ->
            DropdownItem(
                text = stringResource(
                    when (style) {
                        LyricTopLayoutStyle.Current -> R.string.settings_lyric_top_style_current
                        LyricTopLayoutStyle.ScrollWithLyrics -> R.string.settings_lyric_top_style_scroll
                        LyricTopLayoutStyle.TitleOnly -> R.string.settings_lyric_top_style_title_only
                    }
                ),
                summary = stringResource(
                    when (style) {
                        LyricTopLayoutStyle.Current -> R.string.settings_lyric_top_style_current_summary
                        LyricTopLayoutStyle.ScrollWithLyrics -> R.string.settings_lyric_top_style_scroll_summary
                        LyricTopLayoutStyle.TitleOnly -> R.string.settings_lyric_top_style_title_only_summary
                    }
                ),
                selected = lyricTopLayoutStyle == style,
                onClick = { LyricLayoutPreferences.lyricTopLayoutStyle = style }
            )
        }
    )

    SettingsPage(title = stringResource(R.string.settings_lyric_management_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_lyric_layout_section)) {
            RawWindowDropdownPreference(
                entry = lyricTopLayoutEntry,
                title = stringResource(R.string.settings_lyric_top_style_title),
                summary = stringResource(
                    when (lyricTopLayoutStyle) {
                        LyricTopLayoutStyle.Current -> R.string.settings_lyric_top_style_current
                        LyricTopLayoutStyle.ScrollWithLyrics -> R.string.settings_lyric_top_style_scroll
                        LyricTopLayoutStyle.TitleOnly -> R.string.settings_lyric_top_style_title_only
                    }
                ),
                showValue = true,
                maxHeight = 420.dp,
                collapseOnSelection = true,
            )
            SliderPreference(
                title = stringResource(R.string.settings_lyric_bottom_padding_title),
                summary = stringResource(R.string.settings_lyric_bottom_padding_summary),
                valueText = stringResource(
                    R.string.settings_lyric_bottom_padding_value,
                    lyricBottomPaddingDp,
                ),
                value = lyricBottomPaddingDp.toFloat(),
                onValueChange = { value ->
                    LyricLayoutPreferences.tailBottomPaddingDp = value.roundToInt()
                },
                valueRange = LyricLayoutPreferences.MIN_BOTTOM_PADDING_DP.toFloat()..
                    LyricLayoutPreferences.MAX_BOTTOM_PADDING_DP.toFloat(),
                steps = 17,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }

        SettingsSection(stringResource(R.string.settings_lyric_effects_section)) {
            SwitchRow(
                label = stringResource(R.string.settings_lyric_karaoke_glow),
                checked = karaokeGlowEnabled
            ) { enabled ->
                karaokeGlowEnabled = enabled
                AppPreferences.UI.lyricKaraokeGlowEnabled = enabled
            }
            SwitchRow(
                label = stringResource(R.string.settings_lyric_karaoke_lift),
                checked = karaokeLiftEnabled
            ) { enabled ->
                karaokeLiftEnabled = enabled
                AppPreferences.UI.lyricKaraokeLiftEnabled = enabled
            }
        }

        SettingsSection(stringResource(R.string.settings_lyric_color_section)) {
            SwitchRow(
                label = stringResource(R.string.settings_lyric_color_rainbow),
                checked = lyricColorSettings.source == LyricColorSource.RAINBOW,
            ) { enabled ->
                LyricColorPreferences.setRainbowEnabled(enabled)
            }
            SwitchRow(
                label = stringResource(R.string.settings_lyric_color_album_art),
                checked = lyricColorSettings.source == LyricColorSource.ALBUM_ART,
            ) { enabled ->
                LyricColorPreferences.setAlbumArtEnabled(enabled)
            }
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyric_color_presets),
                description = stringResource(R.string.settings_lyric_color_presets_summary),
            )
            LyricColorPresetGrid(
                selectedColor = lyricColorSettings.solidColorArgb.takeIf {
                    lyricColorSettings.source == LyricColorSource.SOLID
                },
                onPresetSelected = LyricColorPreferences::selectPreset,
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_color_custom),
                description = lyricColorHex(Color(lyricColorSettings.solidColorArgb)),
                onClick = {
                    lyricEditingColor = Color(lyricColorSettings.solidColorArgb)
                    showLyricColorPicker = true
                },
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyric_color_default),
                description = stringResource(R.string.settings_lyric_color_default_summary),
                onClick = LyricColorPreferences::resetToDefault,
            )
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyric_color_scope),
                description = stringResource(R.string.settings_lyric_color_scope_summary),
            )
            SwitchRow(
                label = stringResource(R.string.settings_lyric_color_scope_lyric_page),
                checked = lyricColorSettings.applyToLyricPage,
                onCheckedChange = LyricColorPreferences::setApplyToLyricPage,
            )
            SwitchRow(
                label = stringResource(R.string.settings_lyric_color_scope_mini_player),
                checked = lyricColorSettings.applyToMiniPlayer,
                onCheckedChange = LyricColorPreferences::setApplyToMiniPlayer,
            )
            SwitchRow(
                label = stringResource(R.string.settings_lyric_color_scope_home),
                checked = lyricColorSettings.applyToHome,
                onCheckedChange = LyricColorPreferences::setApplyToHome,
            )
        }

        SettingsSection(stringResource(R.string.settings_lyrico_sources_section)) {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyrico_runtime_title),
                description = stringResource(
                    when (lyricoRuntimeReady) {
                        true -> R.string.settings_lyrico_runtime_ready
                        false -> R.string.settings_lyrico_runtime_failed
                        null -> R.string.settings_lyrico_runtime_checking
                    }
                )
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyrico_import_plugin),
                description = stringResource(R.string.settings_lyrico_import_plugin_summary),
                onClick = { pluginImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
            )
            if (lyricoPlugins.isEmpty()) {
                SettingsInfoEntry(
                    title = stringResource(R.string.settings_lyrico_no_plugins),
                    description = stringResource(R.string.settings_lyrico_no_plugins_summary)
                )
            } else {
                lyricoPlugins.forEach { plugin ->
                    SwitchRow(
                        label = context.getString(
                            R.string.settings_lyrico_plugin_label,
                            plugin.manifest.name,
                            plugin.manifest.versionName
                        ),
                        checked = plugin.enabled,
                        enabled = lyricoRuntimeReady == true
                    ) { enabled ->
                        lyricoPluginStore.setEnabled(plugin.manifest.id, enabled)
                        lyricoPlugins = lyricoPlugins.map { item ->
                            if (item.manifest.id == plugin.manifest.id) item.copy(enabled = enabled) else item
                        }
                    }
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_lyrico_section)) {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyrico_title),
                description = stringResource(
                    if (lyricoInstalled) R.string.settings_lyrico_installed
                    else R.string.settings_lyrico_not_installed
                )
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.settings_lyrico_open),
                description = stringResource(R.string.settings_lyrico_open_summary),
                onClick = {
                    val intent = LyricoIntegration.buildLaunchIntent(context)
                        ?: LyricoIntegration.buildProjectIntent()
                    runCatching {
                        LyricoIntegration.traceIntent(context, intent, "settings_launch_attempt")
                        context.startActivity(intent)
                        Log.i(LyricoIntegration.LOG_TAG, "settings_launch_dispatched")
                    }.onFailure { error ->
                        LyricoIntegration.traceLaunchFailure("settings_launch", error)
                    }
                }
            )
        }

        SettingsSection(stringResource(R.string.settings_lyricon_section)) {
            SettingsInfoEntry(
                title = stringResource(R.string.settings_lyricon_status_title),
                description = stringResource(R.string.settings_lyricon_status_desc, lyriconStatus)
            )
            SwitchRow(stringResource(R.string.settings_lyricon_enable), lyriconEnabled) { checked ->
                lyriconEnabled = checked
                lyriconPrefs.enabled = checked
                if (checked) {
                    LyriconProviderManager.init(context.applicationContext, com.rawsmusic.R.mipmap.ic_launcher)
                    lyriconStatus = if (LyriconProviderManager.isConnected()) context.getString(R.string.settings_status_connected) else context.getString(R.string.settings_status_disconnected)
                } else {
                    LyriconProviderManager.stopPositionSync()
                    LyriconProviderManager.destroy()
                    lyriconStatus = context.getString(R.string.settings_status_disabled)
                }
            }
            SwitchRow(stringResource(R.string.settings_lyricon_show_translation), lyriconTranslation, enabled = lyriconEnabled) { checked ->
                lyriconTranslation = checked
                LyriconProviderManager.setDisplayTranslation(checked)
            }
            SwitchRow(stringResource(R.string.settings_lyricon_show_roma), lyriconRoma, enabled = lyriconEnabled) { checked ->
                lyriconRoma = checked
                LyriconProviderManager.setDisplayRoma(checked)
            }
            SwitchRow(
                stringResource(R.string.settings_lyricon_original_color_compat),
                lyriconOriginalColorCompat,
                enabled = lyriconEnabled
            ) { checked ->
                lyriconOriginalColorCompat = checked
                LyriconProviderManager.setOriginalTextColorCompatibility(checked)
            }
        }

    }

    if (showLyricColorPicker) {
        Dialog(onDismissRequest = { showLyricColorPicker = false }) {
            SettingsCard {
                Text(text = stringResource(R.string.settings_lyric_color_custom))
                Spacer(modifier = Modifier.height(12.dp))
                ColorPicker(
                    color = lyricEditingColor,
                    onColorChanged = { lyricEditingColor = it },
                    colorSpace = ColorSpace.HSV,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        LyricColorPreferences.setSolidColor(lyricEditingColor.toArgb())
                        showLyricColorPicker = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            }
        }
    }
}

@Composable
private fun LyricColorPresetGrid(
    selectedColor: Int?,
    onPresetSelected: (LyricColorPreset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LyricColorPreset.entries.chunked(4).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowPresets.forEach { preset ->
                    val selected = selectedColor == preset.argb
                    Box(
                        modifier = Modifier
                            .size(if (selected) 46.dp else 42.dp)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.outline.copy(alpha = 0.42f)
                                },
                                shape = RoundedCornerShape(50),
                            )
                            .padding(4.dp)
                            .background(
                                color = Color(preset.argb),
                                shape = RoundedCornerShape(50),
                            )
                            .clickable { onPresetSelected(preset) },
                    )
                }
            }
        }
    }
}

private fun lyricColorHex(color: Color): String =
    "#%08X".format(color.toArgb())
