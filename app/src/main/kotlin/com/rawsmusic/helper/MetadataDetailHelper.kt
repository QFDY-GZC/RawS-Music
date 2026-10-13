package com.rawsmusic.helper

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.core.common.model.isLossyCodec
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.module.player.PlayerController
import io.github.proify.lyricon.lyric.model.Song
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog

class MetadataDetailHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val getLyricSong: () -> Song?,
    private val setGestureInterceptDisabled: (Boolean) -> Unit,
    private val getMetadataEditor: () -> MetadataEditorHelper,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    var isVisible by mutableStateOf(false)
        private set

    var entries by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    var activeEditField by mutableStateOf<MetadataEditField?>(null)
        private set

    fun setup() = Unit

    fun open() {
        getMetadataEditor().onMetadataSaved = ::onMetadataSaved
        entries = buildEntries(
            song = getPlayerController()?.currentSong?.value,
            lyricSong = getLyricSong()
        )
        isVisible = true
        setGestureInterceptDisabled(true)
        onVisibilityChanged(true)
    }

    fun close() {
        if (getMetadataEditor().isMetadataSaving) return
        cancelEditing()
        isVisible = false
        setGestureInterceptDisabled(false)
        onVisibilityChanged(false)
    }

    fun editField(label: String) {
        val field = fieldForLabel(label) ?: return
        getMetadataEditor().prepareInlineEdit(field)
        activeEditField = field
    }

    fun saveEditing() = getMetadataEditor().saveMetadata()

    fun getMetadataSaving(): Boolean = getMetadataEditor().isMetadataSaving

    fun cancelEditing() {
        getMetadataEditor().cancelInlineEdit()
        activeEditField = null
    }

    fun valueFor(label: String, fallback: String): String {
        val field = fieldForLabel(label) ?: return fallback
        return if (activeEditField != null) getMetadataEditor().valueFor(field) else fallback
    }

    fun updateValue(label: String, value: String) {
        fieldForLabel(label)?.let { getMetadataEditor().updateValue(it, value) }
    }

    fun fieldForLabel(label: String): MetadataEditField? = when (label) {
        context.getString(R.string.metadata_field_title),
        context.getString(R.string.metadata_detail_tag_title) -> MetadataEditField.TITLE
        context.getString(R.string.metadata_field_artist) -> MetadataEditField.ARTIST
        context.getString(R.string.metadata_field_album) -> MetadataEditField.ALBUM
        context.getString(R.string.metadata_field_album_artist) -> MetadataEditField.ALBUM_ARTIST
        context.getString(R.string.metadata_field_genre) -> MetadataEditField.GENRE
        context.getString(R.string.metadata_field_composer) -> MetadataEditField.COMPOSER
        context.getString(R.string.metadata_field_year) -> MetadataEditField.YEAR
        context.getString(R.string.metadata_field_track) -> MetadataEditField.TRACK
        context.getString(R.string.metadata_field_disc) -> MetadataEditField.DISC
        context.getString(R.string.metadata_field_bpm) -> MetadataEditField.BPM
        else -> null
    }

    private fun onMetadataSaved(song: AudioFile) {
        activeEditField = null
        entries = buildEntries(song, getLyricSong())
    }

    fun isEditable(label: String): Boolean = fieldForLabel(label) != null

    private fun buildEntries(song: AudioFile?, lyricSong: Song?): List<Pair<String, String>> {
        song ?: return listOf(
            context.getString(R.string.metadata_detail_status) to
                context.getString(R.string.metadata_detail_no_current_song)
        )
        val usbSr = getPlayerController()?.getUsbOutputSampleRate() ?: 0
        val srDisplay = when {
            usbSr > 0 && song.sampleRate != usbSr -> "${song.sampleRate} Hz → ${usbSr} Hz（DAC）"
            usbSr > 0 -> "${usbSr} Hz（DAC）"
            song.sampleRate > 0 -> "${song.sampleRate} Hz"
            else -> context.getString(R.string.metadata_unknown)
        }
        val bitRateDisplay = com.rawsmusic.core.common.utils.BitrateNormalizer.formatKbps(
            rawBitrate = song.bitRate,
            durationMs = song.duration,
            fileSizeBytes = song.fileSize,
            codecName = song.encodingFormat,
            formatName = song.format,
            filePath = song.path
        )
        return listOf(
            context.getString(R.string.metadata_field_title) to song.displayName,
            context.getString(R.string.metadata_detail_tag_title) to song.title,
            context.getString(R.string.metadata_field_artist) to song.artist,
            context.getString(R.string.metadata_field_album) to song.album,
            context.getString(R.string.metadata_field_album_artist) to song.albumArtist,
            context.getString(R.string.metadata_field_genre) to song.genre,
            context.getString(R.string.metadata_field_composer) to song.composer,
            context.getString(R.string.metadata_field_year) to song.year.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_field_track) to song.trackNumber.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_field_disc) to song.discNumber.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_field_bpm) to song.bpm.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_detail_duration) to AudioUtils.formatDuration(song.duration),
            context.getString(R.string.metadata_detail_format) to song.format.ifBlank { song.extension },
            context.getString(R.string.metadata_detail_encoding_format) to song.encodingFormat,
            context.getString(R.string.metadata_detail_bitrate) to bitRateDisplay,
            context.getString(R.string.metadata_detail_sample_rate) to srDisplay,
            context.getString(R.string.metadata_detail_bit_depth) to formatBitDepth(song),
            context.getString(R.string.metadata_detail_channel_count) to song.channelCount.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_detail_file_extension) to song.extension,
            context.getString(R.string.metadata_detail_file_size) to formatFileSize(song.fileSize),
            context.getString(R.string.metadata_detail_file_path) to song.path,
            context.getString(R.string.metadata_detail_artwork_path) to song.albumArtPath,
            context.getString(R.string.metadata_detail_database_id) to song.id.toString(),
            context.getString(R.string.metadata_detail_album_id) to song.albumId.toString(),
            context.getString(R.string.metadata_detail_favorite_status) to
                context.getString(if (song.isFavorite) R.string.metadata_detail_favorited else R.string.metadata_detail_not_favorited),
            "Hi-Res" to context.getString(if (song.isHiRes) R.string.common_yes else R.string.common_no),
            context.getString(R.string.metadata_detail_dsd_source) to
                context.getString(if (song.isDsdFormat) R.string.common_yes else R.string.common_no),
            "Track Gain" to formatGain(song.trackGain),
            "Track Peak" to formatPeak(song.trackPeak),
            "Album Gain" to formatGain(song.albumGain),
            "Album Peak" to formatPeak(song.albumPeak),
            context.getString(R.string.metadata_detail_cue_track_index) to song.cueTrackIndex.takeIf { it > 0 }?.toString().orEmpty(),
            context.getString(R.string.metadata_detail_cue_start) to formatOptionalDuration(song.cueOffsetMs),
            context.getString(R.string.metadata_detail_cue_end) to formatOptionalDuration(song.cueEndMs),
            context.getString(R.string.metadata_detail_date_added) to formatTimestamp(song.dateAdded),
            context.getString(R.string.metadata_detail_date_modified) to formatTimestamp(song.dateModified),
            context.getString(R.string.metadata_detail_lyrics) to formatLyrics(lyricSong)
        )
    }

    private fun formatLyrics(song: Song?): String {
        val lines = song?.lyrics.orEmpty()
        if (lines.isEmpty()) return context.getString(R.string.metadata_detail_no_lyrics)
        return lines.joinToString(separator = "\n\n") { line ->
            val main = line.text.orEmpty().trim().ifBlank { "…" }
            val secondary = listOfNotNull(
                line.translation?.trim()?.takeIf { it.isNotBlank() },
                line.roma?.trim()?.takeIf { it.isNotBlank() },
                line.secondary?.trim()?.takeIf { it.isNotBlank() }
            ).distinct()
            buildString {
                append('[')
                append(formatLyricTimestamp(line.begin))
                append("] ")
                append(main)
                secondary.forEach {
                    append('\n')
                    append(it)
                }
            }
        }
    }

    private fun formatLyricTimestamp(milliseconds: Long): String {
        val safe = milliseconds.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val centiseconds = (safe % 1_000L) / 10L
        return "%02d:%02d.%02d".format(Locale.US, minutes, seconds, centiseconds)
    }

    private fun formatBitDepth(song: AudioFile): String {
        val bits = song.bitsPerSample
        if (song.isDsdSourceFile()) return "1 bit（DSD）"
        if (bits > 0) {
            return when {
                song.encodingFormat.contains("FLOAT", true) -> "$bits bit（Float）"
                else -> "$bits bit"
            }
        }
        return if (song.isLossyCodec()) {
            context.getString(R.string.metadata_detail_lossy_encoding)
        } else {
            context.getString(R.string.metadata_unknown)
        }
    }

    private fun formatFileSize(size: Long): String = when {
        size >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, size / (1024.0 * 1024.0 * 1024.0))
        size >= 1024L * 1024L -> "%.2f MB".format(Locale.US, size / (1024.0 * 1024.0))
        size >= 1024L -> "%.2f KB".format(Locale.US, size / 1024.0)
        size > 0L -> "$size B"
        else -> context.getString(R.string.metadata_unknown)
    }

    private fun formatGain(value: Float): String = "%.2f dB".format(Locale.US, value)

    private fun formatPeak(value: Float): String = "%.6f".format(Locale.US, value)

    private fun formatOptionalDuration(valueMs: Long): String =
        valueMs.takeIf { it > 0L }?.let(AudioUtils::formatDuration).orEmpty()

    private fun formatTimestamp(value: Long): String {
        if (value <= 0L) return ""
        val millis = if (value < 10_000_000_000L) value * 1_000L else value
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}

@Composable
fun MetadataDetailOverlay(
    helper: MetadataDetailHelper,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    RawMiuixOverlayDialog(
        show = helper.isVisible,
        modifier = modifier.fillMaxWidth().heightIn(max = 620.dp),
        backgroundColor = scheme.surface,
        onDismissRequest = helper::close,
        renderInRootScaffold = true
    ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.metadata_editor_title),
                            color = scheme.onSurface,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (helper.activeEditField != null) {
                            Text(
                                text = stringResource(R.string.metadata_cancel),
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable(
                                        enabled = !helper.getMetadataSaving(),
                                        onClick = helper::cancelEditing
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                            Text(
                                text = if (helper.getMetadataSaving()) {
                                    stringResource(R.string.metadata_saving)
                                } else {
                                    stringResource(R.string.metadata_save)
                                },
                                color = scheme.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable(
                                        enabled = !helper.getMetadataSaving(),
                                        onClick = helper::saveEditing
                                    )
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.metadata_close),
                                color = scheme.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable(onClick = helper::close)
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        helper.entries.forEach { (label, value) ->
                            MetadataRow(
                                label = label,
                                value = helper.valueFor(label, value),
                                editing = helper.activeEditField == helper.fieldForLabel(label),
                                enabled = !helper.getMetadataSaving(),
                                numeric = helper.fieldForLabel(label) in setOf(
                                    MetadataEditField.YEAR,
                                    MetadataEditField.TRACK,
                                    MetadataEditField.DISC,
                                    MetadataEditField.BPM
                                ),
                                onValueChange = { helper.updateValue(label, it) },
                                onEdit = if (helper.isEditable(label)) {
                                    { helper.editField(label) }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
    editing: Boolean,
    enabled: Boolean,
    numeric: Boolean,
    onValueChange: (String) -> Unit,
    onEdit: (() -> Unit)?
) {
    val scheme = MiuixTheme.colorScheme
    val focusRequester = remember(label) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(editing) {
        if (editing) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = scheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(5.dp))
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                ),
                textStyle = TextStyle(
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .background(scheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        } else {
            Text(
                text = value.ifBlank { stringResource(R.string.metadata_unknown) },
                color = scheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onEdit != null) {
                            Modifier
                                .clickable(onClick = onEdit)
                                .padding(vertical = 4.dp)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}
