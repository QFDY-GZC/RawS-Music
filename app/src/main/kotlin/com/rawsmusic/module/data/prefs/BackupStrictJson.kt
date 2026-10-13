package com.rawsmusic.module.data.prefs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

/** Reject duplicate members before building the tree; Gson's normal tree parser overwrites them. */
internal object BackupStrictJson {
    fun parse(text: String): JsonElement {
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val tree = read(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON content" }
                return tree
            }
        } catch (failure: Exception) {
            if (failure is IllegalArgumentException) throw failure
            throw IllegalArgumentException("Invalid backup JSON", failure)
        }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 64) { "JSON nesting too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name)) { "Duplicate JSON member: $name" }
                    add(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(read(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Expected JSON value")
        }
    }

    fun number(value: JsonElement): Double {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Expected number" }
        return value.asDouble.also { require(it.isFinite()) { "Non-finite number" } }
    }

    fun integer(value: JsonElement): Long {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Expected integer" }
        return try { value.asBigDecimal.longValueExact() }
        catch (failure: ArithmeticException) { throw IllegalArgumentException("Invalid integer", failure) }
    }
}
