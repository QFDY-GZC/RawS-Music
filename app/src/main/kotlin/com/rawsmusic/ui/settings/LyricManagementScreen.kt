package com.rawsmusic.ui.settings

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
import androidx.compose.ui.unit.dp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricLayoutPreferences
import com.rawsmusic.module.data.prefs.LyricTopLayoutStyle
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.helper.LyricoIntegration
import com.rawsmusic.lyrico.InstalledLyricoPlugin
import com.rawsmusic.lyrico.LyricoPluginStore
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
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

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
    var karaokeGlowEnabled by remember { mutableStateOf(AppPreferences.UI.lyricKaraokeGlowEnabled) }
    var karaokeLiftEnabled by remember { mutableStateOf(AppPreferences.UI.lyricKaraokeLiftEnabled) }
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
                Toast.makeText(
                    context,
                    message,
                    if (result.failures.isEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_lyrico_plugin_import_failed, result.error.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
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
            WindowDropdownPreference(
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
        }

    }
}
