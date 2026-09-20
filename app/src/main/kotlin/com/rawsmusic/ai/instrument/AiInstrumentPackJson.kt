package com.rawsmusic.ai.instrument

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Stable JSON codec for instrument-pack manifests.
 *
 * Do not deserialize [AiInstrumentPackManifest] through Gson reflection. In an optimized Android
 * build the generic signature of `samples: List<AiInstrumentSampleManifest>` is not a safe runtime
 * schema boundary: Gson can materialize list elements as LinkedTreeMap and the first typed access
 * then fails with ClassCastException. This codec treats the JSON field names as the public pack
 * contract and constructs every Kotlin value explicitly, so R8 name/generic transformations cannot
 * change the on-disk format or element type.
 */
object AiInstrumentPackJson {
    fun parse(text: String): AiInstrumentPackManifest {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { error("无法解析乐器 manifest: ${it.message ?: "JSON 无效"}") }
        val samplesJson = root.required("samples").takeIf { it.isJsonArray }?.asJsonArray
            ?: error("乐器 manifest samples 必须是数组")
        val samples = ArrayList<AiInstrumentSampleManifest>(samplesJson.size())
        samplesJson.forEachIndexed { index, element ->
            val sample = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: error("乐器 manifest sample[$index] 必须是对象")
            samples += AiInstrumentSampleManifest(
                path = sample.requiredString("path"),
                rootMidiNote = sample.requiredInt("rootMidiNote"),
                velocityMin = sample.optionalInt("velocityMin", 1),
                velocityMax = sample.optionalInt("velocityMax", 127),
                gainDb = sample.optionalFloat("gainDb", 0f),
                loopStartFrame = sample.optionalLong("loopStartFrame"),
                loopEndFrameExclusive = sample.optionalLong("loopEndFrameExclusive"),
                sha256 = sample.requiredString("sha256"),
            ).validated()
        }
        return AiInstrumentPackManifest(
            schemaVersion = root.requiredInt("schemaVersion"),
            id = root.requiredString("id"),
            version = root.requiredString("version"),
            displayName = root.requiredString("displayName"),
            instrument = root.requiredString("instrument"),
            sampleRate = root.requiredInt("sampleRate"),
            channels = root.requiredInt("channels"),
            licenseFile = root.requiredString("licenseFile"),
            samples = samples,
        ).validated()
    }

    fun stringify(manifest: AiInstrumentPackManifest): String {
        manifest.validated()
        val root = JsonObject().apply {
            addProperty("schemaVersion", manifest.schemaVersion)
            addProperty("id", manifest.id)
            addProperty("version", manifest.version)
            addProperty("displayName", manifest.displayName)
            addProperty("instrument", manifest.instrument)
            addProperty("sampleRate", manifest.sampleRate)
            addProperty("channels", manifest.channels)
            addProperty("licenseFile", manifest.licenseFile)
        }
        val samples = com.google.gson.JsonArray()
        manifest.samples.forEach { sample ->
            samples.add(JsonObject().apply {
                addProperty("path", sample.path)
                addProperty("rootMidiNote", sample.rootMidiNote)
                addProperty("velocityMin", sample.velocityMin)
                addProperty("velocityMax", sample.velocityMax)
                addProperty("gainDb", sample.gainDb)
                sample.loopStartFrame?.let { addProperty("loopStartFrame", it) }
                sample.loopEndFrameExclusive?.let { addProperty("loopEndFrameExclusive", it) }
                addProperty("sha256", sample.sha256)
            })
        }
        root.add("samples", samples)
        return root.toString()
    }

    private fun JsonObject.required(name: String): JsonElement =
        get(name)?.takeUnless { it.isJsonNull } ?: error("乐器 manifest 缺少 $name")

    private fun JsonObject.requiredString(name: String): String = runCatching { required(name).asString }
        .getOrElse { error("乐器 manifest $name 类型无效") }

    private fun JsonObject.requiredInt(name: String): Int = runCatching { required(name).asInt }
        .getOrElse { error("乐器 manifest $name 类型无效") }

    private fun JsonObject.optionalInt(name: String, fallback: Int): Int {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return fallback
        return runCatching { value.asInt }.getOrElse { error("乐器 manifest $name 类型无效") }
    }

    private fun JsonObject.optionalFloat(name: String, fallback: Float): Float {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return fallback
        return runCatching { value.asFloat }.getOrElse { error("乐器 manifest $name 类型无效") }
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        return runCatching { value.asLong }.getOrElse { error("乐器 manifest $name 类型无效") }
    }
}
