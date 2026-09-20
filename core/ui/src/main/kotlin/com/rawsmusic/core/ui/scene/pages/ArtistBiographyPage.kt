package com.rawsmusic.core.ui.scene.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalRetainedSceneMotionActive
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListCustomProvider
import com.rawsmusic.module.data.artist.ArtistBiography
import com.rawsmusic.module.data.artist.ArtistBiographyPreferences
import com.rawsmusic.module.data.artist.ArtistBiographyRepository
import com.rawsmusic.module.data.artist.ArtistBiographySource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val BIOGRAPHY_ITEM_PREFIX = "artist_biography:"
private const val BIOGRAPHY_HEADER = "header"
private const val BIOGRAPHY_CONTROLS = "controls"
private const val BIOGRAPHY_STATUS = "status"
private const val BIOGRAPHY_PARAGRAPH = "paragraph"
private const val BIOGRAPHY_SOURCE_LINK = "source_link"

private data class BiographyLanguage(
    val code: String,
    val label: String,
)

private val biographyLanguages = listOf(
    BiographyLanguage("zh", "简中"),
    BiographyLanguage("en", "English"),
    BiographyLanguage("ja", "日本語"),
    BiographyLanguage("ko", "한국어"),
    BiographyLanguage("zh-tw", "繁中"),
    BiographyLanguage("zh-hk", "香港"),
)

@Composable
internal fun ArtistBiographyPage(
    artistName: String,
    listState: ComposeVirtualListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val publicationOnly = LocalReferenceLibraryProviderPublicationOnly.current
    val sceneMotionActive = LocalRetainedSceneMotionActive.current
    val context = LocalContext.current
    val initialLanguage = remember(artistName) { ArtistBiographyPreferences.language }
    val initialSource = remember(artistName, initialLanguage) {
        ArtistBiographyPreferences.source.takeUnless {
            it == ArtistBiographySource.Netease && initialLanguage != "zh"
        } ?: ArtistBiographySource.Wikipedia
    }
    var source by rememberSaveable(artistName) {
        mutableStateOf(initialSource)
    }
    var language by rememberSaveable(artistName) {
        mutableStateOf(initialLanguage)
    }
    var biography by remember(artistName) { mutableStateOf<ArtistBiography?>(null) }
    var loading by remember(artistName) { mutableStateOf(true) }
    var errorMessage by remember(artistName) { mutableStateOf<String?>(null) }
    var retryToken by remember(artistName) { mutableIntStateOf(0) }

    LaunchedEffect(artistName, source, language, retryToken, publicationOnly, sceneMotionActive) {
        if (publicationOnly || sceneMotionActive) return@LaunchedEffect
        ArtistBiographyPreferences.source = source
        ArtistBiographyPreferences.language = language
        loading = true
        errorMessage = null
        biography = null
        runCatching {
            ArtistBiographyRepository.fetch(
                artistName = artistName,
                source = source,
                language = language,
            )
        }.onSuccess {
            biography = it
        }.onFailure { error ->
            errorMessage = error.message?.takeIf(String::isNotBlank)
                ?: error.localizedMessage?.takeIf(String::isNotBlank)
                ?: error.javaClass.simpleName
        }
        loading = false
    }

    val paragraphs = remember(biography?.text) {
        splitBiographyParagraphs(biography?.text.orEmpty())
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val paragraphStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
    val paragraphWidthPx = with(density) {
        (configuration.screenWidthDp.dp - 40.dp).roundToPx().coerceAtLeast(1)
    }
    val paragraphHeights = remember(paragraphs, paragraphWidthPx, density.fontScale) {
        paragraphs.map { paragraph ->
            val measured = textMeasurer.measure(
                text = AnnotatedString(paragraph),
                style = paragraphStyle,
                constraints = Constraints(maxWidth = paragraphWidthPx),
            )
            with(density) { measured.size.height.toDp() + 30.dp }
                .coerceAtLeast(58.dp)
        }
    }

    val baseId = remember(artistName) {
        -8_700_000_000L - artistName.hashCode().toLong().let { if (it == Long.MIN_VALUE) 0L else kotlin.math.abs(it) }
    }
    val items = remember(artistName, paragraphs, biography, loading, errorMessage, baseId) {
        buildList {
            add(biographySyntheticAudioFile(baseId - 1L, BIOGRAPHY_HEADER, artistName))
            add(biographySyntheticAudioFile(baseId - 2L, BIOGRAPHY_CONTROLS, artistName))
            if (loading || biography == null || errorMessage != null) {
                add(biographySyntheticAudioFile(baseId - 3L, BIOGRAPHY_STATUS, artistName))
            } else {
                paragraphs.forEachIndexed { index, paragraph ->
                    add(
                        biographySyntheticAudioFile(
                            id = baseId - 100L - index,
                            kind = "$BIOGRAPHY_PARAGRAPH:$index",
                            title = paragraph.take(64),
                        )
                    )
                }
                add(biographySyntheticAudioFile(baseId - 4L, BIOGRAPHY_SOURCE_LINK, artistName))
            }
        }
    }

    val customProvider = remember(
        artistName,
        source,
        language,
        biography,
        loading,
        errorMessage,
        paragraphs,
        paragraphHeights,
        onBack,
    ) {
        VirtualListCustomProvider(
            itemHeight = { item, _ ->
                when (val kind = item.encodingFormat.removePrefix(BIOGRAPHY_ITEM_PREFIX)) {
                    BIOGRAPHY_HEADER -> 116.dp
                    BIOGRAPHY_CONTROLS -> 164.dp
                    BIOGRAPHY_STATUS -> 300.dp
                    BIOGRAPHY_SOURCE_LINK -> 92.dp
                    else -> if (kind.startsWith("$BIOGRAPHY_PARAGRAPH:")) {
                        val index = kind.substringAfter(':').toIntOrNull() ?: -1
                        paragraphHeights.getOrNull(index) ?: 80.dp
                    } else null
                }
            },
            handles = { item, _ -> item.encodingFormat.startsWith(BIOGRAPHY_ITEM_PREFIX) },
            content = { item, _, itemModifier ->
                when (val kind = item.encodingFormat.removePrefix(BIOGRAPHY_ITEM_PREFIX)) {
                    BIOGRAPHY_HEADER -> BiographyHeader(
                        artistName = artistName,
                        onBack = onBack,
                        modifier = itemModifier,
                    )

                    BIOGRAPHY_CONTROLS -> BiographyControls(
                        source = source,
                        language = language,
                        onSourceChange = { next ->
                            source = next
                            if (next == ArtistBiographySource.Netease) language = "zh"
                        },
                        onLanguageChange = { next ->
                            language = next
                            if (source == ArtistBiographySource.Netease && next != "zh") {
                                source = ArtistBiographySource.Wikipedia
                            }
                        },
                        modifier = itemModifier,
                    )

                    BIOGRAPHY_STATUS -> BiographyStatus(
                        loading = loading,
                        errorMessage = errorMessage,
                        onRetry = { retryToken++ },
                        modifier = itemModifier,
                    )

                    BIOGRAPHY_SOURCE_LINK -> BiographySourceLink(
                        source = biography?.source ?: source,
                        onOpen = {
                            biography?.sourceUrl
                                ?.takeIf(String::isNotBlank)
                                ?.let { url ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                        },
                        modifier = itemModifier,
                    )

                    else -> if (kind.startsWith("$BIOGRAPHY_PARAGRAPH:")) {
                        val index = kind.substringAfter(':').toIntOrNull() ?: -1
                        val paragraph = paragraphs.getOrNull(index).orEmpty()
                        Text(
                            text = paragraph,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            modifier = itemModifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }

    CompositionLocalProvider(LocalVirtualListCustomProvider provides customProvider) {
        ComposeVirtualList(
            songs = items,
            state = listState,
            modifier = modifier.fillMaxSize(),
            pinchEnabled = false,
            contentTopPadding = 0.dp,
            contentBottomPadding = 148.dp,
            onSongClick = { _, _ -> },
            onSongLongClick = { _, _ -> },
        )
    }
}

@Composable
private fun BiographyHeader(
    artistName: String,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = MiuixIcons.Regular.Back,
                contentDescription = stringResource(R.string.common_back),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.artist_biography_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Text(
                text = artistName,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BiographyControls(
    source: ArtistBiographySource,
    language: String,
    onSourceChange: (ArtistBiographySource) -> Unit,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.artist_biography_source),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BiographyChoiceChip("Wikipedia", source == ArtistBiographySource.Wikipedia) {
                onSourceChange(ArtistBiographySource.Wikipedia)
            }
            BiographyChoiceChip("Last.fm", source == ArtistBiographySource.LastFm) {
                onSourceChange(ArtistBiographySource.LastFm)
            }
            BiographyChoiceChip("网易云", source == ArtistBiographySource.Netease) {
                onSourceChange(ArtistBiographySource.Netease)
            }
        }
        Text(
            text = stringResource(R.string.artist_biography_language),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            biographyLanguages.take(3).forEach { option ->
                BiographyChoiceChip(option.label, language == option.code) {
                    onLanguageChange(option.code)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            biographyLanguages.drop(3).forEach { option ->
                BiographyChoiceChip(option.label, language == option.code) {
                    onLanguageChange(option.code)
                }
            }
        }
    }
}

@Composable
private fun BiographyChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val primary = MiuixTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(
                if (selected) primary.copy(alpha = 0.16f)
                else MiuixTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                width = 1.dp,
                color = if (selected) primary else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) primary else MiuixTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun BiographyStatus(
    loading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = MiuixTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(R.string.artist_biography_loading),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
        } else {
            Text(
                text = stringResource(R.string.artist_biography_failed),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            Text(
                text = stringResource(R.string.artist_biography_retry),
                color = MiuixTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun BiographySourceLink(
    source: ArtistBiographySource,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val sourceName = when (source) {
        ArtistBiographySource.Wikipedia -> "Wikipedia"
        ArtistBiographySource.LastFm -> "Last.fm"
        ArtistBiographySource.Netease -> "网易云音乐"
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = stringResource(R.string.artist_biography_read_more, sourceName),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun splitBiographyParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\\n{2,}"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .ifEmpty { listOf(text.trim()).filter(String::isNotBlank) }

private fun biographySyntheticAudioFile(id: Long, kind: String, title: String): AudioFile = AudioFile(
    id = id,
    path = "",
    title = title,
    artist = "",
    album = "",
    albumArtPath = "",
    duration = 0L,
    sampleRate = 0,
    bitsPerSample = 0,
    format = "",
    fileSize = 0L,
    trackNumber = 0,
    year = 0,
    dateAdded = 0L,
    dateModified = 0L,
    genre = "",
    composer = "",
    discNumber = 0,
    channelCount = 0,
    bpm = 0,
    albumArtist = "",
    encodingFormat = "$BIOGRAPHY_ITEM_PREFIX$kind",
    isFavorite = false,
    trackGain = 0f,
    trackPeak = 1f,
    albumGain = 0f,
    albumPeak = 1f,
    cueOffsetMs = 0L,
    cueEndMs = 0L,
    cueTrackIndex = 0,
)
