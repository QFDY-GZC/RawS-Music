package com.rawsmusic.core.ui.widget.export

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.RangeSlider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class LyricShareMode { VIDEO, POSTER }

private sealed interface ShareTaskState {
    data object Idle : ShareTaskState
    data class Working(val progress: Float?) : ShareTaskState
    data class Done(val file: File, val poster: Bitmap?, val savedTo: String?) : ShareTaskState
    data class Failed(val reason: String) : ShareTaskState
}

private val SHORT_EDGES = listOf(720, 1080, 1440, 2160)
private val RESOLUTION_LABELS = listOf("720p", "1080p", "2K", "4K")
private const val LEAD_IN_MS = 600L
private const val TAIL_OUT_MS = 800L

/**
 * Lyric video / lyric poster exporter entry. [mode] null keeps the dialog hidden.
 *
 * Video uses a continuous range; poster allows any six lines in song order.
 */
@Composable
fun LyricShareDialog(
    mode: LyricShareMode?,
    currentSong: AudioFile?,
    lyricSong: Song?,
    coverKey: String?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    onDismiss: () -> Unit,
) {
    val visible = mode != null && currentSong != null
    if (!visible) return
    val activeMode = requireNotNull(mode)
    val song = requireNotNull(currentSong)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MiuixTheme.colorScheme
    val backgroundArgb = scheme.background.toArgb()

    val lines: List<RichLyricLine> = remember(lyricSong) {
        lyricSong?.lyrics.orEmpty().filter { !it.text.isNullOrBlank() && it.end > it.begin }
    }
    val hasLyrics = lines.isNotEmpty()
    val songDurationMs = song.duration.coerceAtLeast(lines.lastOrNull()?.end ?: 0L)

    var rangeStart by remember(lines) { mutableIntStateOf(0) }
    var rangeEnd by remember(lines, activeMode) {
        mutableIntStateOf(
            if (activeMode == LyricShareMode.POSTER) minOf(lines.lastIndex, 3) else lines.lastIndex
        )
    }
    var awaitingEnd by remember(lines) { mutableStateOf(false) }
    var posterSelection by remember(lines, song.path) {
        mutableStateOf((0 until minOf(lines.size, LyricPosterPolicy.MAX_LINES)).toSet())
    }
    var timeRange by remember(song.path) {
        mutableStateOf(0f..(minOf(songDurationMs, 30_000L).toFloat()))
    }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var resolutionIndex by remember { mutableIntStateOf(1) }
    var fps by remember { mutableIntStateOf(30) }
    var codecIndex by remember { mutableIntStateOf(0) }
    var task by remember(activeMode, song.path) { mutableStateOf<ShareTaskState>(ShareTaskState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = task is ShareTaskState.Working

    val codecs = remember { LyricVideoCodec.entries.filter { MediaCodecListHolder.encoderFor(it.mimeType) != null } }
    val aspect = LyricVideoAspect.entries[aspectIndex]
    val videoSize = remember(aspectIndex, resolutionIndex) { lyricVideoSizeFor(aspect, SHORT_EDGES[resolutionIndex]) }
    val codec = codecs.getOrNull(codecIndex) ?: LyricVideoCodec.H264
    val supported = remember(codec, videoSize, fps) {
        isVideoSizeSupported(codec, videoSize.width, videoSize.height, fps)
    }

    val (windowStartMs, windowEndMs) = if (hasLyrics) {
        val first = lines[minOf(rangeStart, rangeEnd).coerceIn(0, lines.lastIndex)]
        val last = lines[maxOf(rangeStart, rangeEnd).coerceIn(0, lines.lastIndex)]
        (first.begin - LEAD_IN_MS).coerceAtLeast(0L) to
            (last.end + TAIL_OUT_MS).coerceAtMost(songDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
    } else {
        timeRange.start.toLong() to timeRange.endInclusive.toLong()
    }

    // Keep the screen awake and block the dialog from closing while a job is running.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(busy, activity) {
        if (busy) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun selectedLines(): List<RichLyricLine> {
        if (!hasLyrics) return emptyList()
        val from = minOf(rangeStart, rangeEnd).coerceIn(0, lines.lastIndex)
        val to = maxOf(rangeStart, rangeEnd).coerceIn(0, lines.lastIndex)
        return lines.subList(from, to + 1)
    }

    fun startVideoExport() {
        val host = activity ?: return
        job?.cancel()
        job = scope.launch {
            task = ShareTaskState.Working(0f)
            val cover = loadLyricExportArtwork(context, coverKey, videoSize)
            val trimmed = if (hasLyrics) lyricSong?.copy(lyrics = selectedLines()) else null
            val output = lyricExportCacheFile(context, song.displayName, "mp4")
            val result = try {
                exportLyricVideo(
                    activity = host,
                    request = LyricVideoRequest(
                        audioPath = song.path,
                        startMs = windowStartMs,
                        endMs = windowEndMs,
                        lyricSong = trimmed,
                        title = song.displayName,
                        artist = song.artist,
                        coverKey = coverKey,
                        cover = cover,
                        size = videoSize,
                        frameRate = fps,
                        codec = codec,
                        displayTranslation = displayTranslation,
                        displayRoma = displayRoma,
                        simpleBackgroundArgb = backgroundArgb,
                        outputFile = output,
                        signature = LyricPosterPolicy.signature(AppPreferences.Lyrics.exportSignature),
                    ),
                    onProgress = { progress -> task = ShareTaskState.Working(progress) },
                )
            } catch (cancel: CancellationException) {
                task = ShareTaskState.Idle
                throw cancel
            }
            task = when (result) {
                is LyricExportResult.Success -> ShareTaskState.Done(
                    file = result.file,
                    poster = null,
                    savedTo = saveExportToGallery(context, result.file, isVideo = true),
                )
                is LyricExportResult.Failed -> ShareTaskState.Failed(result.reason)
                LyricExportResult.Cancelled -> ShareTaskState.Idle
            }
        }
    }

    fun startPoster() {
        val host = activity ?: return
        job?.cancel()
        job = scope.launch {
            task = ShareTaskState.Working(null)
            val cover = loadLyricExportArtwork(context, coverKey, LyricVideoSize(1080, 1440))
            val posterLines = LyricPosterPolicy.ordered(posterSelection).mapNotNull { lines.getOrNull(it) }.map { line ->
                LyricPosterLine(
                    text = line.text.orEmpty(),
                    translation = line.translation?.takeIf { displayTranslation },
                )
            }
            val bitmap = runCatching {
                renderLyricPoster(
                    activity = host,
                    request = LyricPosterRequest(
                        lines = posterLines,
                        title = song.displayName,
                        artist = song.artist,
                        coverKey = coverKey,
                        cover = cover,
                        size = LyricVideoSize(1080, 1440),
                        simpleBackgroundArgb = backgroundArgb,
                        backgroundTimeSeconds = windowStartMs / 1000f,
                        signature = LyricPosterPolicy.signature(AppPreferences.Lyrics.exportSignature),
                    ),
                )
            }.getOrNull()
            task = if (bitmap == null) {
                ShareTaskState.Failed("render")
            } else {
                val file = writePosterPng(bitmap, lyricExportCacheFile(context, song.displayName, "png"))
                ShareTaskState.Done(file = file, poster = bitmap, savedTo = null)
            }
        }
    }

    val title = stringResource(
        if (activeMode == LyricShareMode.VIDEO) R.string.lyric_share_video_title else R.string.lyric_share_poster_title
    )
    RawMiuixOverlayDialog(
        show = true,
        title = title,
        summary = stringResource(
            if (activeMode == LyricShareMode.VIDEO) R.string.lyric_share_video_summary else R.string.lyric_share_poster_summary
        ),
        onDismissRequest = if (busy) null else onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            when (val state = task) {
                is ShareTaskState.Done -> {
                    ShareDoneContent(state)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            text = stringResource(R.string.lyric_share_regenerate),
                            onClick = { task = ShareTaskState.Idle },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.lyric_share_share),
                            onClick = { shareExport(context, state.file, isVideo = activeMode == LyricShareMode.VIDEO) },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            text = stringResource(R.string.lyric_share_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.lyric_share_save),
                            onClick = {
                                scope.launch {
                                    val saved = saveExportToGallery(context, state.file, isVideo = activeMode == LyricShareMode.VIDEO)
                                    task = state.copy(savedTo = saved)
                                }
                            },
                            enabled = state.savedTo == null,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    return@Column
                }
                else -> Unit
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
            if (hasLyrics) {
                Text(
                    text = stringResource(if (activeMode == LyricShareMode.POSTER) R.string.lyric_share_poster_select_hint else R.string.lyric_share_select_hint),
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = minOf(rangeStart, rangeEnd).coerceAtLeast(0))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainer.copy(alpha = 0.6f)),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val selected = if (activeMode == LyricShareMode.POSTER) index in posterSelection else index in minOf(rangeStart, rangeEnd)..maxOf(rangeStart, rangeEnd)
                        Text(
                            text = line.text.orEmpty(),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) scheme.primary else scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainer.copy(alpha = 0f))
                                .clickable(enabled = !busy) {
                                    if (activeMode == LyricShareMode.POSTER) {
                                        posterSelection = LyricPosterPolicy.toggle(posterSelection, index)
                                    } else if (awaitingEnd) {
                                        rangeEnd = index
                                        awaitingEnd = false
                                    } else {
                                        rangeStart = index
                                        rangeEnd = index
                                        awaitingEnd = true
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (activeMode == LyricShareMode.POSTER) stringResource(
                            R.string.lyric_share_poster_selected, posterSelection.size, LyricPosterPolicy.MAX_LINES,
                        ) else stringResource(
                            R.string.lyric_share_selected,
                            maxOf(rangeStart, rangeEnd) - minOf(rangeStart, rangeEnd) + 1,
                            formatClip(windowStartMs, windowEndMs),
                        ),
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    if (activeMode == LyricShareMode.VIDEO) Text(
                        text = stringResource(R.string.lyric_share_select_all),
                        fontSize = 13.sp,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) {
                                rangeStart = 0
                                rangeEnd = lines.lastIndex
                                awaitingEnd = false
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else if (activeMode == LyricShareMode.VIDEO) {
                Text(
                    text = stringResource(R.string.lyric_share_no_lyrics),
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                RangeSlider(
                    value = timeRange,
                    onValueChange = { range ->
                        timeRange = range.start..maxOf(range.endInclusive, range.start + 1_000f)
                    },
                    valueRange = 0f..songDurationMs.coerceAtLeast(1_000L).toFloat(),
                    enabled = !busy,
                )
                Text(
                    text = stringResource(R.string.lyric_share_range, formatMs(windowStartMs), formatMs(windowEndMs)),
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariantSummary,
                )
            }

            if (activeMode == LyricShareMode.VIDEO) {
                Spacer(Modifier.height(10.dp))
                OptionRow(stringResource(R.string.lyric_share_aspect), LyricVideoAspect.entries.map { it.label }, aspectIndex, !busy) {
                    aspectIndex = it
                }
                OptionRow(stringResource(R.string.lyric_share_resolution), RESOLUTION_LABELS, resolutionIndex, !busy) {
                    resolutionIndex = it
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.lyric_share_frame_rate),
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "$fps FPS", fontSize = 13.sp, color = scheme.onSurface)
                }
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { fps = it.roundToInt().coerceIn(30, 144) },
                    valueRange = 30f..144f,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (codecs.size > 1) {
                    OptionRow(stringResource(R.string.lyric_share_codec), codecs.map { it.label }, codecIndex, !busy) {
                        codecIndex = it
                    }
                }
                if (!supported) {
                    Text(
                        text = stringResource(R.string.lyric_share_unsupported),
                        fontSize = 12.sp,
                        color = scheme.error,
                    )
                }
            }
            }

            Spacer(Modifier.height(12.dp))
            when (val state = task) {
                is ShareTaskState.Working -> {
                    LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (state.progress != null) {
                            stringResource(R.string.lyric_share_exporting, (state.progress * 100).toInt())
                        } else {
                            stringResource(R.string.lyric_share_generating_poster)
                        },
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariantSummary,
                    )
                    if (activeMode == LyricShareMode.VIDEO) {
                        Text(
                            text = stringResource(R.string.lyric_share_exporting_hint),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        text = stringResource(R.string.lyric_share_cancel),
                        onClick = {
                            job?.cancel()
                            task = ShareTaskState.Idle
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    (state as? ShareTaskState.Failed)?.let {
                        Text(
                            text = stringResource(R.string.lyric_share_failed, it.reason),
                            fontSize = 13.sp,
                            color = scheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(
                            text = stringResource(R.string.lyric_share_cancel),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(
                                if (activeMode == LyricShareMode.VIDEO) R.string.lyric_share_export else R.string.lyric_share_generate_poster
                            ),
                            enabled = LyricPosterPolicy.canGenerate(visible, hasLyrics) &&
                                activity != null && (activeMode == LyricShareMode.POSTER || supported),
                            onClick = {
                                if (activeMode == LyricShareMode.VIDEO) startVideoExport() else startPoster()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareDoneContent(
    state: ShareTaskState.Done,
) {
    val scheme = MiuixTheme.colorScheme
    state.poster?.let { poster ->
        val image = remember(poster) { poster.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(10.dp))
    }
    val savedTo = state.savedTo
    if (savedTo != null) {
        Text(
            text = stringResource(R.string.lyric_share_saved, savedTo),
            fontSize = 13.sp,
            color = scheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(4.dp))
        TabRowWithContour(
            tabs = options,
            selectedTabIndex = selected.coerceIn(0, options.lastIndex),
            onTabSelected = { if (enabled) onSelect(it) },
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatClip(startMs: Long, endMs: Long): String =
    "${formatMs(startMs)} – ${formatMs(endMs)}"

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
