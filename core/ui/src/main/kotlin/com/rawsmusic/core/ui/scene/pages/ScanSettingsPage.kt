package com.rawsmusic.core.ui.scene.pages

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.prefs.AppPreferences
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun ScanSettingsPage(
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onRequestLegacyAudioAccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = if (isDark) Color(0xFF101014) else Color(0xFFF4F4F7)

    val selectedFolderUris = remember {
        mutableStateListOf<String>().apply {
            addAll(AppPreferences.Scanner.musicFolderUris)
        }
    }

    var showLegacyConfirmDialog by remember { mutableStateOf(false) }

    var legacyFileAccessEnabled by remember {
        mutableStateOf(AppPreferences.Scanner.legacyFileAccessEnabled)
    }

    var minTrackDurationSeconds by remember {
        mutableStateOf(AppPreferences.Scanner.minTrackDurationSeconds.coerceIn(0, 60))
    }

    var ignoreVideoFormats by remember {
        mutableStateOf(AppPreferences.Scanner.ignoreVideoFormats)
    }

    // 生命周期监听：从系统权限页返回时同步状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val granted = Environment.isExternalStorageManager()
                    legacyFileAccessEnabled = granted
                    AppPreferences.Scanner.legacyFileAccessEnabled = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)

        if (!persisted) {
            Toast.makeText(context, context.getString(R.string.scan_settings_folder_permission_failed), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        AppPreferences.Scanner.addMusicFolderUri(uri.toString())
        selectedFolderUris.clear()
        selectedFolderUris.addAll(AppPreferences.Scanner.musicFolderUris)
        Toast.makeText(context, context.getString(R.string.scan_settings_folder_added), Toast.LENGTH_SHORT).show()
        onRescan()
    }

    OverlayDialog(
        show = showLegacyConfirmDialog,
        title = stringResource(R.string.scan_settings_legacy_access_dialog_title),
        summary = stringResource(R.string.scan_settings_legacy_access_dialog_text),
        onDismissRequest = { showLegacyConfirmDialog = false }
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.scan_settings_action_cancel),
                onClick = { showLegacyConfirmDialog = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            TextButton(
                text = stringResource(R.string.scan_settings_action_authorize),
                onClick = {
                    showLegacyConfirmDialog = false
                    onRequestLegacyAudioAccess()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = stringResource(R.string.scan_settings_title),
            color = pageBackground,
            titleColor = MiuixTheme.colorScheme.onBackground,
            navigationIcon = {}
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 音乐库 ──
            SmallTitle(text = stringResource(R.string.scan_settings_library_section))
            CardGroup {
                ArrowPreference(
                    title = stringResource(R.string.scan_settings_rescan_title),
                    summary = stringResource(R.string.scan_settings_rescan_summary),
                    onClick = onRescan
                )
                ArrowPreference(
                    title = stringResource(R.string.scan_settings_choose_folder_title),
                    summary = stringResource(R.string.scan_settings_choose_folder_summary),
                    onClick = { folderPicker.launch(null) }
                )
            }

            // ── 已选择文件夹 ──
            if (selectedFolderUris.isNotEmpty()) {
                SmallTitle(text = stringResource(R.string.scan_settings_folder_scan_section))
                CardGroup {
                    selectedFolderUris.forEachIndexed { index, rawUri ->
                        FolderRow(
                            rawUri = rawUri,
                            onScan = onRescan,
                            onRemove = {
                                AppPreferences.Scanner.removeMusicFolderUri(rawUri)
                                selectedFolderUris.clear()
                                selectedFolderUris.addAll(AppPreferences.Scanner.musicFolderUris)
                                Toast.makeText(context, context.getString(R.string.scan_settings_folder_removed), Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (index != selectedFolderUris.lastIndex) {
                            MiuixDivider()
                        }
                    }
                }
            }

            // ── 访问方式 ──
            SmallTitle(text = stringResource(R.string.scan_settings_access_section))
            CardGroup {
                SwitchPreference(
                    title = stringResource(R.string.scan_settings_legacy_access_title),
                    checked = legacyFileAccessEnabled,
                    onCheckedChange = { checked: Boolean ->
                        if (checked) {
                            showLegacyConfirmDialog = true
                        } else {
                            legacyFileAccessEnabled = false
                            AppPreferences.Scanner.legacyFileAccessEnabled = false
                        }
                    }
                )
            }

            // ── 扫描过滤 ──
            SmallTitle(text = stringResource(R.string.scan_settings_filter_section))
            CardGroup {
                SliderRow(
                    title = stringResource(R.string.scan_settings_min_duration_title),
                    valueLabel = if (minTrackDurationSeconds <= 0) stringResource(R.string.scan_settings_never_ignore) else stringResource(R.string.scan_settings_seconds_value, minTrackDurationSeconds),
                    description = stringResource(R.string.scan_settings_min_duration_summary),
                    value = minTrackDurationSeconds.toFloat(),
                    valueRange = 0f..60f,
                    steps = 59,
                    onValueChange = { minTrackDurationSeconds = it.roundToInt().coerceIn(0, 60) },
                    onValueChangeFinished = {
                        AppPreferences.Scanner.minTrackDurationSeconds = minTrackDurationSeconds
                    }
                )
                MiuixDivider()
                SwitchPreference(
                    title = stringResource(R.string.scan_settings_ignore_video_title),
                    checked = ignoreVideoFormats,
                    onCheckedChange = { checked: Boolean ->
                        ignoreVideoFormats = checked
                        AppPreferences.Scanner.ignoreVideoFormats = checked
                    }
                )
            }

            // ── 提示 ──
            SmallTitle(text = stringResource(R.string.scan_settings_hint_section))
            CardGroup {
                Text(
                    text = stringResource(R.string.scan_settings_hint_text),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            Spacer(Modifier.height(180.dp))
        }
    }
}

// ─────────────── Miuix 风格组件 ───────────────

@Composable
private fun CardGroup(content: @Composable () -> Unit) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF1E1E1E) else Color.White

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .padding(vertical = 4.dp)
    ) {
        content()
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun MiuixDivider() {
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .padding(horizontal = 16.dp)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f))
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SliderPreference(
            title = title,
            summary = description,
            valueText = valueLabel,
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            hapticEffect = SliderDefaults.SliderHapticEffect.Step
        )
    }
}

@Composable
private fun FolderRow(
    rawUri: String,
    onScan: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val fallbackName = stringResource(R.string.scan_settings_selected_folder_fallback)

    val displayName = remember(rawUri, fallbackName) {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        uri?.let { DocumentFile.fromTreeUri(context, it)?.name }
            .orEmpty().ifBlank { rawUri.substringAfterLast('/').ifBlank { fallbackName } }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = displayName,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rawUri,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.scan_settings_action_scan),
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onScan)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                text = stringResource(R.string.scan_settings_action_remove),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
