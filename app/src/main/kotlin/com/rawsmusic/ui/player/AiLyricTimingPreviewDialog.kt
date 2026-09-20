package com.rawsmusic.ui.player

import android.widget.Toast
import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.common.model.LyricTimingChange
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.LyricTimingCorrectionStatus
import com.rawsmusic.core.common.model.LyricWordTimingChange
import com.rawsmusic.core.common.model.stableTimingFingerprint
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.separation.AiLyricTimingCorrectionService
import com.rawsmusic.separation.AiLyricTimingOutputMode
import com.rawsmusic.separation.AiLyricTimingWritebackService
import com.rawsmusic.module.scanner.LyricReader
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AiLyricTimingAnalysisMode {
    VOCAL_ACTIVITY,
    CTC,
}

/**
 * Read-only first step of AI lyric timing correction.
 *
 * Analysis is loaded from the cached vocal activity map. The dialog never changes the active
 * lyric tree until the user explicitly accepts and writes the correction.
 */
@Composable
fun AiLyricTimingPreviewDialog(
    show: Boolean,
    song: AudioFile?,
    lyrics: LyricData,
    lyricsSongKey: String?,
    onDismiss: () -> Unit,
    onApplied: (LyricData) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val songKey = remember(song?.path, song?.duration, song?.cueOffsetMs, song?.cueEndMs, song?.cueTrackIndex) {
        song?.let(::lyricSongKey)
    }
    val suppliedLyricsHash = remember(lyrics) { lyrics.stableTimingFingerprint() }
    var resolvedLyrics by remember(show, songKey) {
        mutableStateOf(LyricData())
    }
    var lyricsLoading by remember(show, songKey) {
        mutableStateOf(false)
    }
    val lyricHash = remember(resolvedLyrics) { resolvedLyrics.stableTimingFingerprint() }
    val hasWordTiming = remember(resolvedLyrics) {
        resolvedLyrics.lines.any { it.words.isNotEmpty() }
    }
    val detectedFormat = when {
        resolvedLyrics.lines.any { it.isTtml } -> R.string.ai_lyric_timing_format_ttml
        hasWordTiming -> R.string.ai_lyric_timing_format_word
        else -> R.string.ai_lyric_timing_format_line
    }
    var correction by remember(show, song?.path, lyricHash) {
        mutableStateOf<LyricTimingCorrection?>(null)
    }
    var outputMode by remember(show, song?.path, lyricHash) {
        mutableStateOf(AiLyricTimingOutputMode.KEEP_CURRENT)
    }
    var analysisMode by remember(show, song?.path, lyricHash) {
        mutableStateOf(AiLyricTimingAnalysisMode.VOCAL_ACTIVITY)
    }
    var analysisGeneration by remember(show, song?.path, lyricHash) { mutableIntStateOf(0) }
    var analysisProgress by remember(show, song?.path, lyricHash) { mutableStateOf(0f) }
    var loading by remember(show, song?.path, lyricHash) { mutableStateOf(false) }
    var busy by remember(show, song?.path, lyricHash) { mutableStateOf(false) }
    var errorText by remember(show, song?.path, lyricHash) { mutableStateOf<String?>(null) }

    // The player lyric state is asynchronous and can still contain the previous song for a
    // short period after a manual switch. Always bind this preview to the selected song and read
    // its lyric tree first; only a matching in-memory tree is allowed as a fallback.
    LaunchedEffect(show, songKey, suppliedLyricsHash, lyricsSongKey) {
        if (!show) return@LaunchedEffect
        val currentSong = song
        if (currentSong == null || songKey == null) {
            resolvedLyrics = LyricData()
            lyricsLoading = false
            return@LaunchedEffect
        }
        lyricsLoading = true
        val parsedLyrics = withContext(Dispatchers.IO) {
            LyricReader.readLyrics(currentSong)
        }
        val matchingMemoryLyrics = if (lyricsSongKey == songKey) lyrics else LyricData()
        resolvedLyrics = parsedLyrics.takeUnless { it.isEmpty } ?: matchingMemoryLyrics
        lyricsLoading = false
    }

    LaunchedEffect(show, songKey, lyricHash) {
        if (!show) return@LaunchedEffect
        correction = null
        errorText = null
    }

    val analysisWordMode = analysisMode == AiLyricTimingAnalysisMode.CTC
    val writebackService = remember(context) {
        AiLyricTimingWritebackService.get(context)
    }
    val enhancedWordReady = analysisWordMode && correction?.let {
        writebackService.canWriteOutput(it, AiLyricTimingOutputMode.ENHANCED_WORD)
    } == true

    LaunchedEffect(analysisWordMode) {
        if (!analysisWordMode && outputMode == AiLyricTimingOutputMode.ENHANCED_WORD) {
            outputMode = AiLyricTimingOutputMode.KEEP_CURRENT
        }
    }

    LaunchedEffect(
        show,
        songKey,
        lyricHash,
        analysisMode,
        analysisGeneration,
        lyricsLoading,
    ) {
        if (!show) return@LaunchedEffect
        correction = null
        errorText = null
        analysisProgress = 0f
        val currentSong = song
        if (currentSong == null || resolvedLyrics.isEmpty) {
            if (lyricsLoading) return@LaunchedEffect
            errorText = context.getString(R.string.ai_lyric_timing_no_lyrics)
            return@LaunchedEffect
        }
        loading = true
        if (analysisWordMode) {
            val analysisContext = currentCoroutineContext()
            AiLyricTimingCorrectionService.get(context)
                .previewWordTiming(
                    song = currentSong,
                    lyrics = resolvedLyrics,
                    onProgress = { progress ->
                        analysisProgress = progress.coerceIn(0f, 1f)
                    },
                    isCancelled = { !analysisContext.isActive },
                )
                .onSuccess { correction = it }
                .onFailure {
                    errorText = when (it.message) {
                        "Lyric correction may change timing only, not lyric content" ->
                            context.getString(R.string.ai_lyric_timing_content_mismatch)
                        else -> it.message
                            ?: context.getString(R.string.ai_lyric_timing_unavailable)
                    }
                }
        } else {
            val analysisContext = currentCoroutineContext()
            AiLyricTimingCorrectionService.get(context)
                .preview(
                    song = currentSong,
                    lyrics = resolvedLyrics,
                    onProgress = { progress ->
                        analysisProgress = progress.coerceIn(0f, 1f)
                    },
                    isCancelled = { !analysisContext.isActive },
                )
                .onSuccess { correction = it }
                .onFailure {
                    errorText = it.message
                        ?: context.getString(R.string.ai_lyric_timing_unavailable)
                }
        }
        loading = false
    }

    fun reject(current: LyricTimingCorrection) {
        scope.launch {
            busy = true
            AiLyricTimingCorrectionService.get(context)
                .decide(current, LyricTimingCorrectionStatus.REJECTED)
            busy = false
            onDismiss()
        }
    }

    fun accept(current: LyricTimingCorrection) {
        val currentSong = song ?: return
        scope.launch {
            busy = true
            val service = AiLyricTimingCorrectionService.get(context)
            val writeback = AiLyricTimingWritebackService.get(context)
            val prepared = writeback.prepareForOutput(current, outputMode).getOrElse {
                errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                busy = false
                return@launch
            }
            val accepted = service.decide(
                prepared,
                LyricTimingCorrectionStatus.ACCEPTED
            ).getOrElse {
                errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                busy = false
                return@launch
            }
            val receipt = service.apply(currentSong, accepted, outputMode).getOrElse {
                errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                busy = false
                return@launch
            }
            // Publish exactly what the player will read after a restart. This also catches a
            // serializer/parser mismatch instead of showing a successful in-memory preview while
            // playback continues with a different timeline from disk.
            val persisted = withContext(Dispatchers.IO) {
                LyricReader.readLyrics(currentSong)
            }.takeUnless { it.isEmpty } ?: accepted.corrected
            Log.i(
                "AiLyricTiming",
                "apply_complete song=${currentSong.path} output=${receipt.outputPath} " +
                    "preview=${accepted.corrected.stableTimingFingerprint()} " +
                    "persisted=${persisted.stableTimingFingerprint()}"
            )
            onApplied(persisted)
            busy = false
            AppNoticeBus.post(
                message = context.getString(
                    R.string.ai_lyric_timing_applied,
                    accepted.changes.size,
                ),
                icon = AppNoticeIcon.LYRICS,
            )
            onDismiss()
        }
    }

    fun confirmWithoutChanges(current: LyricTimingCorrection) {
        scope.launch {
            busy = true
            val service = AiLyricTimingCorrectionService.get(context)
            val writeback = AiLyricTimingWritebackService.get(context)
            val prepared = writeback.prepareForOutput(current, outputMode).getOrElse {
                errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                busy = false
                return@launch
            }
            val accepted = service.decide(prepared, LyricTimingCorrectionStatus.ACCEPTED)
                .getOrElse {
                    errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                    busy = false
                    return@launch
                }
            val currentSong = song
            if (currentSong != null && outputMode != AiLyricTimingOutputMode.KEEP_CURRENT) {
                service.apply(currentSong, accepted, outputMode).getOrElse {
                    errorText = it.message ?: context.getString(R.string.ai_lyric_timing_write_failed)
                    busy = false
                    return@launch
                }
            }
            val applied = if (currentSong != null && outputMode != AiLyricTimingOutputMode.KEEP_CURRENT) {
                withContext(Dispatchers.IO) { LyricReader.readLyrics(currentSong) }
                    .takeUnless { it.isEmpty }
                    ?: accepted.corrected
            } else {
                accepted.corrected
            }
            onApplied(applied)
            busy = false
            AppNoticeBus.post(
                message = context.getString(R.string.ai_lyric_timing_confirmed_no_changes),
                icon = AppNoticeIcon.LYRICS,
            )
            onDismiss()
        }
    }

    RawMiuixOverlayDialog(
        show = show,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp),
        backgroundColor = scheme.surface,
        onDismissRequest = if (busy) null else onDismiss,
        renderInRootScaffold = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = context.getString(R.string.ai_lyric_timing_title),
                        color = scheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        modifier = Modifier
                            .offset(x = (-4).dp)
                            .clickable(enabled = !busy && !lyricsLoading) {
                            // Analysis and output format are intentionally independent. Pressing
                            // CTC always cancels the previous preview and starts a fresh pass.
                            analysisMode = AiLyricTimingAnalysisMode.CTC
                            analysisGeneration++
                        },
                        color = if (analysisWordMode) {
                            scheme.primary.copy(alpha = 0.16f)
                        } else {
                            scheme.surfaceContainerHigh
                        },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    ) {
                        Text(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            text = context.getString(R.string.ai_lyric_timing_ctc_action),
                            color = if (analysisWordMode) scheme.primary else scheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            Text(
                text = song?.displayName
                    ?: context.getString(com.rawsmusic.core.ui.R.string.player_no_song),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                maxLines = 1
            )
            Text(
                text = when {
                    loading || lyricsLoading -> context.getString(R.string.ai_lyric_timing_loading)
                    errorText != null -> errorText.orEmpty()
                    correction?.changed == true -> context.getString(
                        R.string.ai_lyric_timing_summary_changed,
                        formatAnalysisDuration(
                            correction?.analyzedDurationMs ?: 0L,
                        ),
                        resolvedLyrics.lines.size,
                        correction?.changes?.size ?: 0
                    )
                    correction != null -> context.getString(
                        R.string.ai_lyric_timing_summary_unchanged,
                        formatAnalysisDuration(
                            correction?.analyzedDurationMs ?: 0L,
                        ),
                        resolvedLyrics.lines.size
                    )
                    analysisWordMode -> context.getString(R.string.ai_lyric_timing_ctc_analyzing)
                    else -> context.getString(R.string.ai_lyric_timing_loading)
                },
                color = if (errorText != null) scheme.error else scheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
            if (loading && !lyricsLoading) {
                LinearProgressIndicator(
                    progress = { analysisProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHigh,
                )
                Text(
                    text = context.getString(
                        if (analysisWordMode) R.string.ai_lyric_timing_ctc_progress
                        else R.string.ai_lyric_timing_progress,
                        (analysisProgress * 100f).toInt().coerceIn(0, 100),
                    ),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            if (!loading && !lyricsLoading && !resolvedLyrics.isEmpty) {
                Text(
                    text = context.getString(R.string.ai_lyric_timing_detected_format,
                        context.getString(detectedFormat)),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = context.getString(R.string.ai_lyric_timing_output_title),
                color = scheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val outputRows = listOf(
                    AiLyricTimingOutputMode.KEEP_CURRENT to R.string.ai_lyric_timing_output_keep,
                    AiLyricTimingOutputMode.LINE to R.string.ai_lyric_timing_output_line,
                    AiLyricTimingOutputMode.ENHANCED_WORD to R.string.ai_lyric_timing_output_word,
                    AiLyricTimingOutputMode.TTML to R.string.ai_lyric_timing_output_ttml,
                )
                outputRows.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (mode, labelId) ->
                            val modeEnabled = when (mode) {
                                AiLyricTimingOutputMode.ENHANCED_WORD -> enhancedWordReady
                                else -> true
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !busy && !lyricsLoading && modeEnabled) {
                                        outputMode = mode
                                    },
                                color = if (outputMode == mode) {
                                    scheme.primary.copy(alpha = 0.16f)
                                } else {
                                    scheme.surfaceContainerHigh
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                    text = context.getString(labelId),
                                    color = when {
                                        outputMode == mode -> scheme.primary
                                        !modeEnabled -> scheme.onSurface.copy(alpha = 0.38f)
                                        else -> scheme.onSurface
                                    },
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            val changes = correction?.changes.orEmpty()
            if (changes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val wordChanges = correction?.wordChanges.orEmpty()
                    if (analysisWordMode && wordChanges.isNotEmpty()) {
                        wordChanges.groupBy { it.lineIndex }.toSortedMap().forEach { (lineIndex, words) ->
                            WordTimingChangeRow(
                                lineIndex = lineIndex,
                                text = correction?.corrected?.lines
                                    ?.getOrNull(lineIndex)
                                    ?.text
                                    .orEmpty(),
                                words = words.sortedBy { it.wordIndex },
                            )
                        }
                    } else {
                        changes.forEach { change ->
                            TimingChangeRow(
                                change = change,
                                text = correction?.corrected?.lines
                                    ?.getOrNull(change.lineIndex)
                                    ?.text
                                    .orEmpty(),
                            )
                        }
                    }
                }
            }

            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            ) {
                TextButton(
                    onClick = {
                        correction?.let(::reject) ?: onDismiss()
                    },
                    enabled = !busy
                ) {
                    Text(context.getString(R.string.ai_lyric_timing_reject))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(context.getString(R.string.common_cancel))
                }
                TextButton(
                    onClick = {
                        correction?.let { current ->
                            if (current.changed || outputMode != AiLyricTimingOutputMode.KEEP_CURRENT) {
                                accept(current)
                            } else {
                                confirmWithoutChanges(current)
                            }
                        }
                    },
                    enabled = !busy && correction != null
                ) {
                    Text(
                        context.getString(
                            if (correction?.changed == true) {
                                R.string.ai_lyric_timing_accept
                            } else {
                                R.string.ai_lyric_timing_confirm
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun WordTimingChangeRow(
    lineIndex: Int,
    text: String,
    words: List<LyricWordTimingChange>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MiuixTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${lineIndex + 1}. ${text.cleanPreviewText()}".trimEnd(),
                color = scheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = context.getString(R.string.ai_lyric_timing_word_preview_count, words.size),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                words.forEach { word ->
                    Surface(
                        color = scheme.surface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            val originalStartMs = word.originalStartMs
                            Text(
                                text = word.text,
                                color = scheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = if (originalStartMs != null) {
                                    context.getString(
                                        R.string.ai_lyric_timing_word_time_change,
                                        formatMsPrecise(originalStartMs),
                                        formatMsPrecise(word.correctedStartMs),
                                    )
                                } else {
                                    context.getString(
                                        R.string.ai_lyric_timing_word_new_time,
                                        formatMsPrecise(word.correctedStartMs),
                                    )
                                },
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = context.getString(
                                    R.string.ai_lyric_timing_word_confidence,
                                    (word.confidence * 100f).toInt(),
                                ),
                                color = scheme.primary,
                                fontSize = 10.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lyricSongKey(song: AudioFile): String = buildString {
    append(song.path)
    append('|')
    append(song.duration)
    append('|')
    append(song.cueOffsetMs)
    append('|')
    append(song.cueEndMs)
    append('|')
    append(song.cueTrackIndex)
}

@Composable
private fun TimingChangeRow(change: LyricTimingChange, text: String) {
    val scheme = MiuixTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = "${change.lineIndex + 1}. ${text.cleanPreviewText()}".trimEnd(),
                    color = scheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            Text(
                text = "${formatMs(change.originalStartMs)} - ${formatMs(change.originalEndMs)}  ->  " +
                    "${formatMs(change.correctedStartMs)} - ${formatMs(change.correctedEndMs)}  " +
                    "(${(change.confidence * 100f).toInt()}%)",
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp
            )
        }
    }
}

private fun String.cleanPreviewText(): String =
    trim().replace(Regex("\\s+"), " ").ifBlank { "-" }

private fun formatAnalysisDuration(value: Long): String {
    if (value <= 0L) return "--"
    val totalSeconds = value / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatMs(value: Long): String {
    val totalSeconds = (value.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatMsPrecise(value: Long): String {
    val safe = value.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}
