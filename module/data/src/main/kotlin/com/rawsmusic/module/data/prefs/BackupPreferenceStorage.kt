package com.rawsmusic.module.data.prefs

/**
 * Module-safe primitive access used by the backup codec.
 *
 * The data module owns the MMKV implementation dependency.  App/UI modules must not depend on
 * MMKV types just to export or restore preferences, so this facade exposes only Kotlin/JDK types.
 */
object BackupPreferenceStorage {
    private val storage get() = AppPreferences.storage

    fun allKeys(): List<String> = storage.allKeys()?.toList().orEmpty()

    fun contains(key: String): Boolean = storage.containsKey(key)

    fun readBoolean(key: String, defaultValue: Boolean = false): Boolean =
        storage.decodeBool(key, defaultValue)

    fun readInt(key: String, defaultValue: Int = 0): Int =
        storage.decodeInt(key, defaultValue)

    fun readLong(key: String, defaultValue: Long = 0L): Long =
        storage.decodeLong(key, defaultValue)

    fun readFloat(key: String, defaultValue: Float = 0f): Float =
        storage.decodeFloat(key, defaultValue)

    fun readDouble(key: String, defaultValue: Double = 0.0): Double =
        storage.decodeDouble(key, defaultValue)

    fun readString(key: String, defaultValue: String = ""): String =
        storage.decodeString(key, defaultValue).orEmpty()

    fun readStringSet(key: String): Set<String> =
        storage.decodeStringSet(key, emptySet()).orEmpty().toSet()

    fun writeBoolean(key: String, value: Boolean): Boolean = storage.encode(key, value)

    fun writeInt(key: String, value: Int): Boolean = storage.encode(key, value)

    fun writeLong(key: String, value: Long): Boolean = storage.encode(key, value)

    fun writeFloat(key: String, value: Float): Boolean = storage.encode(key, value)

    fun writeDouble(key: String, value: Double): Boolean = storage.encode(key, value)

    fun writeString(key: String, value: String): Boolean = storage.encode(key, value)

    fun writeStringSet(key: String, value: Set<String>): Boolean = storage.encode(key, value)

    fun sync() = AppPreferences.sync()
}
