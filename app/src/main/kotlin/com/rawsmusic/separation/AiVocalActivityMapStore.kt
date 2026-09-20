package com.rawsmusic.separation

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rawsmusic.core.common.model.VoiceActivityMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Private cache for voice-activity maps; generated audio folders stay media-library friendly. */
class AiVocalActivityMapStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_separation/activity_maps")

    suspend fun analyzeAndStore(
        resultId: String,
        vocalWav: File,
        analyzerVersion: String = ANALYZER_VERSION,
    ): Result<VoiceActivityMap> = withContext(Dispatchers.IO) {
        runCatching {
            require(resultId.matches(SAFE_TOKEN)) { "分离结果 ID 无效" }
            val existing = load(resultId, analyzerVersion)
            if (existing != null) return@runCatching existing
            val map = AiSeparationRuntimeBridge.analyzeVocalActivity(
                vocalWav = vocalWav,
                sourceIdentity = resultId,
                analyzerVersion = analyzerVersion,
            ).getOrThrow()
            require(root.isDirectory || root.mkdirs()) { "无法创建人声活动缓存目录" }
            val target = cacheFile(resultId, analyzerVersion)
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(toJson(map))
            require(temporary.renameTo(target)) { "无法提交人声活动缓存" }
            map
        }
    }

    suspend fun load(
        resultId: String,
        analyzerVersion: String = ANALYZER_VERSION,
    ): VoiceActivityMap? = withContext(Dispatchers.IO) {
        if (!resultId.matches(SAFE_TOKEN)) return@withContext null
        val file = cacheFile(resultId, analyzerVersion)
        if (!file.isFile) return@withContext null
        runCatching {
            AiVocalActivityMapParser.parse(
                json = file.readText(),
                expectedSourceIdentity = resultId,
                expectedAnalyzerVersion = analyzerVersion,
            )
        }.getOrNull()
    }

    private fun cacheFile(resultId: String, analyzerVersion: String): File =
        File(root, "${resultId}_${safeVersion(analyzerVersion)}.json")

    private fun safeVersion(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(32)
        .ifBlank { "v1" }

    private fun toJson(map: VoiceActivityMap): String = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("sourceIdentity", map.sourceIdentity)
        addProperty("analyzerVersion", map.analyzerVersion)
        addProperty("sampleRate", map.sampleRate)
        addProperty("hopMs", map.hopMs)
        addProperty("durationMs", map.durationMs)
        add("spans", JsonArray().apply {
            map.spans.forEach { span ->
                add(JsonObject().apply {
                    addProperty("startMs", span.startMs)
                    addProperty("endMs", span.endMs)
                    addProperty("confidence", span.confidence)
                })
            }
        })
    }.toString()

    companion object {
        const val ANALYZER_VERSION = "v1"
        private val SAFE_TOKEN = Regex("[A-Za-z0-9._-]{1,96}")

        @Volatile private var instance: AiVocalActivityMapStore? = null

        fun get(context: Context): AiVocalActivityMapStore = instance ?: synchronized(this) {
            instance ?: AiVocalActivityMapStore(context).also { instance = it }
        }
    }

    fun remove(resultId: String) {
        if (!resultId.matches(SAFE_TOKEN)) return
        cacheFile(resultId, ANALYZER_VERSION).delete()
        root.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("${resultId}_") && it.extension == "json" }
            .forEach { it.delete() }
    }

    fun clear() {
        root.deleteRecursively()
    }
}
