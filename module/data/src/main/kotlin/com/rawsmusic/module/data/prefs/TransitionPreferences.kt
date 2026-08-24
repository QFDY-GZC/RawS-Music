package com.rawsmusic.module.data.prefs

import com.tencent.mmkv.MMKV

/**
 * Playback transition settings kept outside AppPreferences so the player and UI
 * do not keep growing one large preference block.
 */
object TransitionPreferences {
    private val kv: MMKV get() = AppPreferences.storage

    const val SHORT_MANUAL_DURATION_MIN_MS = 10
    const val SHORT_MANUAL_DURATION_MAX_MS = 1000
    const val SHORT_MANUAL_DURATION_DEFAULT_MS = 400

    const val FULL_CROSSFADE_DURATION_MIN_MS = 100
    const val FULL_CROSSFADE_DURATION_MAX_MS = 15_000
    const val FULL_CROSSFADE_DURATION_DEFAULT_MS = 5_000

    @Deprecated("Use SHORT_MANUAL_DURATION_* or FULL_CROSSFADE_DURATION_* as appropriate")
    const val MANUAL_DURATION_MIN_MS = SHORT_MANUAL_DURATION_MIN_MS
    @Deprecated("Use SHORT_MANUAL_DURATION_* or FULL_CROSSFADE_DURATION_* as appropriate")
    const val MANUAL_DURATION_MAX_MS = SHORT_MANUAL_DURATION_MAX_MS
    @Deprecated("Use SHORT_MANUAL_DURATION_* or FULL_CROSSFADE_DURATION_* as appropriate")
    const val MANUAL_DURATION_DEFAULT_MS = SHORT_MANUAL_DURATION_DEFAULT_MS

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

    /** Reference fade_short_xfade_ms equivalent. */
    var shortManualFadeMs: Int
        get() = kv.decodeInt("transition_manual_track_fade_ms", SHORT_MANUAL_DURATION_DEFAULT_MS)
            .coerceIn(SHORT_MANUAL_DURATION_MIN_MS, SHORT_MANUAL_DURATION_MAX_MS)
        set(value) {
            kv.encode(
                "transition_manual_track_fade_ms",
                value.coerceIn(SHORT_MANUAL_DURATION_MIN_MS, SHORT_MANUAL_DURATION_MAX_MS)
            )
        }

    /** Reference crossfade_length_ms equivalent; deliberately independent from short fade. */
    var fullManualCrossfadeMs: Int
        get() = kv.decodeInt("transition_manual_full_crossfade_ms", FULL_CROSSFADE_DURATION_DEFAULT_MS)
            .coerceIn(FULL_CROSSFADE_DURATION_MIN_MS, FULL_CROSSFADE_DURATION_MAX_MS)
        set(value) {
            kv.encode(
                "transition_manual_full_crossfade_ms",
                value.coerceIn(FULL_CROSSFADE_DURATION_MIN_MS, FULL_CROSSFADE_DURATION_MAX_MS)
            )
        }

    fun manualTransitionDurationOrZero(): Int = when (manualTrackTransitionMode) {
        ManualTrackTransitionMode.NONE -> 0
        ManualTrackTransitionMode.SHORT_FADE -> shortManualFadeMs
        ManualTrackTransitionMode.CROSSFADE -> fullManualCrossfadeMs
    }

    @Deprecated("Use shortManualFadeMs or fullManualCrossfadeMs")
    var manualTrackFadeMs: Int
        get() = shortManualFadeMs
        set(value) { shortManualFadeMs = value }

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
