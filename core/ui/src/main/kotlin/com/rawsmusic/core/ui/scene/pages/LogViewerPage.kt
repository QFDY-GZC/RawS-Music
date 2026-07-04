package com.rawsmusic.core.ui.scene.pages

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppLogStore
import com.rawsmusic.module.data.prefs.LogEntry
import com.rawsmusic.module.data.prefs.LogStats
import com.rawsmusic.core.ui.scene.pages.SectionHeader
import com.rawsmusic.core.ui.scene.pages.SettingsCard
import com.rawsmusic.core.ui.scene.pages.SettingsPage
import com.rawsmusic.core.ui.scene.pages.appFontFamily
import com.rawsmusic.core.ui.scene.pages.themeColors
import kotlinx.coroutines.launch

/**
 * 日志分析页面。
 * 从 LogViewerFragment 迁移，原封不动保留 UI 内容。
 */
@Composable
fun LogViewerPage(onBack: () -> Unit) {
    val colors = themeColors()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    var levelFilter by remember { mutableStateOf<String?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf<LogEntry?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val allEntries by AppLogStore.entries.collectAsState()
    val stats by AppLogStore.stats.collectAsState()

    LaunchedEffect(Unit) {
        AppLogStore.refresh()
        isLoading = false
    }

    val filteredEntries = remember(allEntries, levelFilter, tagFilter, searchQuery) {
        AppLogStore.filter(allEntries, levelFilter, tagFilter, searchQuery.ifBlank { null })
    }

    val availableTags = remember(allEntries) { AppLogStore.getTags() }

    SettingsPage(title = "日志分析", onBack = onBack) {
        if (isLoading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
        } else {
            StatsCard(stats)

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader("过滤")
                    Row {
                        TextButton(onClick = { scope.launch { AppLogStore.refresh() } }) {
                            Text("刷新", color = colors.primary, fontSize = 13.sp, fontFamily = appFontFamily())
                        }
                        TextButton(onClick = { showClearConfirm = true }) {
                            Text("清空", color = Color(0xFFC62828), fontSize = 13.sp, fontFamily = appFontFamily())
                        }
                        TextButton(onClick = {
                            val file = AppLogger.getLogFile()
                            if (file != null && file.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "分享日志"))
                                } catch (_: Exception) {}
                            }
                        }) {
                            Text("分享", color = colors.primary, fontSize = 13.sp, fontFamily = appFontFamily())
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                LevelFilterRow(selectedLevel = levelFilter, stats = stats, onSelect = { level ->
                    levelFilter = if (levelFilter == level) null else level
                })

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索日志", fontFamily = appFontFamily()) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = appFontFamily())
                )

                if (availableTags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("标签过滤:", fontSize = 13.sp, color = colors.secondaryText, fontFamily = appFontFamily())
                    Spacer(Modifier.height(4.dp))
                    TagFilterFlowRow(tags = availableTags, selectedTag = tagFilter, onSelect = { tag ->
                        tagFilter = if (tagFilter == tag) null else tag
                    })
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "共 ${filteredEntries.size} 条日志",
                fontSize = 13.sp, color = colors.secondaryText, fontFamily = appFontFamily(),
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.fillMaxWidth().height(400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filteredEntries) { entry ->
                    LogEntryRow(entry) { showDetail = entry }
                }
            }
        }
    }

    if (showDetail != null) {
        val entry = showDetail!!
        AlertDialog(
            onDismissRequest = { showDetail = null },
            title = { Text("${entry.level}/${entry.tag}", fontFamily = appFontFamily()) },
            text = {
                Column {
                    Text(entry.timestamp, fontSize = 12.sp, color = colors.secondaryText, fontFamily = appFontFamily())
                    Spacer(Modifier.height(8.dp))
                    Text(entry.message, fontSize = 13.sp, color = colors.onSurface, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showDetail = null }) { Text("关闭", fontFamily = appFontFamily()) } }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空日志", fontFamily = appFontFamily()) },
            text = { Text("确定要清空所有日志吗？此操作不可恢复。", fontFamily = appFontFamily()) },
            confirmButton = {
                TextButton(onClick = { AppLogStore.clearLog(); showClearConfirm = false }) {
                    Text("清空", color = Color(0xFFC62828), fontFamily = appFontFamily())
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消", fontFamily = appFontFamily()) } }
        )
    }
}

@Composable
private fun StatsCard(stats: LogStats) {
    val colors = themeColors()
    SettingsCard {
        SectionHeader("概览")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("总计", stats.total.toString(), colors.onSurface)
            StatItem("错误", stats.errorCount.toString(), Color(0xFFC62828))
            StatItem("警告", stats.warnCount.toString(), Color.White)
            StatItem("信息", stats.infoCount.toString(), Color(0xFF1565C0))
            StatItem("调试", stats.debugCount.toString(), Color(0xFF616161))
        }
        if (stats.topTags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("高频标签 Top 5:", fontSize = 13.sp, color = colors.secondaryText, fontFamily = appFontFamily())
            Spacer(Modifier.height(4.dp))
            stats.topTags.take(5).forEach { (tag, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tag, fontSize = 13.sp, color = colors.onSurface, fontFamily = appFontFamily())
                    Text("$count 次", fontSize = 13.sp, color = colors.secondaryText, fontFamily = appFontFamily())
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color, fontFamily = appFontFamily())
        Text(label, fontSize = 12.sp, color = color.copy(alpha = 0.7f), fontFamily = appFontFamily())
    }
}

@Composable
private fun LevelFilterRow(selectedLevel: String?, stats: LogStats, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LevelChip("E", "错误 ${stats.errorCount}", selectedLevel == "E", Color(0xFFC62828), onSelect)
        LevelChip("W", "警告 ${stats.warnCount}", selectedLevel == "W", Color.White, onSelect)
        LevelChip("I", "信息 ${stats.infoCount}", selectedLevel == "I", Color(0xFF1565C0), onSelect)
        LevelChip("D", "调试 ${stats.debugCount}", selectedLevel == "D", Color(0xFF616161), onSelect)
    }
}

@Composable
private fun LevelChip(level: String, label: String, selected: Boolean, color: Color, onSelect: (String) -> Unit) {
    val backgroundColor = if (selected) color.copy(alpha = 0.2f) else Color.Transparent
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(backgroundColor).clickable { onSelect(level) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = if (selected) color else color.copy(alpha = 0.7f), fontFamily = appFontFamily())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFilterFlowRow(tags: Set<String>, selectedTag: String?, onSelect: (String) -> Unit) {
    val colors = themeColors()
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.sorted().forEach { tag ->
            val isSelected = selectedTag == tag
            val bgColor = if (isSelected) colors.primaryContainer else Color.Transparent
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(bgColor).clickable { onSelect(tag) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tag, fontSize = 11.sp, color = if (isSelected) colors.onPrimaryContainer else colors.secondaryText, fontFamily = appFontFamily())
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, onClick: () -> Unit) {
    val colors = themeColors()
    val levelColor = when (entry.level) {
        "E" -> Color(0xFFC62828)
        "W" -> Color.White
        "I" -> Color(0xFF1565C0)
        else -> Color(0xFF616161)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(entry.level, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = levelColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.tag, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.onSurface, fontFamily = appFontFamily(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(entry.timestamp.substring(11), fontSize = 10.sp, color = colors.secondaryText, fontFamily = appFontFamily())
            }
            Text(entry.message.take(120), fontSize = 12.sp, color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
