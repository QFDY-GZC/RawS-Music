package com.rawsmusic.core.common.source

/**
 * RawSMusic online-source protocol.
 *
 * The source layer only returns serializable media data. It must not access the
 * local playback engine, USB exclusive output, Activity, Compose, or arbitrary
 * application files.
 */
enum class RawSourceMediaType {
    Music,
    Album,
    Artist,
    Playlist,
}

enum class RawSourceQuality(val key: String) {
    Standard("standard"),
    High("high"),
    Super("super"),
    Lossless("lossless"),
    HiRes("hi-res");

    companion object {
        fun fromKey(value: String?): RawSourceQuality = when (value?.lowercase()) {
            "high", "320k" -> High
            "super" -> Super
            "lossless", "flac" -> Lossless
            "hi-res", "hires", "flac24bit" -> HiRes
            else -> Standard
        }
    }
}

data class RawSourceManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val supportedTypes: Set<RawSourceMediaType> = setOf(RawSourceMediaType.Music),
    val capabilities: Set<RawSourceCapability> = emptySet(),
)

enum class RawSourceCapability {
    Search,
    ResolveAudio,
    Lyric,
    MusicInfo,
    ImportMusic,
    ImportPlaylist,
    Album,
    Artist,
    Playlist,
}

data class RawSourceMediaItem(
    val sourceId: String,
    val remoteId: String,
    val mediaType: RawSourceMediaType,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String = "",
    val availableQualities: Set<RawSourceQuality> = setOf(RawSourceQuality.Standard),
    /** Opaque source payload returned unchanged on later source calls. */
    val sourcePayload: String = "",
) {
    val stableIdentity: String
        get() = "remote://$sourceId/$remoteId"
}

data class RawSourcePage<T>(
    val data: List<T>,
    val isEnd: Boolean = true,
)

data class RawResolvedAudioSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val quality: RawSourceQuality = RawSourceQuality.Standard,
    val expiresAtMs: Long? = null,
)

data class RawSourceLyric(
    val original: String = "",
    val translation: String = "",
    val romanization: String = "",
    val wordByWord: String = "",
)

interface RawMusicSourcePlugin {
    val manifest: RawSourceManifest

    suspend fun search(
        query: String,
        page: Int,
        type: RawSourceMediaType,
    ): RawSourcePage<RawSourceMediaItem>

    suspend fun resolveAudio(
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource?

    suspend fun getLyric(item: RawSourceMediaItem): RawSourceLyric? = null

    suspend fun getMusicInfo(item: RawSourceMediaItem): RawSourceMediaItem? = null

    suspend fun importMusic(input: String): RawSourceMediaItem? = null

    suspend fun importPlaylist(input: String): List<RawSourceMediaItem>? = null
}
