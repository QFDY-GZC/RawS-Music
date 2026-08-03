package com.rawsmusic.module.data.source.runtime

import android.content.Context
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.InstalledMusicSource
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Aggregates enabled MusicFree plugins and the Hacylon-style LX built-in catalogs. */
object MusicSourceSearchCoordinator {
    private const val SEARCH_PAGE_COUNT = 4
    private const val MAX_RESULTS = 120

    suspend fun search(
        context: Context,
        sources: List<InstalledMusicSource>,
        lxSources: List<InstalledLxSource> = emptyList(),
        query: String,
        page: Int = 1,
        type: RawSourceMediaType = RawSourceMediaType.Music,
    ): AggregatedMusicSourceSearch = coroutineScope {
        val eligible = sources.filter { source ->
            source.enabled && source.methods.any { it.equals("Search", ignoreCase = true) }
        }
        val musicFreeGroups = eligible.map { source ->
            async {
                runCatching {
                    val pages = (page until page + SEARCH_PAGE_COUNT).map { requestedPage ->
                        async {
                            MusicSourceRuntimeClient.search(
                                context = context,
                                source = source,
                                query = query,
                                page = requestedPage,
                                type = type,
                            )
                        }
                    }.awaitAll()
                    val items = pages
                        .flatMap(MusicSourceSearchGroup::items)
                        .distinctBy(RawSourceMediaItem::stableIdentity)
                    MusicSourceSearchGroup(
                        sourceId = source.id,
                        sourceName = source.name,
                        items = items,
                        isEnd = pages.lastOrNull()?.isEnd != false,
                    )
                }.getOrElse { error ->
                    MusicSourceSearchGroup(
                        sourceId = source.id,
                        sourceName = source.name,
                        items = emptyList(),
                        error = error.message.orEmpty().ifBlank { "音源搜索失败" }.take(1_024),
                    )
                }
            }
        }.awaitAll()
        musicFreeGroups.forEach { group -> MusicSourcePluginStore.setLastError(group.sourceId, group.error) }

        val preferredLxSource = lxSources
            .asSequence()
            .filter { it.enabled }
            .maxByOrNull(InstalledLxSource::updatedAtMs)
        val lxGroups = if (type == RawSourceMediaType.Music && preferredLxSource != null) {
            (page until page + SEARCH_PAGE_COUNT)
                .map { requestedPage ->
                    async {
                        LxCatalogSearchService.search(
                            source = preferredLxSource,
                            query = query,
                            page = requestedPage,
                        )
                    }
                }
                .awaitAll()
                .flatten()
                .groupBy(MusicSourceSearchGroup::sourceId)
                .map { (_, pages) ->
                    pages.first().copy(
                        items = pages
                            .flatMap(MusicSourceSearchGroup::items)
                            .distinctBy(RawSourceMediaItem::stableIdentity),
                        isEnd = pages.lastOrNull()?.isEnd != false,
                        error = pages.map(MusicSourceSearchGroup::error)
                            .firstOrNull(String::isNotBlank)
                            .orEmpty(),
                    )
                }
                .also { groups ->
                LxSourcePluginStore.setLastError(
                    preferredLxSource.id,
                    groups.map(MusicSourceSearchGroup::error).filter(String::isNotBlank).joinToString("；").take(1_024),
                )
            }
        } else {
            emptyList()
        }
        val sourceGroups = musicFreeGroups + lxGroups
        val sourceNamesByIdentity = sourceGroups
            .flatMap { group -> group.items.map { item -> item.stableIdentity to group.sourceName } }
            .toMap()
        val ranked = sourceGroups
            .flatMap(MusicSourceSearchGroup::items)
            .distinctBy { item ->
                listOf(item.title, item.artists.joinToString(), item.album)
                    .joinToString("|")
                    .lowercase()
            }
            .sortedWith(
                compareByDescending<RawSourceMediaItem> { relevanceScore(it, query) }
                    .thenBy { it.title.length }
                    .thenBy(RawSourceMediaItem::title)
            )
            .take(MAX_RESULTS)
        AggregatedMusicSourceSearch(
            // One-item groups preserve the global relevance order while retaining the provider
            // label. The UI no longer renders group headers, so providers cannot form blocks.
            groups = ranked.map { item ->
                MusicSourceSearchGroup(
                    sourceId = "${item.sourceId}:${item.remoteId}",
                    sourceName = sourceNamesByIdentity[item.stableIdentity].orEmpty().ifBlank { "在线音源" },
                    items = listOf(item),
                )
            } + sourceGroups.filter { it.error.isNotBlank() }
        )
    }

    private fun relevanceScore(item: RawSourceMediaItem, rawQuery: String): Int {
        val query = rawQuery.trim().lowercase()
        if (query.isBlank()) return 0
        val title = item.title.trim().lowercase()
        val artists = item.artists.joinToString(" ").lowercase()
        val album = item.album.trim().lowercase()
        return when {
            title == query -> 10_000
            title.startsWith(query) -> 8_000
            title.contains(query) -> 6_000
            artists == query -> 5_000
            artists.contains(query) -> 4_000
            album == query -> 3_000
            album.contains(query) -> 2_000
            else -> 0
        }
    }
}
