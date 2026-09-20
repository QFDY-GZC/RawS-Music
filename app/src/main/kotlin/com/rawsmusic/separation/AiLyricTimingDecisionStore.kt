package com.rawsmusic.separation

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.LyricTimingCorrectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Stores only the user's preview decision; lyric and audio data remain outside this cache. */
class AiLyricTimingDecisionStore private constructor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ai_separation/lyric_timing")

    suspend fun load(correction: LyricTimingCorrection): LyricTimingCorrectionStatus? =
        withContext(Dispatchers.IO) {
            val file = decisionFile(correction)
            if (!file.isFile) return@withContext null
            runCatching {
                val json = JsonParser.parseString(file.readText()).asJsonObject
                val source = json.get("sourceIdentity")?.asString
                val hash = json.get("originalLyricHash")?.asString
                val version = json.get("analyzerVersion")?.asString
                if (source != correction.sourceIdentity ||
                    hash != correction.originalLyricHash ||
                    version != correction.analyzerVersion
                ) {
                    return@runCatching null
                }
                json.get("status")?.asString?.let(LyricTimingCorrectionStatus::valueOf)
            }.getOrNull()
        }

    suspend fun save(correction: LyricTimingCorrection): Unit = withContext(Dispatchers.IO) {
        require(correction.status in setOf(
            LyricTimingCorrectionStatus.PREVIEW,
            LyricTimingCorrectionStatus.ACCEPTED,
            LyricTimingCorrectionStatus.REJECTED,
        )) { "不支持保存当前歌词修正状态" }
        require(root.isDirectory || root.mkdirs()) { "无法创建歌词修正缓存目录" }
        val target = decisionFile(correction)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("sourceIdentity", correction.sourceIdentity)
            addProperty("originalLyricHash", correction.originalLyricHash)
            addProperty("analyzerVersion", correction.analyzerVersion)
            addProperty("correctionId", correction.correctionId)
            addProperty("status", correction.status.name)
            addProperty("changedLines", correction.changes.size)
            addProperty("updatedAtEpochMs", System.currentTimeMillis())
        }.toString())
        if (target.exists()) require(target.delete()) { "无法替换歌词修正决定" }
        require(temporary.renameTo(target)) { "无法提交歌词修正决定" }
    }

    fun clear() {
        root.deleteRecursively()
    }

    private fun decisionFile(correction: LyricTimingCorrection): File = File(
        root,
        "${digest(correction.sourceIdentity + "\u0000" + correction.originalLyricHash + "\u0000" + correction.analyzerVersion)}.json",
    )

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        @Volatile private var instance: AiLyricTimingDecisionStore? = null

        fun get(context: Context): AiLyricTimingDecisionStore = instance ?: synchronized(this) {
            instance ?: AiLyricTimingDecisionStore(context).also { instance = it }
        }
    }
}
