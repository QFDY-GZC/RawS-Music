package com.rawsmusic.module.data.prefs

/** Persistent audio-focus behavior, kept separate from output-engine preferences. */
object AudioFocusPreferences {
    private val kv get() = AppPreferences.storage

    var resumeAfterCall: Boolean
        get() = kv.decodeBool("audio_focus_resume_after_call", true)
        set(value) { kv.encode("audio_focus_resume_after_call", value) }

    var resumeOnStart: Boolean
        get() = kv.decodeBool("audio_focus_resume_on_start", false)
        set(value) { kv.encode("audio_focus_resume_on_start", value) }

    var resumeOnResume: Boolean
        get() = kv.decodeBool("audio_focus_resume_on_resume", false)
        set(value) { kv.encode("audio_focus_resume_on_resume", value) }

    var handleTransientChangesAndCalls: Boolean
        get() = kv.decodeBool("audio_focus_handle_transient_and_calls", true)
        set(value) { kv.encode("audio_focus_handle_transient_and_calls", value) }

    var resumeOnFocusGain: Boolean
        get() = kv.decodeBool("audio_focus_resume_on_gain", true)
        set(value) { kv.encode("audio_focus_resume_on_gain", value) }

    var allowDuck: Boolean
        get() = kv.decodeBool("audio_focus_allow_duck", true)
        set(value) { kv.encode("audio_focus_allow_duck", value) }

    var pauseOnPermanentLoss: Boolean
        get() = kv.decodeBool("audio_focus_pause_on_permanent_loss", true)
        set(value) { kv.encode("audio_focus_pause_on_permanent_loss", value) }

    var phonePermissionPrompted: Boolean
        get() = kv.decodeBool("audio_focus_phone_permission_prompted", false)
        set(value) { kv.encode("audio_focus_phone_permission_prompted", value) }
}
