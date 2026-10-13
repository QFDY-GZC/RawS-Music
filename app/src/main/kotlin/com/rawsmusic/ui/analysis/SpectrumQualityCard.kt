package com.rawsmusic.ui.analysis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpectrumQualityCard(song: AudioFile, analysis: AudioSpectrumAnalysis,
    report: SpectrumQualityReport, referenceResult: SpectrumReferenceResult,
    onChooseReference: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = spectrumUiColors()
    val view = LocalView.current
    val quality = remember(song, analysis, report, referenceResult) {
        assessSpectrumSignal(song, analysis, report, referenceResult)
    }
    var expanded by rememberSaveable(song.path) { mutableStateOf(false) }
    var selectedDetail by rememberSaveable(song.path) { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val gradeText = stringResource(signalGradeResource(quality.grade))
    val primaryReason = quality.issues.firstOrNull()?.let { stringResource(signalIssueResource(it)) }
        ?: stringResource(if (quality.grade == SpectrumSignalGrade.ReferenceVerified)
            R.string.spectrum_signal_reference else R.string.spectrum_signal_good)
    val encodingKind = when {
        isKnownLosslessSpectrumSource(song) -> stringResource(R.string.spectrum_signal_lossless_encoding)
        isKnownLossySpectrumSource(song) -> stringResource(R.string.spectrum_signal_lossy_encoding)
        else -> null
    }
    val formatSummary = listOfNotNull(encodingKind, song.encodingFormat.ifBlank { song.format }.takeIf { it.isNotBlank() },
        song.bitRate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        "${String.format(Locale.ROOT, "%.1f", analysis.sampleRate / 1000f)} kHz").joinToString(" · ")
    val summary = stringResource(R.string.spectrum_signal_summary, primaryReason, formatSummary)
    val details = remember(song, analysis, report, quality, context.resources.configuration) {
        qualityCardDetails(context, song, analysis, report, quality)
    }
    val referenceStatus = stringResource(when (referenceResult) {
        SpectrumReferenceResult.NotChecked -> R.string.spectrum_quality_reference_not_checked
        SpectrumReferenceResult.Checking -> R.string.spectrum_quality_reference_checking
        SpectrumReferenceResult.Match -> R.string.spectrum_quality_reference_match
        SpectrumReferenceResult.Different -> R.string.spectrum_quality_reference_different
        SpectrumReferenceResult.Unsupported -> R.string.spectrum_quality_reference_unsupported
        SpectrumReferenceResult.Failed -> R.string.spectrum_quality_reference_failed
    })
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.bottom > 0f && bounds.top < view.height
    }.clip(shape).background(colors.surface).border(1.dp, colors.line, shape)
        .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QualityEmoji(quality.grade, visible)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${stringResource(R.string.spectrum_quality_title)} · $gradeText",
                    color = colors.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(320)) + fadeIn(tween(320)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(200))) {
                Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 8.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = colors.line)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        details.forEachIndexed { index, (title, _) ->
                            SpectrumDetailCapsule(title, selectedDetail == index) {
                                selectedDetail = if (selectedDetail == index) -1 else index
                            }
                        }
                        SpectrumDetailCapsule(stringResource(R.string.spectrum_quality_reference_title), selectedDetail == details.size) {
                            selectedDetail = if (selectedDetail == details.size) -1 else details.size
                        }
                    }
                    androidx.compose.animation.AnimatedContent(targetState = selectedDetail, label = "quality_section") { index ->
                        val detail = details.getOrNull(index)
                        if (detail != null) SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(detail.first, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(detail.second, color = colors.text, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                        if (index == details.size) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.spectrum_quality_reference_title),
                                color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(referenceStatus, color = colors.text, fontSize = 13.sp, lineHeight = 20.sp)
                            TextButton(onClick = onChooseReference,
                                enabled = referenceResult != SpectrumReferenceResult.Checking) {
                                Text(stringResource(R.string.spectrum_quality_reference_choose), color = colors.accent)
                            }
                        }
                    }
                    TextButton(onClick = {
                        val title = context.getString(R.string.spectrum_quality_title)
                        val body = buildString {
                            appendLine(song.displayName)
                            appendLine("$title · $gradeText")
                            appendLine(summary)
                            details.forEach { (heading, explanation) ->
                                appendLine()
                                appendLine(heading)
                                appendLine(explanation)
                            }
                            appendLine()
                            appendLine(context.getString(R.string.spectrum_quality_reference_title))
                            appendLine(referenceStatus)
                        }
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(title, body))
                    }) { Text(stringResource(R.string.spectrum_quality_copy), color = colors.accent) }
                }
        }
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "quality_expand")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (expanded) R.string.spectrum_quality_collapse else R.string.spectrum_quality_expand),
                color = colors.muted, fontSize = 12.sp)
            IconButton(onClick = { expanded = !expanded }) {
                Icon(Icons.Rounded.KeyboardArrowDown,
                    stringResource(if (expanded) R.string.spectrum_quality_collapse else R.string.spectrum_quality_expand),
                    Modifier.graphicsLayer { rotationZ = rotation }, tint = colors.accent)
            }
        }
    }
}

@Composable
private fun QualityEmoji(display: SpectrumSignalGrade, visible: Boolean) {
    val colors = spectrumUiColors()
    val resource = when (display) {
        SpectrumSignalGrade.Unrated -> null
        SpectrumSignalGrade.Fair -> R.raw.noto_emoji_upside_down
        SpectrumSignalGrade.High -> R.raw.noto_emoji_warm_smile
        SpectrumSignalGrade.ReferenceVerified -> R.raw.noto_emoji_yum
        SpectrumSignalGrade.HighNeedsReview -> R.raw.noto_emoji_thinking
        SpectrumSignalGrade.Poor -> R.raw.noto_emoji_melting
    }
    Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
        if (resource == null) Text("—", color = colors.muted, fontSize = 32.sp)
        else {
            val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resource))
            LottieAnimation(composition = composition, iterations = Int.MAX_VALUE,
                isPlaying = visible, modifier = Modifier.size(62.dp))
        }
    }
}

private fun qualityCardDetails(context: Context, song: AudioFile, analysis: AudioSpectrumAnalysis,
    report: SpectrumQualityReport, quality: SpectrumSignalQuality): List<Pair<String, String>> {
    fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    fun decimal(value: Float?) = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.1f", it) }
        ?: text(R.string.spectrum_quality_unknown)
    val source = buildString {
        append(song.format.ifBlank { text(R.string.spectrum_quality_unknown) })
        if (song.encodingFormat.isNotBlank() && !song.encodingFormat.equals(song.format, true)) append(" · ${song.encodingFormat}")
        if (song.bitRate > 0) append(" · ${song.bitRate / 1000} kbps")
        if (song.bitsPerSample > 0) append(" · ${song.bitsPerSample} bit")
        append(" · ")
        append(if (song.sampleRate > 0) "${song.sampleRate} Hz" else text(R.string.spectrum_quality_unknown))
    }
    val level = text(R.string.spectrum_quality_card_levels, decimal(report.peakDb), decimal(report.rmsDb),
        decimal(report.crestDb))
    val frequency = report.bandEdge?.let { edge ->
        text(R.string.spectrum_quality_card_edge, String.format(Locale.ROOT, "%.1f", edge.frequencyHz / 1000f),
            edge.matchingFrames, edge.eligibleFrames)
    } ?: text(R.string.spectrum_quality_card_no_edge)
    val extent = report.bandEdge?.takeIf { it.evidence == SpectrumEvidence.Moderate }?.frequencyHz
        ?: analysis.cutoffHz.takeIf { it.isFinite() && it > 0f && it <= analysis.maxFrequencyHz }
    val occupancy = extent?.let { text(R.string.spectrum_ui_band_occupancy,
        decimal(it / analysis.maxFrequencyHz * 100f)) }
    val findings = text(signalGradeResource(quality.grade)) + "\n" +
        (quality.issues.map { text(signalIssueResource(it)) }.ifEmpty { listOf(text(R.string.spectrum_signal_good)) }
            .joinToString("\n")) + "\n" + text(R.string.spectrum_signal_grade_rules)
    val railEvidence = if (analysis.decodedFrameCount > 0L) {
        text(R.string.spectrum_quality_card_rail_counts,
            analysis.nearRailFrameCount, analysis.sustainedRailFrameCount,
            analysis.sustainedRailRunCount, analysis.decodedFrameCount)
    } else text(R.string.spectrum_quality_card_rail_unavailable)
    val sourceClues = buildString {
        append(when (report.upsamplingRisk) {
            SpectrumUpsamplingRisk.None -> text(R.string.spectrum_quality_upsampling_none)
            SpectrumUpsamplingRisk.BandEdgeOnly -> text(R.string.spectrum_quality_upsampling_edge_only)
            SpectrumUpsamplingRisk.CombinedEvidence -> text(R.string.spectrum_quality_upsampling_combined)
        })
        appendLine()
        append(when (report.precisionEvidence) {
            SpectrumPrecisionEvidence.None -> text(R.string.spectrum_quality_precision_unavailable)
            SpectrumPrecisionEvidence.SixteenBitGrid ->
                text(R.string.spectrum_quality_precision_16_grid, song.bitsPerSample)
            SpectrumPrecisionEvidence.TwentyFourBitGrid ->
                text(R.string.spectrum_quality_precision_24_grid)
        })
        if (analysis.nonzeroSampleCount > 0L) {
            appendLine()
            append(text(R.string.spectrum_quality_precision_counts,
                analysis.nonzeroSampleCount, analysis.aligned16SampleCount, analysis.aligned24SampleCount))
        }
        appendLine()
        append(text(R.string.spectrum_quality_high_band_tail, decimal(report.highBandLevelDb)))
        appendLine()
        append(text(R.string.spectrum_quality_ai_unverified))
    }
    return listOf(
        text(R.string.spectrum_signal_grade_basis) to findings,
        text(R.string.spectrum_quality_card_file) to source,
        text(R.string.spectrum_quality_card_measured) to listOfNotNull(level, frequency, occupancy, railEvidence,
            text(R.string.spectrum_quality_frames, report.activeFrames, report.inspectedFrames, analysis.durationMs / 1000f),
            text(R.string.spectrum_ui_log_resolution)).joinToString("\n"),
        text(R.string.spectrum_signal_loudness_title) to text(R.string.spectrum_signal_loudness_values,
            decimal(analysis.integratedLufs.takeIf { analysis.qualityFlags and AudioSpectrumAnalysis.LOUDNESS_MEASURED != 0 }),
            decimal(analysis.loudnessRangeLu.takeIf { analysis.qualityFlags and AudioSpectrumAnalysis.LOUDNESS_RANGE_MEASURED != 0 }),
            decimal(analysis.truePeakDb.takeIf { analysis.qualityFlags and AudioSpectrumAnalysis.TRUE_PEAK_MEASURED != 0 })),
        text(R.string.spectrum_signal_channels_title) to text(R.string.spectrum_signal_channels_values,
            decimal(analysis.leftDcOffset * 100), decimal(analysis.rightDcOffset * 100),
            analysis.stereoCorrelation.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.3f", it) }
                ?: text(R.string.spectrum_signal_not_measured), decimal(analysis.midRmsDb), decimal(analysis.sideRmsDb)),
        text(R.string.spectrum_signal_coverage_title) to text(R.string.spectrum_signal_coverage_values,
            decimal(analysis.decodedFrameCount.toFloat() / analysis.sampleRate), decimal(analysis.durationMs / 1000f),
            decimal(if (analysis.decodedFrameCount > 0) analysis.silentFrameCount * 100f / analysis.decodedFrameCount else null),
            decimal(analysis.longestSilentFrameCount.toFloat() / analysis.sampleRate),
            analysis.hardRailFrameCount, analysis.decodedFrameCount) + "\n" +
            text(R.string.spectrum_signal_analysis_scope, analysis.sampleRate, analysis.channels, analysis.fftSize),
        text(R.string.spectrum_quality_source_clues_title) to sourceClues,
        text(R.string.spectrum_quality_card_limit_title) to text(R.string.spectrum_quality_card_limit)
    )
}

private fun signalGradeResource(grade: SpectrumSignalGrade): Int = when (grade) {
    SpectrumSignalGrade.Unrated -> R.string.spectrum_signal_grade_unrated
    SpectrumSignalGrade.Poor -> R.string.spectrum_signal_grade_poor
    SpectrumSignalGrade.Fair -> R.string.spectrum_signal_grade_fair
    SpectrumSignalGrade.High -> R.string.spectrum_signal_grade_high
    SpectrumSignalGrade.HighNeedsReview -> R.string.spectrum_signal_grade_review
    SpectrumSignalGrade.ReferenceVerified -> R.string.spectrum_signal_grade_reference
}

private fun signalIssueResource(issue: SpectrumSignalIssue): Int = when (issue) {
    SpectrumSignalIssue.MissingMeasurement -> R.string.spectrum_signal_missing
    SpectrumSignalIssue.Silence -> R.string.spectrum_signal_silence
    SpectrumSignalIssue.IncompleteDecode -> R.string.spectrum_signal_incomplete
    SpectrumSignalIssue.SustainedRail -> R.string.spectrum_signal_rail
    SpectrumSignalIssue.StrongDc -> R.string.spectrum_signal_strong_dc
    SpectrumSignalIssue.DcOffset -> R.string.spectrum_signal_dc
    SpectrumSignalIssue.TruePeakOver -> R.string.spectrum_signal_true_peak
    SpectrumSignalIssue.MonoCancellation -> R.string.spectrum_signal_phase
    SpectrumSignalIssue.ChannelImbalance -> R.string.spectrum_signal_balance
    SpectrumSignalIssue.StrongCompression -> R.string.spectrum_signal_compressed
    SpectrumSignalIssue.LimitedBandwidth -> R.string.spectrum_signal_bandwidth
    SpectrumSignalIssue.SourceResolutionReview -> R.string.spectrum_signal_source_review
}
