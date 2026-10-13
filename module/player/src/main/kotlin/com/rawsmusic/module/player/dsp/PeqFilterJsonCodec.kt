package com.rawsmusic.module.player.dsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

object PeqFilterJsonCodec {
    private val canonicalKeys = setOf("type", "frequency", "gainDB", "Q", "enabled")
    private val historicalKeys = setOf("a", "b", "c", "d", "e")
    // Confirmed against this project's release mapping; not a generic obfuscation guess.
    private val historicalTypes = mapOf("h" to FilterType.PEAK, "i" to FilterType.LOW_SHELF,
        "j" to FilterType.HIGH_SHELF, "k" to FilterType.LOW_PASS, "l" to FilterType.HIGH_PASS,
        "m" to FilterType.BAND_PASS, "n" to FilterType.NOTCH)

    fun encode(filters: List<PEQFilter>): String = JsonArray().apply {
        filters.forEach { filter ->
            require(filter.frequency.isFinite() && filter.gainDB.isFinite() && filter.Q.isFinite()) { "PEQ 参数不是有限数值" }
            add(JsonObject().apply {
                addProperty("type", filter.type.name)
                addProperty("frequency", filter.frequency)
                addProperty("gainDB", filter.gainDB)
                addProperty("Q", filter.Q)
                addProperty("enabled", filter.enabled)
            })
        }
    }.toString()

    @Suppress("DEPRECATION")
    fun decode(json: String): List<PEQFilter> {
        if (json.isBlank()) return emptyList()
        try {
            return JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "PEQ 数据不是数组" }
                reader.beginArray()
                val result = mutableListOf<PEQFilter>()
                while (reader.hasNext()) {
                    require(result.size < PEQFilter.MAX_FILTERS && reader.peek() == JsonToken.BEGIN_OBJECT) { "PEQ 条目异常" }
                    reader.beginObject()
                    val values = linkedMapOf<String, Any>()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name !in values) { "PEQ 字段重复：$name" }
                        values[name] = when (reader.peek()) {
                            JsonToken.STRING -> reader.nextString()
                            JsonToken.BOOLEAN -> reader.nextBoolean()
                            JsonToken.NUMBER -> reader.nextDouble().toFloat().also { require(it.isFinite()) { "PEQ 数值溢出" } }
                            else -> throw IllegalArgumentException("PEQ 字段类型异常")
                        }
                    }
                    reader.endObject()
                    val historical = values.keys == historicalKeys
                    require(historical || values.keys == canonicalKeys) { "PEQ 字段不完整或无法识别" }
                    val token = values[if (historical) "a" else "type"] as? String
                        ?: throw IllegalArgumentException("PEQ 滤波器类型异常")
                    val type = FilterType.entries.firstOrNull { it.name == token }
                        ?: if (historical) historicalTypes[token] else null
                    require(type != null) { "PEQ 滤波器类型未知：$token" }
                    fun numeric(key: String, oldKey: String) = values[if (historical) oldKey else key] as? Float
                        ?: throw IllegalArgumentException("PEQ 数值类型异常：$key")
                    val enabled = values[if (historical) "e" else "enabled"] as? Boolean
                        ?: throw IllegalArgumentException("PEQ 开关类型异常")
                    result += PEQFilter(type, numeric("frequency", "b"), numeric("gainDB", "c"), numeric("Q", "d"), enabled)
                }
                reader.endArray()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "PEQ 数据含尾随内容" }
                result
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("PEQ 数据格式无效", error)
        }
    }
}
