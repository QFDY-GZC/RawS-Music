package com.rawsmusic.ui.effects.graphiceq

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.roundToInt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

data class GraphicEQBandUi(
    val frequency: Float,
    val gainDB: Float
)

data class GraphicEQPresetUi(
    val name: String,
    val gains: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GraphicEQPresetUi) return false
        return name == other.name && gains.contentEquals(other.gains)
    }
    override fun hashCode(): Int = 31 * name.hashCode() + gains.contentHashCode()
}

private object GraphicEQColors {
    val Background = Color(0xFF070812)
    val Card = Color(0xFF15172C)
    val CardDeep = Color(0xFF0F1122)
    val Track = Color(0xFF2A2E4D)
    val Text = Color(0xFFF4F6FF)
    val Muted = Color(0xFF8E95B8)
    val MutedDark = Color(0xFF5F6689)

    val Cyan = Color(0xFF19D8FF)
    val Green = Color(0xFF45D66E)
    val Red = Color(0xFFFF5C6C)
    val Orange = Color(0xFFFFA33A)
    val Blue = Color(0xFF3B7CFF)
    val DeepBlue = Color(0xFF2454D8)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphicEQScreen(
    enabled: Boolean,
    bandCount: Int,
    preampDB: Float,
    bands: List<GraphicEQBandUi>,
    presetName: String,
    presets: List<GraphicEQPresetUi>,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPreampChange: (Float) -> Unit,
    onBandGainChange: (index: Int, gainDB: Float) -> Unit,
    onBandReset: (index: Int) -> Unit,
    onBandCountChange: (Int) -> Unit,
    onPresetClick: (GraphicEQPresetUi) -> Unit,
    onResetAll: () -> Unit,
    onSavePreset: () -> Unit,
    onApply: () -> Unit
) {
    var editingBandIndex by remember { mutableStateOf<Int?>(null) }
    var showQInfo by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF05060E),
                        GraphicEQColors.Background,
                        Color(0xFF05060E)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            GraphicEQHeaderCard(
                enabled = enabled,
                bandCount = bandCount,
                presetName = presetName,
                bands = bands,
                preampDB = preampDB,
                onEnabledChange = onEnabledChange
            )

            Spacer(Modifier.height(8.dp))

            PresetChipsRow(
                presets = presets,
                currentPresetName = presetName,
                onPresetClick = onPresetClick
            )

            Spacer(Modifier.height(8.dp))

            GraphicEQBandControlRow(
                bandCount = bandCount,
                qValue = qForGraphicBand(bandCount),
                onBandCountChange = onBandCountChange,
                onQClick = { showQInfo = true }
            )

            Spacer(Modifier.height(8.dp))

            GraphicEQFaderPanel(
                modifier = Modifier.weight(1f),
                preampDB = preampDB,
                bands = bands,
                onPreampChange = onPreampChange,
                onBandGainChange = onBandGainChange,
                onBandLongPress = { index ->
                    editingBandIndex = index
                }
            )

            Spacer(Modifier.height(10.dp))

            GraphicEQActionRow(
                onResetAll = onResetAll,
                onSavePreset = onSavePreset,
                onApply = onApply
            )

            Spacer(Modifier.height(20.dp))
        }

        editingBandIndex?.let { index ->
            val band = bands.getOrNull(index)
            if (band != null) {
                ModalBottomSheet(
                    onDismissRequest = { editingBandIndex = null },
                    sheetState = sheetState,
                    containerColor = Color(0xFF101226),
                    contentColor = GraphicEQColors.Text,
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                ) {
                    GraphicEQBandEditorSheet(
                        index = index,
                        band = band,
                        onGainChange = { gain ->
                            onBandGainChange(index, gain)
                        },
                        onReset = {
                            onBandReset(index)
                        },
                        onDone = {
                            editingBandIndex = null
                        }
                    )
                }
            }
        }

        if (showQInfo) {
            ModalBottomSheet(
                onDismissRequest = { showQInfo = false },
                containerColor = Color(0xFF101226),
                contentColor = GraphicEQColors.Text,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                GraphicEQQInfoSheet(
                    bandCount = bandCount,
                    qValue = qForGraphicBand(bandCount),
                    onDone = { showQInfo = false }
                )
            }
        }
    }
}

@Composable
private fun GraphicEQTopBar(
    title: String,
    onBack: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(
                text = "← 返回",
                color = GraphicEQColors.Cyan,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = title,
            color = GraphicEQColors.Text,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.weight(1f))

        TextButton(onClick = onReset) {
            Text(
                text = "重置",
                color = GraphicEQColors.Cyan,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GraphicEQHeaderCard(
    enabled: Boolean,
    bandCount: Int,
    presetName: String,
    bands: List<GraphicEQBandUi>,
    preampDB: Float,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GraphicEQColors.Card,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .height(82.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "图形均衡器",
                    color = GraphicEQColors.Text,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "$bandCount 段 · $presetName",
                    color = GraphicEQColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
            }

            MiniCurvePreview(
                bands = bands,
                preampDB = preampDB,
                modifier = Modifier
                    .width(96.dp)
                    .height(38.dp)
            )

            Spacer(Modifier.width(12.dp))

            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun MiniCurvePreview(
    bands: List<GraphicEQBandUi>,
    preampDB: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111327))
    ) {
        if (bands.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val pad = 5.dp.toPx()

        val left = pad
        val right = w - pad
        val top = pad
        val bottom = h - pad
        val chartH = bottom - top

        val zeroY = top + chartH * 0.5f

        drawLine(
            color = Color.White.copy(alpha = 0.18f),
            start = Offset(left, zeroY),
            end = Offset(right, zeroY),
            strokeWidth = 1.dp.toPx()
        )

        val path = Path()

        bands.forEachIndexed { index, band ->
            val x = if (bands.size == 1) {
                (left + right) * 0.5f
            } else {
                left + (right - left) * index / (bands.size - 1).toFloat()
            }

            val gain = (band.gainDB + preampDB).coerceIn(-12f, 12f)
            val normalized = (gain + 12f) / 24f
            val y = bottom - chartH * normalized

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = GraphicEQColors.Cyan,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        )
    }
}

@Composable
private fun PresetChipsRow(
    presets: List<GraphicEQPresetUi>,
    currentPresetName: String,
    onPresetClick: (GraphicEQPresetUi) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            val selected = preset.name == currentPresetName

            AssistChip(
                onClick = { onPresetClick(preset) },
                label = {
                    Text(
                        text = preset.name,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (selected) {
                        GraphicEQColors.Cyan
                    } else {
                        GraphicEQColors.Card
                    },
                    labelColor = if (selected) {
                        Color(0xFF061016)
                    } else {
                        GraphicEQColors.Muted
                    }
                ),
                border = null
            )
        }
    }
}

@Composable
private fun GraphicEQBandControlRow(
    bandCount: Int,
    qValue: Float,
    onBandCountChange: (Int) -> Unit,
    onQClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = GraphicEQColors.Card,
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "段数",
                    color = GraphicEQColors.Muted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BandCountPill("10", bandCount == 10) { onBandCountChange(10) }
                    BandCountPill("31", bandCount == 31) { onBandCountChange(31) }
                    BandCountPill("40", bandCount == 40) { onBandCountChange(40) }
                }
            }
        }

        Surface(
            onClick = onQClick,
            modifier = Modifier.width(128.dp),
            color = GraphicEQColors.Card,
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "范围",
                        color = GraphicEQColors.Muted,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Text(
                        text = "±12 dB",
                        color = GraphicEQColors.Text,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Q",
                        color = GraphicEQColors.Cyan,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "%.2f".format(qValue),
                        color = GraphicEQColors.Muted,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun BandCountPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) GraphicEQColors.Cyan else Color(0xFF242844),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF061016) else GraphicEQColors.Cyan,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun SmallPill(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF242844),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = GraphicEQColors.Cyan,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun GraphicEQFaderPanel(
    preampDB: Float,
    bands: List<GraphicEQBandUi>,
    onPreampChange: (Float) -> Unit,
    onBandGainChange: (index: Int, gainDB: Float) -> Unit,
    onBandLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GraphicEQColors.CardDeep,
        shape = RoundedCornerShape(30.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 14.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(
                    when {
                        bands.size >= 35 -> 16.dp
                        bands.size >= 25 -> 18.dp
                        else -> 22.dp
                    }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PreampFaderColumn(
                    gainDB = preampDB,
                    onGainChange = onPreampChange,
                    onLongPress = {},
                    modifier = Modifier
                        .width(58.dp)
                        .fillMaxHeight()
                )

                bands.forEachIndexed { index, band ->
                    BandFaderColumn(
                        index = index,
                        frequency = band.frequency,
                        gainDB = band.gainDB,
                        onGainChange = { gain ->
                            onBandGainChange(index, gain)
                        },
                        onLongPress = {
                            onBandLongPress(index)
                        },
                        modifier = Modifier
                            .width(
                                when {
                                    bands.size >= 35 -> 42.dp
                                    bands.size >= 25 -> 44.dp
                                    else -> 48.dp
                                }
                            )
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun PreampFaderColumn(
    gainDB: Float,
    onGainChange: (Float) -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val safeGain = gainDB.coerceIn(-12f, 12f)
    val animatedGain by animateFloatAsState(
        targetValue = safeGain,
        label = "preampGain"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF11131F))
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val y = change.position.y.coerceIn(0f, height)
                        val normalized = 1f - y / height
                        onGainChange((normalized * 24f - 12f).coerceIn(-12f, 12f))
                    }
                }
        ) {
            EqVerticalFader(
                gainDB = animatedGain,
                isPreamp = true,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "增益",
            color = GraphicEQColors.Text,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = "%.1f".format(safeGain),
            color = colorForGain(safeGain).copy(alpha = 0.88f),
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun BandFaderColumn(
    index: Int,
    frequency: Float,
    gainDB: Float,
    onGainChange: (Float) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeGain = gainDB.coerceIn(-12f, 12f)
    val animatedGain by animateFloatAsState(
        targetValue = safeGain,
        label = "bandGain_$index"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        val height = size.height.toFloat().coerceAtLeast(1f)
                        val y = change.position.y.coerceIn(0f, height)
                        val normalized = 1f - y / height
                        onGainChange((normalized * 24f - 12f).coerceIn(-12f, 12f))
                    }
                }
        ) {
            EqVerticalFader(
                gainDB = animatedGain,
                isPreamp = false,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))

        BandValueBubble(
            frequency = frequency,
            gainDB = safeGain
        )
    }
}

@Composable
private fun BandValueBubble(
    frequency: Float,
    gainDB: Float
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Transparent)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatFrequencyForGEQ(frequency),
            color = GraphicEQColors.Text,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = "%.1f".format(gainDB),
            color = Color.White.copy(alpha = 0.82f),
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun gainToSegmentColor(gainDB: Float): Color {
    return when {
        gainDB > 6f -> Color(0xFFFF5C6C)
        gainDB > 0f -> Color(0xFFFFA33A)
        gainDB > -3f -> Color(0xFF45D66E)
        gainDB > -8f -> Color(0xFF19D8FF)
        else -> Color(0xFF3B7CFF)
    }
}

@Composable
private fun EqVerticalFader(
    gainDB: Float,
    isPreamp: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width * 0.5f

        val knobWidth = if (isPreamp) 34.dp.toPx() else 32.dp.toPx()
        val knobHeight = if (isPreamp) 54.dp.toPx() else 48.dp.toPx()
        val knobRadius = knobWidth * 0.48f
        val knobHalf = knobHeight * 0.5f

        val top = knobHalf + 6.dp.toPx()
        val bottom = size.height - knobHalf - 6.dp.toPx()
        val height = (bottom - top).coerceAtLeast(1f)

        val zeroY = top + height * 0.5f
        val normalized = ((gainDB + 12f) / 24f).coerceIn(0f, 1f)
        val knobY = bottom - height * normalized

        val trackWidth = if (isPreamp) 2.8.dp.toPx() else 2.4.dp.toPx()
        val indicatorWidth = if (isPreamp) 5.dp.toPx() else 4.5.dp.toPx()
        val indicatorHeight = if (isPreamp) 10.dp.toPx() else 9.dp.toPx()

        // 背景轨道：细线
        drawRoundRect(
            color = Color.White.copy(alpha = 0.20f),
            topLeft = Offset(centerX - trackWidth / 2f, top),
            size = Size(trackWidth, height),
            cornerRadius = CornerRadius(trackWidth, trackWidth)
        )

        // 三段刻度
        listOf(0.12f, 0.50f, 0.88f).forEach { t ->
            val y = top + height * t
            val alpha = if (t == 0.50f) 0.45f else 0.30f

            drawLine(
                color = Color.White.copy(alpha = alpha),
                start = Offset(centerX - 12.dp.toPx(), y),
                end = Offset(centerX - 9.dp.toPx(), y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )

            drawLine(
                color = Color.White.copy(alpha = alpha),
                start = Offset(centerX + 9.dp.toPx(), y),
                end = Offset(centerX + 12.dp.toPx(), y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 填充线
        val fillTop = minOf(knobY, zeroY)
        val fillBottom = maxOf(knobY, zeroY)
        val fillHeight = fillBottom - fillTop

        val fillColor = lerpColor(
            Color(0xFF888888),
            Color(0xFFFFFFFF),
            normalized
        )

        if (fillHeight > 1.dp.toPx()) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(centerX - trackWidth / 2f, fillTop),
                size = Size(trackWidth, fillHeight),
                cornerRadius = CornerRadius(trackWidth, trackWidth)
            )

            val glowPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = trackWidth * 1.8f
                color = fillColor.copy(alpha = 0.35f).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(
                    trackWidth * 2.5f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }

            val glowPath = android.graphics.Path().apply {
                addRoundRect(
                    centerX - trackWidth / 2f,
                    fillTop,
                    centerX + trackWidth / 2f,
                    fillBottom,
                    trackWidth,
                    trackWidth,
                    android.graphics.Path.Direction.CW
                )
            }
            drawContext.canvas.nativeCanvas.drawPath(glowPath, glowPaint)
        }

        // 胶囊阴影
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.25f),
            topLeft = Offset(
                x = centerX - knobWidth / 2f,
                y = knobY - knobHeight / 2f + 2.dp.toPx()
            ),
            size = Size(knobWidth, knobHeight),
            cornerRadius = CornerRadius(knobRadius, knobRadius)
        )

        // 胶囊主体
        drawRoundRect(
            color = Color(0xFF1E1E1E),
            topLeft = Offset(
                x = centerX - knobWidth / 2f,
                y = knobY - knobHeight / 2f
            ),
            size = Size(knobWidth, knobHeight),
            cornerRadius = CornerRadius(knobRadius, knobRadius)
        )

        // 胶囊描边
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(
                x = centerX - knobWidth / 2f,
                y = knobY - knobHeight / 2f
            ),
            size = Size(knobWidth, knobHeight),
            cornerRadius = CornerRadius(knobRadius, knobRadius),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // 内部指示器
        drawRoundRect(
            color = fillColor.copy(alpha = 0.9f),
            topLeft = Offset(
                x = centerX - indicatorWidth / 2f,
                y = knobY - indicatorHeight / 2f
            ),
            size = Size(indicatorWidth, indicatorHeight),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
    }
}

private fun formatFrequencyForGEQ(freq: Float): String {
    return when {
        freq >= 1000f -> {
            val k = freq / 1000f
            if (abs(k - k.roundToInt()) < 0.05f) {
                "${k.roundToInt()}K"
            } else {
                "%.1fK".format(k)
            }
        }
        else -> freq.roundToInt().toString()
    }
}

@Composable
private fun GraphicEQActionRow(
    onResetAll: () -> Unit,
    onSavePreset: () -> Unit,
    onApply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SurfaceButton(
            text = "重置",
            modifier = Modifier.weight(1f),
            onClick = onResetAll
        )

        SurfaceButton(
            text = "保存预设",
            modifier = Modifier.weight(1f),
            onClick = onSavePreset
        )

        SurfaceButton(
            text = "应用",
            primary = true,
            modifier = Modifier.weight(1f),
            onClick = onApply
        )
    }
}

@Composable
private fun SurfaceButton(
    text: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        color = if (primary) GraphicEQColors.Cyan else GraphicEQColors.Card,
        shape = RoundedCornerShape(999.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = if (primary) Color(0xFF061016) else GraphicEQColors.Text,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun GraphicEQBandEditorSheet(
    index: Int,
    band: GraphicEQBandUi,
    onGainChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit
) {
    var showBypassHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 54.dp, height = 5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF4A4F70))
        )

        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatFrequency(band.frequency),
                    color = GraphicEQColors.Text,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = "第 ${index + 1} 段",
                    color = GraphicEQColors.Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Surface(
                color = colorForGain(band.gainDB).copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = formatGain(band.gainDB),
                    color = colorForGain(band.gainDB),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                onClick = { showBypassHelp = true },
                color = GraphicEQColors.Card,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = "说明",
                    color = GraphicEQColors.Cyan,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (showBypassHelp) {
            Spacer(Modifier.height(12.dp))

            BypassHelpCard(
                onDismiss = { showBypassHelp = false }
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            DetailReadCard(
                title = "增益",
                value = formatGain(band.gainDB),
                modifier = Modifier.weight(1f)
            )

            DetailReadCard(
                title = "频率",
                value = formatFrequency(band.frequency),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "增益",
            color = GraphicEQColors.Muted,
            fontWeight = FontWeight.Bold
        )

        Slider(
            value = band.gainDB.coerceIn(-12f, 12f),
            onValueChange = onGainChange,
            valueRange = -12f..12f,
            steps = 47
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-12 dB", color = GraphicEQColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text("0 dB", color = GraphicEQColors.Muted, style = MaterialTheme.typography.labelSmall)
            Text("+12 dB", color = GraphicEQColors.Muted, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(22.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SurfaceButton(
                text = "旁路",
                modifier = Modifier.weight(1f),
                onClick = {
                    onGainChange(0f)
                }
            )

            SurfaceButton(
                text = "归零",
                modifier = Modifier.weight(1f),
                onClick = onReset
            )

            SurfaceButton(
                text = "完成",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = onDone
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "图形均衡器默认固定频率和 Q 值。这里主要用于精确调整当前频段增益。",
            color = GraphicEQColors.Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DetailReadCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = GraphicEQColors.Card,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = title,
                color = GraphicEQColors.Muted,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = value,
                color = GraphicEQColors.Text,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

private fun colorForGain(gainDB: Float): Color {
    val g = gainDB.coerceIn(-12f, 12f)

    return when {
        g > 0f -> {
            val t = (g / 12f).coerceIn(0f, 1f)
            lerpColor(
                from = GraphicEQColors.Cyan,
                to = GraphicEQColors.Red,
                t = smoothStep(t)
            )
        }

        g < 0f -> {
            val t = (-g / 12f).coerceIn(0f, 1f)
            lerpColor(
                from = GraphicEQColors.Cyan,
                to = GraphicEQColors.DeepBlue,
                t = smoothStep(t)
            )
        }

        else -> GraphicEQColors.Cyan
    }
}

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpColor(
    from: Color,
    to: Color,
    t: Float
): Color {
    val x = t.coerceIn(0f, 1f)

    return Color(
        red = from.red + (to.red - from.red) * x,
        green = from.green + (to.green - from.green) * x,
        blue = from.blue + (to.blue - from.blue) * x,
        alpha = from.alpha + (to.alpha - from.alpha) * x
    )
}

private fun formatGain(gainDB: Float): String {
    val g = gainDB.coerceIn(-12f, 12f)
    return if (g >= 0f) {
        "+%.1f dB".format(g)
    } else {
        "%.1f dB".format(g)
    }
}

private fun formatGainCompact(gainDB: Float): String {
    val rounded = (gainDB * 10f).roundToInt() / 10f
    return when {
        abs(rounded) < 0.05f -> "0"
        rounded > 0f -> "+%.1f".format(rounded)
        else -> "%.1f".format(rounded)
    }
}

private fun formatFrequency(freq: Float): String {
    return if (freq >= 1000f) {
        val k = freq / 1000f
        if (abs(k - k.roundToInt()) < 0.05f) {
            "${k.roundToInt()}k"
        } else {
            "%.1fk".format(k)
        }
    } else {
        freq.roundToInt().toString()
    }
}

private fun qForGraphicBand(count: Int): Float {
    val safeCount = count.coerceIn(10, 40)

    val minFreq = 25f
    val maxFreq = 18000f

    val ratio = (maxFreq / minFreq).pow(
        1f / (safeCount - 1)
    )

    return (
        sqrt(ratio) / (ratio - 1f)
    ).coerceIn(0.5f, 8f)
}

@Composable
private fun GraphicEQQInfoSheet(
    bandCount: Int,
    qValue: Float,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 54.dp, height = 5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF4A4F70))
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "固定 Q 值",
            color = GraphicEQColors.Text,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "%d 段图形均衡器当前 Q ≈ %.2f。图形均衡器默认固定频率和固定 Q，只调整每段增益。".format(bandCount, qValue),
            color = GraphicEQColors.Muted,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))

        SurfaceButton(
            text = "知道了",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDone
        )

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun BypassHelpCard(
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF15172C),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = GraphicEQColors.Cyan.copy(alpha = 0.20f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "旁路说明",
                    color = GraphicEQColors.Text,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    onClick = onDismiss,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "收起",
                        color = GraphicEQColors.Cyan,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "旁路表示暂时跳过当前频段，不改变已经设置好的增益值。重新启用后，原来的增益会恢复。",
                color = GraphicEQColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "归零表示把当前频段的增益直接改为 0 dB，原来的增益值会被清除。",
                color = GraphicEQColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
