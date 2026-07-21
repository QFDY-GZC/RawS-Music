package com.rawsmusic.module.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.rawsmusic.module.data.prefs.AudioFocusPreferences

internal data class AudioFocusPolicy(
    val resumeAfterCall: Boolean,
    val resumeOnStart: Boolean,
    val resumeOnResume: Boolean,
    val handleTransientChangesAndCalls: Boolean,
    val resumeOnFocusGain: Boolean,
    val allowDuck: Boolean,
    val pauseOnPermanentLoss: Boolean
) {
    companion object {
        fun current() = AudioFocusPolicy(
            resumeAfterCall = AudioFocusPreferences.resumeAfterCall,
            resumeOnStart = AudioFocusPreferences.resumeOnStart,
            resumeOnResume = AudioFocusPreferences.resumeOnResume,
            handleTransientChangesAndCalls = AudioFocusPreferences.handleTransientChangesAndCalls,
            resumeOnFocusGain = AudioFocusPreferences.resumeOnFocusGain,
            allowDuck = AudioFocusPreferences.allowDuck,
            pauseOnPermanentLoss = AudioFocusPreferences.pauseOnPermanentLoss
        )
    }
}

internal enum class AudioFocusPauseCause {
    None,
    Transient,
    Call
}

/** Optional explicit call-state monitor. AudioManager mode remains the no-permission fallback. */
internal class PhoneCallStateMonitor(
    context: Context,
    private val onCallStateChanged: (Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var telephonyCallback: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null

    fun refresh() {
        stop()
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = telephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        this@PhoneCallStateMonitor.onCallStateChanged(state)
                    }
                }
                manager.registerTelephonyCallback(appContext.mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Android")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        this@PhoneCallStateMonitor.onCallStateChanged(state)
                    }
                }
                @Suppress("DEPRECATION")
                manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
            }
        } catch (_: Throwable) {
            stop()
        }
    }

    fun stop() {
        val manager = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let { callback ->
                runCatching { manager.unregisterTelephonyCallback(callback) }
            }
            telephonyCallback = null
        }
        @Suppress("DEPRECATION")
        legacyListener?.let { listener -> runCatching { manager.listen(listener, PhoneStateListener.LISTEN_NONE) } }
        legacyListener = null
    }
}

internal fun AudioManager.isLikelyInPhoneCall(): Boolean = when (mode) {
    AudioManager.MODE_IN_CALL,
    AudioManager.MODE_IN_COMMUNICATION -> true
    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING
}
