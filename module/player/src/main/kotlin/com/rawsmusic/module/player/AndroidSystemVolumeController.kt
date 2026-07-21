package com.rawsmusic.module.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.rawsmusic.core.common.utils.AppLogger
import kotlin.math.roundToInt

/**
 * Owns the Android STREAM_MUSIC bridge used by PlayerController.
 *
 * Policy remains in PlayerController: this class only reads/writes the system stream,
 * observes external changes, and suppresses callbacks caused by RawSMusic's own USB
 * transition/mirroring writes. Keeping the observer and broadcast receiver here prevents
 * Android lifecycle plumbing from leaking back into the playback state machine.
 */
internal class AndroidSystemVolumeController(
    context: Context,
    private val onExternalVolumeChanged: (linear: Float) -> Unit,
) {
    companion object {
        private const val TAG = "AndroidSystemVolume"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var ignoreCallbacksUntilElapsedMs: Long = 0L

    @Volatile
    private var suppressCallbacksUntilElapsedMs: Long = 0L

    private var contentObserver: ContentObserver? = null
    private var contentObserverRegistered = false
    private var broadcastReceiverRegistered = false

    private val volumeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED) return
            val streamType = intent.getIntExtra(
                EXTRA_VOLUME_STREAM_TYPE,
                AudioManager.STREAM_MUSIC,
            )
            if (streamType != AudioManager.STREAM_MUSIC) return
            dispatchVolumeChanged("broadcast")
        }
    }

    fun getMusicVolumeLinear(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return volume.toFloat() / max.toFloat()
    }

    fun setMusicVolumeLinear(
        linear: Float,
        flags: Int = AudioManager.FLAG_SHOW_UI,
    ) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (linear.coerceIn(0f, 1f) * max)
            .roundToInt()
            .coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, flags)
    }

    fun setMusicVolumeLinearIgnoringCallbacks(
        linear: Float,
        flags: Int,
        ignoreWindowMs: Long,
        reason: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        ignoreCallbacksUntilElapsedMs = maxOf(
            ignoreCallbacksUntilElapsedMs,
            now + ignoreWindowMs.coerceAtLeast(0L),
        )
        AppLogger.i(
            TAG,
            "setMusicVolumeLinearIgnoringCallbacks: linear=$linear flags=$flags " +
                "until=$ignoreCallbacksUntilElapsedMs reason=$reason",
        )
        setMusicVolumeLinear(linear, flags)
    }

    fun adjustMusicVolume(direction: Int, flags: Int = AudioManager.FLAG_SHOW_UI) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags)
    }

    fun suppressCallbacks(windowMs: Long, reason: String) {
        val now = SystemClock.elapsedRealtime()
        suppressCallbacksUntilElapsedMs = maxOf(
            suppressCallbacksUntilElapsedMs,
            now + windowMs.coerceAtLeast(0L),
        )
        AppLogger.i(
            TAG,
            "suppressCallbacks: until=$suppressCallbacksUntilElapsedMs " +
                "windowMs=$windowMs reason=$reason",
        )
    }

    fun syncObservation(shouldObserve: Boolean, reason: String) {
        if (shouldObserve) {
            register()
        } else {
            unregister()
        }
        AppLogger.i(TAG, "syncObservation: observe=$shouldObserve reason=$reason")
    }

    fun unregister() {
        if (!contentObserverRegistered && !broadcastReceiverRegistered) return
        contentObserver?.let { observer ->
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
        if (broadcastReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(volumeChangedReceiver) }
        }
        contentObserverRegistered = false
        broadcastReceiverRegistered = false
        AppLogger.i(TAG, "System volume observation unregistered")
    }

    fun release() {
        unregister()
        contentObserver = null
    }

    private fun register() {
        if (!contentObserverRegistered) {
            val observer = ensureContentObserver()
            runCatching {
                appContext.contentResolver.registerContentObserver(
                    Settings.System.CONTENT_URI,
                    true,
                    observer,
                )
            }.onSuccess {
                contentObserverRegistered = true
            }.onFailure { error ->
                AppLogger.w(TAG, "System volume ContentObserver register failed", error)
            }
        }
        if (!broadcastReceiverRegistered) {
            runCatching {
                appContext.registerReceiver(
                    volumeChangedReceiver,
                    IntentFilter(ACTION_VOLUME_CHANGED),
                )
            }.onSuccess {
                broadcastReceiverRegistered = true
            }.onFailure { error ->
                AppLogger.w(TAG, "System volume broadcast receiver register failed", error)
            }
        }
        AppLogger.i(
            TAG,
            "System volume observation registered: content=$contentObserverRegistered " +
                "broadcast=$broadcastReceiverRegistered",
        )
    }

    private fun ensureContentObserver(): ContentObserver {
        return contentObserver ?: object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                dispatchVolumeChanged("content_observer")
            }
        }.also { contentObserver = it }
    }

    private fun dispatchVolumeChanged(source: String) {
        val now = SystemClock.elapsedRealtime()
        val linear = getMusicVolumeLinear()
        when {
            now < ignoreCallbacksUntilElapsedMs -> {
                AppLogger.i(
                    TAG,
                    "dispatchVolumeChanged ignored by mirror guard: source=$source " +
                        "linear=$linear until=$ignoreCallbacksUntilElapsedMs",
                )
            }

            now < suppressCallbacksUntilElapsedMs -> {
                AppLogger.i(
                    TAG,
                    "dispatchVolumeChanged ignored by transition guard: source=$source " +
                        "linear=$linear until=$suppressCallbacksUntilElapsedMs",
                )
            }

            else -> {
                AppLogger.i(TAG, "dispatchVolumeChanged: source=$source linear=$linear")
                onExternalVolumeChanged(linear)
            }
        }
    }
}
