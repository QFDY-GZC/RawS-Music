package com.rawsmusic.module.data.prefs

internal enum class BackupSection { PREFERENCES, PLAYLISTS, PLAYBACK, DEVICE_REFERENCES }

internal data class BackupSelection(
    val sections: Set<BackupSection> = setOf(
        BackupSection.PREFERENCES, BackupSection.PLAYLISTS, BackupSection.PLAYBACK,
    ),
)

internal data class PreferenceAddress(val store: String, val key: String)

internal sealed interface PreferenceValue {
    val type: BackupPreferenceSchema.ValueType
    data class BooleanValue(val value: Boolean) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.BOOLEAN }
    data class IntValue(val value: Int) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.INT }
    data class LongValue(val value: Long) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.LONG }
    data class FloatValue(val value: Float) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.FLOAT }
    data class DoubleValue(val value: Double) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.DOUBLE }
    data class StringValue(val value: String) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.STRING }
    data class StringSetValue(val value: Set<String>) : PreferenceValue { override val type = BackupPreferenceSchema.ValueType.STRING_SET }
}

internal data class PreferenceEntry(val address: PreferenceAddress, val value: PreferenceValue)

internal data class BackupPlayback(
    val stats: List<SongPlaybackStats>,
    val history: List<PlaybackHistoryEntry>,
    val daily: Map<String, Long>,
    val paths: Map<Long, String> = emptyMap(),
)

internal data class ValidatedBackup(
    val version: Int,
    val timestamp: Long,
    val appVersion: String,
    val sections: Set<BackupSection>,
    val preferences: List<PreferenceEntry> = emptyList(),
    val playlists: List<com.rawsmusic.core.common.model.UserPlaylist>? = null,
    val playback: BackupPlayback? = null,
    val skipped: List<String> = emptyList(),
)
