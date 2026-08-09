package com.rawsmusic.ui.analysis

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysisCache
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysisProgress
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private sealed interface SpectrumScreenState {
    data object Loading : SpectrumScreenState
    data class Ready(val analysis: AudioSpectrumAnalysis) : SpectrumScreenState
    data class Failed(val message: String) : SpectrumScreenState
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioSpectrumAnalysisScreen(
    song: AudioFile,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val pagerState = rememberPagerState(pageCount = { 2 })
    var screenState by remember(song.path) { mutableStateOf<SpectrumScreenState>(SpectrumScreenState.Loading) }
    var progress by remember(song.path) { mutableStateOf(AudioSpectrumAnalysisProgress(0f, -120f, -120f)) }

    LaunchedEffect(song.path, song.fileSize, song.dateModified) {
        screenState = SpectrumScreenState.Loading
        progress = AudioSpectrumAnalysisProgress(0f, -120f, -120f)
        runCatching {
            withContext(Dispatchers.IO) {
                AudioSpectrumAnalysisCache.loadOrAnalyze(context, song) { update ->
                    progress = update
                }
            }
        }.onSuccess { analysis ->
            screenState = SpectrumScreenState.Ready(analysis)
        }.onFailure { error ->
            screenState = SpectrumScreenState.Failed(error.message ?: "Unknown analysis error")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        SpectrumSongHeader(
            song = song,
            state = screenState,
            progress = progress,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
        )

        when (val state = screenState) {
            SpectrumScreenState.Loading -> {
                LoadingAnalysisBody(progress = progress, modifier = Modifier.fillMaxSize())
            }
            is SpectrumScreenState.Failed -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.audio_spectrum_error), fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, fontSize = 13.sp, color = scheme.onSurfaceVariantSummary)
                }
            }
            is SpectrumScreenState.Ready -> {
                var selectedFrame by remember(state.analysis) { mutableIntStateOf(0) }
                val leftDb = progress.leftDb.takeIf { it > -119f }
                    ?: state.analysis.leftLevelsDb.getOrNull(selectedFrame) ?: -120f
                val rightDb = progress.rightDb.takeIf { it > -119f }
                    ?: state.analysis.rightLevelsDb.getOrNull(selectedFrame) ?: -120f
                Column(modifier = Modifier.fillMaxSize()) {
                    StereoVolumeMeters(
                        leftDb = leftDb,
                        rightDb = rightDb,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(2) { page ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(if (pagerState.currentPage == page) 8.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (pagerState.currentPage == page) scheme.primary
                                        else scheme.onSurface.copy(alpha = 0.25f)
                                    )
                            )
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        beyondViewportPageCount = 0
                    ) { page ->
                        if (page == 0) {
                            WaterfallSpectrumPage(
                                analysis = state.analysis,
                                selectedFrame = selectedFrame,
                                onFrameSelected = { selectedFrame = it }
                            )
                        } else {
                            AverageSpectrumPage(
                                analysis = state.analysis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpectrumSongHeader(
    song: AudioFile,
    state: SpectrumScreenState,
    progress: AudioSpectrumAnalysisProgress,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh)
        ) {
            if (song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = song.displayName,
                    modifier = Modifier.fillMaxSize(),
                    targetWidth = 256,
                    targetHeight = 256,
                    surface = ArtworkSurface.Playback,
                    fadeInMillis = 200
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.displayName, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.sp,
                color = scheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val metrics = when (state) {
                is SpectrumScreenState.Ready -> listOf(
                    metric(stringResource(R.string.audio_spectrum_peak), formatDb(state.analysis.stereoPeakDb)),
                    metric(stringResource(R.string.audio_spectrum_average_level), formatDb(state.analysis.stereoAverageDb)),
                    metric(stringResource(R.string.audio_spectrum_cutoff), formatFrequency(state.analysis.cutoffHz))
                )
                else -> listOf(
                    metric(stringResource(R.string.audio_spectrum_peak), formatDb(max(progress.leftDb, progress.rightDb))),
                    metric(stringResource(R.string.audio_spectrum_average_level), formatDb((progress.leftDb + progress.rightDb) / 2f)),
                    metric(stringResource(R.string.audio_spectrum_cutoff), "--")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { (label, value) ->
                    Text(
                        text = "$label $value",
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariantSummary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun metric(label: String, value: String): Pair<String, String> = label to value

@Composable
private fun LoadingAnalysisBody(
    progress: AudioSpectrumAnalysisProgress,
    modifier: Modifier
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.audio_spectrum_loading), fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LevelBar(stringResource(R.string.audio_spectrum_left), progress.leftDb, scheme.primary)
            LevelBar(stringResource(R.string.audio_spectrum_right), progress.rightDb, scheme.primary)
        }
    }
}

@Composable
private fun AverageSpectrumPage(
    analysis: AudioSpectrumAnalysis
) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize().padding(end = 28.dp, bottom = 28.dp, top = 2.dp)) {
                    val plotWidth = size.width
                    val plotHeight = size.height
                    for (i in 0..6) {
                        val y = plotHeight * i / 6f
                        drawLine(scheme.onSurface.copy(alpha = 0.12f), Offset(0f, y), Offset(plotWidth, y), 1f)
                    }
                    val path = Path()
                    analysis.averageSpectrumDb.forEachIndexed { index, value ->
                        val x = plotWidth * index / max(1, analysis.averageSpectrumDb.lastIndex).toFloat()
                        val y = plotHeight * (1f - ((value + 120f) / 120f).coerceIn(0f, 1f))
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = scheme.primary, style = Stroke(width = 2.5f))
                }
                Column(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("0", "-20", "-40", "-60", "-80", "-100", "-120").forEach {
                        Text(it, fontSize = 9.sp, color = scheme.onSurfaceVariantSummary)
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf(0f, .25f, .5f, .75f, 1f).forEach { fraction ->
                        Text(formatFrequency(analysis.maxFrequencyHz * fraction), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WaterfallSpectrumPage(
    analysis: AudioSpectrumAnalysis,
    selectedFrame: Int,
    onFrameSelected: (Int) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val leftBitmap = remember(analysis) {
        createWaterfallBitmap(analysis, analysis.leftWaterfall)
    }
    val rightBitmap = remember(analysis) {
        createWaterfallBitmap(analysis, analysis.rightWaterfall)
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.width(34.dp).fillMaxHeight().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    frequencyLabels(analysis.maxFrequencyHz).forEach { label ->
                        Text(label, fontSize = 9.sp, color = scheme.onSurfaceVariantSummary)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(analysis.waterfallFrames) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onFrameSelected(
                                        (fraction * (analysis.waterfallFrames - 1))
                                            .roundToInt()
                                            .coerceIn(0, analysis.waterfallFrames - 1)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onFrameSelected(
                                        (fraction * (analysis.waterfallFrames - 1))
                                            .roundToInt()
                                            .coerceIn(0, analysis.waterfallFrames - 1)
                                    )
                                }
                            )
                        }
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            androidx.compose.foundation.Image(
                                bitmap = leftBitmap,
                                contentDescription = stringResource(R.string.audio_spectrum_left),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High
                            )
                            Text(
                                text = "L",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(scheme.background.copy(alpha = 0.85f))
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            androidx.compose.foundation.Image(
                                bitmap = rightBitmap,
                                contentDescription = stringResource(R.string.audio_spectrum_right),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High
                            )
                            Text(
                                text = "R",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                            )
                        }
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (analysis.waterfallFrames > 1) {
                            val x = size.width * selectedFrame / (analysis.waterfallFrames - 1).toFloat()
                            drawLine(
                                color = Color.White.copy(alpha = 0.65f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(start = 34.dp, end = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            timeLabels(analysis.durationMs).forEach { label ->
                Text(
                    text = label,
                    fontSize = 9.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = scheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun StereoVolumeMeters(
    leftDb: Float,
    rightDb: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        HorizontalVolumeMeter(label = "L", db = leftDb)
        HorizontalVolumeMeter(label = "R", db = rightDb)
    }
}

@Composable
private fun HorizontalVolumeMeter(
    label: String,
    db: Float,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    val targetNormalized = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val normalized by animateFloatAsState(
        targetValue = targetNormalized,
        animationSpec = tween(durationMillis = 140),
        label = "horizontal_volume_meter"
    )
    val displayedDb = -60f + normalized * 60f
    val meterColor = Color.hsv(
        hue = 120f - normalized * 120f,
        saturation = 0.82f,
        value = 0.94f
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = scheme.onSurface, modifier = Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(scheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalized)
                    .fillMaxHeight()
                    .background(meterColor)
            )
        }
        Text(
            text = formatDb(displayedDb),
            fontSize = 9.sp,
            color = scheme.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.width(52.dp).padding(start = 4.dp)
        )
    }
}

@Composable
private fun LevelBar(label: String, db: Float, color: Color) {
    val scheme = MiuixTheme.colorScheme
    val targetNormalized = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val normalized by animateFloatAsState(
        targetValue = targetNormalized,
        animationSpec = tween(durationMillis = 140),
        label = "level_bar"
    )
    val displayedDb = -60f + normalized * 60f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = scheme.onSurfaceVariantSummary)
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(62.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(scheme.onSurface.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(normalized)
                    .align(Alignment.BottomCenter)
                    .background(color)
            )
        }
        Text(formatDb(displayedDb), fontSize = 9.sp, color = scheme.onSurfaceVariantSummary)
    }
}

private fun createWaterfallBitmap(
    analysis: AudioSpectrumAnalysis,
    source: ByteArray
): androidx.compose.ui.graphics.ImageBitmap {
    // The native payload is stored as [time][frequency]. The chart is intentionally
    // rendered as [time -> X][frequency -> Y] so the time axis stays horizontal.
    val width = analysis.waterfallFrames
    val height = analysis.waterfallBins
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val frequencyBin = height - 1 - y
            val value = source[x * analysis.waterfallBins + frequencyBin].toInt() and 0xff
            // Keep quiet details visible without flattening the loudest bins.
            val intensity = (value / 255f).pow(0.48f).coerceIn(0f, 1f)
            val color = Color.hsv(
                hue = 250f - intensity * 250f,
                saturation = (0.78f + intensity * 0.22f).coerceIn(0f, 1f),
                value = (0.16f + intensity * 0.84f).coerceIn(0f, 1f)
            )
            pixels[y * width + x] = color.toArgb()
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun frequencyLabels(maxFrequency: Float): List<String> =
    (0..8).map { formatAxisFrequency(maxFrequency * it / 8f) }.reversed()

private fun formatAxisFrequency(value: Float): String = when {
    !value.isFinite() || value <= 0f -> "0"
    value >= 1000f -> "%.1fk".format(value / 1000f)
    else -> "${value.roundToInt()}"
}

private fun timeLabels(durationMs: Long): List<String> {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val fullMinutes = safeDuration / 60_000L
    val labels = (0L..fullMinutes)
        .map { minute -> formatTimeAxisMs(minute * 60_000L) }
        .toMutableList()
    val endLabel = formatTimeAxisMs(safeDuration)
    if (labels.lastOrNull() != endLabel) labels += endLabel
    return labels
}

private fun formatTimeAxisMs(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatDb(value: Float): String =
    if (!value.isFinite() || value <= -119f) "-inf" else "%.1f dB".format(value)

private fun formatFrequency(value: Float): String = when {
    !value.isFinite() || value <= 0f -> "0 Hz"
    value >= 1000f -> "%.1f kHz".format(value / 1000f)
    else -> "${value.roundToInt()} Hz"
}
