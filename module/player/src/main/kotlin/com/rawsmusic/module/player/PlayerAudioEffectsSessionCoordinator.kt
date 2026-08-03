package com.rawsmusic.module.player

import android.media.AudioManager
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences

/** Owns the audio-session-bound effect controls exposed by PlayerController. */
internal class PlayerAudioEffectsSessionCoordinator(
    private val tag: String,
    private val player: () -> FfmpegAudioPlayer,
) {
    var onAudioSessionChanged: ((newSessionId: Int) -> Unit)? = null

    private var equalizerController: EqualizerController? = null

    fun onAudioSessionReady(sessionId: Int) {
        onAudioSessionChanged?.invoke(sessionId)
        equalizerController?.release()
        equalizerController = EqualizerController(sessionId).apply { init() }
        Log.d(tag, "EqualizerController initialized for session $sessionId")
    }

    fun audioSessionId(): Int = runCatching {
        player().audioSessionId.takeIf { it != 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE
    }.getOrDefault(AudioManager.AUDIO_SESSION_ID_GENERATE)

    fun setEqualizerController(reinitFn: ((newSessionId: Int) -> Unit)?) {
        onAudioSessionChanged = reinitFn
    }

    fun setStereoWidenFactor(factor: Float) {
        val coerced = factor.coerceIn(0f, 1f)
        Log.w(tag, "setStereoWidenFactor: input=$factor, coerced=$coerced, playerState=${player().state}")
        player().stereoWidenFactor = coerced
        AppPreferences.Equalizer.virtualizer = (coerced * 1000f).toInt().coerceIn(0, 1000)
    }

    fun setCrossfeedEnabled(enabled: Boolean) {
        AppPreferences.Equalizer.crossfeedEnabled = enabled
        player().dspEngine?.takeIf { it.isInitialized() }?.setCrossfeedEnabled(enabled)
        Log.d(tag, "setCrossfeedEnabled: $enabled")
    }

    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDb: Float) {
        val lowCut = lowCutFreq.coerceIn(50f, 1000f)
        val highCut = highCutFreq.coerceIn(500f, 8000f)
        val attenuation = attenuationDb.coerceIn(0f, 15f)
        AppPreferences.Equalizer.crossfeedLowCut = lowCut.toInt()
        AppPreferences.Equalizer.crossfeedHighCut = highCut.toInt()
        AppPreferences.Equalizer.crossfeedAttenuation = (attenuation * 10f).toInt()
        player().dspEngine?.takeIf { it.isInitialized() }?.setCrossfeedParams(lowCut, highCut, attenuation)
        Log.d(tag, "setCrossfeedParams: lowCut=$lowCut, highCut=$highCut, atten=$attenuation")
    }

    fun restoreSettings() {
        val savedVirtualizer = AppPreferences.Equalizer.virtualizer.coerceIn(0, 1000)
        player().stereoWidenFactor = savedVirtualizer / 1000f
        val engine = player().dspEngine ?: return
        if (!engine.isInitialized()) return
        val enabled = AppPreferences.Equalizer.crossfeedEnabled
        val lowCut = AppPreferences.Equalizer.crossfeedLowCut.toFloat()
        val highCut = AppPreferences.Equalizer.crossfeedHighCut.toFloat()
        val attenuation = AppPreferences.Equalizer.crossfeedAttenuation / 10f
        engine.setCrossfeedParams(lowCut, highCut, attenuation)
        engine.setCrossfeedEnabled(enabled)
        Log.d(tag, "restoreCrossfeedSettings: enabled=$enabled, lowCut=$lowCut, highCut=$highCut, atten=$attenuation")
    }

    fun release() {
        onAudioSessionChanged = null
        equalizerController?.release()
        equalizerController = null
    }
}
