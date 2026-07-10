package com.rawsmusic.module.data.prefs

import com.tencent.mmkv.MMKV

/**
 * Playback transition settings kept outside AppPreferences so the player and UI
 * do not keep growing one large preference block.
 */
object TransitionPreferences {
    private val kv: MMKV get() = AppPreferences.storage

    const val MANUAL_DURATION_MIN_MS = 10
    const val MANUAL_DURATION_MAX_MS = 1000
    const val MANUAL_DURATION_DEFAULT_MS = 400

    const val TRANSPORT_DURATION_MIN_MS = 10
    const val TRANSPORT_DURATION_MAX_MS = 1000
    const val TRANSPORT_DURATION_DEFAULT_MS = 400

    const val SEEK_DURATION_MIN_MS = 10
    const val SEEK_DURATION_MAX_MS = 500
    const val SEEK_DURATION_DEFAULT_MS = 100

    enum class ManualTrackTransitionMode {
        NONE,
        SHORT_FADE,
        CROSSFADE;

        companion object {
            fun fromOrdinal(value: Int): ManualTrackTransitionMode =
                entries.getOrElse(value) { SHORT_FADE }
        }
    }

    var manualTrackTransitionMode: ManualTrackTransitionMode
        get() = ManualTrackTransitionMode.fromOrdinal(
            kv.decodeInt("transition_manual_track_mode", ManualTrackTransitionMode.SHORT_FADE.ordinal)
        )
        set(value) { kv.encode("transition_manual_track_mode", value.ordinal) }

    var manualTrackFadeMs: Int
        get() = kv.decodeInt("transition_manual_track_fade_ms", MANUAL_DURATION_DEFAULT_MS)
            .coerceIn(MANUAL_DURATION_MIN_MS, MANUAL_DURATION_MAX_MS)
        set(value) {
            kv.encode(
                "transition_manual_track_fade_ms",
                value.coerceIn(MANUAL_DURATION_MIN_MS, MANUAL_DURATION_MAX_MS)
            )
        }

    var transportFadeEnabled: Boolean
        get() = kv.decodeBool("transition_transport_fade_enabled", true)
        set(value) { kv.encode("transition_transport_fade_enabled", value) }

    var transportFadeMs: Int
        get() = kv.decodeInt("transition_transport_fade_ms", TRANSPORT_DURATION_DEFAULT_MS)
            .coerceIn(TRANSPORT_DURATION_MIN_MS, TRANSPORT_DURATION_MAX_MS)
        set(value) {
            kv.encode(
                "transition_transport_fade_ms",
                value.coerceIn(TRANSPORT_DURATION_MIN_MS, TRANSPORT_DURATION_MAX_MS)
            )
        }

    var seekFadeEnabled: Boolean
        get() = kv.decodeBool("transition_seek_fade_enabled", true)
        set(value) { kv.encode("transition_seek_fade_enabled", value) }

    var seekFadeMs: Int
        get() = kv.decodeInt("transition_seek_fade_ms", SEEK_DURATION_DEFAULT_MS)
            .coerceIn(SEEK_DURATION_MIN_MS, SEEK_DURATION_MAX_MS)
        set(value) {
            kv.encode(
                "transition_seek_fade_ms",
                value.coerceIn(SEEK_DURATION_MIN_MS, SEEK_DURATION_MAX_MS)
            )
        }

    fun transportDurationOrZero(): Int = if (transportFadeEnabled) transportFadeMs else 0

    fun seekDurationOrZero(): Int = if (seekFadeEnabled) seekFadeMs else 0
}
