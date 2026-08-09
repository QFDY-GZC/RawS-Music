package com.rawsmusic.module.data.source

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Explicit persistence codec for imported source metadata.
 *
 * Do not use reflective Gson deserialization for these records. Release/R8 may remove nested
 * generic signatures, causing JSON arrays to be restored as ArrayList even when the Kotlin model
 * declares Set. The UI/runtime then fails with ArrayList cannot be cast to Set.
 */
internal object MusicSourcePersistenceJson {
    fun decodeMusicFreeSources(json: String): List<InstalledMusicSource> =
        decodeArray(json) { element -> element.asObjectOrNull()?.toMusicFreeSource() }

    fun encodeMusicFreeSources(sources: List<InstalledMusicSource>): String = JsonArray().apply {
        sources.forEach { source -> add(source.toJsonObject()) }
    }.toString()

    fun decodeLxSources(json: String): List<InstalledLxSource> =
        decodeArray(json) { element -> element.asObjectOrNull()?.toLxSource() }

    fun encodeLxSources(sources: List<InstalledLxSource>): String = JsonArray().apply {
        sources.forEach { source -> add(source.toJsonObject()) }
    }.toString()

    private inline fun <T> decodeArray(
        json: String,
        transform: (JsonElement) -> T?,
    ): List<T> {
        if (json.isBlank()) return emptyList()
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull(transform)
    }

    private fun JsonObject.toMusicFreeSource(): InstalledMusicSource? {
        val id = string("id")
        val scriptPath = string("scriptPath")
        val scriptSha256 = string("scriptSha256")
        if (id.isBlank() || scriptPath.isBlank() || scriptSha256.isBlank()) return null
        val installedAt = long("installedAtMs", 0L)
        return InstalledMusicSource(
            id = id,
            name = string("name", id),
            version = string("version"),
            author = string("author"),
            description = string("description"),
            appVersion = string("appVersion"),
            sourceUrl = string("sourceUrl"),
            scriptPath = scriptPath,
            scriptSha256 = scriptSha256,
            origin = origin("origin"),
            enabled = boolean("enabled", true),
            methods = stringSet("methods"),
            installedAtMs = installedAt,
            updatedAtMs = long("updatedAtMs", installedAt),
            lastError = string("lastError"),
        )
    }

    private fun JsonObject.toLxSource(): InstalledLxSource? {
        val id = string("id")
        val scriptPath = string("scriptPath")
        val scriptSha256 = string("scriptSha256")
        if (id.isBlank() || scriptPath.isBlank() || scriptSha256.isBlank()) return null
        val installedAt = long("installedAtMs", 0L)
        val parsedActions = stringSet("actions")
        return InstalledLxSource(
            id = id,
            name = string("name", id),
            version = string("version"),
            author = string("author"),
            description = string("description"),
            homepage = string("homepage"),
            sourceUrl = string("sourceUrl"),
            scriptPath = scriptPath,
            scriptSha256 = scriptSha256,
            origin = origin("origin"),
            enabled = boolean("enabled", true),
            format = string("format", "userApi"),
            platforms = platformMap("platforms"),
            actions = parsedActions.ifEmpty { linkedSetOf("musicUrl") },
            installedAtMs = installedAt,
            updatedAtMs = long("updatedAtMs", installedAt),
            lastError = string("lastError"),
        )
    }

    private fun InstalledMusicSource.toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("version", version)
        addProperty("author", author)
        addProperty("description", description)
        addProperty("appVersion", appVersion)
        addProperty("sourceUrl", sourceUrl)
        addProperty("scriptPath", scriptPath)
        addProperty("scriptSha256", scriptSha256)
        addProperty("origin", origin.name)
        addProperty("enabled", enabled)
        add("methods", methods.toJsonArray())
        addProperty("installedAtMs", installedAtMs)
        addProperty("updatedAtMs", updatedAtMs)
        addProperty("lastError", lastError)
    }

    private fun InstalledLxSource.toJsonObject(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("version", version)
        addProperty("author", author)
        addProperty("description", description)
        addProperty("homepage", homepage)
        addProperty("sourceUrl", sourceUrl)
        addProperty("scriptPath", scriptPath)
        addProperty("scriptSha256", scriptSha256)
        addProperty("origin", origin.name)
        addProperty("enabled", enabled)
        addProperty("format", format)
        add("platforms", JsonObject().apply {
            platforms.toSortedMap().forEach { (platform, qualities) ->
                add(platform, qualities.toJsonArray())
            }
        })
        add("actions", actions.toJsonArray())
        addProperty("installedAtMs", installedAtMs)
        addProperty("updatedAtMs", updatedAtMs)
        addProperty("lastError", lastError)
    }

    private fun Iterable<String>.toJsonArray(): JsonArray = JsonArray().apply {
        this@toJsonArray
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .forEach(::add)
    }

    private fun JsonObject.string(name: String, fallback: String = ""): String =
        get(name).primitiveStringOrNull()?.trim().orEmpty().ifBlank { fallback }

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            runCatching { element.asBoolean }.getOrNull()
        } ?: fallback

    private fun JsonObject.long(name: String, fallback: Long): Long =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            runCatching { element.asLong }.getOrNull()
        } ?: fallback

    private fun JsonObject.origin(name: String): MusicSourceOrigin {
        val wire = string(name)
        return MusicSourceOrigin.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
            ?: MusicSourceOrigin.LocalFile
    }

    private fun JsonObject.stringSet(name: String): Set<String> =
        get(name).toStringSet()

    private fun JsonObject.platformMap(name: String): Map<String, Set<String>> {
        val value = get(name) ?: return emptyMap()
        if (!value.isJsonObject) return emptyMap()
        return linkedMapOf<String, Set<String>>().apply {
            value.asJsonObject.entrySet().forEach { (platform, qualities) ->
                val normalizedPlatform = platform.trim()
                if (normalizedPlatform.isNotEmpty()) {
                    put(normalizedPlatform, qualities.toStringSet())
                }
            }
        }
    }

    private fun JsonElement?.toStringSet(): Set<String> {
        if (this == null || isJsonNull) return emptySet()
        val result = linkedSetOf<String>()
        when {
            isJsonArray -> asJsonArray.forEach { element ->
                element.primitiveStringOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let(result::add)
            }
            isJsonPrimitive -> primitiveStringOrNull()
                ?.split(',', '|')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.forEach(result::add)
            isJsonObject -> asJsonObject.entrySet().forEach { (key, enabled) ->
                val include = runCatching { enabled.asBoolean }.getOrDefault(true)
                if (include && key.isNotBlank()) result += key.trim()
            }
        }
        return result
    }

    private fun JsonElement?.primitiveStringOrNull(): String? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return runCatching { asString }.getOrNull()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null
}
