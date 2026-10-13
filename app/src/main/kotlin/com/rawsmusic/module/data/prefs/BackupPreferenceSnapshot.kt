package com.rawsmusic.module.data.prefs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.ByteBuffer

/** Private recovery format, not a portable archive: original values need not satisfy new rules. */
internal object BackupPreferenceSnapshot {
    fun encode(values: Map<PreferenceAddress, PreferenceValue?>): ByteArray {
        val array = JsonArray()
        values.forEach { (address, value) ->
            array.add(JsonObject().apply {
                addProperty("store", address.store)
                addProperty("key", address.key)
                addProperty("present", value != null)
                if (value != null) {
                    addProperty("type", value.type.name)
                    when (value) {
                        is PreferenceValue.BooleanValue -> addProperty("value", value.value)
                        is PreferenceValue.IntValue -> addProperty("value", value.value)
                        is PreferenceValue.LongValue -> addProperty("value", value.value)
                        // A rollback must also retain signed zero and original non-finite bit patterns.
                        is PreferenceValue.FloatValue -> addProperty("value", value.value.toRawBits())
                        is PreferenceValue.DoubleValue -> addProperty("value", value.value.toRawBits())
                        is PreferenceValue.StringValue -> addProperty("value", value.value)
                        is PreferenceValue.StringSetValue -> add("value", JsonArray().apply { value.value.sorted().forEach(::add) })
                    }
                }
            })
        }
        return array.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= BackupBoundedReader.MAX_BYTES) { "偏好恢复快照过大" }
        }
    }

    fun decode(bytes: ByteArray): Map<PreferenceAddress, PreferenceValue?> {
        require(bytes.size <= BackupBoundedReader.MAX_BYTES) { "偏好恢复快照过大" }
        val root = BackupStrictJson.parse(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        require(root.isJsonArray) { "偏好快照格式异常" }
        return buildMap {
            root.asJsonArray.forEach { element ->
                require(element.isJsonObject) { "偏好快照条目异常" }
                val entry = element.asJsonObject
                val address = PreferenceAddress(string(entry.get("store")), string(entry.get("key")))
                require(address.store.isNotBlank() && address.key.isNotBlank() && !containsKey(address)) { "偏好快照键为空或重复" }
                val present = boolean(entry.get("present"))
                if (!present) {
                    require(!entry.has("type") && !entry.has("value")) { "缺失键不应包含值" }
                    put(address, null)
                } else {
                    require(entry.has("value")) { "偏好快照缺少原值" }
                    val value = entry.get("value")
                    put(address, when (BackupPreferenceSchema.ValueType.valueOf(string(entry.get("type")))) {
                        BackupPreferenceSchema.ValueType.BOOLEAN -> PreferenceValue.BooleanValue(boolean(value))
                        BackupPreferenceSchema.ValueType.INT -> PreferenceValue.IntValue(int(value))
                        BackupPreferenceSchema.ValueType.LONG -> PreferenceValue.LongValue(BackupStrictJson.integer(value))
                        BackupPreferenceSchema.ValueType.FLOAT -> PreferenceValue.FloatValue(Float.fromBits(int(value)))
                        BackupPreferenceSchema.ValueType.DOUBLE -> PreferenceValue.DoubleValue(Double.fromBits(BackupStrictJson.integer(value)))
                        BackupPreferenceSchema.ValueType.STRING -> PreferenceValue.StringValue(string(value))
                        BackupPreferenceSchema.ValueType.STRING_SET -> {
                            require(value.isJsonArray) { "偏好快照集合异常" }
                            val strings = value.asJsonArray.map(::string)
                            require(strings.distinct().size == strings.size) { "偏好快照集合重复" }
                            PreferenceValue.StringSetValue(strings.toSet())
                        }
                    })
                }
            }
        }
    }

    private fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "偏好快照字符串异常" }
        return value.asString
    }
    private fun boolean(value: JsonElement?): Boolean {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "偏好快照布尔值异常" }
        return value.asBoolean
    }
    private fun int(value: JsonElement): Int {
        val number = BackupStrictJson.integer(value)
        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "偏好快照整数溢出" }
        return number.toInt()
    }
}
