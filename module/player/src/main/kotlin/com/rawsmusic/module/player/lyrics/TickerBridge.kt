package com.rawsmusic.module.player.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences

object PlaybackTickerState {
    @Volatile
    var tickerText: String = ""
        private set

    @Volatile
    var tickerTranslation: String = ""
        private set

    fun update(text: String, translation: String = "") {
        tickerText = text
        tickerTranslation = translation
    }

    fun clear() {
        tickerText = ""
        tickerTranslation = ""
    }
}

object TickerBridge {

    private const val TAG = "TickerBridge"
    private const val CHANNEL_ID = "rawsmusic_ticker_channel_v2"
    private const val NOTIFICATION_ID = 2001

    private const val FLYME_STATUS_BAR_TICKER_ACTION = "com.flyme.statusbar.ticker"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x1000000
    private const val FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x2000000

    private var isRegistered = false
    private var lastText = ""
    private var contextRef: Context? = null

    private val flagAlwaysShowTicker: Int by lazy {
        getNotificationFlag("FLAG_ALWAYS_SHOW_TICKER")
    }

    private val flagOnlyUpdateTicker: Int by lazy {
        getNotificationFlag("FLAG_ONLY_UPDATE_TICKER")
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                postTickerNotification(lastText)
            }
        }
    }

    fun init(context: Context) {
        if (!AppPreferences.Lyrics.tickerEnabled) return
        contextRef = context.applicationContext
        if (isRegistered) return
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            context.registerReceiver(screenOffReceiver, filter)
            isRegistered = true
            Log.d(TAG, "TickerBridge initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen off receiver", e)
        }
    }

    fun destroy(context: Context) {
        try {
            if (isRegistered) {
                context.unregisterReceiver(screenOffReceiver)
                isRegistered = false
            }
        } catch (_: Exception) {}
        contextRef = null
        lastText = ""
        cancelTickerNotification(context)
        PlaybackTickerState.clear()
    }

    fun updateLyric(context: Context, text: String, translation: String = "") {
        if (!AppPreferences.Lyrics.tickerEnabled) return
        if (text.isBlank()) {
            clearLyric(context)
            return
        }
        if (text == lastText) return
        lastText = text

        PlaybackTickerState.update(text, translation)

        sendFlymeBroadcast(context, text, translation)
        if (AppPreferences.Lyrics.tickerHideNotification) {
            cancelTickerNotification(context)
        } else {
            postTickerNotification(text, translation)
        }

        Log.d(TAG, "Ticker lyric sent: $text")
    }

    fun clearLyric(context: Context) {
        lastText = ""
        PlaybackTickerState.clear()
        sendFlymeBroadcast(context, "")
        cancelTickerNotification(context)
    }

    private fun sendFlymeBroadcast(context: Context, text: String, translation: String = "") {
        try {
            val intent = Intent(FLYME_STATUS_BAR_TICKER_ACTION).apply {
                putExtra("ticker_text", text)
                putExtra("lyric", text)
                putExtra("text", text)
                putExtra("content", text)
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
                putExtra("ticker_app_name", "RawSMusic")
                putExtra("app_name", "RawSMusic")
                if (translation.isNotBlank()) {
                    putExtra("ticker_translation", translation)
                }
                putExtra("flag", flagAlwaysShowTicker or flagOnlyUpdateTicker)
            }
            context.sendBroadcast(intent)
            context.sendBroadcast(Intent(intent).setPackage(SYSTEM_UI_PACKAGE))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send Flyme ticker broadcast", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun postTickerNotification(text: String, translation: String = "") {
        val ctx = contextRef ?: return
        if (text.isBlank()) return

        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Flyme 状态栏歌词",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "用于向 Flyme 状态栏推送歌词"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            val contentIntent = PendingIntent.getActivity(
                ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val flymeTickerSupported = isFlymeTickerSupported()
            val tickerText: CharSequence? = if (flymeTickerSupported) text else null

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                Notification.Builder(ctx)
                    .setPriority(Notification.PRIORITY_LOW)
            }

            val notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(text)
                .setContentText(translation)
                .setTicker(tickerText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setDefaults(0)
                .setContentIntent(contentIntent)
                .build()

            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

            if (flymeTickerSupported) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    notification.extras.putBoolean("ticker_icon_switch", false)
                    notification.extras.putInt("ticker_icon", android.R.drawable.ic_media_play)
                    notification.extras.putString("ticker_text", text)
                    notification.extras.putString("lyric", text)
                    if (translation.isNotBlank()) {
                        notification.extras.putString("ticker_translation", translation)
                    }
                }
                notification.flags = notification.flags or flagAlwaysShowTicker
                notification.flags = notification.flags or flagOnlyUpdateTicker
            }

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post ticker notification", e)
        }
    }

    private fun cancelTickerNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun isFlymeTickerSupported(): Boolean {
        return flagAlwaysShowTicker > 0 && flagOnlyUpdateTicker > 0
    }

    private fun getNotificationFlag(name: String): Int {
        return try {
            val field = Notification::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.getInt(null)
        } catch (e: Throwable) {
            when (name) {
                "FLAG_ALWAYS_SHOW_TICKER" -> FLAG_ALWAYS_SHOW_TICKER_FALLBACK
                "FLAG_ONLY_UPDATE_TICKER" -> FLAG_ONLY_UPDATE_TICKER_FALLBACK
                else -> 0
            }.also { fallback ->
                Log.w(TAG, "Flyme ticker flag not found: $name, fallback=$fallback")
            }
        }
    }
}
