package com.rawsmusic.module.data.source.runtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted search history and bounded result snapshots for the online-source portal. */
data class MusicSourceSearchSession(
    val query: String,
    val result: AggregatedMusicSourceSearch,
    val searchedAtMs: Long,
)

/** Uses explicit JSON fields so release R8 names never affect restored result data. */
object MusicSourceSearchStore {
    private const val HISTORY_KEY = "music_source_search_history_v1"
    private const val SESSION_KEY = "music_source_search_sessions_v1"
    private const val MAX_HISTORY = 15
    private const val MAX_SESSIONS = 6
    private const val MAX_GROUP_ITEMS = 120
    private const val MAX_ITEM_PAYLOAD_CHARS = 64 * 1024
    private const val MAX_SESSION_PAYLOAD_CHARS = 512 * 1024

    private val mutableHistory = MutableStateFlow(loadHistory())
    val history = mutableHistory.asStateFlow()

    private val mutableSessions = MutableStateFlow(loadSessions())
    val sessions = mutableSessions.asStateFlow()

    fun remember(query: String, result: AggregatedMusicSourceSearch) {
        val normalizedQuery = query.trim().take(160)
        if (normalizedQuery.isBlank()) return
        mutableHistory.value = (listOf(normalizedQuery) + mutableHistory.value.filterNot {
            it.equals(normalizedQuery, ignoreCase = true)
        }).take(MAX_HISTORY)

        val session = MusicSourceSearchSession(
            query = normalizedQuery,
            result = result.boundedForPersistence(),
            searchedAtMs = System.currentTimeMillis(),
        )
        mutableSessions.value = (listOf(session) + mutableSessions.value.filterNot {
            it.query.equals(normalizedQuery, ignoreCase = true)
        }).take(MAX_SESSIONS)
        persist()
    }

    fun resultFor(query: String): AggregatedMusicSourceSearch? {
        val normalized = query.trim()
        if (normalized.isBlank()) return null
        return mutableSessions.value.firstOrNull {
            it.query.equals(normalized, ignoreCase = true)
        }?.result
    }

    fun latest(): MusicSourceSearchSession? = mutableSessions.value.maxByOrNull { it.searchedAtMs }

    fun removeHistory(query: String) {
        mutableHistory.value = mutableHistory.value.filterNot { it.equals(query, ignoreCase = true) }
        persist()
    }

    fun clearHistory() {
        mutableHistory.value = emptyList()
        persist()
    }

    /** Clears every persisted result snapshot while keeping the user's search history. */
    fun clearResults() {
        mutableSessions.value = emptyList()
        persist()
    }

    /** Removes only one saved result snapshot. */
    fun removeResult(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        mutableSessions.value = mutableSessions.value.filterNot {
            it.query.equals(normalized, ignoreCase = true)
        }
        persist()
    }

    private fun AggregatedMusicSourceSearch.boundedForPersistence(): AggregatedMusicSourceSearch {
        var payloadBudget = MAX_SESSION_PAYLOAD_CHARS
        return AggregatedMusicSourceSearch(
            groups = groups.map { group ->
                group.copy(
                    items = group.items.take(MAX_GROUP_ITEMS).mapNotNull { item ->
                        val completePayload = item.sourcePayload.takeIf { it.length <= MAX_ITEM_PAYLOAD_CHARS } ?: "{}"
                        val cost = completePayload.length
                        if (cost > payloadBudget) return@mapNotNull null
                        payloadBudget -= cost
                        item.copy(sourcePayload = completePayload)
                    },
                    error = group.error.take(1_024),
                )
            }
        )
    }

    private fun loadHistory(): List<String> = runCatching {
        val raw = AppPreferences.storage.decodeString(HISTORY_KEY, "").orEmpty()
        if (raw.isBlank()) return@runCatching emptyList()
        JsonParser.parseString(raw).asJsonArray
            .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim() }
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_HISTORY)
    }.getOrDefault(emptyList())

    private fun loadSessions(): List<MusicSourceSearchSession> = runCatching {
        val raw = AppPreferences.storage.decodeString(SESSION_KEY, "").orEmpty()
        if (raw.isBlank()) return@runCatching emptyList()
        JsonParser.parseString(raw).asJsonArray.mapNotNull(::parseSession).take(MAX_SESSIONS)
    }.getOrDefault(emptyList())

    private fun parseSession(element: com.google.gson.JsonElement): MusicSourceSearchSession? = runCatching {
        val obj = element.asJsonObject
        val query = obj.string("query").trim()
        if (query.isBlank()) return null
        val groups = obj.getAsJsonArray("groups")?.mapNotNull { groupElement ->
            val group = groupElement.asJsonObject
            MusicSourceSearchGroup(
                sourceId = group.string("sourceId"),
                sourceName = group.string("sourceName"),
                items = group.getAsJsonArray("items")?.mapNotNull(::parseItem).orEmpty(),
                isEnd = group.get("isEnd")?.asBoolean ?: true,
                error = group.string("error"),
            )
        }.orEmpty()
        MusicSourceSearchSession(
            query = query,
            result = AggregatedMusicSourceSearch(groups),
            searchedAtMs = obj.get("searchedAtMs")?.asLong ?: 0L,
        )
    }.getOrNull()

    private fun parseItem(element: com.google.gson.JsonElement): RawSourceMediaItem? = runCatching {
        val obj = element.asJsonObject
        RawSourceMediaItem(
            sourceId = obj.string("sourceId"),
            remoteId = obj.string("remoteId"),
            mediaType = runCatching { RawSourceMediaType.valueOf(obj.string("mediaType")) }
                .getOrDefault(RawSourceMediaType.Music),
            title = obj.string("title"),
            artists = obj.getAsJsonArray("artists")?.mapNotNull { it.asString }.orEmpty(),
            album = obj.string("album"),
            durationMs = obj.get("durationMs")?.asLong ?: 0L,
            artworkUrl = obj.string("artworkUrl"),
            availableQualities = obj.getAsJsonArray("availableQualities")
                ?.mapNotNull { value -> runCatching { RawSourceQuality.valueOf(value.asString) }.getOrNull() }
                ?.toSet()
                ?.ifEmpty { setOf(RawSourceQuality.Standard) }
                ?: setOf(RawSourceQuality.Standard),
            sourcePayload = obj.string("sourcePayload"),
        )
    }.getOrNull()

    private fun persist() {
        AppPreferences.storage.encode(HISTORY_KEY, JsonArray().apply {
            mutableHistory.value.forEach { add(it) }
        }.toString())
        AppPreferences.storage.encode(SESSION_KEY, JsonArray().apply {
            mutableSessions.value.forEach { add(it.toJson()) }
        }.toString())
    }

    private fun MusicSourceSearchSession.toJson(): JsonObject = JsonObject().apply {
        addProperty("query", query)
        addProperty("searchedAtMs", searchedAtMs)
        add("groups", JsonArray().apply {
            result.groups.forEach { group ->
                add(JsonObject().apply {
                    addProperty("sourceId", group.sourceId)
                    addProperty("sourceName", group.sourceName)
                    addProperty("isEnd", group.isEnd)
                    addProperty("error", group.error)
                    add("items", JsonArray().apply { group.items.forEach { add(it.toJson()) } })
                })
            }
        })
    }

    private fun RawSourceMediaItem.toJson(): JsonObject = JsonObject().apply {
        addProperty("sourceId", sourceId)
        addProperty("remoteId", remoteId)
        addProperty("mediaType", mediaType.name)
        addProperty("title", title)
        add("artists", JsonArray().apply { artists.forEach { add(it) } })
        addProperty("album", album)
        addProperty("durationMs", durationMs)
        addProperty("artworkUrl", artworkUrl)
        add("availableQualities", JsonArray().apply { availableQualities.forEach { add(it.name) } })
        addProperty("sourcePayload", sourcePayload)
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
}
