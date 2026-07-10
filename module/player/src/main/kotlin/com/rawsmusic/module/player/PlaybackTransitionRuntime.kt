package com.rawsmusic.module.player

import com.rawsmusic.module.data.prefs.TransitionPreferences

/** Thin player-side bridge for transition preferences. */
internal object PlaybackTransitionRuntime {
    val manualMode: TransitionPreferences.ManualTrackTransitionMode
        get() = TransitionPreferences.manualTrackTransitionMode

    val manualFadeMs: Int
        get() = TransitionPreferences.manualTrackFadeMs

    val transportFadeMs: Int
        get() = TransitionPreferences.transportDurationOrZero()

    val seekFadeMs: Int
        get() = TransitionPreferences.seekDurationOrZero()
}
