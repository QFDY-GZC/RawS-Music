package com.rawsmusic.core.common.source.musicfree

import com.rawsmusic.core.common.source.RawMusicSourcePlugin
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceCapability
import com.rawsmusic.core.common.source.RawSourceLyric
import com.rawsmusic.core.common.source.RawSourceManifest
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourcePage
import com.rawsmusic.core.common.source.RawSourceQuality

/**
 * Host-facing bridge for a MusicFree JavaScript runtime.
 *
 * The runtime itself is deliberately outside this contract. A future isolated
 * process can implement this bridge without exposing Android or RawSMusic engine
 * objects to imported JavaScript.
 */
interface MusicFreePluginGateway {
    val descriptor: MusicFreePluginDescriptor

    suspend fun search(
        query: String,
        page: Int,
        type: MusicFreeMediaType,
    ): MusicFreeSearchResult

    suspend fun getMediaSource(
        item: MusicFreeMediaItem,
        quality: String,
    ): MusicFreeMediaSource?

    suspend fun getLyric(item: MusicFreeMediaItem): MusicFreeLyric? = null

    suspend fun getMusicInfo(item: MusicFreeMediaItem): MusicFreeMediaItem? = null

    suspend fun importMusicItem(input: String): MusicFreeMediaItem? = null

    suspend fun importMusicSheet(input: String): List<MusicFreeMediaItem>? = null
}

data class MusicFreePluginDescriptor(
    val platform: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val supportedSearchTypes: Set<MusicFreeMediaType> = setOf(MusicFreeMediaType.Music),
    val methods: Set<MusicFreeMethod> = emptySet(),
)

enum class MusicFreeMethod {
    Search,
    GetMediaSource,
    GetLyric,
    GetMusicInfo,
    ImportMusicItem,
    ImportMusicSheet,
    GetAlbumInfo,
    GetArtistWorks,
    GetMusicSheetInfo,
}

enum class MusicFreeMediaType {
    Music,
    Album,
    Artist,
    Sheet,
}

data class MusicFreeMediaItem(
    val id: String,
    val platform: String,
    val type: MusicFreeMediaType = MusicFreeMediaType.Music,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Double = 0.0,
    val artwork: String = "",
    val qualityKeys: Set<String> = setOf("standard"),
    /** Serialized original plugin item, retained without interpretation. */
    val rawPayload: String = "",
)

data class MusicFreeSearchResult(
    val data: List<MusicFreeMediaItem>,
    val isEnd: Boolean = true,
)

data class MusicFreeMediaSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "standard",
)

data class MusicFreeLyric(
    val rawLrc: String = "",
    val translation: String = "",
    val romanization: String = "",
    val lyric: String = "",
)

/**
 * MusicFree compatibility adapter. RawSMusic stays dependent on its own protocol;
 * MusicFree field names are confined to this adapter and its isolated gateway.
 */
class MusicFreeCompatAdapter(
    private val gateway: MusicFreePluginGateway,
) : RawMusicSourcePlugin {

    override val manifest: RawSourceManifest = gateway.descriptor.toRawManifest()

    override suspend fun search(
        query: String,
        page: Int,
        type: RawSourceMediaType,
    ): RawSourcePage<RawSourceMediaItem> {
        val result = gateway.search(query, page, type.toMusicFreeType())
        return RawSourcePage(
            data = result.data.map(MusicFreeMediaItem::toRawItem),
            isEnd = result.isEnd,
        )
    }

    override suspend fun resolveAudio(
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource? {
        val result = gateway.getMediaSource(item.toMusicFreeItem(gateway.descriptor.platform), quality.toMusicFreeQuality())
            ?: return null
        return RawResolvedAudioSource(
            url = result.url,
            headers = result.headers,
            userAgent = result.headers.entries
                .firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
                ?.value,
            quality = RawSourceQuality.fromKey(result.quality),
        )
    }

    override suspend fun getLyric(item: RawSourceMediaItem): RawSourceLyric? {
        val lyric = gateway.getLyric(item.toMusicFreeItem(gateway.descriptor.platform)) ?: return null
        return RawSourceLyric(
            original = lyric.rawLrc.ifBlank { lyric.lyric },
            translation = lyric.translation,
            romanization = lyric.romanization,
        )
    }

    override suspend fun getMusicInfo(item: RawSourceMediaItem): RawSourceMediaItem? =
        gateway.getMusicInfo(item.toMusicFreeItem(gateway.descriptor.platform))?.toRawItem()

    override suspend fun importMusic(input: String): RawSourceMediaItem? =
        gateway.importMusicItem(input)?.toRawItem()

    override suspend fun importPlaylist(input: String): List<RawSourceMediaItem>? =
        gateway.importMusicSheet(input)?.map(MusicFreeMediaItem::toRawItem)
}

private fun MusicFreePluginDescriptor.toRawManifest(): RawSourceManifest {
    val capabilities = buildSet {
        if (MusicFreeMethod.Search in methods) add(RawSourceCapability.Search)
        if (MusicFreeMethod.GetMediaSource in methods) add(RawSourceCapability.ResolveAudio)
        if (MusicFreeMethod.GetLyric in methods) add(RawSourceCapability.Lyric)
        if (MusicFreeMethod.GetMusicInfo in methods) add(RawSourceCapability.MusicInfo)
        if (MusicFreeMethod.ImportMusicItem in methods) add(RawSourceCapability.ImportMusic)
        if (MusicFreeMethod.ImportMusicSheet in methods) add(RawSourceCapability.ImportPlaylist)
        if (MusicFreeMethod.GetAlbumInfo in methods) add(RawSourceCapability.Album)
        if (MusicFreeMethod.GetArtistWorks in methods) add(RawSourceCapability.Artist)
        if (MusicFreeMethod.GetMusicSheetInfo in methods) add(RawSourceCapability.Playlist)
    }
    return RawSourceManifest(
        id = "musicfree:${platform.trim().lowercase()}",
        name = platform,
        version = version,
        author = author,
        description = description,
        supportedTypes = supportedSearchTypes.mapTo(linkedSetOf(), MusicFreeMediaType::toRawType),
        capabilities = capabilities,
    )
}

private fun MusicFreeMediaItem.toRawItem(): RawSourceMediaItem = RawSourceMediaItem(
    sourceId = "musicfree:${platform.trim().lowercase()}",
    remoteId = id,
    mediaType = type.toRawType(),
    title = title,
    artists = artist.split('/', '、', '&')
        .map(String::trim)
        .filter(String::isNotEmpty),
    album = album,
    durationMs = (durationSeconds * 1_000.0).toLong().coerceAtLeast(0L),
    artworkUrl = artwork,
    availableQualities = qualityKeys.mapTo(linkedSetOf(), RawSourceQuality::fromKey),
    sourcePayload = rawPayload,
)

private fun RawSourceMediaItem.toMusicFreeItem(platformName: String): MusicFreeMediaItem = MusicFreeMediaItem(
    id = remoteId,
    platform = platformName,
    type = mediaType.toMusicFreeType(),
    title = title,
    artist = artists.joinToString(" / "),
    album = album,
    durationSeconds = durationMs / 1_000.0,
    artwork = artworkUrl,
    qualityKeys = availableQualities.mapTo(linkedSetOf()) { it.toMusicFreeQuality() },
    rawPayload = sourcePayload,
)

private fun RawSourceQuality.toMusicFreeQuality(): String = when (this) {
    RawSourceQuality.Standard -> "standard"
    RawSourceQuality.High -> "320k"
    RawSourceQuality.Super -> "super"
    RawSourceQuality.Lossless -> "flac"
    RawSourceQuality.HiRes -> "flac24bit"
}

private fun RawSourceMediaType.toMusicFreeType(): MusicFreeMediaType = when (this) {
    RawSourceMediaType.Music -> MusicFreeMediaType.Music
    RawSourceMediaType.Album -> MusicFreeMediaType.Album
    RawSourceMediaType.Artist -> MusicFreeMediaType.Artist
    RawSourceMediaType.Playlist -> MusicFreeMediaType.Sheet
}

private fun MusicFreeMediaType.toRawType(): RawSourceMediaType = when (this) {
    MusicFreeMediaType.Music -> RawSourceMediaType.Music
    MusicFreeMediaType.Album -> RawSourceMediaType.Album
    MusicFreeMediaType.Artist -> RawSourceMediaType.Artist
    MusicFreeMediaType.Sheet -> RawSourceMediaType.Playlist
}
