package com.rawsmusic.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.provider.MediaStore
import android.app.RecoverableSecurityException
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.lyrico.LyricoAudioWriteAccess
import com.rawsmusic.lyrico.LyricoPluginStore
import com.rawsmusic.lyrico.LyricoCoverCandidate
import com.rawsmusic.lyrico.LyricoLyricTiming
import com.rawsmusic.lyrico.LyricoSongCandidate
import com.rawsmusic.lyrico.LyricoSourceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class LyricoSourceFilter(
    val id: String,
    val name: String
)

class LyricoSearchActivity : BaseSettingsActivity() {
    private var pendingAudioWriteConsent: CompletableDeferred<Boolean>? = null

    private val audioWriteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingAudioWriteConsent?.complete(result.resultCode == Activity.RESULT_OK)
        pendingAudioWriteConsent = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val song = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SONG, AudioFile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_SONG)
        }
        if (song == null) {
            finish()
            return
        }
        setContent {
            LyricoSearchScreen(
                song = song,
                onBack = ::finish,
                requestCoverWriteAccess = ::requestCoverWriteAccess,
                onApplied = { lyricsChanged, artworkChanged ->
                    setResult(
                        Activity.RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_SONG_PATH, song.path)
                            .putExtra(EXTRA_CHANGED_LYRICS, lyricsChanged)
                            .putExtra(EXTRA_CHANGED_ARTWORK, artworkChanged)
                    )
                    finish()
                }
            )
        }
    }


    private suspend fun requestCoverWriteAccess(song: AudioFile): Boolean {
        val before = LyricoAudioWriteAccess.inspect(this, song)
        if (before.canWrite) return true
        val mediaUri = before.mediaUri ?: return false

        val intentSender = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                runCatching {
                    MediaStore.createWriteRequest(contentResolver, listOf(mediaUri)).intentSender
                }.getOrNull()
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                try {
                    contentResolver.openFileDescriptor(mediaUri, "rw")?.use { }
                    return LyricoAudioWriteAccess.inspect(this, song).canWrite
                } catch (recoverable: RecoverableSecurityException) {
                    recoverable.userAction.actionIntent.intentSender
                } catch (_: SecurityException) {
                    null
                }
            }
            else -> null
        } ?: return false

        val granted = launchAudioWriteConsent(intentSender)
        return granted && LyricoAudioWriteAccess.inspect(this, song).canWrite
    }

    private suspend fun launchAudioWriteConsent(intentSender: IntentSender): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingAudioWriteConsent?.complete(false)
        pendingAudioWriteConsent = deferred
        return try {
            audioWriteConsentLauncher.launch(
                IntentSenderRequest.Builder(intentSender).build()
            )
            deferred.await()
        } catch (_: Throwable) {
            pendingAudioWriteConsent = null
            deferred.complete(false)
            false
        }
    }

    override fun onDestroy() {
        pendingAudioWriteConsent?.complete(false)
        pendingAudioWriteConsent = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SONG = "lyrico_song"
        const val EXTRA_SONG_PATH = "lyrico_song_path"
        const val EXTRA_CHANGED_LYRICS = "lyrico_changed_lyrics"
        const val EXTRA_CHANGED_ARTWORK = "lyrico_changed_artwork"
    }
}

@Composable
private fun LyricoSearchScreen(
    song: AudioFile,
    onBack: () -> Unit,
    requestCoverWriteAccess: suspend (AudioFile) -> Boolean,
    onApplied: (lyricsChanged: Boolean, artworkChanged: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember(context) { LyricoSourceEngine(context) }
    val store = remember(context) { LyricoPluginStore.get(context) }
    var query by remember(song.path) {
        mutableStateOf(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" "))
    }
    var results by remember { mutableStateOf<List<LyricoSongCandidate>>(emptyList()) }
    var availableSources by remember { mutableStateOf<List<LyricoSourceFilter>>(emptyList()) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var applyingId by remember { mutableStateOf<String?>(null) }
    var previewCandidate by remember { mutableStateOf<LyricoSongCandidate?>(null) }
    var previewLyrics by remember { mutableStateOf<LyricData?>(null) }
    var previewCovers by remember { mutableStateOf<List<LyricoCoverCandidate>>(emptyList()) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var lyricTiming by remember { mutableStateOf(LyricoLyricTiming.LINE_BY_LINE) }
    var includeTranslation by remember { mutableStateOf(true) }
    var includeRomanization by remember { mutableStateOf(true) }
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    var applyLyrics by remember { mutableStateOf(true) }
    var applyCover by remember { mutableStateOf(false) }

    fun search() {
        if (query.isBlank() || searching) return
        scope.launch {
            searching = true
            status = context.getString(R.string.lyrico_search_searching)
            try {
                val newResults = withContext(Dispatchers.IO) {
                    val songResults = engine.search(song, query.trim())
                    val directSources = store.listInstalled()
                        .asSequence()
                        .filter { it.enabled }
                        .filter { plugin ->
                            !engine.sourceSupports(plugin.manifest.id, "searchSongs") &&
                                (engine.sourceSupports(plugin.manifest.id, "getLyrics") ||
                                    engine.sourceSupports(plugin.manifest.id, "searchCovers"))
                        }
                        .map { plugin ->
                            LyricoSongCandidate(
                                pluginId = plugin.manifest.id,
                                pluginName = plugin.manifest.name.ifBlank { plugin.manifest.id },
                                id = "local:${song.id}",
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                durationMs = song.duration,
                                coverUrl = "",
                                supportsCoverSearch = engine.sourceSupports(plugin.manifest.id, "searchCovers"),
                                fields = emptyMap(),
                                internal = emptyMap()
                            )
                        }
                        .toList()
                    songResults + directSources
                }
                results = newResults
                status = if (newResults.isEmpty()) {
                    context.getString(R.string.lyrico_search_empty)
                } else {
                    context.getString(R.string.lyrico_search_count, newResults.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e("LyricoSearch", "Lyric search failed", error)
                status = context.getString(
                    R.string.lyrico_search_failed,
                    error.message.orEmpty().ifBlank { error.javaClass.simpleName }
                )
            } finally {
                searching = false
            }
        }
    }

    LaunchedEffect(song.path) {
        try {
            availableSources = withContext(Dispatchers.IO) {
                store.listInstalled()
                    .filter { it.enabled }
                    .filter { plugin ->
                        engine.sourceSupports(plugin.manifest.id, "searchSongs") ||
                            engine.sourceSupports(plugin.manifest.id, "getLyrics") ||
                            engine.sourceSupports(plugin.manifest.id, "searchCovers")
                    }
                    .map { plugin ->
                        LyricoSourceFilter(
                            id = plugin.manifest.id,
                            name = plugin.manifest.name.ifBlank { plugin.manifest.id }
                        )
                    }
            }
            if (availableSources.isEmpty()) {
                status = context.getString(R.string.lyrico_search_no_sources)
            } else {
                search()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("LyricoSearch", "Unable to enumerate lyric sources", error)
            status = context.getString(
                R.string.lyrico_search_failed,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            )
        }
    }

    val visibleResults = remember(results, selectedSourceId) {
        selectedSourceId?.let { sourceId ->
            results.filter { it.pluginId == sourceId }
        } ?: results
    }

    SettingsPage(title = stringResource(R.string.lyrico_search_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.lyrico_search_query_section)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.lyrico_search_query_hint),
                modifier = Modifier.fillMaxWidth()
            )
            SettingsNavigationEntry(
                title = stringResource(R.string.lyrico_search_action),
                description = status.ifBlank { stringResource(R.string.lyrico_search_action_summary) },
                onClick = ::search
            )
        }

        if (results.isNotEmpty()) {
            SettingsSection(stringResource(R.string.lyrico_search_results_section)) {
                LyricoSourceFilterRow(
                    sources = availableSources,
                    selectedSourceId = selectedSourceId,
                    onSourceSelected = { sourceId ->
                        selectedSourceId = if (selectedSourceId == sourceId) null else sourceId
                    }
                )
                if (visibleResults.isEmpty()) {
                    SettingsInfoEntry(
                        title = stringResource(R.string.lyrico_search_status_title),
                        description = stringResource(R.string.lyrico_search_source_empty)
                    )
                }
                visibleResults.forEach { candidate ->
                    val isApplying = applyingId == "${candidate.pluginId}:${candidate.id}"
                    SettingsNavigationEntry(
                        title = candidate.title.ifBlank { stringResource(R.string.lyrico_search_unknown_title) },
                        description = if (isApplying) {
                            stringResource(R.string.lyrico_search_applying)
                        } else {
                            listOf(candidate.artist, candidate.album, candidate.pluginName)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        },
                        onClick = {
                            if (applyingId != null) return@SettingsNavigationEntry
                            previewCandidate = candidate
                            previewLyrics = null
                            previewCovers = emptyList()
                            selectedCoverUrl = null
                            applyLyrics = true
                            applyCover = false
                            previewError = null
                            previewLoading = true
                            scope.launch {
                                val lyricsDeferred = async(Dispatchers.IO) {
                                    runCatching { engine.getLyrics(candidate) }
                                }
                                val coversDeferred = async(Dispatchers.IO) {
                                    runCatching { engine.searchCovers(candidate) }.getOrDefault(emptyList())
                                }
                                val lyricsResult = lyricsDeferred.await()
                                val covers = coversDeferred.await()
                                previewLyrics = lyricsResult.getOrNull()
                                previewCovers = covers
                                selectedCoverUrl = covers.firstOrNull()?.url
                                applyCover = covers.isNotEmpty()
                                val loadedLyrics = previewLyrics
                                applyLyrics = loadedLyrics != null && !loadedLyrics.isEmpty
                                if (loadedLyrics != null) {
                                    lyricTiming = if (loadedLyrics.hasWordTiming()) {
                                        LyricoLyricTiming.WORD_BY_WORD
                                    } else {
                                        LyricoLyricTiming.LINE_BY_LINE
                                    }
                                    includeTranslation = loadedLyrics.lines.any { it.translation.isNotBlank() }
                                    includeRomanization = loadedLyrics.lines.any { it.romanization.isNotBlank() }
                                }
                                previewError = lyricsResult.exceptionOrNull()?.message
                                previewLoading = false
                            }
                        }
                    )
                }
            }
        } else if (status.isNotBlank()) {
            SettingsSection(stringResource(R.string.lyrico_search_results_section)) {
                SettingsInfoEntry(
                    title = stringResource(R.string.lyrico_search_status_title),
                    description = status
                )
            }
        }
    }

    previewCandidate?.let { candidate ->
        fun applySelection(writeLyrics: Boolean, writeCover: Boolean) {
            val loadedLyrics = previewLyrics
            val coverUrlToWrite = selectedCoverUrl
            val shouldWriteLyrics = writeLyrics && loadedLyrics != null && !loadedLyrics.isEmpty
            val shouldWriteCover = writeCover && coverUrlToWrite != null
            if (!shouldWriteLyrics && !shouldWriteCover) return
            val applyId = "${candidate.pluginId}:${candidate.id}"
            applyingId = applyId
            scope.launch {
                val failures = mutableListOf<String>()
                var appliedParts = 0
                var lyricsApplied = false
                var artworkApplied = false

                if (shouldWriteLyrics && loadedLyrics != null) {
                    val prepared = engine.prepareLyrics(
                        lyrics = loadedLyrics,
                        timing = lyricTiming,
                        includeTranslation = includeTranslation,
                        includeRomanization = includeRomanization
                    )
                    withContext(Dispatchers.IO) {
                        runCatching { engine.writeOverride(song, prepared) }
                    }.onSuccess {
                        appliedParts++
                        lyricsApplied = true
                    }.onFailure { error ->
                        failures += context.getString(
                            R.string.lyrico_search_lyrics_failed,
                            error.message.orEmpty()
                        )
                    }
                }

                if (shouldWriteCover) {
                    val authorized = requestCoverWriteAccess(song)
                    if (!authorized) {
                        failures += context.getString(R.string.lyrico_search_cover_permission_failed)
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                engine.writeEmbeddedCover(song, requireNotNull(coverUrlToWrite))
                            }
                        }.onSuccess {
                            appliedParts++
                            artworkApplied = true
                        }.onFailure { error ->
                            failures += context.getString(
                                R.string.lyrico_search_cover_failed,
                                error.message.orEmpty()
                            )
                        }
                    }
                }

                applyingId = null
                if (appliedParts > 0) {
                    val message = if (failures.isEmpty()) {
                        context.getString(R.string.lyrico_search_applied)
                    } else {
                        context.getString(
                            R.string.lyrico_search_applied_partial,
                            failures.joinToString("; ")
                        )
                    }
                    if (failures.isEmpty()) {
                        AppNoticeBus.post(
                            message = message,
                            icon = AppNoticeIcon.LYRICS,
                        )
                    } else {
                        AppNoticeBus.error(message)
                    }
                    previewCandidate = null
                    onApplied(lyricsApplied, artworkApplied)
                } else {
                    AppNoticeBus.error(
                        context.getString(
                            R.string.lyrico_search_apply_failed,
                            failures.joinToString("; ").ifBlank { "Unknown error" }
                        )
                    )
                }
            }
        }

        LyricoApplyPreviewDialog(
            candidate = candidate,
            lyrics = previewLyrics,
            covers = previewCovers,
            loading = previewLoading,
            error = previewError,
            timing = lyricTiming,
            includeTranslation = includeTranslation,
            includeRomanization = includeRomanization,
            selectedCoverUrl = selectedCoverUrl,
            applyLyrics = applyLyrics,
            applyCover = applyCover,
            applying = applyingId != null,
            onTimingChange = { lyricTiming = it },
            onTranslationChange = { includeTranslation = it },
            onRomanizationChange = { includeRomanization = it },
            onCoverSelected = { selectedCoverUrl = it },
            onApplyLyricsChange = { applyLyrics = it },
            onApplyCoverChange = { applyCover = it },
            onDismiss = {
                if (applyingId == null) previewCandidate = null
            },
            onApplyCoverOnly = { applySelection(writeLyrics = false, writeCover = true) },
            onApply = { applySelection(writeLyrics = applyLyrics, writeCover = applyCover) }
        )
    }
}

private fun LyricData.hasWordTiming(): Boolean = lines.any { line ->
    line.words.size > 1 || line.words.any { it.end > it.begin }
}

@Composable
private fun LyricoApplyPreviewDialog(
    candidate: LyricoSongCandidate,
    lyrics: LyricData?,
    covers: List<LyricoCoverCandidate>,
    loading: Boolean,
    error: String?,
    timing: LyricoLyricTiming,
    includeTranslation: Boolean,
    includeRomanization: Boolean,
    selectedCoverUrl: String?,
    applyLyrics: Boolean,
    applyCover: Boolean,
    applying: Boolean,
    onTimingChange: (LyricoLyricTiming) -> Unit,
    onTranslationChange: (Boolean) -> Unit,
    onRomanizationChange: (Boolean) -> Unit,
    onCoverSelected: (String) -> Unit,
    onApplyLyricsChange: (Boolean) -> Unit,
    onApplyCoverChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApplyCoverOnly: () -> Unit,
    onApply: () -> Unit
) {
    val hasWordTiming = lyrics?.hasWordTiming() == true
    val hasTranslation = lyrics?.lines?.any { it.translation.isNotBlank() } == true
    val hasRomanization = lyrics?.lines?.any { it.romanization.isNotBlank() } == true
    val previewText = remember(lyrics, timing, includeTranslation, includeRomanization) {
        lyrics?.lines.orEmpty().take(160).joinToString("\n") { line ->
            buildString {
                append(line.text)
                if (includeRomanization && line.romanization.isNotBlank()) {
                    append('\n').append(line.romanization)
                }
                if (includeTranslation && line.translation.isNotBlank()) {
                    append('\n').append(line.translation)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .padding(horizontal = 24.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MiuixTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.lyrico_preview_title),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = listOf(candidate.title, candidate.artist, candidate.pluginName)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    if (covers.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.lyrico_preview_cover),
                            color = MiuixTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            covers.forEach { cover ->
                                LyricoCoverPreview(
                                    cover = cover,
                                    selected = selectedCoverUrl == cover.url,
                                    onClick = { onCoverSelected(cover.url) }
                                )
                            }
                        }
                        LyricoTogglePill(
                            text = stringResource(R.string.lyrico_preview_write_cover),
                            selected = applyCover,
                            enabled = selectedCoverUrl != null,
                            onClick = { onApplyCoverChange(!applyCover) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        text = stringResource(R.string.lyrico_preview_lyric_type),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    LyricoTogglePill(
                        text = stringResource(R.string.lyrico_preview_write_lyrics),
                        selected = applyLyrics,
                        enabled = lyrics != null && !lyrics.isEmpty,
                        onClick = { onApplyLyricsChange(!applyLyrics) }
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        LyricoTogglePill(
                            text = stringResource(R.string.lyrico_preview_word_by_word),
                            selected = timing == LyricoLyricTiming.WORD_BY_WORD,
                            enabled = applyLyrics && hasWordTiming,
                            onClick = { onTimingChange(LyricoLyricTiming.WORD_BY_WORD) }
                        )
                        LyricoTogglePill(
                            text = stringResource(R.string.lyrico_preview_line_by_line),
                            selected = timing == LyricoLyricTiming.LINE_BY_LINE,
                            enabled = applyLyrics && lyrics != null,
                            onClick = { onTimingChange(LyricoLyricTiming.LINE_BY_LINE) }
                        )
                        if (hasTranslation) {
                            LyricoTogglePill(
                                text = stringResource(R.string.lyrico_preview_translation),
                                selected = includeTranslation,
                                enabled = applyLyrics,
                                onClick = { onTranslationChange(!includeTranslation) }
                            )
                        }
                        if (hasRomanization) {
                            LyricoTogglePill(
                                text = stringResource(R.string.lyrico_preview_romanization),
                                selected = includeRomanization,
                                enabled = applyLyrics,
                                onClick = { onRomanizationChange(!includeRomanization) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = when {
                                loading -> stringResource(R.string.lyrico_preview_loading)
                                lyrics != null -> previewText.ifBlank { stringResource(R.string.lyrico_search_empty) }
                                else -> error.orEmpty().ifBlank { stringResource(R.string.lyrico_search_empty) }
                            },
                            color = if (lyrics == null && !loading) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.onBackground
                            },
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canApplyLyrics = applyLyrics && lyrics != null && !lyrics.isEmpty
                        val canApplyCover = applyCover && selectedCoverUrl != null
                        TextButton(
                            onClick = onApplyCoverOnly,
                            enabled = selectedCoverUrl != null && !loading && !applying
                        ) {
                            Text(stringResource(R.string.lyrico_preview_cover_only))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onDismiss, enabled = !applying) {
                                Text(stringResource(R.string.lyrico_preview_cancel))
                            }
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = onApply,
                                enabled = (canApplyLyrics || canApplyCover) && !loading && !applying
                            ) {
                                Text(
                                    if (applying) stringResource(R.string.lyrico_search_applying)
                                    else stringResource(R.string.lyrico_preview_apply)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricoCoverPreview(
    cover: LyricoCoverCandidate,
    selected: Boolean,
    onClick: () -> Unit
) {
    var resolution by remember(cover.url) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(cover.url) {
        resolution = withContext(Dispatchers.IO) { readRemoteImageSize(cover.url) }
    }
    Box(
        modifier = Modifier
            .size(82.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.border(
                    3.dp,
                    MiuixTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        BitmapImage(
            key = cover.url,
            contentDescription = cover.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            targetWidth = 192,
            targetHeight = 192,
            priority = BitmapRequest.Priority.LOADING_LIST,
            surface = ArtworkSurface.List,
            showDefaultArtwork = false,
        )
        resolution?.let { size ->
            Text(
                text = "${size.first}×${size.second}",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 3.dp, vertical = 2.dp)
            )
        }
    }
}

private fun readRemoteImageSize(url: String): Pair<Int, Int>? {
    val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
        ?: return null
    return try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "RawSMusic")
        connection.inputStream.use { input ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun LyricoSourceFilterRow(
    sources: List<LyricoSourceFilter>,
    selectedSourceId: String?,
    onSourceSelected: (String?) -> Unit
) {
    if (sources.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LyricoSourcePill(
            text = stringResource(R.string.lyrico_search_source_all),
            selected = selectedSourceId == null,
            onClick = { onSourceSelected(null) }
        )
        sources.forEach { source ->
            LyricoSourcePill(
                text = source.name,
                selected = selectedSourceId == source.id,
                onClick = { onSourceSelected(source.id) }
            )
        }
    }
}

@Composable
private fun LyricoSourcePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun LyricoTogglePill(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = when {
            !enabled -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            selected -> MiuixTheme.colorScheme.onPrimary
            else -> MiuixTheme.colorScheme.onSurface
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected && enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}
