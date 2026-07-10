package com.rawsmusic.module.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.core.common.model.EqualizerPreset
import com.tencent.mmkv.MMKV

object EqualizerRepository {

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()

    private const val KEY_PRESETS = "eq_presets"
    private const val KEY_BUILT_IN_INITIALIZED = "eq_built_in_initialized"

    private val builtInPresets = listOf(
        EqualizerPreset(name = "Normal", bandLevels = listOf(0, 0, 0, 0, 0), isBuiltIn = true),
        EqualizerPreset(name = "Pop", bandLevels = listOf(-1, 1, 3, 2, -1), isBuiltIn = true),
        EqualizerPreset(name = "Rock", bandLevels = listOf(3, 1, -1, 1, 3), isBuiltIn = true),
        EqualizerPreset(name = "Jazz", bandLevels = listOf(2, 1, -1, 1, 2), isBuiltIn = true),
        EqualizerPreset(name = "Classical", bandLevels = listOf(3, 2, 0, 2, 3), isBuiltIn = true),
        EqualizerPreset(name = "Bass Boost", bandLevels = listOf(5, 4, 0, 0, 0), isBuiltIn = true),
        EqualizerPreset(name = "Treble Boost", bandLevels = listOf(0, 0, 0, 3, 5), isBuiltIn = true),
        EqualizerPreset(name = "Electronic", bandLevels = listOf(3, 1, -2, 1, 3), isBuiltIn = true),
        EqualizerPreset(name = "Acoustic", bandLevels = listOf(2, 1, 0, 1, 2), isBuiltIn = true),
        EqualizerPreset(name = "R&B", bandLevels = listOf(2, 3, 0, 2, 1), isBuiltIn = true)
    )

    fun getAllPresets(): List<EqualizerPreset> {
        initBuiltInPresets()
        val json = kv.decodeString(KEY_PRESETS, "") ?: return builtInPresets
        if (json.isBlank()) return builtInPresets
        return try {
            val type = object : TypeToken<List<EqualizerPreset>>() {}.type
            gson.fromJson(json, type) ?: builtInPresets
        } catch (_: Exception) {
            builtInPresets
        }
    }

    fun getPresetById(id: Long): EqualizerPreset? {
        return getAllPresets().find { it.id == id }
    }

    fun savePreset(preset: EqualizerPreset): Boolean {
        val presets = getAllPresets().toMutableList()
        val newId = if (presets.isEmpty()) 1L else presets.maxOf { it.id } + 1
        val newPreset = preset.copy(id = newId)
        presets.add(newPreset)
        kv.encode(KEY_PRESETS, gson.toJson(presets))
        return true
    }

    fun deletePreset(id: Long): Int {
        val presets = getAllPresets()
        val preset = presets.find { it.id == id } ?: return 0
        if (preset.isBuiltIn) return 0
        val newPresets = presets.filter { it.id != id }
        kv.encode(KEY_PRESETS, gson.toJson(newPresets))
        return 1
    }

    fun initBuiltInPresets() {
        if (kv.decodeBool(KEY_BUILT_IN_INITIALIZED, false)) return
        val presets = builtInPresets.mapIndexed { index, preset ->
            preset.copy(id = (index + 1).toLong())
        }
        kv.encode(KEY_PRESETS, gson.toJson(presets))
        kv.encode(KEY_BUILT_IN_INITIALIZED, true)
    }
}
