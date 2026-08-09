package com.rawsmusic.module.player.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rawsmusic.module.player.R

/** Android live-activity compatible lyric notification, independent from vendor ticker APIs. */
internal class LiveLyricNotificationBridge(context: Context) {
    private companion object {
        const val TAG = "LiveLyricNotification"
        const val CHANNEL_ID = "rawsmusic_live_lyric_updates_v1"
        const val NOTIFICATION_ID = 0x52534c59
        const val MIN_UPDATE_INTERVAL_MS = 220L
    }

    private data class Payload(
        val songTitle: String,
        val lyric: String,
        val compactLyric: String,
        val secondaryText: String,
        val artwork: Bitmap?
    )

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var lastPayload: Payload? = null
    private var pendingPayload: Payload? = null
    private var pendingDispatch: Runnable? = null
    private var lastDispatchElapsedMs = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) clear() else {
            lastPayload = null
            lastDispatchElapsedMs = 0L
        }
    }

    fun sendLyric(
        songTitle: String?,
        lyric: String?,
        compactLyric: String? = lyric,
        allowLongCompactLyric: Boolean = false,
        preserveCompactLyric: Boolean = false,
        secondaryLyric: String? = null,
        artwork: Bitmap? = null
    ) {
        if (!enabled) return
        val cleanLyric = lyric?.replace(Regex("\\s*\\n\\s*"), " ")?.trim()
            ?.takeIf { it.isNotBlank() } ?: run { clear(); return }
        val cleanCompact = compactLyric?.replace(Regex("\\s*\\n\\s*"), " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (preserveCompactLyric) it else compactLiveLyricText(it, allowLongCompactLyric) }
            ?: compactLiveLyricText(cleanLyric)
        val payload = Payload(
            songTitle = songTitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: appContext.getString(R.string.app_name),
            lyric = cleanLyric,
            compactLyric = cleanCompact,
            secondaryText = secondaryLyric?.trim().takeUnless { it.isNullOrBlank() }
                ?: songTitle.orEmpty(),
            artwork = artwork
        )
        if (payload == lastPayload) {
            cancelPendingDispatch()
            return
        }
        if (payload == pendingPayload) return
        pendingPayload = payload
        dispatchPendingPayload()
    }

    private fun dispatchPendingPayload() {
        val payload = pendingPayload ?: return
        val now = SystemClock.elapsedRealtime()
        val delayMs = if (lastDispatchElapsedMs == 0L) 0L else
            (MIN_UPDATE_INTERVAL_MS - (now - lastDispatchElapsedMs)).coerceAtLeast(0L)
        if (delayMs > 0L) {
            if (pendingDispatch == null) {
                pendingDispatch = Runnable {
                    pendingDispatch = null
                    dispatchPendingPayload()
                }.also { handler.postDelayed(it, delayMs) }
            }
            return
        }
        pendingDispatch?.let(handler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
        lastDispatchElapsedMs = now
        ensureChannel()
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                NOTIFICATION_ID,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flyme_ticker)
            .setContentTitle(payload.lyric)
            .setContentText(payload.secondaryText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(payload.lyric)
                    .setSummaryText(payload.secondaryText)
            )
            .apply { payload.artwork?.let(::setLargeIcon) }
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
            lastPayload = payload
        }.onFailure { error -> Log.w(TAG, "Unable to post live lyric notification", error) }
    }

    fun clear() {
        cancelPendingDispatch()
        lastPayload = null
        lastDispatchElapsedMs = 0L
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun cancelPendingDispatch() {
        pendingDispatch?.let(handler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_live_lyrics),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.notification_channel_live_lyrics_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
