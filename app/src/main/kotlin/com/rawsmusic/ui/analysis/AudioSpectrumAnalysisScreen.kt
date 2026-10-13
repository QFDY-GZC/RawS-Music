package com.rawsmusic.ui.analysis

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysisCache
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysisProgress
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.locale.localizedErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioSpectrumAnalysisScreen(song: AudioFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = spectrumUiColors()
    val scope = rememberCoroutineScope()
    var referenceResult by remember(song.path, song.fileSize, song.dateModified) {
        mutableStateOf(SpectrumReferenceResult.NotChecked)
    }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            referenceResult = SpectrumReferenceResult.Checking
            scope.launch { referenceResult = compareSpectrumReference(context, song, uri) }
        }
    }
    var analysis by remember(song.path) { mutableStateOf<AudioSpectrumAnalysis?>(null) }
    var error by remember(song.path) { mutableStateOf<String?>(null) }
    var progress by remember(song.path) { mutableStateOf(AudioSpectrumAnalysisProgress(0f, -120f, -120f)) }
    var expanded by rememberSaveable(song.path) { mutableStateOf(false) }
    var average by rememberSaveable(song.path) { mutableStateOf(false) }
    var logarithmic by rememberSaveable(song.path) { mutableStateOf(false) }
    var channel by rememberSaveable(song.path) { mutableStateOf(SpectrumChannel.Stereo) }
    var cursorX by rememberSaveable(song.path) { mutableFloatStateOf(0.33f) }
    var cursorY by rememberSaveable(song.path) { mutableFloatStateOf(0.45f) }
    val overviewScroll = rememberScrollState()
    val expandedScroll = rememberScrollState()
    BackHandler(expanded) { expanded = false }

    LaunchedEffect(song.path, song.fileSize, song.dateModified) {
        analysis = null
        error = null
        try {
            analysis = withContext(Dispatchers.IO) {
                AudioSpectrumAnalysisCache.loadOrAnalyze(context, song) { progress = it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.localizedErrorMessage(
                rawMessage = failure.message, fallbackRes = R.string.audio_spectrum_unknown_error)
        }
    }
    // Lives above both layouts: fullscreen reuses the analysis and rendered bitmap.
    val bitmap by produceState<ImageBitmap?>(null, analysis, channel, logarithmic) {
        value = null
        val source = analysis ?: return@produceState
        value = withContext(Dispatchers.Default) { renderSpectrumBitmap(source, channel, logarithmic) }
    }
    val quality by produceState<SpectrumQualityReport?>(null, analysis, song.sampleRate, song.channelCount,
        song.bitsPerSample, song.format, song.encodingFormat, song.isDsdSourceFile()) {
        value = null
        val source = analysis ?: return@produceState
        value = withContext(Dispatchers.Default) {
            evaluateSpectrumQuality(source, song.sampleRate, song.channelCount,
                song.isDsdSourceFile(), song.bitsPerSample.takeIf { isKnownLosslessSpectrumSource(song) } ?: 0)
        }
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = colors.accent, background = colors.background, surface = colors.surface,
        onSurface = colors.text, onBackground = colors.text, onPrimary = Color.White,
        onSurfaceVariant = colors.muted, surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface, outline = colors.line
    )) {
      CompositionLocalProvider(LocalContentColor provides colors.text) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
            val chartHeight = if (expanded) (maxHeight - 64.dp).coerceAtLeast(280.dp)
                else (maxHeight - 342.dp).coerceAtLeast(260.dp)
            Column(Modifier.fillMaxSize().verticalScroll(if (expanded) expandedScroll else overviewScroll)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (expanded) expanded = false else onBack() }) {
                        Icon(if (expanded) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            stringResource(if (expanded) R.string.spectrum_close_chart else R.string.spectrum_back))
                    }
                    Text(stringResource(R.string.spectrum_heading), Modifier.weight(1f), fontSize = 18.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (expanded && analysis != null) {
                        ChartControls(channel, logarithmic, average, analysis!!.channels,
                            { channel = it }, { logarithmic = it })
                    }
                }
                if (!expanded) {
                    SongHeader(song)
                    analysis?.let { Metrics(it) }
                }
                val result = analysis
                if (result == null) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(24.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (error == null) {
                            InfiniteProgressIndicator(color = colors.accent, size = 36.dp)
                            Spacer(Modifier.height(20.dp))
                        }
                        Text(error ?: stringResource(R.string.audio_spectrum_loading), color = colors.muted)
                        if (error == null) {
                            Spacer(Modifier.height(12.dp))
                            Text("${(progress.fraction * 100).roundToInt()}%", color = colors.muted)
                        }
                    }
                } else {
                    if (!expanded) {
                        SpectrumTabs(average) { average = it }
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ChartControls(channel, logarithmic, average, result.channels,
                                { channel = it }, { logarithmic = it })
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(8.dp)) {
                                ExpandIcon()
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.spectrum_fullscreen), color = colors.accent, fontSize = 12.sp)
                            }
                        }
                    }
                    SpectrumPlot(result, bitmap, quality, average, logarithmic, channel, cursorX, cursorY,
                        { x, y -> cursorX = x; cursorY = y },
                        Modifier.fillMaxWidth().height(chartHeight).padding(start = 12.dp, end = 20.dp, bottom = 12.dp))
                    if (!expanded) quality?.let { report ->
                        SpectrumQualityCard(song, result, report, referenceResult,
                            onChooseReference = { referencePicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp))
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun SongHeader(song: AudioFile) {
    val colors = spectrumUiColors()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface)) {
            if (song.coverKey.isNotBlank()) BitmapImage(key = song.coverKey,
                contentDescription = song.displayName, modifier = Modifier.fillMaxSize(),
                targetWidth = 256, targetHeight = 256, surface = ArtworkSurface.Playback, fadeInMillis = 200)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(song.displayName, fontSize = 21.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 14.sp, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Metrics(analysis: AudioSpectrumAnalysis) {
    val colors = spectrumUiColors()
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val items = listOf(
        stringResource(R.string.spectrum_mean_level) to db(analysis.stereoAverageDb, "dBFS"),
        stringResource(R.string.spectrum_peak_level) to db(analysis.stereoPeakDb, "dBFS"),
        stringResource(R.string.spectrum_bandwidth) to frequency(analysis.cutoffHz),
        stringResource(R.string.spectrum_duration) to time(analysis.durationMs)
    )
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { index, item ->
                SpectrumDetailCapsule("${item.first} · ${item.second}", selected == index) {
                    selected = if (selected == index) -1 else index
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(selected in items.indices) {
            Text(stringResource(when (selected) {
                0 -> R.string.spectrum_ui_mean_help
                1 -> R.string.spectrum_ui_peak_help
                2 -> R.string.spectrum_ui_band_help
                else -> R.string.spectrum_ui_duration_help
            }), Modifier.fillMaxWidth().padding(top = 8.dp), color = colors.muted,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SpectrumTabs(average: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = spectrumUiColors()
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)
        .fillMaxWidth().clip(RoundedCornerShape(50)).border(1.dp, colors.line, RoundedCornerShape(50))) {
        listOf(false, true).forEach { value ->
            val emphasis by animateFloatAsState(if (value == average) 1f else 0f, tween(180), label = "spectrum_tab")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50))
                .background(colors.accent.copy(alpha = emphasis * 0.12f))
                .border(1.dp, colors.accent.copy(alpha = emphasis * 0.5f), RoundedCornerShape(50))
                .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onSelect(value) }
                .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (value) R.string.audio_spectrum_average else R.string.spectrum_waterfall_tab),
                    fontSize = 13.sp, color = if (value == average) colors.accent else colors.muted)
            }
        }
    }
}

@Composable
private fun ChartControls(channel: SpectrumChannel, log: Boolean, average: Boolean, channels: Int,
    onChannel: (SpectrumChannel) -> Unit, onAxis: (Boolean) -> Unit) {
    val channelLabels = listOf(stringResource(R.string.spectrum_stereo),
        stringResource(R.string.audio_spectrum_left), stringResource(R.string.audio_spectrum_right))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!average && channels > 1) Selector(channelLabels, channel.ordinal) { onChannel(SpectrumChannel.entries[it]) }
        Selector(listOf(stringResource(R.string.spectrum_log), stringResource(R.string.spectrum_linear)),
            if (log) 0 else 1) { onAxis(it == 0) }
    }
}

@Composable
private fun Selector(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = spectrumUiColors()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, colors.line, RoundedCornerShape(50))
            .clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(options[selected], fontSize = 12.sp, color = colors.text)
            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(14.dp), tint = colors.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label, color = colors.text) }, onClick = { onSelect(index); open = false })
            }
        }
    }
}

@Composable
private fun ExpandIcon() {
    val colors = spectrumUiColors()
    Canvas(Modifier.size(20.dp)) {
        val inset = 3.dp.toPx()
        val arm = 4.dp.toPx()
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val x = if (right) size.width - inset else inset
            val y = if (bottom) size.height - inset else inset
            drawLine(colors.accent, Offset(x, y), Offset(x + if (right) -arm else arm, y), 1.5.dp.toPx())
            drawLine(colors.accent, Offset(x, y), Offset(x, y + if (bottom) -arm else arm), 1.5.dp.toPx())
        }
    }
}

@Composable
private fun SpectrumPlot(analysis: AudioSpectrumAnalysis, bitmap: ImageBitmap?, report: SpectrumQualityReport?, average: Boolean,
    log: Boolean, channel: SpectrumChannel, cursorX: Float, cursorY: Float,
    onCursor: (Float, Float) -> Unit, modifier: Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val colors = spectrumUiColors()
    val cutoff = analysis.chartCutoff(report?.bandEdge)
    val cutoffText = cutoff?.let { stringResource(R.string.spectrum_ui_cutoff, frequency(it)) }
    val ticks = remember(analysis, log) {
        if (log) (listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f,
            10000f, 20000f, 50000f, 100000f, 200000f) + analysis.maxFrequencyHz)
            .filter { it <= analysis.maxFrequencyHz }.distinct().sorted()
        else (0..4).map { analysis.maxFrequencyHz * it / 4f }
    }
    val density = LocalDensity.current
    val labelSize = with(density) { 10.sp.toPx() }
    val gutter = remember(ticks, labelSize, density) {
        paint.textSize = labelSize
        with(density) { ((ticks.maxOfOrNull { paint.measureText(frequency(it)) } ?: 0f) +
            12.dp.toPx()).toDp().coerceAtLeast(48.dp) }
    }
    val plotTop = with(density) { labelSize.toDp() / 2 + 8.dp }
    val plotBottom = with(density) { labelSize.toDp() + 16.dp }
    Column(modifier) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Canvas(Modifier.fillMaxSize()) {
            val left = gutter.toPx()
            val top = plotTop.toPx()
            val width = (size.width - left).coerceAtLeast(1f)
            val height = (size.height - top - plotBottom.toPx()).coerceAtLeast(1f)
            paint.textSize = 10.sp.toPx()
            paint.color = colors.muted.toArgb()
            fun label(text: String, x: Float, y: Float, align: Paint.Align = Paint.Align.LEFT) {
                paint.textAlign = align
                drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
            }
            val chartText = if (average) colors.text else Color(0xffeef3ff)
            val grid = if (average) colors.line else Color.White.copy(alpha = 0.16f)
            drawRect(if (average) colors.surface else Color(0xff080e17), Offset(left, top), Size(width, height))
            if (!average && bitmap != null) drawImage(bitmap,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium)
            if (average) {
                for (step in 0..6) {
                    val y = top + height * step / 6f
                    drawLine(grid, Offset(left, y), Offset(left + width, y), 1f)
                    label("${-20 * step}", left - 6.dp.toPx(), y + paint.textSize / 3f, Paint.Align.RIGHT)
                }
                val path = Path()
                val values = analysis.averageSpectrumDb
                val steps = width.roundToInt().coerceAtLeast(1)
                for (x in 0..steps) {
                    val hz = spectrumFrequency(x.toFloat() / steps, analysis.maxFrequencyHz, log)
                    val bin = (hz / analysis.maxFrequencyHz * values.lastIndex).coerceAtLeast(0f)
                    val index = bin.toInt().coerceAtMost(values.lastIndex.coerceAtLeast(0))
                    val a = values.getOrElse(index) { -120f }
                    val b = values.getOrElse(index + 1) { a }
                    val value = a + (b - a) * (bin - index)
                    val px = left + width * x / steps
                    val py = top + height * (-(value.coerceIn(-120f, 0f)) / 120f)
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, colors.accent, style = Stroke(1.5.dp.toPx()))
                var previousRight = Float.NEGATIVE_INFINITY
                ticks.forEachIndexed { index, hz ->
                    val t = spectrumFraction(hz, analysis.maxFrequencyHz, log)
                    val text = frequency(hz)
                    val half = paint.measureText(text) / 2
                    val x = (left + width * t).coerceIn(left + half, (left + width - half).coerceAtLeast(left + half))
                    if (x - half >= previousRight + 8.dp.toPx() || index == ticks.lastIndex) {
                        // Reserve the final Nyquist label; intermediate ticks never collide with it.
                        val finalLeft = left + width - paint.measureText(frequency(analysis.maxFrequencyHz))
                        if (index == ticks.lastIndex || x + half < finalLeft - 8.dp.toPx()) {
                            drawLine(grid, Offset(left + width * t, top), Offset(left + width * t, top + height), 1f)
                            label(text, x, top + height + 20.dp.toPx(), Paint.Align.CENTER)
                            previousRight = x + half
                        }
                    }
                }
            } else {
                var previousBottom = Float.NEGATIVE_INFINITY
                ticks.asReversed().forEach { hz ->
                    val y = top + height * (1f - spectrumFraction(hz, analysis.maxFrequencyHz, log))
                    if (y - paint.textSize / 2 >= previousBottom + 4.dp.toPx()) {
                        drawLine(grid, Offset(left, y), Offset(left + width, y), 1f)
                        label(frequency(hz), left - 6.dp.toPx(), y + paint.textSize / 3f, Paint.Align.RIGHT)
                        previousBottom = y + paint.textSize / 2
                    }
                }
                for (step in 0..2) {
                    val t = step / 2f
                    label(time((analysis.durationMs * t).toLong()), left + width * t,
                        top + height + 20.dp.toPx(), when(step) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER })
                }
            }
            var cutoffLabelRect: Rect? = null
            cutoff?.let { hz ->
                val t = spectrumFraction(hz, analysis.maxFrequencyHz, log)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                if (average) drawLine(colors.accent, Offset(left + width * t, top),
                    Offset(left + width * t, top + height), 1.5.dp.toPx(), pathEffect = dash)
                else drawLine(Color(0xff9ac3ff), Offset(left, top + height * (1 - t)),
                    Offset(left + width, top + height * (1 - t)), 1.5.dp.toPx(), pathEffect = dash)
                val markerColor = if (average) colors.accent else Color(0xffa8ccff)
                val pad = 6.dp.toPx()
                val markerWidth = (paint.measureText(cutoffText.orEmpty()) + pad * 2).coerceAtMost(width)
                val markerHeight = paint.descent() - paint.ascent() + pad * 2
                val lineX = left + width * t
                val lineY = top + height * (1 - t)
                val markerX = if (average) {
                    (if (lineX + markerWidth + pad < left + width) lineX + pad else lineX - markerWidth - pad)
                        .coerceIn(left, (left + width - markerWidth).coerceAtLeast(left))
                } else left + width - markerWidth - pad
                val markerY = if (average) top + pad else {
                    (if (lineY - markerHeight - pad >= top) lineY - markerHeight - pad else lineY + pad)
                        .coerceIn(top, (top + height - markerHeight).coerceAtLeast(top))
                }
                cutoffLabelRect = Rect(markerX, markerY, markerX + markerWidth, markerY + markerHeight)
                drawRoundRect(if (average) colors.background.copy(alpha = 0.96f) else Color(0xf0152237),
                    Offset(markerX, markerY), Size(markerWidth, markerHeight),
                    androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                paint.color = markerColor.toArgb()
                label(cutoffText.orEmpty(), markerX + pad, markerY + pad - paint.ascent())
                paint.color = colors.muted.toArgb()
            }
            val x = left + width * cursorX
            val hz = spectrumFrequency(if (average) cursorX else 1f - cursorY, analysis.maxFrequencyHz, log)
            val frame = (cursorX * (analysis.waterfallFrames - 1)).roundToInt().coerceAtLeast(0)
            val value = if (average) analysis.averageSpectrumDb.getOrElse(
                (hz / analysis.maxFrequencyHz * analysis.averageSpectrumDb.lastIndex).roundToInt().coerceAtLeast(0)) { -120f }
                else analysis.waterfallDb(frame, hz, channel)
            val y = top + height * if (average) (-value / 120f).coerceIn(0f, 1f) else cursorY
            drawLine(chartText.copy(alpha = 0.7f), Offset(x, top), Offset(x, top + height), 1.dp.toPx())
            drawCircle(chartText, 3.dp.toPx(), Offset(x, y))
            val readout = (if (average) "" else time((analysis.durationMs * cursorX).toLong()) + " · ") +
                frequency(hz) + " · ≈" + db(value, "dB")
            val labelWidth = (paint.measureText(readout) + 16.dp.toPx()).coerceAtMost(width)
            val labelHeight = paint.textSize + 12.dp.toPx()
            val labelX = (x + 10.dp.toPx()).coerceIn(left, (left + width - labelWidth).coerceAtLeast(left))
            var labelY = (y - labelHeight / 2).coerceIn(top, (top + height - labelHeight).coerceAtLeast(top))
            cutoffLabelRect?.let { marker ->
                if (Rect(labelX, labelY, labelX + labelWidth, labelY + labelHeight).overlaps(marker)) {
                    labelY = (if (marker.bottom + labelHeight + 6.dp.toPx() <= top + height)
                        marker.bottom + 6.dp.toPx() else marker.top - labelHeight - 6.dp.toPx())
                        .coerceIn(top, (top + height - labelHeight).coerceAtLeast(top))
                }
            }
            drawRoundRect(if (average) colors.background.copy(alpha = 0.96f) else Color(0xee11171d), Offset(labelX, labelY), Size(labelWidth, labelHeight),
                androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
            paint.color = chartText.toArgb()
            label(readout, labelX + 8.dp.toPx(), labelY + 6.dp.toPx() - paint.ascent())
        }
        Box(Modifier.fillMaxSize().padding(start = gutter, top = plotTop, bottom = plotBottom)
            .pointerInput(analysis, average) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun select(position: Offset) = onCursor((position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        (position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                    down.consume()
                    select(down.position)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) { select(change.position); change.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
        if (bitmap == null && !average) InfiniteProgressIndicator(
            modifier = Modifier.align(Alignment.Center), size = 24.dp, color = colors.accent)
      }
        if (!average) SpectrumColorLegend(Modifier.padding(start = gutter, top = 8.dp))
    }
}

@Composable
private fun SpectrumColorLegend(modifier: Modifier = Modifier) {
    val colors = spectrumUiColors()
    val stops = remember { spectrumColors().filterIndexed { index, _ -> index % 32 == 0 || index == 255 }.map { Color(it) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.spectrum_ui_legend), color = colors.muted, fontSize = 10.sp, lineHeight = 14.sp)
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(stops)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−120", "−60", "0").forEach { Text(it, color = colors.muted, fontSize = 10.sp, lineHeight = 14.sp) }
        }
    }
}

private fun time(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun db(value: Float, unit: String): String = if (!value.isFinite() || value <= -119f) "−∞ $unit"
    else String.format(Locale.ROOT, "%.1f %s", value, unit)
private fun frequency(hz: Float): String = if (hz >= 1000f) String.format(Locale.ROOT, "%.1f kHz", hz / 1000f)
    else "${hz.roundToInt()} Hz"
