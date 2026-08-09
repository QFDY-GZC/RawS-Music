package com.rawsmusic.module.data.prefs

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VideoCoverMode {
    PERMANENT,
    CURRENT,
    TEMPORARY
}

data class VideoCoverState(
    val enabled: Boolean,
    val mode: VideoCoverMode,
    val permanentUri: String,
    val currentAssignments: Map<String, String>,
    val temporarySongKey: String = "",
    val temporaryUri: String = ""
) {
    fun resolve(songKey: String): String? {
        if (!enabled || songKey.isBlank()) return null
        return when (mode) {
            VideoCoverMode.PERMANENT -> permanentUri
            VideoCoverMode.CURRENT -> currentAssignments[songKey].orEmpty()
            VideoCoverMode.TEMPORARY -> if (temporarySongKey == songKey) temporaryUri else ""
        }.takeIf(String::isNotBlank)
    }
}

/** Persistent video-cover policy plus the process-only temporary assignment. */
object VideoCoverPreferences {
    private const val KEY_ENABLED = "ui_video_cover_enabled"
    private const val KEY_MODE = "ui_video_cover_mode"
    private const val KEY_PERMANENT_URI = "ui_video_cover_permanent_uri"
    private const val KEY_CURRENT_ASSIGNMENTS = "ui_video_cover_current_assignments"

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type
    private val storage get() = AppPreferences.storage

    private val mutableState = MutableStateFlow(loadState())
    val state = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        storage.encode(KEY_ENABLED, enabled)
        mutableState.update { it.copy(enabled = enabled) }
    }

    fun setMode(mode: VideoCoverMode) {
        storage.encode(KEY_MODE, mode.ordinal)
        mutableState.update { it.copy(mode = mode) }
    }

    fun assign(uri: String, songKey: String) {
        if (uri.isBlank() || songKey.isBlank()) return
        when (mutableState.value.mode) {
            VideoCoverMode.PERMANENT -> {
                storage.encode(KEY_PERMANENT_URI, uri)
                mutableState.update { it.copy(permanentUri = uri) }
            }
            VideoCoverMode.CURRENT -> {
                val assignments = mutableState.value.currentAssignments + (songKey to uri)
                storage.encode(KEY_CURRENT_ASSIGNMENTS, gson.toJson(assignments))
                mutableState.update { it.copy(currentAssignments = assignments) }
            }
            VideoCoverMode.TEMPORARY -> {
                mutableState.update { it.copy(temporarySongKey = songKey, temporaryUri = uri) }
            }
        }
        setEnabled(true)
    }

    fun clearForMode(songKey: String) {
        when (mutableState.value.mode) {
            VideoCoverMode.PERMANENT -> {
                storage.removeValueForKey(KEY_PERMANENT_URI)
                mutableState.update { it.copy(permanentUri = "") }
            }
            VideoCoverMode.CURRENT -> {
                val assignments = mutableState.value.currentAssignments - songKey
                storage.encode(KEY_CURRENT_ASSIGNMENTS, gson.toJson(assignments))
                mutableState.update { it.copy(currentAssignments = assignments) }
            }
            VideoCoverMode.TEMPORARY -> {
                mutableState.update { it.copy(temporarySongKey = "", temporaryUri = "") }
            }
        }
    }

    fun onSongChanged(songKey: String) {
        mutableState.update { state ->
            if (state.temporaryUri.isNotBlank() && state.temporarySongKey != songKey) {
                state.copy(temporarySongKey = "", temporaryUri = "")
            } else {
                state
            }
        }
    }

    private fun loadState(): VideoCoverState {
        val assignments = runCatching {
            val json = storage.decodeString(KEY_CURRENT_ASSIGNMENTS, "").orEmpty()
            if (json.isBlank()) emptyMap() else gson.fromJson<Map<String, String>>(json, mapType)
        }.getOrDefault(emptyMap())
        return VideoCoverState(
            enabled = storage.decodeBool(KEY_ENABLED, false),
            mode = VideoCoverMode.entries.getOrElse(storage.decodeInt(KEY_MODE, 0)) {
                VideoCoverMode.PERMANENT
            },
            permanentUri = storage.decodeString(KEY_PERMANENT_URI, "").orEmpty(),
            currentAssignments = assignments
        )
    }
}
