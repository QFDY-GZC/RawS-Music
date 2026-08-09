package com.rawsmusic.core.common.source.lx

import com.rawsmusic.core.common.source.RawMusicSourcePlugin
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceCapability
import com.rawsmusic.core.common.source.RawSourceManifest
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourcePage
import com.rawsmusic.core.common.source.RawSourceQuality

enum class LxSourceFormat(val wireName: String) {
    UserApi("userApi"),
    RenderApi("renderApi"),
}

enum class LxSourceAction(val wireName: String) {
    MusicUrl("musicUrl"),
    Lyric("lyric"),
    Pic("pic");

    companion object {
        fun fromWireName(value: String): LxSourceAction? = entries.firstOrNull {
            it.wireName.equals(value.trim(), ignoreCase = true)
        }
    }
}

data class LxPlatformCapability(
    val platform: String,
    val qualities: Set<String> = emptySet(),
    val actions: Set<LxSourceAction> = setOf(LxSourceAction.MusicUrl),
)

data class LxSourceDescriptor(
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val homepage: String = "",
    val platforms: List<LxPlatformCapability> = emptyList(),
)

/**
 * Isolated runtime boundary for the LX User API protocol.
 *
 * LX sources are URL/lyric/picture resolvers for catalog items supplied by another provider;
 * they are not search providers. The adapter therefore exposes an empty search result while
 * keeping RawSMusic's own media and resolved-audio contracts unchanged.
 */
interface LxSourceGateway {
    val descriptor: LxSourceDescriptor

    suspend fun resolveAudio(
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource?
}

class LxCompatAdapter(
    private val gateway: LxSourceGateway,
) : RawMusicSourcePlugin {
    override val manifest: RawSourceManifest = RawSourceManifest(
        id = "lx:${gateway.descriptor.name.trim().lowercase()}",
        name = gateway.descriptor.name,
        version = gateway.descriptor.version,
        author = gateway.descriptor.author,
        description = gateway.descriptor.description,
        supportedTypes = setOf(RawSourceMediaType.Music),
        capabilities = setOf(RawSourceCapability.ResolveAudio),
    )

    override suspend fun search(
        query: String,
        page: Int,
        type: RawSourceMediaType,
    ): RawSourcePage<RawSourceMediaItem> = RawSourcePage(emptyList())

    override suspend fun resolveAudio(
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource? = gateway.resolveAudio(item, quality)
}
