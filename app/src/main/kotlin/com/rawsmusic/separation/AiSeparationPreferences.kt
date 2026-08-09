package com.rawsmusic.separation

import android.content.Context

object AiSeparationPreferences {
    private const val PREFS = "ai_separation_runtime_options"
    private const val KEY_DENOISE = "uvr_denoise_double_inference"
    private const val KEY_LIVE_STREAMING = "uvr_live_stem_streaming"
    private const val KEY_LIVE_STEM = "uvr_live_stem"
    private const val KEY_LIVE_STRENGTH = "uvr_live_strength"

    fun isDenoiseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DENOISE, false)

    fun setDenoiseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DENOISE, enabled)
            .apply()
    }

    fun isLiveStreamingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_STREAMING, true)

    fun setLiveStreamingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_STREAMING, enabled)
            .apply()
    }

    fun liveStem(context: Context): AiSeparationStem {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIVE_STEM, AiSeparationStem.VOCALS.name)
        return runCatching { AiSeparationStem.valueOf(raw.orEmpty()) }
            .getOrDefault(AiSeparationStem.VOCALS)
    }

    fun setLiveStem(context: Context, stem: AiSeparationStem) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE_STEM, stem.name)
            .apply()
    }

    fun liveStrength(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_LIVE_STRENGTH, 1f)
            .coerceIn(0f, 1f)

    fun setLiveStrength(context: Context, strength: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LIVE_STRENGTH, strength.coerceIn(0f, 1f))
            .apply()
    }
}
