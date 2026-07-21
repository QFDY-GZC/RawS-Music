package com.rawsmusic.module.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.AudioFocusPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns Android system-audio interruption policy for [PlayerController].
 *
 * This coordinator deliberately contains only platform concerns: AudioFocus, phone
 * call state, ACTION_AUDIO_BECOMING_NOISY, Bluetooth SCO state and automatic resume
 * bookkeeping. Decoder/queue/USB details stay behind callbacks in PlayerController.
 */
internal class AndroidAudioInterruptionController(
    context: Context,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    internal data class Callbacks(
        val isReleased: () -> Boolean,
        val isUsbExclusive: () -> Boolean,
        val isPlaybackActive: () -> Boolean,
        val playbackStateSummary: () -> String,
        val pauseForInterruption: (reason: String) -> Unit,
        val pauseForNoisyRouteChange: () -> Unit,
        val resumeAfterInterruption: (reason: String) -> Unit,
        val onDuckFactorChanged: () -> Unit,
        val repairRouteAfterNoisy: () -> Unit,
        val shouldUseScoMode: () -> Boolean,
        val rebuildForScoConnected: () -> Unit,
        val rebuildForScoDisconnected: () -> Unit,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val startupAutoResumeAttempted = AtomicBoolean(false)

    private var wasPlayingBeforeFocusLoss = false
    private var pauseCause = AudioFocusPauseCause.None
    private var phoneCallActive = false
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false

    @Volatile
    private var _duckVolumeFactor = 1.0f
    val duckVolumeFactor: Float
        get() = _duckVolumeFactor

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener(::handleAudioFocusChange)
    private val phoneCallStateMonitor = PhoneCallStateMonitor(appContext, ::handlePhoneCallStateChanged)

    private var noisyRegistered = false
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            Log.w(TAG, "NoisyAudio: route removed, pausing playback and repairing speaker route")
            callbacks.pauseForNoisyRouteChange()
            scope.launch(Dispatchers.Main.immediate) {
                delay(NOISY_ROUTE_REPAIR_DELAY_MS)
                if (!callbacks.isReleased()) {
                    runCatching(callbacks.repairRouteAfterNoisy)
                        .onFailure { AppLogger.w(TAG, "NoisyAudio: route repair failed", it) }
                }
            }
        }
    }

    private var scoReceiverRegistered = false
    @Volatile
    private var suppressScoDisconnectCallbackRebuild = false
    private val _scoConnected = MutableStateFlow(false)
    val scoConnected: StateFlow<Boolean> = _scoConnected.asStateFlow()

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            )
            Log.d(TAG, "SCO state changed: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    suppressScoDisconnectCallbackRebuild = false
                    _scoConnected.value = true
                    if (!callbacks.isReleased() && callbacks.shouldUseScoMode()) {
                        scope.launch(Dispatchers.Main.immediate) {
                            runCatching(callbacks.rebuildForScoConnected)
                                .onFailure { Log.e(TAG, "Failed to rebuild AudioTrack for SCO", it) }
                        }
                    }
                }

                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    _scoConnected.value = false
                    val suppressRebuild = suppressScoDisconnectCallbackRebuild
                    suppressScoDisconnectCallbackRebuild = false
                    if (suppressRebuild) {
                        Log.d(TAG, "SCO disconnected after explicit stop; host owns route rebuild")
                    } else if (!callbacks.isReleased()) {
                        scope.launch(Dispatchers.Main.immediate) {
                            runCatching(callbacks.rebuildForScoDisconnected)
                                .onFailure { Log.e(TAG, "Failed to rebuild AudioTrack after SCO disconnect", it) }
                        }
                    }
                }

                AudioManager.SCO_AUDIO_STATE_CONNECTING -> Log.d(TAG, "SCO connecting...")
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    _scoConnected.value = false
                    Log.e(TAG, "SCO error")
                }
            }
        }
    }

    fun start() {
        phoneCallStateMonitor.refresh()
        maybeResumePlaybackOnColdStart()
    }

    fun release() {
        unregisterNoisyReceiver()
        phoneCallStateMonitor.stop()
        abandonAudioFocus()
        stopBluetoothSco(rebuildRoute = false)
    }

    fun requestAudioFocus(): Boolean {
        if (callbacks.isUsbExclusive()) {
            AppLogger.d(TAG, "AudioFocus: USB exclusive bypass")
            return true
        }
        if (focusGranted) return true
        val manager = audioManager ?: return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(!AudioFocusPreferences.allowDuck)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }

        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        if (!focusGranted) {
            focusRequest = null
            Log.w(TAG, "AudioFocus: denied (result=$result)")
        } else {
            AppLogger.i(
                TAG,
                "AudioFocus: request result=$result allowDuck=${AudioFocusPreferences.allowDuck}",
            )
        }
        return focusGranted
    }

    fun abandonAudioFocus() {
        val manager = audioManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { request ->
                    runCatching { manager.abandonAudioFocusRequest(request) }
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching { manager.abandonAudioFocus(focusChangeListener) }
            }
        }
        focusRequest = null
        focusGranted = false
        setDuckFactor(1.0f)
        clearAutomaticFocusResume("abandon")
    }

    fun clearAutomaticFocusResume(reason: String) {
        if (wasPlayingBeforeFocusLoss || pauseCause != AudioFocusPauseCause.None) {
            AppLogger.d(TAG, "AudioFocus: clear pending resume reason=$reason cause=$pauseCause")
        }
        wasPlayingBeforeFocusLoss = false
        pauseCause = AudioFocusPauseCause.None
    }

    fun maybeResumeOnAppForeground(returningFromBackground: Boolean) {
        if (!returningFromBackground || phoneCallActive ||
            !AudioFocusPreferences.resumeOnResume || callbacks.isPlaybackActive()
        ) return
        AppLogger.i(TAG, "AudioFocus: resume_on_resume triggered")
        callbacks.resumeAfterInterruption("resume_on_resume")
    }

    fun refreshSettings() {
        phoneCallStateMonitor.refresh()
        if (callbacks.isUsbExclusive()) {
            abandonAudioFocus()
            return
        }
        if (!AudioFocusPreferences.handleTransientChangesAndCalls) {
            clearAutomaticFocusResume("settings_transient_disabled")
        }
        if (_duckVolumeFactor < 1.0f && !AudioFocusPreferences.allowDuck) {
            setDuckFactor(1.0f)
        }
        val hadFocusSession = focusGranted || focusRequest != null
        if (hadFocusSession) {
            abandonAudioFocus()
            if (callbacks.isPlaybackActive()) requestAudioFocus()
        }
    }

    fun registerNoisyReceiver() {
        if (noisyRegistered) return
        try {
            appContext.registerReceiver(
                noisyAudioReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
            noisyRegistered = true
            Log.d(TAG, "NoisyAudio: registered")
        } catch (t: Throwable) {
            Log.w(TAG, "NoisyAudio: register failed", t)
        }
    }

    fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { appContext.unregisterReceiver(noisyAudioReceiver) }
        noisyRegistered = false
    }

    fun startBluetoothSco(): Boolean {
        val manager = audioManager ?: return false
        suppressScoDisconnectCallbackRebuild = false
        return try {
            @Suppress("DEPRECATION")
            if (!manager.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "startBluetoothSco: SCO not available off call")
                return false
            }
            registerScoReceiver()
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
            @Suppress("DEPRECATION")
            runCatching { manager.isBluetoothScoOn = true }
            Log.i(TAG, "startBluetoothSco: SCO start requested")
            true
        } catch (t: Throwable) {
            unregisterScoReceiver()
            _scoConnected.value = false
            Log.e(TAG, "startBluetoothSco failed", t)
            false
        }
    }

    fun stopBluetoothSco(rebuildRoute: Boolean = true) {
        val manager = audioManager
        suppressScoDisconnectCallbackRebuild = true
        try {
            if (manager != null) {
                @Suppress("DEPRECATION")
                runCatching { manager.stopBluetoothSco() }
                @Suppress("DEPRECATION")
                runCatching { manager.isBluetoothScoOn = false }
            }
        } finally {
            unregisterScoReceiver()
            _scoConnected.value = false
        }
        Log.i(TAG, "stopBluetoothSco: SCO stopped rebuildRoute=$rebuildRoute")
        if (rebuildRoute && !callbacks.isReleased()) {
            scope.launch(Dispatchers.Main.immediate) {
                runCatching(callbacks.rebuildForScoDisconnected)
                    .onFailure { Log.e(TAG, "Failed to rebuild AudioTrack after SCO stop", it) }
            }
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        if (callbacks.isUsbExclusive()) {
            AppLogger.d(TAG, "AudioFocus: USB exclusive active, ignore focusChange=$focusChange")
            setDuckFactor(1.0f)
            return
        }
        val policy = AudioFocusPolicy.current()
        AppLogger.i(
            TAG,
            "AudioFocus: change=$focusChange state=${callbacks.playbackStateSummary()} " +
                "transient=${policy.handleTransientChangesAndCalls} duck=${policy.allowDuck} " +
                "resumeGain=${policy.resumeOnFocusGain} permanent=${policy.pauseOnPermanentLoss}",
        )
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!policy.handleTransientChangesAndCalls) {
                    AppLogger.d(TAG, "AudioFocus: DUCK ignored by preference")
                } else if (policy.allowDuck) {
                    setDuckFactor(DUCK_FACTOR)
                } else {
                    pauseForFocus(resolveTransientPauseCause(), "duck_pause")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (policy.handleTransientChangesAndCalls) {
                    pauseForFocus(resolveTransientPauseCause(), "loss_transient")
                } else {
                    AppLogger.d(TAG, "AudioFocus: LOSS_TRANSIENT ignored by preference")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusGranted = false
                focusRequest = null
                if (policy.pauseOnPermanentLoss) {
                    pauseForFocus(
                        cause = AudioFocusPauseCause.None,
                        reason = "loss_permanent",
                        allowAutomaticResume = false,
                    )
                } else {
                    AppLogger.d(TAG, "AudioFocus: LOSS ignored by preference")
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                setDuckFactor(1.0f)
                maybeResumeAfterAutomaticPause("focus_gain")
            }
        }
    }

    private fun resolveTransientPauseCause(): AudioFocusPauseCause {
        return if (phoneCallActive || audioManager?.isLikelyInPhoneCall() == true) {
            AudioFocusPauseCause.Call
        } else {
            AudioFocusPauseCause.Transient
        }
    }

    private fun pauseForFocus(
        cause: AudioFocusPauseCause,
        reason: String,
        allowAutomaticResume: Boolean = true,
    ) {
        if (callbacks.isUsbExclusive() || !callbacks.isPlaybackActive()) return
        setDuckFactor(1.0f)
        wasPlayingBeforeFocusLoss = allowAutomaticResume
        pauseCause = if (allowAutomaticResume) cause else AudioFocusPauseCause.None
        callbacks.pauseForInterruption(reason)
        AppLogger.i(
            TAG,
            "AudioFocus: paused reason=$reason cause=$cause autoResume=$allowAutomaticResume",
        )
    }

    private fun maybeResumeAfterAutomaticPause(reason: String) {
        if (!wasPlayingBeforeFocusLoss || phoneCallActive) return
        val policy = AudioFocusPolicy.current()
        val shouldResume = when (pauseCause) {
            AudioFocusPauseCause.Call -> policy.resumeAfterCall
            AudioFocusPauseCause.Transient -> policy.resumeOnFocusGain
            AudioFocusPauseCause.None -> false
        }
        if (!shouldResume) {
            clearAutomaticFocusResume("$reason-disabled")
            return
        }
        clearAutomaticFocusResume(reason)
        callbacks.resumeAfterInterruption(reason)
    }

    private fun handlePhoneCallStateChanged(state: Int) {
        if (callbacks.isUsbExclusive()) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                phoneCallActive = true
                if (AudioFocusPreferences.handleTransientChangesAndCalls) {
                    pauseForFocus(AudioFocusPauseCause.Call, "phone_call")
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                val callEnded = phoneCallActive
                phoneCallActive = false
                if (callEnded && pauseCause == AudioFocusPauseCause.Call) {
                    maybeResumeAfterAutomaticPause("phone_call_ended")
                }
            }
        }
    }

    private fun maybeResumePlaybackOnColdStart() {
        if (!AudioFocusPreferences.resumeOnStart ||
            !startupAutoResumeAttempted.compareAndSet(false, true)
        ) return
        val savedState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
            PlayState.IDLE
        }
        if (savedState != PlayState.PLAYING && savedState != PlayState.PREPARING) return
        scope.launch(Dispatchers.Main.immediate) {
            delay(COLD_START_RESUME_DELAY_MS)
            if (!callbacks.isReleased() && !callbacks.isPlaybackActive()) {
                callbacks.resumeAfterInterruption("resume_on_start")
            }
        }
    }

    private fun setDuckFactor(value: Float) {
        val normalized = value.coerceIn(0.0f, 1.0f)
        if (_duckVolumeFactor == normalized) return
        _duckVolumeFactor = normalized
        callbacks.onDuckFactorChanged()
    }

    private fun registerScoReceiver() {
        if (scoReceiverRegistered) return
        try {
            appContext.registerReceiver(
                scoStateReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            )
            scoReceiverRegistered = true
            Log.d(TAG, "SCO receiver: registered")
        } catch (t: Throwable) {
            Log.w(TAG, "SCO receiver: register failed", t)
        }
    }

    private fun unregisterScoReceiver() {
        if (!scoReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(scoStateReceiver) }
        scoReceiverRegistered = false
    }

    private companion object {
        const val TAG = "AndroidAudioInterrupt"
        const val DUCK_FACTOR = 0.2f
        const val NOISY_ROUTE_REPAIR_DELAY_MS = 220L
        const val COLD_START_RESUME_DELAY_MS = 300L
    }
}
