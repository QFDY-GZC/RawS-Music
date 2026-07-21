package com.rawsmusic.module.player.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.R

object PlaybackTickerState {
    data class Payload(
        val text: String,
        val translation: String?
    )

    @Volatile
    private var payload: Payload? = null
    private var refreshNotification: (() -> Unit)? = null

    @Synchronized
    fun setRefreshCallback(callback: (() -> Unit)?) {
        refreshNotification = callback
    }

    fun current(): Payload? = payload

    fun update(text: String, translation: String = "") {
        payload = text
            .takeIf { it.isNotBlank() }
            ?.let { Payload(it, translation.takeIf { value -> value.isNotBlank() }) }
        refreshNotification?.invoke()
    }

    fun clear() {
        if (payload == null) return
        payload = null
        refreshNotification?.invoke()
    }
}

object TickerBridge {

    private const val TAG = "TickerBridge"
    private const val CHANNEL_ID = "rawsmusic_ticker_channel_v2"
    private const val CHANNEL_ID_HEADS_UP = "rawsmusic_heads_up_lyrics_v1"
    private const val TICKER_NOTIFICATION_ID_BASE = 0x52697000
    private const val HEADS_UP_NOTIFICATION_BASE_ID = 0x52697100
    private const val HEADS_UP_MIN_INTERVAL_MS = 800L
    private const val HEADS_UP_TIMEOUT_MS = 1800L
    private const val FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x1000000
    private const val FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x2000000

    private const val FLYME_STATUS_BAR_TICKER_ACTION = "com.flyme.statusbar.ticker"
    private const val ACTION_SEND_LYRIC = "com.meizu.flyme.ticker.ACTION_SEND"
    private const val ACTION_CLEAR_LYRIC = "com.meizu.flyme.ticker.ACTION_CLEAR"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    private var isRegistered = false
    private var lastPayload: Pair<String?, String?>? = null
    private var hardCancelStandalonePending = true
    private var contextRef: Context? = null
    private var tickerSeq = 0
    private var lastTickerNotificationId: Int? = null
    private var headsUpNotificationSeq = 0
    private var lastHeadsUpNotificationId = 0
    private var lastHeadsUpPostTimeMs = 0L

    private val flagAlwaysShowTicker: Int by lazy {
        getNotificationFlag("FLAG_ALWAYS_SHOW_TICKER")
    }

    private val flagOnlyUpdateTicker: Int by lazy {
        getNotificationFlag("FLAG_ONLY_UPDATE_TICKER")
    }

    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val payload = PlaybackTickerState.current()
                if (payload != null) {
                    postTickerNotification(payload.text, payload.translation)
                }
            }
        }
    }

    fun init(context: Context) {
        if (!AppPreferences.Lyrics.tickerEnabled) return
        contextRef = context.applicationContext
        if (isRegistered) return
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
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
        lastPayload = null
        cancelStandaloneTickerNotifications(context)
        cancelHeadsUpLyricNotification()
        PlaybackTickerState.clear()
    }

    fun updateLyric(context: Context, text: String, translation: String = "") {
        Log.d(TAG, "updateLyric called: enabled=${AppPreferences.Lyrics.tickerEnabled}, " +
            "hideNotification=${AppPreferences.Lyrics.tickerHideNotification}, " +
            "package=${context.packageName}, text=$text")

        if (!AppPreferences.Lyrics.tickerEnabled) {
            Log.d(TAG, "updateLyric skipped: tickerEnabled=false")
            return
        }

        val cleanTranslation = translation.takeIf { it.isNotBlank() }
        val payload = text to cleanTranslation
        if (payload == lastPayload) {
            Log.d(TAG, "updateLyric skipped: same payload")
            return
        }
        lastPayload = payload

        if (text.isBlank()) {
            clearLyric(context)
            return
        }

        try {
            // 发送 Flyme 广播
            val intent = Intent(ACTION_SEND_LYRIC).apply {
                putExtra("ticker_text", text)
                putExtra("lyric", text)
                putExtra("text", text)
                putExtra("content", text)
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
                putExtra("ticker_app_name", "RawSMusic")
                putExtra("app_name", "RawSMusic")
                if (cleanTranslation != null) {
                    putExtra("ticker_translation", cleanTranslation)
                }
                putExtra("flag", flagAlwaysShowTicker or flagOnlyUpdateTicker)
            }

            sendFlymeBroadcast(context, intent)

            val flymeIntent = Intent(FLYME_STATUS_BAR_TICKER_ACTION).apply {
                putExtra("ticker_text", text)
                putExtra("lyric", text)
                putExtra("text", text)
                putExtra("content", text)
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
                putExtra("ticker_app_name", "RawSMusic")
                putExtra("app_name", "RawSMusic")
                if (cleanTranslation != null) {
                    putExtra("ticker_translation", cleanTranslation)
                }
                putExtra("flag", flagAlwaysShowTicker or flagOnlyUpdateTicker)
            }
            sendFlymeBroadcast(context, flymeIntent)

            // 三选一：heads-up / 隐藏到主通知 / 独立 ticker 通知
            if (shouldUseHeadsUpLyrics()) {
                PlaybackTickerState.clear()
                cancelStandaloneTickerNotifications(context)
                postHeadsUpLyricNotification(text, cleanTranslation)
            } else if (AppPreferences.Lyrics.tickerHideNotification) {
                PlaybackTickerState.update(text, translation)
                cancelStandaloneTickerNotifications(context)
                cancelHeadsUpLyricNotification()
            } else {
                PlaybackTickerState.clear()
                cancelHeadsUpLyricNotification()
                postTickerNotification(text, cleanTranslation)
            }

            Log.d(TAG, "Ticker lyric sent: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ticker lyric", e)
        }
    }

    fun clearLyric(context: Context) {
        lastPayload = null
        try {
            val intent = Intent(ACTION_CLEAR_LYRIC).apply {
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
            }
            sendFlymeBroadcast(context, intent)

            val flymeIntent = Intent(FLYME_STATUS_BAR_TICKER_ACTION).apply {
                putExtra("ticker_package", context.packageName)
                putExtra("package", context.packageName)
            }
            sendFlymeBroadcast(context, flymeIntent)

            PlaybackTickerState.clear()

            // 清除两个交替 ticker 通知
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(TICKER_NOTIFICATION_ID_BASE)
                nm.cancel(TICKER_NOTIFICATION_ID_BASE + 1)
                lastTickerNotificationId = null
            } catch (_: Exception) {}

            cancelStandaloneTickerNotifications(context)
            cancelHeadsUpLyricNotification()
        } catch (_: Exception) {}
    }

    private fun cancelStandaloneTickerNotifications(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(TICKER_NOTIFICATION_ID_BASE)
        nm.cancel(TICKER_NOTIFICATION_ID_BASE + 1)
        // Never cancel notification id 1001 here.  That id belongs to PlayerService's
        // foreground media notification.  Cancelling it during pause or a song change makes
        // some vendor ROMs treat the following foreground update as a brand-new notification
        // and play the system notification sound.
        lastTickerNotificationId = null
        if (hardCancelStandalonePending) {
            hardCancelStandalonePending = false
            runCatching {
                ensureNotificationChannel(context)
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, CHANNEL_ID)
                } else {
                    Notification.Builder(context)
                }
                val disposable = builder
                    .setSmallIcon(R.drawable.ic_flyme_ticker)
                    .setContentTitle("")
                    .setContentText("")
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .setLocalOnly(true)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setDefaults(0)
                    .setSound(null)
                    .setVibrate(null)
                    .build()
                nm.notify(TICKER_NOTIFICATION_ID_BASE, disposable)
                nm.cancel(TICKER_NOTIFICATION_ID_BASE)
                nm.cancel(TICKER_NOTIFICATION_ID_BASE + 1)
            }
        }
    }

    private fun sendFlymeBroadcast(context: Context, intent: Intent) {
        context.sendBroadcast(intent)
        context.sendBroadcast(Intent(intent).setPackage(SYSTEM_UI_PACKAGE))
    }

    // ─────────────── Heads-up 悬浮歌词（非魅族设备） ───────────────

    @Suppress("DEPRECATION")
    private fun postHeadsUpLyricNotification(text: String, translation: String?) {
        val ctx = contextRef ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastHeadsUpPostTimeMs < HEADS_UP_MIN_INTERVAL_MS) return
        lastHeadsUpPostTimeMs = now

        ensureHeadsUpChannel(ctx)
        cancelHeadsUpLyricNotification()
        headsUpNotificationSeq = (headsUpNotificationSeq + 1) % 1000
        val notificationId = HEADS_UP_NOTIFICATION_BASE_ID + headsUpNotificationSeq

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID_HEADS_UP)
        } else {
            Notification.Builder(ctx)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_flyme_ticker)
            .setContentTitle(text)
            .setContentText(translation.orEmpty())
            .setTicker(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(HEADS_UP_TIMEOUT_MS)
            .build()

        lastHeadsUpNotificationId = notificationId
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId, notification)
        Log.d(TAG, "Heads-up lyric notification posted: $text")
    }

    private fun cancelHeadsUpLyricNotification() {
        if (lastHeadsUpNotificationId != 0) {
            val ctx = contextRef ?: return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(lastHeadsUpNotificationId)
            } catch (_: Exception) {}
            lastHeadsUpNotificationId = 0
        }
    }

    private fun ensureHeadsUpChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID_HEADS_UP) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID_HEADS_UP,
            "悬浮歌词",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "非魅族设备上以悬浮通知形式显示歌词"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    // ─────────────── Flyme 独立 Ticker 通知 ───────────────

    @Suppress("DEPRECATION")
    private fun postTickerNotification(text: String, translation: String?) {
        val ctx = contextRef ?: return
        if (text.isBlank()) return

        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureNotificationChannel(ctx)

            // 交替 ID：Flyme/SystemUI 对同 ID 的 ticker update 只处理第一次
            val id = TICKER_NOTIFICATION_ID_BASE + (tickerSeq++ % 2)
            val previousId = lastTickerNotificationId
            lastTickerNotificationId = id

            if (previousId != null && previousId != id) {
                try { nm.cancel(previousId) } catch (_: Exception) {}
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                Notification.Builder(ctx)
                    .setPriority(Notification.PRIORITY_LOW)
            }

            val notification = builder
                .setSmallIcon(R.drawable.ic_flyme_ticker)
                .setContentTitle(text)
                .setContentText(translation?.takeIf { it.isNotBlank() } ?: "RawSMusic")
                .setTicker(text)
                .setOngoing(false)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(false)
                .setDefaults(0)
                .setSound(null)
                .setVibrate(null)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setTimeoutAfter(2500L)
                    }
                }
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                notification.extras.putBoolean("ticker_icon_switch", false)
                notification.extras.putInt("ticker_icon", R.drawable.ic_flyme_ticker)
                notification.extras.putString("ticker_text", text)
                notification.extras.putString("lyric", text)
                notification.extras.putString("text", text)
                notification.extras.putString("content", text)
                notification.extras.putString("ticker_package", ctx.packageName)
                notification.extras.putString("package", ctx.packageName)
                notification.extras.putString("ticker_app_name", "RawSMusic")
                notification.extras.putString("app_name", "RawSMusic")
                if (!translation.isNullOrBlank()) {
                    notification.extras.putString("ticker_translation", translation)
                    notification.extras.putString("translation", translation)
                }
            }

            try {
                notification.flags = notification.flags or flagAlwaysShowTicker or flagOnlyUpdateTicker
            } catch (_: Exception) {
                notification.flags = notification.flags or FLAG_ALWAYS_SHOW_TICKER_FALLBACK or FLAG_ONLY_UPDATE_TICKER_FALLBACK
            }

            nm.notify(id, notification)
            Log.d(TAG, "Ticker notification posted: id=$id, text=$text")

            // 2.3 秒后自动取消
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { nm.cancel(id) } catch (_: Exception) {}
            }, 2300L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post ticker notification", e)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

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

    // ─────────────── 设备检测 ───────────────

    private fun isFlymeDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        return manufacturer.contains("meizu", ignoreCase = true) ||
            brand.contains("meizu", ignoreCase = true) ||
            display.contains("flyme", ignoreCase = true)
    }

    private fun isFlymeTickerSupported(): Boolean {
        return isFlymeDevice() && flagAlwaysShowTicker > 0 && flagOnlyUpdateTicker > 0
    }

    private fun shouldUseHeadsUpLyrics(): Boolean {
        return AppPreferences.Lyrics.tickerHeadsUpLyrics && !isFlymeTickerSupported()
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
