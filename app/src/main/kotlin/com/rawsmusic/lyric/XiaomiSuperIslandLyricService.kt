package com.rawsmusic.lyric

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.rawsmusic.core.common.utils.AppLogger

/** Keeps the vendor Focus notification alive without coupling it to the media service. */
internal class XiaomiSuperIslandLyricService : Service() {
    companion object {
        private const val TAG = "RawSuperIslandService"
        private const val ACTION_PUBLISH = "com.rawsmusic.action.PUBLISH_SUPER_ISLAND_LYRIC"
        private const val ACTION_STOP = "com.rawsmusic.action.STOP_SUPER_ISLAND_LYRIC"

        @Volatile private var pendingNotification: Notification? = null
        @Volatile private var running = false

        fun publish(context: Context, notification: Notification) {
            pendingNotification = notification
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java).setAction(ACTION_PUBLISH)
            val result = runCatching {
                if (running) context.startService(intent)
                else ContextCompat.startForegroundService(context, intent)
            }.recoverCatching {
                running = false
                AppLogger.w(TAG, "Super Island service restart requested", it)
                ContextCompat.startForegroundService(context, intent)
            }
            if (result.isSuccess) {
                running = true
            } else {
                AppLogger.w(TAG, "Unable to start Super Island service; posting directly", result.exceptionOrNull())
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(XiaomiSuperIslandLyricBridge.NOTIFICATION_ID, notification)
            }
        }

        fun stop(context: Context) {
            pendingNotification = null
            if (!running) {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(XiaomiSuperIslandLyricBridge.NOTIFICATION_ID)
                return
            }
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    running = false
                    context.stopService(intent)
                }
        }
    }

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.d(TAG, "Super Island lyric service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUBLISH -> pendingNotification?.let(::publishNotification)
                ?: AppLogger.w(TAG, "No Super Island lyric notification available to publish")
            ACTION_STOP -> stopNotificationService()
            else -> AppLogger.d(TAG, "Ignoring service action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun publishNotification(notification: Notification) {
        if (!foregroundStarted) {
            val startedWithLyric = runCatching {
                startForegroundWithType(notification)
                foregroundStarted = true
                AppLogger.d(TAG, "Super Island lyric foreground service started with Focus payload")
            }.isSuccess
            if (startedWithLyric) return

            runCatching {
                startForegroundWithType(buildWarmNotification())
                foregroundStarted = true
                AppLogger.d(TAG, "Super Island lyric foreground service started with warm payload")
            }.onFailure { error -> AppLogger.w(TAG, "Focus foreground start failed", error) }
        }
        if (foregroundStarted) {
            notificationManager?.notify(XiaomiSuperIslandLyricBridge.NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                XiaomiSuperIslandLyricBridge.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(XiaomiSuperIslandLyricBridge.NOTIFICATION_ID, notification)
        }
    }

    private fun buildWarmNotification(): Notification {
        return Notification.Builder(this, XiaomiSuperIslandLyricBridge.CHANNEL_ID)
            .setSmallIcon(com.rawsmusic.R.drawable.ic_music_note)
            .setContentTitle("")
            .setContentText("")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun stopNotificationService() {
        foregroundStarted = false
        running = false
        pendingNotification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        foregroundStarted = false
        running = false
        pendingNotification = null
        AppLogger.d(TAG, "Super Island lyric service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
