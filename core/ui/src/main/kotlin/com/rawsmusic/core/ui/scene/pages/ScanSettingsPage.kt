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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.module.data.prefs.AppPreferences
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
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

    var trackProgressMemoryEnabled by remember {
        mutableStateOf(AppPreferences.Player.trackProgressMemoryEnabled)
    }

    var ignoreVideoFormats by remember {
        mutableStateOf(AppPreferences.Scanner.ignoreVideoFormats)
    }

    var playCountEnabled by remember {
        mutableStateOf(AppPreferences.Player.playCountEnabled)
    }

    var playCountThresholdPercent by remember {
        mutableStateOf(AppPreferences.Player.playCountThresholdPercent.coerceIn(1, 100))
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
            Toast.makeText(context, "无法保存文件夹权限", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        AppPreferences.Scanner.addMusicFolderUri(uri.toString())
        selectedFolderUris.clear()
        selectedFolderUris.addAll(AppPreferences.Scanner.musicFolderUris)
        Toast.makeText(context, "已添加音乐文件夹", Toast.LENGTH_SHORT).show()
        onRescan()
    }

    if (showLegacyConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLegacyConfirmDialog = false },
            title = { Text("启用传统文件访问方式？") },
            text = {
                Text(
                    "传统文件访问会尝试直接扫描存储路径，需要所有音频访问权限。部分系统可能还需要在系统设置中允许完整文件访问。建议优先使用「选择音乐文件夹」。",
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showLegacyConfirmDialog = false
                        onRequestLegacyAudioAccess()
                    }
                ) { Text("去授权") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showLegacyConfirmDialog = false }
                ) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        SmallTopAppBar(
            title = "扫描设置",
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
            SmallTitle(text = "音乐库")
            CardGroup {
                ArrowPreference(
                    title = "重新扫描",
                    summary = "重新扫描媒体库和已选择的音乐文件夹",
                    onClick = onRescan
                )
                ArrowPreference(
                    title = "选择音乐文件夹",
                    summary = "可选择 Music 以外的目录，例如 Download、Android/media",
                    onClick = { folderPicker.launch(null) }
                )
            }

            // ── 已选择文件夹 ──
            if (selectedFolderUris.isNotEmpty()) {
                SmallTitle(text = "文件夹扫描")
                CardGroup {
                    selectedFolderUris.forEachIndexed { index, rawUri ->
                        FolderRow(
                            rawUri = rawUri,
                            onScan = onRescan,
                            onRemove = {
                                AppPreferences.Scanner.removeMusicFolderUri(rawUri)
                                selectedFolderUris.clear()
                                selectedFolderUris.addAll(AppPreferences.Scanner.musicFolderUris)
                                Toast.makeText(context, "已移除文件夹", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (index != selectedFolderUris.lastIndex) {
                            MiuixDivider()
                        }
                    }
                }
            }

            // ── 访问方式 ──
            SmallTitle(text = "访问方式")
            CardGroup {
                SwitchPreference(
                    title = "传统文件访问方式",
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
            SmallTitle(text = "扫描过滤")
            CardGroup {
                SliderRow(
                    title = "过滤短曲目",
                    valueLabel = if (minTrackDurationSeconds <= 0) "从不忽略" else "${minTrackDurationSeconds} 秒",
                    description = "低于该时长的音频不会加入音乐库",
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
                    title = "忽略视频格式",
                    checked = ignoreVideoFormats,
                    onCheckedChange = { checked: Boolean ->
                        ignoreVideoFormats = checked
                        AppPreferences.Scanner.ignoreVideoFormats = checked
                    }
                )
            }

            // ── 播放记录 ──
            SmallTitle(text = "播放记录")
            CardGroup {
                SwitchPreference(
                    title = "音轨进度保存与恢复",
                    checked = trackProgressMemoryEnabled,
                    onCheckedChange = { checked: Boolean ->
                        trackProgressMemoryEnabled = checked
                        AppPreferences.Player.trackProgressMemoryEnabled = checked
                        if (!checked) AppPreferences.Player.lastPosition = 0L
                    }
                )
                MiuixDivider()
                SwitchPreference(
                    title = "计入播放次数",
                    checked = playCountEnabled,
                    onCheckedChange = { checked: Boolean ->
                        playCountEnabled = checked
                        AppPreferences.Player.playCountEnabled = checked
                    }
                )
                MiuixDivider()
                SliderRow(
                    title = "播放次数统计阈值",
                    valueLabel = "${playCountThresholdPercent}%",
                    description = "音轨至少播放到该比例才会计入一次播放次数",
                    value = playCountThresholdPercent.toFloat(),
                    valueRange = 1f..100f,
                    steps = 98,
                    enabled = playCountEnabled,
                    onValueChange = { playCountThresholdPercent = it.roundToInt().coerceIn(1, 100) },
                    onValueChangeFinished = {
                        AppPreferences.Player.playCountThresholdPercent = playCountThresholdPercent
                    }
                )
            }

            // ── 提示 ──
            SmallTitle(text = "提示")
            CardGroup {
                Text(
                    text = "如果某些目录扫不到，优先使用「选择音乐文件夹」。传统文件访问方式只作为兼容兜底，不建议默认启用。",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueLabel,
                color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = description,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(top = 4.dp)
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

    val displayName = remember(rawUri) {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        uri?.let { DocumentFile.fromTreeUri(context, it)?.name }
            .orEmpty().ifBlank { rawUri.substringAfterLast('/').ifBlank { "已选择的文件夹" } }
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
                text = "扫描",
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onScan)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Text(
                text = "移除",
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
