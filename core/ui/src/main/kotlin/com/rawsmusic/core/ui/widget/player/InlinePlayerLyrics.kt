package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingEditorTarget
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.export.LyricShareDialog
import com.rawsmusic.core.ui.widget.export.LyricShareMode
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Invalidates the two inline consumers when a shared option is changed in the player menu. */
internal object InlinePlayerLyricSettings {
    var revision by mutableIntStateOf(0)
        private set
    fun changed() { revision++ }
}

data class InlinePlayerLyricActions(
    val translation: () -> Unit,
    val romanization: () -> Unit,
    val modifyArtwork: () -> Unit,
    val search: () -> Unit,
    val edit: () -> Unit,
    val aiTiming: () -> Unit,
    val externalTiming: (LyricTimingEditorTarget) -> Unit,
)

/** Full lyric list, without the lyric scene's own header, transport or navigation gestures. */
@Composable
internal fun InlinePlayerLyrics(
    song: Song?,
    positionMs: Long,
    isPlaying: Boolean,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    textColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE") val revision = InlinePlayerLyricSettings.revision
    ComposeLyricView(
        song = song,
        positionMs = positionMs,
        isPlaying = isPlaying,
        displayTranslation = displayTranslation,
        displayRoma = displayRoma,
        textColor = textColor,
        dimColor = textColor.copy(alpha = textColor.alpha * 0.42f),
        secondaryColor = textColor.copy(alpha = textColor.alpha * 0.7f),
        fontSizeSp = AppPreferences.UI.inlinePlayerLyricFontSizeSp,
        textPosition = LyricTextPosition.from(AppPreferences.UI.lyricTextPosition),
        blurEnabled = AppPreferences.UI.lyricBlurEnabled,
        relativeLineProgressEnabled = AppPreferences.UI.lyricRelativeLineProgressEnabled,
        highlightAll = AppPreferences.UI.lyricHighlightAllEnabled,
        karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled,
        karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled,
        topPadding = 28.dp,
        bottomPadding = 28.dp,
        lineHorizontalPadding = 16.dp,
        zoomGestureEnabled = false,
        swipeRightGestureEnabled = false,
        onLineClick = onSeek,
        modifier = modifier,
    )
}

/** Uses the actual lyric menu; the only separate preference is the inline font size. */
@Composable
internal fun InlinePlayerLyricOptions(
    currentSong: AudioFile?,
    coverPath: String?,
    lyricSong: Song?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    actions: InlinePlayerLyricActions,
    onDismiss: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val revision = InlinePlayerLyricSettings.revision
    val scope = rememberCoroutineScope()
    var shareMode by remember { mutableStateOf<LyricShareMode?>(null) }
    fun launchAction(action: () -> Unit) {
        // Match the lyric page's deferred launch while the popup begins its exit.
        scope.launch { onDismiss(); delay(160L); action() }
    }
    fun updatePreference(change: () -> Unit) { change(); InlinePlayerLyricSettings.changed() }
    LyricMoreSheetContent(
        currentSong = currentSong,
        coverPath = coverPath,
        displayTranslation = displayTranslation,
        displayRoma = displayRoma,
        blurEnabled = AppPreferences.UI.lyricBlurEnabled,
        relativeLineProgressEnabled = AppPreferences.UI.lyricRelativeLineProgressEnabled,
        highlightAllEnabled = AppPreferences.UI.lyricHighlightAllEnabled,
        fontSizeSp = AppPreferences.UI.inlinePlayerLyricFontSizeSp,
        textPosition = LyricTextPosition.from(AppPreferences.UI.lyricTextPosition),
        onTranslationToggle = actions.translation,
        onRomaToggle = actions.romanization,
        onBlurEnabledChange = { updatePreference { AppPreferences.UI.lyricBlurEnabled = it } },
        onRelativeLineProgressEnabledChange = { updatePreference { AppPreferences.UI.lyricRelativeLineProgressEnabled = it } },
        onHighlightAllEnabledChange = { updatePreference { AppPreferences.UI.lyricHighlightAllEnabled = it } },
        onFontSizeChange = { updatePreference { AppPreferences.UI.inlinePlayerLyricFontSizeSp = it } },
        onTextPositionChange = { updatePreference { AppPreferences.UI.lyricTextPosition = it.value } },
        progressStyleValue = null,
        onProgressStyleValueChange = null,
        landscapeLockEnabled = null,
        onLandscapeLockEnabledChange = null,
        onModifyAlbumArt = { launchAction(actions.modifyArtwork) },
        onSearchLyrico = { launchAction(actions.search) },
        onOpenInLyrico = { launchAction(actions.edit) },
        onAiTimingPreview = { launchAction(actions.aiTiming) },
        onOpenExternalTimingEditor = { target -> launchAction { actions.externalTiming(target) } },
        onLyricVideo = { shareMode = LyricShareMode.VIDEO },
        onLyricPoster = { shareMode = LyricShareMode.POSTER },
        embedded = true,
        fontSizeTitle = stringResource(R.string.inline_player_lyric_font_size),
    )
    LyricShareDialog(
        mode = shareMode,
        currentSong = currentSong,
        lyricSong = lyricSong,
        coverKey = coverPath,
        displayTranslation = displayTranslation,
        displayRoma = displayRoma,
        onDismiss = { shareMode = null },
    )
}
