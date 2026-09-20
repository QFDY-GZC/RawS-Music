package com.rawsmusic.separation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.VoiceActivityMap
import com.rawsmusic.core.common.model.VoiceActivitySpan

internal object AiVocalActivityMapParser {
    fun parse(
        json: String,
        expectedSourceIdentity: String,
        expectedAnalyzerVersion: String,
    ): VoiceActivityMap {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.intValue("schemaVersion") == 1) { "Unsupported voice activity schema" }
        val sourceIdentity = root.stringValue("sourceIdentity")
        val analyzerVersion = root.stringValue("analyzerVersion")
        require(sourceIdentity == expectedSourceIdentity) { "Voice activity source identity mismatch" }
        require(analyzerVersion == expectedAnalyzerVersion) { "Voice activity analyzer version mismatch" }
        val spans = root.getAsJsonArray("spans")?.map { element ->
            val item = element.asJsonObject
            VoiceActivitySpan(
                startMs = item.longValue("startMs"),
                endMs = item.longValue("endMs"),
                confidence = item.floatValue("confidence"),
            )
        }.orEmpty()
        return VoiceActivityMap(
            sourceIdentity = sourceIdentity,
            analyzerVersion = analyzerVersion,
            sampleRate = root.intValue("sampleRate"),
            hopMs = root.longValue("hopMs"),
            durationMs = root.longValue("durationMs"),
            spans = spans,
            generatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun JsonObject.required(name: String) = get(name)?.takeUnless { it.isJsonNull }
        ?: error("Voice activity payload is missing $name")

    private fun JsonObject.stringValue(name: String): String = required(name).asString

    private fun JsonObject.intValue(name: String): Int = required(name).asInt

    private fun JsonObject.longValue(name: String): Long = required(name).asLong

    private fun JsonObject.floatValue(name: String): Float = required(name).asFloat
}
