package com.rawsmusic.separation

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.VoiceActivityMap
import com.rawsmusic.core.common.model.VoiceActivitySpan
import java.io.File
import java.security.MessageDigest

/** Disk cache for fast lyric alignment analysis.
 *
 * The key includes all three inputs that affect timing: audio fingerprint, lyric fingerprint and
 * model version. The cache stores only activity intervals, never a separated WAV/FLAC stem.
 */
class AiFastVocalAlignmentCacheStore private constructor(context: Context) {
    private val directory = File(
        context.applicationContext.filesDir,
        "ai_separation/fast_alignment",
    ).apply { mkdirs() }

    fun load(
        audioFingerprint: String,
        lyricFingerprint: String,
        modelVersion: String,
    ): VoiceActivityMap? {
        val key = cacheKey(audioFingerprint, lyricFingerprint, modelVersion)
        val file = File(directory, "$key.json")
        if (!file.isFile) return null
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            require(root.string("cacheKey") == key)
            require(root.string("audioFingerprint") == audioFingerprint)
            require(root.string("lyricFingerprint") == lyricFingerprint)
            require(root.string("modelVersion") == modelVersion)
            val activity = root.getAsJsonObject("activity")
            VoiceActivityMap(
                sourceIdentity = activity.string("sourceIdentity"),
                analyzerVersion = activity.string("analyzerVersion"),
                sampleRate = activity.int("sampleRate"),
                hopMs = activity.long("hopMs"),
                durationMs = activity.long("durationMs"),
                spans = activity.getAsJsonArray("spans")?.map { element ->
                    val span = element.asJsonObject
                    VoiceActivitySpan(
                        startMs = span.long("startMs"),
                        endMs = span.long("endMs"),
                        confidence = span.float("confidence"),
                    )
                }.orEmpty(),
                generatedAtEpochMs = activity.long("generatedAtEpochMs", 0L),
            )
        }.getOrNull()
    }

    fun save(
        audioFingerprint: String,
        lyricFingerprint: String,
        modelVersion: String,
        activityMap: VoiceActivityMap,
    ) {
        val key = cacheKey(audioFingerprint, lyricFingerprint, modelVersion)
        val target = File(directory, "$key.json")
        val temporary = File(directory, ".${key}.${System.nanoTime()}.tmp")
        runCatching {
            temporary.writeText(
                JsonObject().apply {
                    addProperty("schemaVersion", 1)
                    addProperty("cacheKey", key)
                    addProperty("audioFingerprint", audioFingerprint)
                    addProperty("lyricFingerprint", lyricFingerprint)
                    addProperty("modelVersion", modelVersion)
                    add("activity", activityJson(activityMap))
                }.toString(),
            )
            if (!temporary.renameTo(target)) {
                target.delete()
                require(temporary.renameTo(target)) { "无法提交快速歌词分析缓存" }
            }
        }.onFailure { temporary.delete() }.getOrThrow()
    }

    fun clear() {
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun activityJson(map: VoiceActivityMap): JsonObject = JsonObject().apply {
        addProperty("sourceIdentity", map.sourceIdentity)
        addProperty("analyzerVersion", map.analyzerVersion)
        addProperty("sampleRate", map.sampleRate)
        addProperty("hopMs", map.hopMs)
        addProperty("durationMs", map.durationMs)
        addProperty("generatedAtEpochMs", map.generatedAtEpochMs)
        add("spans", JsonArray().apply {
            map.spans.forEach { span ->
                add(JsonObject().apply {
                    addProperty("startMs", span.startMs)
                    addProperty("endMs", span.endMs)
                    addProperty("confidence", span.confidence)
                })
            }
        })
    }

    companion object {
        fun cacheKey(
            audioFingerprint: String,
            lyricFingerprint: String,
            modelVersion: String,
        ): String = sha256("$audioFingerprint|$lyricFingerprint|$modelVersion")

        @Volatile private var instance: AiFastVocalAlignmentCacheStore? = null

        fun get(context: Context): AiFastVocalAlignmentCacheStore =
            instance ?: synchronized(this) {
                instance ?: AiFastVocalAlignmentCacheStore(context).also { instance = it }
            }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun JsonObject.string(name: String): String = get(name)?.asString.orEmpty()
private fun JsonObject.int(name: String): Int = get(name)?.asInt ?: 0
private fun JsonObject.long(name: String, fallback: Long = 0L): Long =
    get(name)?.asLong ?: fallback
private fun JsonObject.float(name: String): Float = get(name)?.asFloat ?: 0f
