package com.rawsmusic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.google.gson.Gson
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.PEQCurveColors
import com.rawsmusic.core.ui.widget.PEQCurveView
import com.rawsmusic.module.player.dsp.AutoEqCacheManager
import com.rawsmusic.module.player.dsp.AutoEqPreset
import com.rawsmusic.module.player.dsp.AutoEqRepository
import com.rawsmusic.module.player.dsp.AutoEqSearchResult
import com.rawsmusic.module.player.dsp.FilterType
import com.rawsmusic.module.player.dsp.PEQFilter
import com.rawsmusic.module.player.dsp.ParametricEQController
import kotlinx.coroutines.launch

/**
 * 参量均衡器UI颜色
 */
private object PEQUiColors {
    val Background = Color(0xFF0D0D1A)
    val CardBackground = Color(0xFF1A1A2E)
    val RowBackground = Color(0xFF12122A)
    val Accent = Color(0xFF00D4FF)
    val AccentDim = Color(0xFF0088AA)
    val TextPrimary = Color(0xFFEEEEFF)
    val TextSecondary = Color(0xFF8A8AAA)
    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF4ECB71)
    val SliderTrack = Color(0xFF2A2A4A)
    val Underline = Color(0xFF00D4FF)
}

/**
 * 参量均衡器界面 — 紧凑单行布局，10 段标准倍频程
 */
@Composable
fun LiquidGlassPEQScreen(
    peqController: ParametricEQController,
    onBack: () -> Unit,
    onExportToFile: (String) -> Unit = {},
    onImportFromFile: () -> Unit = {},
    importedFileContent: String? = null,
    onImportedFileContentConsumed: () -> Unit = {}
) {
    val isEnabled by peqController.isEnabled.collectAsState()
    val filters by peqController.filters.collectAsState()
    val frequencyResponse by peqController.frequencyResponse.collectAsState()
    val preamp by peqController.preamp.collectAsState()

    // AutoEq 对话框状态
    var showAutoEqDialog by remember { mutableStateOf(false) }
    // 更多选项菜单状态
    var showMoreMenu by remember { mutableStateOf(false) }
    // 导出对话框状态
    var showExportDialog by remember { mutableStateOf(false) }
    // 导入对话框状态
    var showImportDialog by remember { mutableStateOf(false) }
    // 导入文本
    var importText by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(PEQUiColors.Background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // 顶部栏
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = PEQUiColors.Accent, fontSize = 16.sp)
            }
            Text(
                "参量均衡器",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = PEQUiColors.TextPrimary
            )
            Row {
                // AutoEq 按钮
                IconButton(onClick = { showAutoEqDialog = true }) {
                    Icon(
                        Icons.Default.List,
                        contentDescription = "AutoEq",
                        tint = PEQUiColors.Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // 更多选项按钮
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多选项",
                            tint = PEQUiColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    // 下拉菜单
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = PEQUiColors.CardBackground
                    ) {
                        // 重置选项
                        DropdownMenuItem(
                            text = { Text("重置默认", color = PEQUiColors.TextPrimary) },
                            onClick = {
                                peqController.resetToDefault()
                                showMoreMenu = false
                            }
                        )
                        // 导出预设选项
                        DropdownMenuItem(
                            text = { Text("导出预设", color = PEQUiColors.TextPrimary) },
                            onClick = {
                                showExportDialog = true
                                showMoreMenu = false
                            }
                        )
                        // 导入预设选项
                        DropdownMenuItem(
                            text = { Text("导入预设", color = PEQUiColors.TextPrimary) },
                            onClick = {
                                showImportDialog = true
                                showMoreMenu = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 频率响应曲线
        val frequencies = remember(frequencyResponse) {
            if (frequencyResponse.isNotEmpty()) {
                FloatArray(frequencyResponse.size) { i ->
                    20f * Math.pow((20000.0 / 20.0).toDouble(), i.toDouble() / (frequencyResponse.size - 1)).toFloat()
                }
            } else {
                FloatArray(0)
            }
        }

        // 使用 key 确保曲线在数据变化时重新绘制
        key(frequencyResponse.contentHashCode()) {
            PEQCurveView(
                frequencies = frequencies,
                magnitudes = frequencyResponse,
                colors = PEQCurveColors(
                    background = PEQUiColors.CardBackground,
                    curveLine = PEQUiColors.Accent
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        // 前置放大器控制
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Preamp",
                color = PEQUiColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.width(60.dp)
            )
            Slider(
                value = preamp,
                onValueChange = { peqController.setPreamp(it) },
                valueRange = -12f..12f,
                modifier = Modifier.weight(1f).height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = PEQUiColors.Accent,
                    activeTrackColor = PEQUiColors.Accent,
                    inactiveTrackColor = PEQUiColors.SliderTrack
                )
            )
            Text(
                String.format("%+.1f dB", preamp),
                color = PEQUiColors.Accent,
                fontSize = 11.sp,
                modifier = Modifier.width(56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }

        Spacer(Modifier.height(8.dp))

        // 表头
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#", color = PEQUiColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(28.dp))
            Text("类型", color = PEQUiColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(64.dp))
            Text("频率", color = PEQUiColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("增益", color = PEQUiColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("开关", color = PEQUiColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Spacer(Modifier.height(4.dp))

        // 滤波器列表（紧凑行 + 展开滑块）
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(filters) { index, filter ->
                FilterRow(
                    filter = filter,
                    index = index,
                    onUpdate = { peqController.updateFilter(index, it) },
                    onToggle = { peqController.toggleFilter(index) }
                )
            }
        }
    }
    
    // AutoEq 对话框
    if (showAutoEqDialog) {
        AutoEqDialog(
            peqController = peqController,
            onDismiss = { showAutoEqDialog = false }
        )
    }
    
    // 导出预设对话框
    if (showExportDialog) {
        ExportPresetDialog(
            peqController = peqController,
            onDismiss = { showExportDialog = false },
            onExportToFile = onExportToFile
        )
    }
    
    // 导入预设对话框
    if (showImportDialog) {
        ImportPresetDialog(
            peqController = peqController,
            onDismiss = { showImportDialog = false },
            onImportFromFile = onImportFromFile,
            initialImportText = importedFileContent ?: ""
        )
        if (importedFileContent != null) {
            onImportedFileContentConsumed()
        }
    }
}

/**
 * 导出预设对话框
 */
@Composable
private fun ExportPresetDialog(
    peqController: ParametricEQController,
    onDismiss: () -> Unit,
    onExportToFile: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filters by peqController.filters.collectAsState()
    val preamp by peqController.preamp.collectAsState()
    
    // 创建预设对象
    val preset = remember(filters, preamp) {
        PEQPreset(
            name = "PEQ预设_${System.currentTimeMillis()}",
            preamp = preamp,
            filters = filters
        )
    }
    
    val presetJson = remember(preset) { preset.toJson() }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PEQUiColors.CardBackground,
        title = {
            Text("导出预设", color = PEQUiColors.TextPrimary, fontWeight = FontWeight.Medium)
        },
        text = {
            Column {
                Text(
                    "预设配置已生成，请复制以下JSON文本：",
                    color = PEQUiColors.TextSecondary,
                    fontSize = 14.sp
                )
                
                Spacer(Modifier.height(16.dp))
                
                // JSON文本框
                OutlinedTextField(
                    value = presetJson,
                    onValueChange = {},
                    readOnly = true,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = PEQUiColors.TextPrimary,
                        unfocusedTextColor = PEQUiColors.TextPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = PEQUiColors.Accent,
                        unfocusedIndicatorColor = PEQUiColors.TextSecondary,
                        cursorColor = PEQUiColors.Accent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
                
                Spacer(Modifier.height(8.dp))
                
                Text(
                    "包含 ${filters.size} 个滤波器，Preamp: ${String.format("%.1f", preamp)} dB",
                    color = PEQUiColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        onExportToFile(presetJson)
                    }
                ) {
                    Text("保存到文件", color = PEQUiColors.Success)
                }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newRawUri("PEQ预设", null)
                        clip.addItem(android.content.ClipData.Item(presetJson))
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                ) {
                    Text("复制", color = PEQUiColors.Accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = PEQUiColors.TextSecondary)
            }
        }
    )
}

/**
 * 导入预设对话框
 */
@Composable
private fun ImportPresetDialog(
    peqController: ParametricEQController,
    onDismiss: () -> Unit,
    onImportFromFile: () -> Unit = {},
    initialImportText: String = ""
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var importText by remember { mutableStateOf(initialImportText) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(initialImportText) {
        if (initialImportText.isNotEmpty()) {
            importText = initialImportText
            errorMessage = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PEQUiColors.CardBackground,
        title = {
            Text("导入预设", color = PEQUiColors.TextPrimary, fontWeight = FontWeight.Medium)
        },
        text = {
            Column {
                Text(
                    "请粘贴PEQ预设的JSON文本：",
                    color = PEQUiColors.TextSecondary,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(12.dp))

                TextButton(
                    onClick = { onImportFromFile() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("从文件导入", color = PEQUiColors.Success, fontSize = 14.sp)
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = importText,
                    onValueChange = { 
                        importText = it
                        errorMessage = null
                    },
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = PEQUiColors.TextPrimary,
                        unfocusedTextColor = PEQUiColors.TextPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = PEQUiColors.Accent,
                        unfocusedIndicatorColor = PEQUiColors.TextSecondary,
                        cursorColor = PEQUiColors.Accent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
                
                // 错误信息
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = PEQUiColors.Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (importText.isBlank()) {
                        errorMessage = "请输入预设JSON文本"
                        return@TextButton
                    }
                    
                    try {
                        val preset = PEQPreset.fromJson(importText)
                        if (preset == null) {
                            errorMessage = "无效的预设格式"
                            return@TextButton
                        }
                        
                        // 应用预设
                        peqController.setPreamp(preset.preamp)
                        // 导入滤波器
                        if (preset.filters.isNotEmpty()) {
                            val maxFilters = PEQFilter.MAX_FILTERS
                            val filtersToImport = if (preset.filters.size > maxFilters) {
                                preset.filters.take(maxFilters)
                            } else {
                                preset.filters
                            }
                            // 直接设置滤波器列表
                            peqController.importFilters(filtersToImport, preset.name)
                        }
                        
                        android.widget.Toast.makeText(context, "预设导入成功", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } catch (e: Exception) {
                        errorMessage = "解析失败: ${e.message}"
                    }
                }
            ) {
                Text("导入", color = PEQUiColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = PEQUiColors.TextSecondary)
            }
        }
    )
}

/**
 * 紧凑滤波器行：一行显示参数，点击展开滑块
 * 布局: 01 | Peak | 1.0kHz | +0.0dB | [开关]
 */
@Composable
private fun FilterRow(
    filter: PEQFilter,
    index: Int,
    onUpdate: (PEQFilter) -> Unit,
    onToggle: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }

    // 数值输入对话框状态
    var showInputDialog by remember { mutableStateOf(false) }
    var inputDialogType by remember { mutableStateOf("") } // "freq", "gain", "q"
    var inputDialogValue by remember { mutableStateOf("") }
    var inputDialogTitle by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (expanded) PEQUiColors.CardBackground else PEQUiColors.RowBackground)
    ) {
        // 主行
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Text(
                String.format("%02d", index + 1),
                color = PEQUiColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.width(28.dp)
            )

            // 类型（点击弹出菜单）
            Box(modifier = Modifier.width(64.dp)) {
                Text(
                    filter.displayType,
                    color = PEQUiColors.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { showTypeMenu = true }
                )
                DropdownMenu(
                    expanded = showTypeMenu,
                    onDismissRequest = { showTypeMenu = false },
                    containerColor = PEQUiColors.CardBackground
                ) {
                    FilterType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(FilterType.displayName(type), color = PEQUiColors.TextPrimary, fontSize = 13.sp) },
                            onClick = {
                                onUpdate(filter.copy(type = type))
                                showTypeMenu = false
                            }
                        )
                    }
                }
            }

            // 频率
            Text(
                filter.frequencyText + "Hz",
                color = PEQUiColors.TextPrimary,
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.weight(1f)
            )

            // 增益
            Text(
                filter.gainText + "dB",
                color = PEQUiColors.TextPrimary,
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.weight(1f)
            )

            // 开关
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (filter.enabled) PEQUiColors.Success else PEQUiColors.SliderTrack)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (filter.enabled) "ON" else "OFF",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 展开区域：三个滑块
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 频率滑块
                val freqSliderValue = remember(filter.frequency) {
                    if (filter.frequency <= 20f) 0f
                    else if (filter.frequency >= 20000f) 1f
                    else (Math.log((filter.frequency / 20.0)) / Math.log(1000.0)).toFloat()
                }
                CompactSliderRow(
                    label = "频率",
                    valueText = filter.frequencyText + "Hz",
                    value = freqSliderValue,
                    onValueChange = { sliderVal ->
                        val realFreq = (20.0 * Math.pow(1000.0, sliderVal.toDouble())).toFloat()
                            .coerceIn(20f, 20000f)
                        onUpdate(filter.copy(frequency = realFreq))
                    },
                    valueRange = 0f..1f,
                    onValueClick = {
                        inputDialogType = "freq"
                        inputDialogTitle = "输入频率 (Hz)"
                        inputDialogValue = filter.frequencyText
                        showInputDialog = true
                    }
                )

                // 增益滑块
                CompactSliderRow(
                    label = "增益",
                    valueText = filter.gainText + "dB",
                    value = filter.gainDB,
                    onValueChange = { onUpdate(filter.copy(gainDB = String.format("%.1f", it).toFloat())) },
                    valueRange = PEQFilter.GAIN_RANGE,
                    onValueClick = {
                        inputDialogType = "gain"
                        inputDialogTitle = "输入增益 (dB)"
                        inputDialogValue = filter.gainText
                        showInputDialog = true
                    }
                )

                // Q值滑块
                CompactSliderRow(
                    label = "Q值",
                    valueText = String.format("%.2f", filter.Q),
                    value = filter.Q,
                    onValueChange = { onUpdate(filter.copy(Q = it)) },
                    valueRange = PEQFilter.Q_RANGE,
                    onValueClick = {
                        inputDialogType = "q"
                        inputDialogTitle = "输入 Q 值"
                        inputDialogValue = String.format("%.2f", filter.Q)
                        showInputDialog = true
                    }
                )
            }
        }
    }

    // 数值输入对话框
    if (showInputDialog) {
        ValueInputDialog(
            title = inputDialogTitle,
            initialValue = inputDialogValue,
            onDismiss = { showInputDialog = false },
            onConfirm = { valueStr ->
                when (inputDialogType) {
                    "freq" -> {
                        valueStr.toFloatOrNull()?.let { freq ->
                            if (freq in 20f..20000f) {
                                onUpdate(filter.copy(frequency = freq))
                            }
                        }
                    }
                    "gain" -> {
                        valueStr.toFloatOrNull()?.let { gain ->
                            if (gain in PEQFilter.GAIN_RANGE) {
                                onUpdate(filter.copy(gainDB = String.format("%.1f", gain).toFloat()))
                            }
                        }
                    }
                    "q" -> {
                        valueStr.toFloatOrNull()?.let { q ->
                            if (q in PEQFilter.Q_RANGE) {
                                onUpdate(filter.copy(Q = q))
                            }
                        }
                    }
                }
                showInputDialog = false
            }
        )
    }
}

/**
 * 紧凑滑块行
 */
@Composable
private fun CompactSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueClick: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = PEQUiColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(32.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = PEQUiColors.Accent,
                activeTrackColor = PEQUiColors.Accent,
                inactiveTrackColor = PEQUiColors.SliderTrack
            )
        )
        Text(
            valueText,
            color = PEQUiColors.Accent,
            fontSize = 11.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .width(56.dp)
                .clickable { onValueClick() },
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/**
 * 数值输入对话框
 */
@Composable
private fun ValueInputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PEQUiColors.CardBackground,
        titleContentColor = PEQUiColors.TextPrimary,
        textContentColor = PEQUiColors.TextSecondary,
        title = {
            Text(title, color = PEQUiColors.TextPrimary)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        isError = false
                    },
                    isError = isError,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = PEQUiColors.TextPrimary,
                        fontSize = 16.sp
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = PEQUiColors.TextPrimary,
                        unfocusedTextColor = PEQUiColors.TextPrimary,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = PEQUiColors.Accent,
                        unfocusedIndicatorColor = PEQUiColors.TextSecondary,
                        cursorColor = PEQUiColors.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    Text(
                        "请输入有效的数值",
                        color = PEQUiColors.Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = text.toFloatOrNull()
                    if (parsed != null) {
                        onConfirm(text)
                    } else {
                        isError = true
                    }
                }
            ) {
                Text("确定", color = PEQUiColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = PEQUiColors.TextSecondary)
            }
        }
    )
}

/**
 * AutoEq 预设选择对话框
 */
@Composable
fun AutoEqDialog(
    peqController: ParametricEQController,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    
    val cacheManager = remember { AutoEqCacheManager(context) }
    val repository = remember { AutoEqRepository() }
    
    // 状态
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<AutoEqSearchResult>>(emptyList()) }
    var cachedPresets by remember { mutableStateOf(cacheManager.loadAll()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var downloadingPreset by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PEQUiColors.CardBackground,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AutoEq 预设",
                    color = PEQUiColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    tint = PEQUiColors.Accent
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 搜索框
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("搜索耳机", color = PEQUiColors.TextSecondary) },
                        placeholder = { Text("例如: HD600", color = PEQUiColors.TextSecondary) },
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedTextColor = PEQUiColors.TextPrimary,
                            unfocusedTextColor = PEQUiColors.TextPrimary,
                            focusedLabelColor = PEQUiColors.Accent,
                            unfocusedLabelColor = PEQUiColors.TextSecondary,
                            focusedIndicatorColor = PEQUiColors.Accent,
                            unfocusedIndicatorColor = PEQUiColors.TextSecondary,
                            cursorColor = PEQUiColors.Accent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotBlank() && !isSearching) {
                                scope.launch {
                                    isSearching = true
                                    errorMessage = null
                                    try {
                                        searchResults = repository.search(searchQuery)
                                        if (searchResults.isEmpty()) {
                                            errorMessage = "未找到匹配的耳机"
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = "搜索失败: ${e.message}"
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            }
                        },
                        enabled = searchQuery.isNotBlank() && !isSearching
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = PEQUiColors.Accent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "搜索",
                                tint = if (searchQuery.isNotBlank()) PEQUiColors.Accent else PEQUiColors.TextSecondary
                            )
                        }
                    }
                }
                
                // 错误信息
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = PEQUiColors.Danger,
                        fontSize = 12.sp
                    )
                }
                
                // 搜索结果
                if (searchResults.isNotEmpty()) {
                    Text(
                        "搜索结果",
                        color = PEQUiColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    LazyColumn(
                        modifier = Modifier.height(150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(searchResults.size) { index ->
                            val result = searchResults[index]
                            val isCached = cacheManager.exists(result.headphoneName)
                            val isDownloading = downloadingPreset == result.headphoneName
                            
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PEQUiColors.RowBackground)
                                    .clickable {
                                        if (isCached) {
                                            // 从缓存加载
                                            val preset = cacheManager.load(result.headphoneName)
                                            if (preset != null) {
                                                peqController.importFromAutoEq(preset)
                                                onDismiss()
                                            }
                                        } else {
                                            // 下载
                                            scope.launch {
                                                downloadingPreset = result.headphoneName
                                                errorMessage = null
                                                try {
                                                    val preset = repository.download(result)
                                                    if (preset != null) {
                                                        cacheManager.save(preset)
                                                        cachedPresets = cacheManager.loadAll()
                                                        peqController.importFromAutoEq(preset)
                                                        onDismiss()
                                                    } else {
                                                        errorMessage = "下载失败"
                                                    }
                                                } catch (e: Exception) {
                                                    errorMessage = "下载失败: ${e.message}"
                                                } finally {
                                                    downloadingPreset = null
                                                }
                                            }
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        result.headphoneName,
                                        color = PEQUiColors.TextPrimary,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "${result.source} • ${result.deviceType}",
                                        color = PEQUiColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                
                                if (isDownloading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = PEQUiColors.Accent,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        if (isCached) "已下载" else "下载",
                                        color = if (isCached) PEQUiColors.Success else PEQUiColors.Accent,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 已下载列表
                if (cachedPresets.isNotEmpty()) {
                    Text(
                        "已下载",
                        color = PEQUiColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    LazyColumn(
                        modifier = Modifier.height(150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(cachedPresets.size) { index ->
                            val preset = cachedPresets[index]
                            
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PEQUiColors.RowBackground)
                                    .clickable {
                                        peqController.importFromAutoEq(preset)
                                        onDismiss()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        preset.name,
                                        color = PEQUiColors.TextPrimary,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        "${preset.filters.size} 个滤波器" +
                                            if (preset.source.isNotBlank()) " • ${preset.source}" else "",
                                        color = PEQUiColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        cacheManager.delete(preset.name)
                                        cachedPresets = cacheManager.loadAll()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text(
                                        "×",
                                        color = PEQUiColors.Danger,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 空状态提示
                if (searchResults.isEmpty() && cachedPresets.isEmpty() && !isSearching) {
                    Text(
                        "搜索耳机型号以下载 AutoEq 预设",
                        color = PEQUiColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = PEQUiColors.TextSecondary)
            }
        }
    )
}

/**
 * PEQ 预设配置数据类
 */
data class PEQPreset(
    val name: String,
    val preamp: Float,
    val filters: List<PEQFilter>,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private val gson = Gson()
        
        fun fromJson(json: String): PEQPreset? {
            return try {
                gson.fromJson(json, PEQPreset::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    fun toJson(): String {
        return gson.toJson(this)
    }
}
