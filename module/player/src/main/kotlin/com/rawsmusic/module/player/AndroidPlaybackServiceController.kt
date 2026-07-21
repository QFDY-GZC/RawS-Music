package com.rawsmusic.module.player

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Android host bridge for playback-service actions and the controller-side USB wake lock.
 *
 * PlayerController decides *when* foreground playback, remote volume, identity publication,
 * or wake-lock ownership is required. This class owns the Android mechanism and error handling,
 * keeping Context/Intent/PowerManager plumbing outside the playback and USB state machine.
 */
internal class AndroidPlaybackServiceController(context: Context) {
    companion object {
        private const val TAG = "AndroidPlaybackService"
        private const val ACTION_ACTIVATE_USB_REMOTE_VOLUME =
            "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
        private const val ACTION_DEACTIVATE_USB_REMOTE_VOLUME =
            "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME"
        private const val ACTION_RELEASE_USB_WAKELOCK =
            "com.rawsmusic.action.RELEASE_USB_WAKELOCK"
        private const val DEFAULT_USB_WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }

    private val appContext = context.applicationContext
    private val usbPlaybackWakeLock: PowerManager.WakeLock by lazy {
        (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawSMusic:UsbExclusivePlayback")
            .apply { setReferenceCounted(false) }
    }

    fun acquireUsbPlaybackWakeLock(
        reason: String,
        timeoutMs: Long = DEFAULT_USB_WAKELOCK_TIMEOUT_MS,
    ) {
        runCatching {
            usbPlaybackWakeLock.acquire(timeoutMs)
        }.onSuccess {
            AppLogger.i(
                TAG,
                "USB playback wakelock ${if (usbPlaybackWakeLock.isHeld) "acquired/refreshed" else "requested"}: " +
                    "reason=$reason timeoutMs=$timeoutMs",
            )
        }.onFailure { error ->
            AppLogger.w(TAG, "USB playback wakelock acquire failed: reason=$reason", error)
        }
    }

    fun releaseUsbPlaybackWakeLock(reason: String) {
        runCatching {
            if (usbPlaybackWakeLock.isHeld) usbPlaybackWakeLock.release()
        }.onSuccess {
            AppLogger.i(TAG, "USB playback wakelock released: reason=$reason")
        }.onFailure { error ->
            AppLogger.w(TAG, "USB playback wakelock release failed: reason=$reason", error)
        }
    }

    fun ensurePlaybackWakeLock(reason: String) {
        dispatchServiceAction(
            action = PlayerService.ACTION_ENSURE_WAKELOCK,
            foreground = false,
            reason = reason,
        )
    }

    fun ensureUsbPlaybackForeground(reason: String): Boolean {
        return dispatchServiceAction(
            action = PlayerService.ACTION_USB_PLAYBACK_FOREGROUND,
            foreground = true,
            reason = reason,
        )
    }

    fun releaseUsbServiceWakeLock(reason: String) {
        dispatchServiceAction(
            action = ACTION_RELEASE_USB_WAKELOCK,
            foreground = false,
            reason = reason,
        )
    }

    fun setUsbRemoteVolumeActive(active: Boolean, reason: String) {
        dispatchServiceAction(
            action = if (active) {
                ACTION_ACTIVATE_USB_REMOTE_VOLUME
            } else {
                ACTION_DEACTIVATE_USB_REMOTE_VOLUME
            },
            foreground = false,
            reason = reason,
        )
    }

    fun sendUsbMediaIdentity(
        reason: String,
        song: AudioFile?,
        positionMs: Long,
        playing: Boolean,
    ) {
        dispatchServiceAction(
            action = PlayerService.ACTION_USB_MEDIA_IDENTITY,
            foreground = true,
            reason = reason,
        ) { intent ->
            intent.putExtra("reason", reason)
            song?.let { audio ->
                intent.putExtra("title", audio.title.ifBlank { audio.displayName })
                intent.putExtra("artist", audio.artist)
                intent.putExtra("album", audio.album)
                intent.putExtra("albumArtPath", audio.albumArtPath)
                intent.putExtra("duration", audio.duration)
                intent.putExtra("path", audio.path)
            }
            intent.putExtra("position", positionMs.coerceAtLeast(0L))
            intent.putExtra("playing", playing)
        }
    }

    fun release(reason: String) {
        releaseUsbPlaybackWakeLock(reason)
    }

    private fun dispatchServiceAction(
        action: String,
        foreground: Boolean,
        reason: String,
        configure: (Intent) -> Unit = {},
    ): Boolean {
        return runCatching {
            val intent = Intent(appContext, PlayerService::class.java).apply {
                this.action = action
                configure(this)
            }
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            AppLogger.i(
                TAG,
                "PlayerService action dispatched: action=$action foreground=$foreground reason=$reason",
            )
            true
        }.getOrElse { error ->
            AppLogger.w(
                TAG,
                "PlayerService action failed: action=$action foreground=$foreground reason=$reason",
                error,
            )
            false
        }
    }
}
