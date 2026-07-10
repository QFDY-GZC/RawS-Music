package com.rawsmusic.module.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.PlaybackTickerState
import com.rawsmusic.module.player.lyrics.PlayerServiceProxy
import com.rawsmusic.module.player.lyrics.TickerBridge
import com.rawsmusic.module.player.usb.UsbHardwareVolumeModel
import com.rawsmusic.module.player.usb.UsbHardwareVolumeProvider
import com.rawsmusic.module.player.usb.UsbVolumeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

class PlayerService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "rawsmusic_player_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.rawsmusic.action.PLAY"
        const val ACTION_PAUSE = "com.rawsmusic.action.PAUSE"
        const val ACTION_NEXT = "com.rawsmusic.action.NEXT"
        const val ACTION_PREVIOUS = "com.rawsmusic.action.PREVIOUS"
        const val ACTION_STOP = "com.rawsmusic.action.STOP"
        const val ACTION_UPDATE = "com.rawsmusic.action.UPDATE"
        const val ACTION_UPDATE_LYRICS = "com.rawsmusic.action.UPDATE_LYRICS"
        const val ACTION_ENSURE_WAKELOCK = "com.rawsmusic.action.ENSURE_WAKELOCK"
        const val ACTION_TOGGLE_SHUFFLE = "com.rawsmusic.action.TOGGLE_SHUFFLE"
        /** USB 独占播放前台保活 */
        const val ACTION_USB_PLAYBACK_FOREGROUND = "com.rawsmusic.action.USB_PLAYBACK_FOREGROUND"
        /** Keep system media identity active before native USB starts */
        const val ACTION_USB_MEDIA_IDENTITY = "com.rawsmusic.action.USB_MEDIA_IDENTITY"
        /** Sticky restart / task-removed restore path for background playback. */
        const val ACTION_RESTORE_STICKY_STATE = "com.rawsmusic.action.RESTORE_STICKY_STATE"
        /** App process entered foreground; Service should own runtime-side resume hooks. */
        const val ACTION_RUNTIME_APP_FOREGROUND = "com.rawsmusic.action.RUNTIME_APP_FOREGROUND"
        /** App process entered background; Service should own runtime-side background hooks. */
        const val ACTION_RUNTIME_APP_BACKGROUND = "com.rawsmusic.action.RUNTIME_APP_BACKGROUND"
        /** Top activity paused while playback may continue in background. */
        const val ACTION_RUNTIME_ACTIVITY_PAUSED = "com.rawsmusic.action.RUNTIME_ACTIVITY_PAUSED"
        /** Bootstrap runtime state and let Service own the playback facade early. */
        const val ACTION_RUNTIME_BOOTSTRAP = "com.rawsmusic.action.RUNTIME_BOOTSTRAP"
        /** UI host is being destroyed; Service decides whether runtime should survive. */
        const val ACTION_RUNTIME_UI_HOST_DESTROYED = "com.rawsmusic.action.RUNTIME_UI_HOST_DESTROYED"
        /** Route USB attach alias events into the Service-owned runtime. */
        const val ACTION_RUNTIME_USB_ATTACH = "com.rawsmusic.action.RUNTIME_USB_ATTACH"
        /** Ask the Service-owned runtime to probe/request pending USB permission. */
        const val ACTION_RUNTIME_USB_PERMISSION_SCAN = "com.rawsmusic.action.RUNTIME_USB_PERMISSION_SCAN"
        /** 停止播放服务（仅用户主动停止/关闭独占/USB拔出时调用） */
        const val ACTION_STOP_PLAYBACK_SERVICE = "com.rawsmusic.action.STOP_PLAYBACK_SERVICE"
        /** 屏幕解锁时检查USB状态 */
        const val ACTION_SCREEN_UNLOCKED = "com.rawsmusic.action.SCREEN_UNLOCKED"

        var isRunning = false
            private set

        private var _instance: PlayerService? = null

        private var cachedGetTokenMethod: java.lang.reflect.Method? = null
        private var cachedMTokenField: java.lang.reflect.Field? = null
        private var reflectionCacheInitialized = false
            private set

        /** 当前歌词数据 — 由MainActivity加载后设置 */
        private val _currentLyrics = MutableStateFlow<LyricData?>(null)
        val currentLyrics: StateFlow<LyricData?> = _currentLyrics.asStateFlow()

        /** 更新当前歌词 — 供外部（MainActivity）调用 */
        fun updateLyrics(lyricData: LyricData?) {
            _currentLyrics.value = lyricData
        }

        fun pushLyricsToMediaSession() {
            _instance?.updateLyricsInMetadata()
        }

        /**
         * Media identity pulse from PlayerController.
         * Keep this as an in-process call so it can refresh MediaSession/FGS
         * even when repeated service Intents are delayed by background policy.
         */
        fun syncUsbMediaIdentityFromController(
            song: AudioFile?,
            playing: Boolean,
            position: Long,
            reason: String
        ): Boolean {
            val service = _instance ?: return false
            service.syncUsbMediaIdentity(song, playing, position, reason)
            return true
        }

        fun clearUsbMediaIdentityFromController(
            reason: String,
            releaseFocus: Boolean
        ): Boolean {
            val service = _instance ?: return false
            service.clearUsbMediaIdentity(reason, releaseFocus)
            return true
        }

        fun shouldBootstrapRuntime(): Boolean {
            if (_instance != null || PlayerRuntimeRegistry.currentControllerOrNull() != null) {
                return true
            }
            val lastState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
                PlayState.IDLE
            }
            return AppPreferences.Player.lastUsbExclusiveActive ||
                AppPreferences.Player.lastSongPath.isNotBlank() ||
                lastState == PlayState.PLAYING ||
                lastState == PlayState.PAUSED ||
                lastState == PlayState.PREPARING
        }

        fun shouldRetainRuntimeOnUiDestroy(
            controller: PlayerController? = PlayerRuntimeRegistry.currentControllerOrNull()
        ): Boolean {
            val service = _instance
            val serviceState = service?.currentPlayState
            return controller?.isUsbExclusiveActive() == true ||
                controller?.playState?.value == PlayState.PLAYING ||
                serviceState == PlayState.PLAYING ||
                (service != null && AppPreferences.Player.lastUsbExclusiveActive)
        }

        fun currentRuntimeController(): PlayerController? =
            PlayerRuntimeRegistry.currentControllerOrNull() ?: PlayerController.getInstanceOrNull()

        private fun startServiceAction(
            context: Context,
            action: String,
            reason: String,
            requireForegroundStart: Boolean,
            configureIntent: (Intent.() -> Unit)? = null
        ): Boolean {
            return runCatching {
                val intent = Intent(context, PlayerService::class.java).apply {
                    this.action = action
                    putExtra("reason", reason)
                    configureIntent?.invoke(this)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requireForegroundStart) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)
        }

        fun ensureServiceStarted(
            context: Context,
            reason: String,
            action: String = ACTION_RUNTIME_BOOTSTRAP
        ): Boolean {
            if (_instance != null && action == ACTION_RUNTIME_BOOTSTRAP) {
                _instance?.bootstrapRuntimeState(reason)
                return true
            }
            return startServiceAction(
                context = context,
                action = action,
                reason = reason,
                requireForegroundStart = true
            )
        }

        fun ensureRuntimeService(
            context: Context,
            reason: String,
            force: Boolean = false
        ): Boolean {
            if (!force && !shouldBootstrapRuntime()) return false
            if (_instance != null) {
                _instance?.bootstrapRuntimeState(reason)
                return true
            }
            return startServiceAction(
                context = context,
                action = ACTION_RUNTIME_BOOTSTRAP,
                reason = reason,
                requireForegroundStart = true
            )
        }

        fun obtainRuntimeController(
            context: Context,
            reason: String,
            ensureService: Boolean = false
        ): PlayerController {
            val current = currentRuntimeController()
            if (current != null) {
                PlayerRuntimeRegistry.attachController(current, "runtime_obtain:$reason")
                return current
            }
            if (ensureService) {
                ensureServiceStarted(context, "obtain_runtime:$reason")
            } else if (shouldBootstrapRuntime()) {
                ensureRuntimeService(context, "obtain_runtime:$reason")
            }
            return (currentRuntimeController() ?: PlayerController.getInstance(context)).also {
                PlayerRuntimeRegistry.attachController(it, "runtime_obtain:$reason")
            }
        }

        fun dispatchUsbAttachIntent(
            context: Context,
            device: UsbDevice,
            reason: String
        ): Boolean {
            _instance?.let { service ->
                service.playerController.handleUsbDeviceAttachIntent(
                    device,
                    "service_direct:$reason"
                )
                return true
            }
            return startServiceAction(
                context = context,
                action = ACTION_RUNTIME_USB_ATTACH,
                reason = reason,
                requireForegroundStart = true
            ) {
                putExtra("device", device)
            }
        }

        fun dispatchUsbAttachPermissionScan(context: Context, reason: String): Boolean {
            _instance?.let { service ->
                service.playerController.requestUsbAttachPermissionIfPresent(
                    "service_direct:$reason"
                )
                return true
            }
            return startServiceAction(
                context = context,
                action = ACTION_RUNTIME_USB_PERMISSION_SCAN,
                reason = reason,
                requireForegroundStart = false
            )
        }

        fun dispatchUiHostDestroyed(
            context: Context,
            reason: String,
            finishing: Boolean
        ): Boolean {
            _instance?.let { service ->
                service.handleUiHostDestroyed(reason, finishing, source = "direct")
                return true
            }

            val current = currentRuntimeController()
            if (finishing && current != null && !shouldRetainRuntimeOnUiDestroy(current)) {
                runCatching {
                    current.release()
                }
                return true
            }

            if (!isRunning) return false
            return startServiceAction(
                context = context,
                action = ACTION_RUNTIME_UI_HOST_DESTROYED,
                reason = reason,
                requireForegroundStart = false
            ) {
                putExtra("finishing", finishing)
            }
        }

        private fun dispatchRuntimeLifecycleAction(
            context: Context,
            action: String,
            reason: String
        ): Boolean {
            _instance?.let { service ->
                service.handleRuntimeLifecycleAction(action, reason, source = "direct")
                return true
            }
            if (!isRunning) return false
            return runCatching {
                context.startService(
                    Intent(context, PlayerService::class.java).apply {
                        this.action = action
                        putExtra("reason", reason)
                    }
                )
                true
            }.getOrDefault(false)
        }

        fun dispatchAppProcessForeground(context: Context, reason: String): Boolean =
            dispatchRuntimeLifecycleAction(context, ACTION_RUNTIME_APP_FOREGROUND, reason)

        fun dispatchAppProcessBackground(context: Context, reason: String): Boolean =
            dispatchRuntimeLifecycleAction(context, ACTION_RUNTIME_APP_BACKGROUND, reason)

        fun dispatchActivityPaused(context: Context, reason: String): Boolean =
            dispatchRuntimeLifecycleAction(context, ACTION_RUNTIME_ACTIVITY_PAUSED, reason)
    }

    private val playerController: PlayerController by lazy {
        PlayerController.getInstance(this)
    }

    private var mediaSessionCompat: MediaSessionCompat? = null
    private var currentSong: AudioFile? = null
    private var currentPlayState: PlayState = PlayState.IDLE
    private var coverBitmap: Bitmap? = null
    /** 记录当前正在加载封面的 albumArtPath，防止竞态 */
    private var loadingArtPath: String? = null
    /** 上次收到的播放位置，用于通知栏进度条更新 */
    private var lastKnownPosition: Long = 0L
    /** 上次收到精确位置时的系统时间，用于估算当前播放位置 */
    private var lastPositionTime: Long = 0L
    /** 播放位置更新协程 */
    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    /** WakeLock — 防止CPU休眠导致后台播放被杀 */
    private var wakeLock: PowerManager.WakeLock? = null
    /** USB 独占播放专用 WakeLock — 在 usbExclusiveMode 期间持有，防止 OTG 被系统关闭 */
    private var usbWakeLock: PowerManager.WakeLock? = null
    /** Service-owned AudioFocus: ColorOS/Hans recognizes importance=audioFocus only when the playback service owns focus. */
    private var serviceAudioFocusRequest: AudioFocusRequest? = null
    private var serviceAudioFocusGranted = false
    /** USB 硬件音量 VolumeProvider — 接收系统音量键事件 */
    private var usbVolumeProvider: UsbHardwareVolumeProvider? = null
    /** WifiLock. Even for local playback, some OEM schedulers classify
     *  the app more leniently when both media FGS and a high-perf lock are active. */
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastUsbForegroundEnsureElapsed: Long = 0L
    private var lastUsbProgressPulseLogElapsed: Long = 0L
    private var usbBackgroundGuardianJob: Job? = null
    private var lastUsbBackgroundGuardianLogElapsed: Long = 0L

    /** 屏幕解锁广播接收器 — USER_PRESENT 监听，确保USB独占模式在锁屏后正常工作 */
    private val screenUnlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                Log.d("PlayerService", "Screen unlocked, checking USB state")
                // 屏幕解锁时确保 WakeLock 持有
                acquireWakeLockIfNeeded()
                // 如果当前正在播放且使用USB独占模式，确保WakeLock有效
                if (currentPlayState == PlayState.PLAYING && playerController.isUsbExclusiveActive()) {
                    acquireUsbWakeLock()
                }
            }
        }
    }
    private var screenReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        _instance = this

        // Service 持有 PlayerController 单例，避免 UI 层重建导致重复创建
        val controller = playerController
        // Service cold start only remembers an attached DAC.  Do not request
        // permission or arm USB exclusive while Android is still restoring the
        // foreground service; HyperOS can stall cold playback if USB work starts
        // before the media service is fully foreground.
        controller.scanForUsbDevice(startup = true)

        // 初始化 Lyricon（放在 Service 层，避免 Activity 重建丢失）
        LyriconProviderManager.init(this)
        LyriconProviderManager.startPositionSync(controller)

        // 注册屏幕解锁广播接收器 — USER_PRESENT 监听
        registerScreenUnlockReceiver()

        createNotificationChannel()

        // 创建MediaSessionCompat用于通知栏和系统媒体控制
        mediaSessionCompat = MediaSessionCompat(this, "RawSMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playerController.resume()
                    notifyActivity(ACTION_PLAY)
                }
                override fun onPause() {
                    playerController.pause()
                    notifyActivity(ACTION_PAUSE)
                }
                override fun onSkipToNext() {
                    playerController.next()
                    notifyActivity(ACTION_NEXT)
                }
                override fun onSkipToPrevious() {
                    playerController.previous()
                    notifyActivity(ACTION_PREVIOUS)
                }
                override fun onStop() {
                    playerController.stop()
                    notifyActivity(ACTION_STOP)
                }
                override fun onSeekTo(pos: Long) {
                    playerController.seekTo(pos)
                    lastKnownPosition = pos
                    updateMediaSessionPlaybackState(currentPlayState, pos)
                    PlayerEventBus.emit("com.rawsmusic.action.SEEK", pos)
                }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val ev = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev?.action == KeyEvent.ACTION_DOWN) {
                        if (playerController.shouldUseUsbRemoteVolume()) {
                            when (ev.keyCode) {
                                KeyEvent.KEYCODE_VOLUME_UP -> {
                                    playerController.stepUsbVolume(UsbVolumeController.DEFAULT_STEP)
                                    return true
                                }
                                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                    playerController.stepUsbVolume(-UsbVolumeController.DEFAULT_STEP)
                                    return true
                                }
                                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                                    playerController.setUsbVolumeLinear(0f)
                                    return true
                                }
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }

        // 初始化 USB 硬件音量 VolumeProvider
        setupUsbVolumeProvider()

        // 启动前台通知
        startForegroundCompat(NOTIFICATION_ID, buildNotification())

        TickerBridge.init(this)
        PlayerServiceProxy.setUpdateCallback {
            rebuildMetadataWithBluetoothLyric()
        }
        PlaybackTickerState.setRefreshCallback {
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action.isNullOrBlank()) {
            val lastWasPlaying = AppPreferences.Player.lastPlayStateOrdinal == PlayState.PLAYING.ordinal
            if (lastWasPlaying && !AppPreferences.Player.lastUsbExclusiveActive) {
                restoreStickyPlaybackState("null_intent_restart", autoResume = true)
            } else {
                Log.i(
                    "PlayerService",
                    "null intent ignored: lastWasPlaying=$lastWasPlaying lastUsbExclusive=${AppPreferences.Player.lastUsbExclusiveActive}"
                )
            }
        } else {
            handleAction(action, intent)
        }
        val controller = playerController
        val activelyPlaying = currentPlayState == PlayState.PLAYING ||
            controller.playState.value == PlayState.PLAYING
        val usbProtected = controller.isUsbExclusiveActive() || AppPreferences.Player.lastUsbExclusiveActive
        return if (activelyPlaying || usbProtected) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val controller = playerController
        if (controller.isUsbExclusiveActive()) {
            val activelyPlaying = currentPlayState == PlayState.PLAYING ||
                controller.playState.value == PlayState.PLAYING
            if (!activelyPlaying) {
                Log.i("PlayerService", "onTaskRemoved: USB exclusive idle, stopping service without restart")
                clearUsbMediaIdentity("task_removed_idle_usb", releaseFocus = true)
                releaseUsbWakeLock()
                stopForegroundCompat(removeNotification = true)
                stopSelf()
            } else {
                Log.i("PlayerService", "onTaskRemoved: USB exclusive playing, keep existing foreground service without exact restart")
            }
            super.onTaskRemoved(rootIntent)
            return
        }
        if (currentPlayState != PlayState.PLAYING) {
            Log.i("PlayerService", "onTaskRemoved: idle, stop without restart")
            stopForegroundCompat(removeNotification = true)
            stopSelf()
            super.onTaskRemoved(rootIntent)
            return
        }
        // 应用从最近任务移除时，重新拉起可恢复播放态的前台服务。
        val restartIntent = buildStickyRestoreIntent("task_removed")
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                0,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                0,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        val triggerAt = android.os.SystemClock.elapsedRealtime() + 500
        val canUseExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (canUseExactAlarm) {
                alarmManager?.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                Log.w(
                    "PlayerService",
                    "onTaskRemoved: exact alarm not permitted, fallback to inexact while-idle restart"
                )
                alarmManager?.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        } else {
            alarmManager?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) {
                    Service.STOP_FOREGROUND_REMOVE
                } else {
                    Service.STOP_FOREGROUND_DETACH
                }
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun buildStickyRestoreIntent(reason: String): Intent {
        return Intent(this, PlayerService::class.java).apply {
            action = ACTION_RESTORE_STICKY_STATE
            putExtra("reason", reason)
            currentSong?.let { song ->
                putExtra("title", song.title.ifBlank { song.displayName })
                putExtra("artist", song.artist)
                putExtra("album", song.album)
                putExtra("albumArtPath", song.albumArtPath)
                putExtra("duration", song.duration)
                putExtra("path", song.path)
            }
            putExtra("position", lastKnownPosition.coerceAtLeast(0L))
            putExtra("playing", currentPlayState == PlayState.PLAYING)
            putExtra("autoResume", currentPlayState == PlayState.PLAYING && !AppPreferences.Player.lastUsbExclusiveActive)
        }
    }

    private fun bootstrapRuntimeState(reason: String) {
        val controller = playerController
        val controllerSong = controller.currentSong.value
        if (controllerSong == null && AppPreferences.Player.lastSongPath.isBlank()) {
            Log.i("PlayerService", "bootstrapRuntimeState skipped: no retained song reason=$reason")
            return
        }
        if (controllerSong == null) {
            restoreStickyPlaybackState(reason = "bootstrap:$reason", autoResume = false)
            return
        }

        val restoredState = controller.playState.value.takeIf { it != PlayState.IDLE }
            ?: PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
                PlayState.IDLE
            }

        currentSong = controllerSong
        currentPlayState = restoredState
        lastKnownPosition = controller.position.value.coerceAtLeast(0L)
        lastPositionTime = SystemClock.elapsedRealtime()
        mediaSessionCompat?.isActive = true

        updateMediaSessionMetadata(
            controllerSong.title,
            controllerSong.artist,
            controllerSong.album,
            controllerSong.albumArtPath,
            controllerSong.duration
        )
        updateMediaSessionPlaybackState(restoredState, lastKnownPosition)

        if (restoredState == PlayState.PLAYING) {
            acquireWakeLockIfNeeded()
            acquireWifiLockIfNeeded("bootstrap:$reason")
            requestServiceAudioFocus("bootstrap:$reason")
            forceMediaSessionPlaying("bootstrap:$reason")
            startPositionUpdates()
        } else {
            updateNotification()
        }

        if (controller.isUsbExclusiveActive() || AppPreferences.Player.lastUsbExclusiveActive) {
            ensureUsbForegroundThrottled("runtime_bootstrap:$reason", force = true)
            updateForegroundServiceType()
        }

        Log.i(
            "PlayerService",
            "bootstrapRuntimeState: reason=$reason song=${controllerSong.title} " +
                "state=$restoredState usb=${controller.isUsbExclusiveActive()} pos=$lastKnownPosition"
        )
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("device", UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("device")
        }
    }

    private fun restoreStickyPlaybackState(reason: String, autoResume: Boolean) {
        val controller = playerController
        val restoredSong = controller.currentSong.value ?: controller.restoreLastSong()
        if (restoredSong == null) {
            Log.w("PlayerService", "restoreStickyPlaybackState skipped: no last song reason=$reason")
            return
        }

        val restoredState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
            PlayState.IDLE
        }
        val shouldProtectUsb = AppPreferences.Player.lastUsbExclusiveActive || controller.isUsbExclusiveActive()

        currentSong = restoredSong
        currentPlayState = restoredState
        lastKnownPosition = AppPreferences.Player.lastPosition.coerceAtLeast(0L)
        lastPositionTime = SystemClock.elapsedRealtime()
        mediaSessionCompat?.isActive = true

        updateMediaSessionMetadata(
            restoredSong.title,
            restoredSong.artist,
            restoredSong.album,
            restoredSong.albumArtPath,
            restoredSong.duration
        )
        updateMediaSessionPlaybackState(restoredState, lastKnownPosition)
        updateNotification()

        if (restoredState == PlayState.PLAYING) {
            acquireWakeLockIfNeeded()
            acquireWifiLockIfNeeded("restore:$reason")
            requestServiceAudioFocus("restore:$reason")
            ensureForegroundForUsb()
            acquireUsbWakeLock()
            updateForegroundServiceType()
        }
        if (restoredState == PlayState.PLAYING) {
            forceMediaSessionPlaying("restore:$reason")
            startPositionUpdates()
        }

        if (autoResume && restoredState == PlayState.PLAYING) {
            val queueSnapshot = controller.queue.value
            val queueSongs = if (queueSnapshot.songs.isNotEmpty()) queueSnapshot.songs else listOf(restoredSong)
            val queueIndex = queueSongs.indexOfFirst { song ->
                song.path == restoredSong.path &&
                    song.cueOffsetMs == restoredSong.cueOffsetMs &&
                    song.cueTrackIndex == restoredSong.cueTrackIndex
            }.takeIf { it >= 0 } ?: queueSnapshot.currentIndex.coerceIn(0, queueSongs.lastIndex)

            lifecycleScope.launch(Dispatchers.Main.immediate) {
                delay(180)
                if (controller.playState.value == PlayState.PLAYING) return@launch
                runCatching {
                    controller.play(queueSongs[queueIndex], queueSongs, queueIndex)
                }.onFailure {
                    Log.w("PlayerService", "restoreStickyPlaybackState autoResume failed: reason=$reason ${it.message}")
                }
            }
        }

        Log.i(
            "PlayerService",
            "restoreStickyPlaybackState: reason=$reason autoResume=$autoResume " +
                "song=${restoredSong.title} state=$restoredState usb=$shouldProtectUsb pos=$lastKnownPosition"
        )
    }

    private fun handleRuntimeLifecycleAction(action: String, reason: String, source: String) {
        val controller = playerController
        when (action) {
            ACTION_RUNTIME_APP_FOREGROUND -> {
                Log.i("PlayerService", "runtime lifecycle foreground: reason=$reason source=$source")
                controller.onAppForegroundResumed()
            }
            ACTION_RUNTIME_APP_BACKGROUND -> {
                Log.i("PlayerService", "runtime lifecycle background: reason=$reason source=$source")
                controller.onAppWentBackground()
            }
            ACTION_RUNTIME_ACTIVITY_PAUSED -> {
                Log.i("PlayerService", "runtime lifecycle activity paused: reason=$reason source=$source")
                controller.onAppMaybeLeavingForeground()
            }
        }
        syncUsbBackgroundGuardian("runtime_lifecycle:$action:$reason")
    }

    private fun releaseRuntimeController(reason: String) {
        runCatching {
            playerController.release()
            Log.i("PlayerService", "runtime controller released: reason=$reason")
        }.onFailure {
            Log.w("PlayerService", "runtime controller release failed: reason=$reason ${it.message}")
        }
    }

    private fun shutdownIdleRuntime(reason: String) {
        Log.i("PlayerService", "shutdownIdleRuntime: reason=$reason")
        stopUsbBackgroundGuardian("shutdown_idle:$reason")
        clearUsbMediaIdentity("shutdown_idle:$reason", releaseFocus = true)
        releaseUsbWakeLock()
        releaseWakeLock()
        releaseWifiLock("shutdown_idle:$reason")
        stopForegroundCompat(removeNotification = true)
        releaseRuntimeController("shutdown_idle:$reason")
        stopSelf()
    }

    private fun handleUiHostDestroyed(reason: String, finishing: Boolean, source: String) {
        if (!finishing) {
            Log.i("PlayerService", "ui host destroyed but not finishing: reason=$reason source=$source")
            return
        }

        if (shouldRetainRuntimeOnUiDestroy(playerController)) {
            Log.i(
                "PlayerService",
                "ui host destroyed: retaining runtime reason=$reason source=$source " +
                    "playState=${playerController.playState.value} serviceState=$currentPlayState " +
                    "usb=${playerController.isUsbExclusiveActive()}"
            )
            return
        }

        shutdownIdleRuntime("ui_host_destroyed:$reason")
    }

    private fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_PLAY -> {
                notifyActivity(action)
                playerController.resume()
            }
            ACTION_PAUSE -> {
                notifyActivity(action)
                playerController.pause()
            }
            ACTION_NEXT -> {
                notifyActivity(action)
                playerController.next()
            }
            ACTION_PREVIOUS -> {
                notifyActivity(action)
                playerController.previous()
            }
            ACTION_STOP -> {
                notifyActivity(action)
                playerController.stop()
                stopUsbBackgroundGuardian("action_stop")
            }
            "com.rawsmusic.action.SYNC_POSITION" -> {
                // 从应用同步播放进度，避免通知栏进度条漂移
                val pos = intent.getLongExtra("position", 0L)
                if (pos > 0) {
                    lastKnownPosition = pos
                    lastPositionTime = SystemClock.elapsedRealtime()
                    if (currentPlayState == PlayState.PLAYING) {
                        updateMediaSessionPlaybackState(PlayState.PLAYING, pos)
                    }
                }
            }
            ACTION_UPDATE -> {
                // 从Intent读取歌曲信息并更新通知
                val title = intent.getStringExtra("title") ?: return
                val artist = intent.getStringExtra("artist") ?: ""
                val album = intent.getStringExtra("album") ?: ""
                val albumArtPath = intent.getStringExtra("albumArtPath") ?: ""
                val path = intent.getStringExtra("path") ?: ""
                val fileSize = intent.getLongExtra("fileSize", 0L)
                val dateModified = intent.getLongExtra("dateModified", 0L)
                val cueTrackIndex = intent.getIntExtra("cueTrackIndex", 0)
                val serviceArtworkPath = albumArtPath.ifBlank { path }
                val duration = intent.getLongExtra("duration", 0L)
                val state = intent.getIntExtra("playState", PlayState.IDLE.ordinal)
                val position = intent.getLongExtra("position", 0L)

                lastKnownPosition = position
                lastPositionTime = SystemClock.elapsedRealtime()

                val songChanged = currentSong?.albumArtPath != serviceArtworkPath ||
                        currentSong?.path != path ||
                        currentSong?.title != title

                currentSong = AudioFile(
                    id = 0,
                    path = path,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    albumArtPath = serviceArtworkPath,
                    fileSize = fileSize,
                    dateModified = dateModified,
                    cueTrackIndex = cueTrackIndex
                )
                currentPlayState = PlayState.entries.getOrElse(state) { PlayState.IDLE }
                if (currentPlayState == PlayState.PLAYING) {
                    requestServiceAudioFocus("ACTION_UPDATE_PLAYING")
                    acquireWakeLockIfNeeded()
                }

                // 始终更新完整元数据+播放状态（解决切歌后封面不更新）
                updateMediaSessionMetadata(title, artist, album, serviceArtworkPath, duration)

                // 显式更新播放状态，触发系统通知栏刷新元数据
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)

                // 播放中：启动位置定期更新（通知栏进度条）
                startPositionUpdates()
            }
            ACTION_UPDATE_LYRICS -> {
                // 歌词已更新，仅更新歌词和元数据（保留已有封面）
                updateLyricsInMetadata()
            }
            ACTION_ENSURE_WAKELOCK -> {
                // 确保 WakeLock 持有（切歌期间防止被系统挂起）
                acquireWakeLockIfNeeded()
            }
            "com.rawsmusic.action.ACQUIRE_USB_WAKELOCK" -> {
                // 后台冷启动保护：先进入前台播放状态，再拿 WakeLock
                ensureUsbForegroundThrottled("ACQUIRE_USB_WAKELOCK", force = true)
                acquireUsbWakeLock()
                updateForegroundServiceType()
            }
            "com.rawsmusic.action.RELEASE_USB_WAKELOCK" -> {
                releaseUsbWakeLock()
                updateForegroundServiceType()
            }
            ACTION_USB_PLAYBACK_FOREGROUND -> {
                // USB 独占播放：只要 controller 明确要求，就立即前台化。
                // 后台切换边界上 Service 的 currentPlayState 可能还没同步到
                // PLAYING；如果这里跳过，系统会把 libusb event loop 当普通后台
                // 线程冻结，回前台才出现整段 EVENT LOOP GAP。
                requestServiceAudioFocus("USB_PLAYBACK_FOREGROUND")
                forceMediaSessionPlaying("USB_PLAYBACK_FOREGROUND")
                acquireWakeLockIfNeeded()
                ensureUsbForegroundThrottled("USB_PLAYBACK_FOREGROUND", force = true)
                acquireUsbWakeLock()
                syncUsbBackgroundGuardian("usb_playback_foreground")
                Log.i("PlayerService", "USB playback foreground ensured")
            }
            ACTION_RESTORE_STICKY_STATE -> {
                restoreStickyPlaybackState(
                    reason = intent.getStringExtra("reason") ?: "sticky_restore",
                    autoResume = intent.getBooleanExtra("autoResume", !AppPreferences.Player.lastUsbExclusiveActive)
                )
            }
            ACTION_RUNTIME_APP_FOREGROUND,
            ACTION_RUNTIME_APP_BACKGROUND,
            ACTION_RUNTIME_ACTIVITY_PAUSED -> {
                handleRuntimeLifecycleAction(
                    action = action,
                    reason = intent.getStringExtra("reason") ?: "runtime_lifecycle",
                    source = "intent"
                )
            }
            ACTION_RUNTIME_BOOTSTRAP -> {
                bootstrapRuntimeState(
                    reason = intent.getStringExtra("reason") ?: "runtime_bootstrap"
                )
            }
            ACTION_RUNTIME_UI_HOST_DESTROYED -> {
                handleUiHostDestroyed(
                    reason = intent.getStringExtra("reason") ?: "runtime_ui_host_destroyed",
                    finishing = intent.getBooleanExtra("finishing", true),
                    source = "intent"
                )
            }
            ACTION_RUNTIME_USB_ATTACH -> {
                val device = extractUsbDevice(intent)
                if (device == null) {
                    Log.w("PlayerService", "runtime usb attach ignored: missing device")
                } else {
                    playerController.handleUsbDeviceAttachIntent(
                        device,
                        intent.getStringExtra("reason") ?: "runtime_usb_attach"
                    )
                }
            }
            ACTION_RUNTIME_USB_PERMISSION_SCAN -> {
                playerController.requestUsbAttachPermissionIfPresent(
                    intent.getStringExtra("reason") ?: "runtime_usb_permission_scan"
                )
            }
            ACTION_USB_MEDIA_IDENTITY -> {
                handleUsbMediaIdentity(intent)
            }
            ACTION_STOP_PLAYBACK_SERVICE -> {
                // 仅用户主动停止/关闭独占/USB拔出时调用
                releaseUsbWakeLock()
                stopUsbBackgroundGuardian("stop_playback_service")
                abandonServiceAudioFocus("STOP_PLAYBACK_SERVICE")
                updateForegroundServiceType()
                Log.i("PlayerService", "USB playback service stop requested")
            }
            "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME" -> {
                activateUsbRemoteVolume("controller_request")
            }
            "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME" -> {
                deactivateUsbRemoteVolume("controller_request")
            }
            ACTION_TOGGLE_SHUFFLE -> {
                val ctrl = playerController
                if (ctrl.isShuffle.value) {
                    ctrl.toggleShuffle()
                } else {
                    val rm = ctrl.repeatMode.value
                    when {
                        rm == RepeatMode.OFF -> {
                            ctrl.toggleRepeatMode()
                        }
                        rm == RepeatMode.ALL -> {
                            ctrl.toggleRepeatMode()
                        }
                        rm == RepeatMode.ONE -> {
                            ctrl.toggleRepeatMode()
                            ctrl.toggleShuffle()
                        }
                    }
                }
                updateNotification()
            }
        }
    }

    /**
     * 通过广播通知MainActivity执行播放控制
     */
    private fun notifyActivity(action: String) {
        PlayerEventBus.emit(action)

        // 本地状态更新 — NEXT/PREVIOUS 不在此更新播放状态
        // 等待 MainActivity 通过 ACTION_UPDATE 回传真实状态
        when (action) {
            ACTION_PLAY -> {
                currentPlayState = PlayState.PLAYING
                requestServiceAudioFocus("ACTION_PLAY")
                acquireWakeLock()
                startPositionUpdates()
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
                updateNotification()
            }
            ACTION_PAUSE -> {
                currentPlayState = PlayState.PAUSED
                positionUpdateJob?.cancel()
                // 暂停时保留WakeLock，避免蓝牙场景下被杀后台
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
                updateNotification()
            }
            ACTION_NEXT, ACTION_PREVIOUS -> {
                acquireWakeLock()
                startPositionUpdates()
            }
        }
        syncUsbBackgroundGuardian("notify:$action")
    }


    /**
     * Media identity gate.  Keep the service visible to Android/OPlus as
     * an active media playback app before native USB starts, so Hans/Osense should
     * classify the process as importance=audioFocus / Perceptible instead of freezing
     * the libusb event loop during app switches.
     */
    private fun handleUsbMediaIdentity(intent: Intent) {
        val title = intent.getStringExtra("title")
        val artist = intent.getStringExtra("artist") ?: ""
        val album = intent.getStringExtra("album") ?: ""
        val albumArtPath = intent.getStringExtra("albumArtPath") ?: ""
        val path = intent.getStringExtra("path") ?: currentSong?.path.orEmpty()
        val serviceArtworkPath = albumArtPath.ifBlank { path }
        val duration = intent.getLongExtra("duration", currentSong?.duration ?: 0L)
        val position = intent.getLongExtra("position", lastKnownPosition).coerceAtLeast(0L)
        val reason = intent.getStringExtra("reason") ?: "usb_media_identity"
        val playing = intent.getBooleanExtra("playing", true)

        val song = if (!title.isNullOrBlank()) {
            AudioFile(
                id = currentSong?.id ?: 0L,
                path = path,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                albumArtPath = serviceArtworkPath
            )
        } else {
            null
        }

        syncUsbMediaIdentity(song, playing, position, reason)
        Log.i("PlayerService", "USB media identity active: reason=$reason title=${title ?: currentSong?.title} pos=$position playing=$playing")
    }

    private fun forceMediaSessionPlaying(reason: String) {
        mediaSessionCompat?.isActive = true
        if (currentPlayState != PlayState.PLAYING) {
            currentPlayState = PlayState.PLAYING
        }
        if (lastPositionTime <= 0L) {
            lastPositionTime = SystemClock.elapsedRealtime()
        }
        updateMediaSessionPlaybackState(PlayState.PLAYING, lastKnownPosition)
        startPositionUpdates()
        Log.d("PlayerService", "forceMediaSessionPlaying: reason=$reason pos=$lastKnownPosition")
    }

    private fun requestServiceAudioFocus(reason: String): Boolean {
        if (serviceAudioFocusGranted) return true
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                Log.i("PlayerService", "AudioFocus changed: focus=$focusChange reason=$reason state=$currentPlayState")
                // USB exclusive should not be paused merely because Activity went to background.
                // PlayerController owns the real pause/duck policy.
            }
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            val result = am.requestAudioFocus(req)
            serviceAudioFocusRequest = req
            serviceAudioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i("PlayerService", "requestServiceAudioFocus: reason=$reason result=$result granted=$serviceAudioFocusGranted")
            serviceAudioFocusGranted
        } catch (t: Throwable) {
            Log.w("PlayerService", "requestServiceAudioFocus failed: reason=$reason ${t.message}")
            false
        }
    }

    private fun abandonServiceAudioFocus(reason: String) {
        if (!serviceAudioFocusGranted) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            serviceAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            Log.i("PlayerService", "abandonServiceAudioFocus: reason=$reason")
        } catch (t: Throwable) {
            Log.w("PlayerService", "abandonServiceAudioFocus failed: reason=$reason ${t.message}")
        } finally {
            serviceAudioFocusRequest = null
            serviceAudioFocusGranted = false
        }
    }

    /**
     * 更新MediaSession的元数据（标题、艺术家、封面等）
     */
    private fun updateMediaSessionMetadata(title: String, artist: String, album: String, albumArtPath: String, duration: Long) {
        coverBitmap = null
        val serviceArtworkPath = albumArtPath.ifBlank { currentSong?.path.orEmpty() }

        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: artist

        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)

        // 先用无封面更新通知
        updateNotification()

        // 异步加载新封面
        if (serviceArtworkPath.isNotBlank()) {
            loadCoverBitmap(serviceArtworkPath)
        }

        // 强制更新播放状态，触发系统通知栏刷新元数据（尤其是封面）
        updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
    }

    /**
     * 仅更新歌词到MediaSession元数据，保留已有封面（不清除coverBitmap）
     * 解决：自然切歌时歌词异步加载完成后，不清空已加载的封面
     */
    private fun updateLyricsInMetadata() {
        val song = currentSong ?: return
        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist

        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)
        updateNotification()
    }

    /**
     * 更新MediaSession的播放状态
     */
    private fun updateMediaSessionPlaybackState(state: PlayState, position: Long) {
        val (stateCompat, speed) = when (state) {
            PlayState.PLAYING -> PlaybackStateCompat.STATE_PLAYING to 1.0f
            PlayState.PAUSED -> PlaybackStateCompat.STATE_PAUSED to 0f
            PlayState.PREPARING -> PlaybackStateCompat.STATE_BUFFERING to 0f
            PlayState.STOPPED -> PlaybackStateCompat.STATE_STOPPED to 0f
            PlayState.IDLE -> PlaybackStateCompat.STATE_NONE to 0f
            PlayState.ERROR -> PlaybackStateCompat.STATE_ERROR to 0f
        }

        val playbackState = PlaybackStateCompat.Builder().apply {
            setState(stateCompat, position.coerceAtLeast(0L), speed, SystemClock.elapsedRealtime())
            setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
        }.build()
        mediaSessionCompat?.setPlaybackState(playbackState)
    }

    private fun rebuildMetadataWithBluetoothLyric() {
        val song = currentSong ?: return
        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }
        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist
        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)
        updateNotification()
    }

    /**
     * 构建LRC格式歌词文本
     */
    private fun buildLrcText(lyricData: LyricData): String {
        val lrcBuilder = StringBuilder()
        for (line in lyricData.lines) {
            if (line.timeStamp < 0) continue
            val ts = formatTimestamp(line.timeStamp)
            val endTime = if (line.endTime > 0) "<${formatTimestamp(line.endTime)}>" else ""
            lrcBuilder.append("[$ts]$endTime${line.text}")
            if (line.translation.isNotBlank()) {
                lrcBuilder.append(" / ${line.translation}")
            }
            lrcBuilder.append("\n")
        }
        return lrcBuilder.toString().trim()
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = (ms % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, millis)
    }

    private fun normalizeServiceArtworkPath(rawPath: String): String {
        if (!rawPath.startsWith("audio://", ignoreCase = true)) return rawPath
        val body = rawPath.removePrefix("audio://")
        // RawSMusic UI keys are audio://<path>|<fileSize>|<dateModified>.  The service does
        // not need the version suffix; it needs the actual audio file so it can extract embedded art
        // for MediaSession / notification just like the in-app artwork provider.
        val withoutModified = body.substringBeforeLast("|", body)
        return withoutModified.substringBeforeLast("|", withoutModified)
    }

    /**
     * 异步加载封面Bitmap
     * 支持：content:// URI（含 albumart 高清提取）、文件路径
     */
    private fun loadCoverBitmap(albumArtPath: String) {
        loadingArtPath = albumArtPath
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawPath = try { URLDecoder.decode(albumArtPath, "UTF-8") } catch (_: Exception) { albumArtPath }
                val path = normalizeServiceArtworkPath(rawPath)
                var bitmap: Bitmap? = null

                if (path.startsWith("file://")) {
                    val filePath = path.removePrefix("file://")
                    val file = File(filePath)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(filePath, 512, 512)
                    }
                } else if (path.startsWith("content://") && path.contains("albumart")) {
                    // albumart URI — 优先从音频文件内嵌封面提取高清原图
                    bitmap = extractEmbeddedArtwork(path)
                    // 回退到 content URI 缩略图
                    if (bitmap == null) {
                        bitmap = loadFromContentUri(path, 512, 512)
                    }
                } else if (path.startsWith("content://")) {
                    // 其他 content URI
                    bitmap = loadFromContentUri(path, 512, 512)
                } else {
                    // 文件路径 — 尝试直接解码
                    val file = File(path)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(path, 512, 512)
                    }
                    // 文件路径解码失败时，尝试作为内嵌封面从音频文件提取
                    if (bitmap == null && path.isNotBlank()) {
                        bitmap = extractEmbeddedFromAudioFile(path)
                    }
                }

                if (bitmap == null) {
                    val audioFallback = currentSong?.path.orEmpty()
                    if (audioFallback.isNotBlank() && audioFallback != path && File(audioFallback).exists()) {
                        bitmap = extractEmbeddedFromAudioFile(audioFallback)
                    }
                }

                // 防竞态：如果歌曲已切换，丢弃旧封面的加载结果
                if (loadingArtPath != albumArtPath) return@launch

                if (bitmap != null) {
                    coverBitmap = bitmap
                    val song = currentSong ?: return@launch
                    val lrcText = _currentLyrics.value?.let { lyrics ->
                        if (!lyrics.isEmpty) buildLrcText(lyrics) else null
                    }
                    val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist
                    val metadata = MediaMetadataCompat.Builder().apply {
                        putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        if (!lrcText.isNullOrBlank()) {
                            putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
                        }
                    }.build()
                    mediaSessionCompat?.setMetadata(metadata)
                    // 强制使用startForeground更新通知（确保封面图显示）
                    launch(Dispatchers.Main) {
                        try {
                            startForegroundCompat(NOTIFICATION_ID, buildNotification())
                        } catch (_: Exception) {
                            try { updateNotification() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 从音频文件内嵌封面提取高清原图
     */
    private fun extractEmbeddedArtwork(albumArtUri: String): Bitmap? {
        val uri = Uri.parse(albumArtUri)
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null

        // 查询该专辑的第一首音频文件路径
        val audioPath = queryFirstAudioPathForAlbum(albumId) ?: return null

        extractCoverWithTagLib(audioPath, 512, 512)?.let { return it }

        val ext = audioPath.substringAfterLast(".", "").uppercase()
        // WAV/DSF/DFF/AIFF 等格式：MediaMetadataRetriever 无法提取封面，使用 FFmpegKit
        if (ext in setOf("WAV", "DSF", "DFF", "AIFF", "AIF")) {
            return extractCoverWithFfmpeg(audioPath)
        }

        // 其他格式：native TagLib 失败后才 fallback 到 MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 512, 512)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            // MediaMetadataRetriever 失败时回退到 FFmpegKit
            return extractCoverWithFfmpeg(audioPath)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Notification/MediaSession artwork path: try native TagLib first so playback
     * metadata does not bypass the shared artwork policy by pulling embeddedPicture into Java heap.
     */
    private fun extractCoverWithTagLib(audioPath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        decodeSampledRegion(audioPath, reqWidth, reqHeight)?.let { return it }

        if (!TagLibBridge.isLoaded()) return null
        return try {
            val audioFile = File(audioPath)
            if (!audioFile.exists() || !audioFile.canRead()) return null
            val dir = File(cacheDir, "albumart_sources")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "service_${audioPath.hashCode()}_${audioFile.length()}_${audioFile.lastModified()}.art")
            if (!out.exists() || out.length() <= 1024) {
                val tmp = File(out.parentFile, "${out.name}.tmp")
                if (tmp.exists()) tmp.delete()
                val ok = TagLibBridge.extractEmbeddedArtworkToFile(audioPath, tmp.absolutePath)
                if (!ok || !tmp.exists() || tmp.length() <= 1024) {
                    tmp.delete()
                    return null
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.delete()
                    return null
                }
            }
            decodeSampledFile(out.absolutePath, reqWidth, reqHeight)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledRegion(audioPath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val region = EmbeddedArtworkRegion.find(audioPath) ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            region.openStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            region.openStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 使用 FFmpegKit 从音频文件提取嵌入封面
     */
    private fun extractCoverWithFfmpeg(audioPath: String): Bitmap? {
        return try {
            val audioFile = File(audioPath)
            val version = "${audioPath.hashCode()}_${audioFile.length()}_${audioFile.lastModified()}"
            val coverFile = java.io.File(cacheDir, "albumart/cover_$version.jpg")
            val coverDir = coverFile.parentFile
            if (coverDir != null && !coverDir.exists()) coverDir.mkdirs()

            if (!coverFile.exists() || coverFile.length() <= 1024) {
                val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(audioPath, coverFile.absolutePath)
                if (ret != 0 || !coverFile.exists() || coverFile.length() <= 1024) {
                    if (coverFile.exists()) coverFile.delete()
                    return null
                }
            }
            decodeSampledFile(coverFile.absolutePath, 512, 512)
        } catch (_: Exception) { null }
    }

    /**
     * 查询指定专辑的第一首音频文件路径
     */
    private fun queryFirstAudioPathForAlbum(albumId: Long): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        val selectionArgs = arrayOf(albumId.toString())
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 从 content URI 加载封面（降采样到指定尺寸）
     */
    private fun loadFromContentUri(uriString: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val uri = Uri.parse(uriString)
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val inputStream2 = contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
        inputStream2.close()
        return bitmap
    }

    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (width > reqWidth || height > reqHeight) {
            val halfW = width / 2
            val halfH = height / 2
            while (halfW / sampleSize >= reqWidth && halfH / sampleSize >= reqHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /** 从文件路径降采样解码 */
    private fun decodeSampledFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (_: Exception) { null }
    }

    /** 从音频文件内嵌封面提取（albumArtPath可能是音频文件路径本身） */
    private fun extractEmbeddedFromAudioFile(audioPath: String): Bitmap? {
        val extensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "wma", "ape", "opus", "dsf", "dff", "aiff")
        val ext = audioPath.substringAfterLast(".", "").lowercase()
        if (ext !in extensions) return null

        extractCoverWithTagLib(audioPath, 512, 512)?.let { return it }

        // WAV/DSF/DFF/AIFF：native TagLib 失败后直接用 FFmpegKit
        if (ext in setOf("wav", "dsf", "dff", "aiff", "aif")) {
            return extractCoverWithFfmpeg(audioPath)
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 512, 512)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            return extractCoverWithFfmpeg(audioPath)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private var isForegroundStarted = false

    private fun updateNotification() {
        val notification = buildNotification()
        if (!isForegroundStarted) {
            startForegroundCompat(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            // 后续更新使用 notify()，避免 startForeground() 可能的封面图更新问题
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // USB exclusive playback needs both MEDIA_PLAYBACK and CONNECTED_DEVICE.
            // Using only MEDIA_PLAYBACK can still let Android throttle the libusb
            // event loop during repeated app launches/background transitions.
            val type = usbForegroundServiceType()
            startForegroundWithTypeFallback(id, notification, type)
            Log.i("PlayerService", "Foreground service started/updated type=$type usb=${playerController.isUsbExclusiveActive()}")
        } else {
            startForeground(id, notification)
        }
    }

    private fun usbForegroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            playerController.isUsbExclusiveActive()
        ) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
    }

    private fun startForegroundWithTypeFallback(id: Int, notification: Notification, type: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(id, notification)
            return
        }
        try {
            startForeground(id, notification, type)
        } catch (t: Throwable) {
            // Some manifests/build variants may not yet declare CONNECTED_DEVICE.
            // Keep playback protected by falling back to MEDIA_PLAYBACK instead of
            // losing the foreground service entirely.
            Log.w("PlayerService", "startForeground type=$type failed, fallback to MEDIA_PLAYBACK: ${t.message}")
            startForeground(
                id,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val song = currentSong
        val notificationSmallIcon = R.drawable.ic_music_2_fill
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val tickerPayload = PlaybackTickerState.current()
        val tickerText = tickerPayload?.text ?: ""
        val tickerTranslation = tickerPayload?.translation ?: ""
        val samsungTranslation = AppPreferences.Lyrics.samsungFloatingLyricTranslation

        val notificationTitle = if (tickerText.isNotBlank()) {
            tickerText
        } else {
            song?.title ?: "RawSMusic"
        }

        val notificationText = when {
            tickerText.isNotBlank() && tickerTranslation.isNotBlank() -> tickerTranslation
            tickerText.isNotBlank() -> song?.artist ?: ""
            samsungTranslation && tickerTranslation.isNotBlank() -> "${song?.artist ?: ""} · $tickerTranslation"
            else -> song?.artist ?: "准备播放"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(notificationText)
            .setSubText(song?.album)
            .setSmallIcon(notificationSmallIcon)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (tickerText.isNotBlank()) {
            builder.setTicker(tickerText)
        }

        val ctrl = playerController
        val isShuffle = ctrl.isShuffle.value
        val repeatMode = ctrl.repeatMode.value

        val favIntent = PendingIntent.getService(
            this, 10,
            Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_SHUFFLE),
            flags
        )
        val favIcon = when {
            isShuffle -> R.drawable.ic_notification_shuffle
            repeatMode == RepeatMode.ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val favLabel = when {
            isShuffle -> "随机播放"
            repeatMode == RepeatMode.ONE -> "单曲循环"
            repeatMode == RepeatMode.ALL -> "列表循环"
            else -> "顺序播放"
        }
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, favIcon),
                favLabel, favIntent
            ).build()
        )

        val prevIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).setAction(ACTION_PREVIOUS),
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_previous),
                "上一曲", prevIntent
            ).build()
        )

        val isPlaying = currentPlayState == PlayState.PLAYING
        if (isPlaying) {
            val pauseIntent = PendingIntent.getService(
                this, 1,
                Intent(this, PlayerService::class.java).setAction(ACTION_PAUSE),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pause),
                    "暂停", pauseIntent
                ).build()
            )
        } else {
            val playIntent = PendingIntent.getService(
                this, 2,
                Intent(this, PlayerService::class.java).setAction(ACTION_PLAY),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_play),
                    "播放", playIntent
                ).build()
            )
        }

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlayerService::class.java).setAction(ACTION_NEXT),
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_skip_next),
                "下一曲", nextIntent
            ).build()
        )

        val modeIntent = PendingIntent.getService(
            this, 11,
            Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_SHUFFLE),
            flags
        )
        val modeIcon = when {
            isShuffle -> R.drawable.ic_notification_shuffle
            repeatMode == RepeatMode.ONE -> R.drawable.ic_repeat_one
            else -> R.drawable.ic_repeat
        }
        val modeLabel = when {
            isShuffle -> "随机播放"
            repeatMode == RepeatMode.ONE -> "单曲循环"
            repeatMode == RepeatMode.ALL -> "列表循环"
            else -> "顺序播放"
        }
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, modeIcon),
                modeLabel, modeIntent
            ).build()
        )

        mediaSessionCompat?.let { session ->
            val compatToken = session.sessionToken
            val frameworkToken = try {
                if (!reflectionCacheInitialized) {
                    reflectionCacheInitialized = true
                    try {
                        cachedGetTokenMethod = compatToken.javaClass.getMethod("getToken")
                    } catch (_: NoSuchMethodException) {
                        try {
                            val field = compatToken.javaClass.getDeclaredField("mToken")
                            field.isAccessible = true
                            cachedMTokenField = field
                        } catch (_: Exception) {}
                    }
                }
                val method = cachedGetTokenMethod
                if (method != null) {
                    method.invoke(compatToken) as? android.media.session.MediaSession.Token
                } else {
                    cachedMTokenField?.get(compatToken) as? android.media.session.MediaSession.Token
                }
            } catch (e: Exception) {
                Log.w("PlayerService", "Failed to get framework token", e)
                null
            }

            val mediaStyle = Notification.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3)
            if (frameworkToken != null) {
                mediaStyle.setMediaSession(frameworkToken)
            }
            builder.setStyle(mediaStyle)
        }

        coverBitmap?.let { builder.setLargeIcon(it) }

        val notification = builder.build()

        Log.d("StatusLyric", "buildNotification: tickerText=$tickerText, " +
            "translation=$tickerTranslation, " +
            "hide=${AppPreferences.Lyrics.tickerHideNotification}")

        if (tickerText.isNotBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                notification.extras.putBoolean("ticker_icon_switch", false)
                notification.extras.putInt("ticker_icon", notificationSmallIcon)
                notification.extras.putString("ticker_text", tickerText)
                notification.extras.putString("lyric", tickerText)
                notification.extras.putString("text", tickerText)
                notification.extras.putString("content", tickerText)
                notification.extras.putString("ticker_package", packageName)
                notification.extras.putString("package", packageName)
                notification.extras.putString("ticker_app_name", "RawSMusic")
                notification.extras.putString("app_name", "RawSMusic")
                if (tickerTranslation.isNotBlank()) {
                    notification.extras.putString("ticker_translation", tickerTranslation)
                    notification.extras.putString("translation", tickerTranslation)
                }
            }
            try {
                val flagAlwaysShowTicker = Notification::class.java.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER")
                flagAlwaysShowTicker.isAccessible = true
                val flagOnlyUpdateTicker = Notification::class.java.getDeclaredField("FLAG_ONLY_UPDATE_TICKER")
                flagOnlyUpdateTicker.isAccessible = true
                notification.flags = notification.flags or flagAlwaysShowTicker.getInt(null) or flagOnlyUpdateTicker.getInt(null)
            } catch (_: Exception) {
                notification.flags = notification.flags or 0x1000000 or 0x2000000
            }
        }

        return notification
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUsbBackgroundGuardian("service_destroy")
        releaseRuntimeController("service_destroy")
        isRunning = false
        _instance = null
        positionUpdateJob?.cancel()
        abandonServiceAudioFocus("service_destroy")
        releaseWakeLock()
        releaseUsbWakeLock()
        releaseWifiLock("service_destroy")
        unregisterScreenUnlockReceiver()
        TickerBridge.destroy(this)
        BluetoothLyricBridge.destroy()
        LyriconProviderManager.stopPositionSync()
        LyriconProviderManager.destroy()
        PlayerServiceProxy.setUpdateCallback(null)
        PlaybackTickerState.setRefreshCallback(null)
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        mediaSessionCompat = null
    }

    /**
     * 注册屏幕解锁广播接收器
     * USER_PRESENT 监听策略：屏幕解锁时检查USB状态，确保WakeLock持有
     */
    private fun registerScreenUnlockReceiver() {
        if (!screenReceiverRegistered) {
            try {
                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenUnlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(screenUnlockReceiver, filter)
                }
                screenReceiverRegistered = true
                Log.d("PlayerService", "Screen unlock receiver registered")
            } catch (e: Exception) {
                Log.w("PlayerService", "Failed to register screen unlock receiver", e)
            }
        }
    }

    /**
     * 注销屏幕解锁广播接收器
     */
    private fun unregisterScreenUnlockReceiver() {
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenUnlockReceiver)
                screenReceiverRegistered = false
                Log.d("PlayerService", "Screen unlock receiver unregistered")
            } catch (_: Exception) {}
        }
    }

    /**
     * 更新前台服务类型
     * USB独占模式激活时使用CONNECTED_DEVICE|MEDIA_PLAYBACK，防止系统冻结USB通信线程
     * 非USB独占时仅使用MEDIA_PLAYBACK
     */
    private fun updateForegroundServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val type = usbForegroundServiceType()
                val notification = buildNotification()
                startForegroundWithTypeFallback(NOTIFICATION_ID, notification, type)
                Log.d("PlayerService", "Foreground service type updated: $type (USB exclusive: ${playerController.isUsbExclusiveActive()})")
            } catch (e: Exception) {
                Log.w("PlayerService", "Failed to update foreground service type", e)
            }
        }
    }

    /** 后台冷启动保护：确保前台服务已启动 */
    private fun ensureForegroundForUsb() {
        try {
            val notification = buildNotification()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val type = usbForegroundServiceType()
                startForegroundWithTypeFallback(NOTIFICATION_ID, notification, type)
                android.util.Log.i("PlayerService", "USB foreground service started for cold start protection type=$type")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                android.util.Log.i("PlayerService", "USB foreground service started for cold start protection")
            }
            isForegroundStarted = true
        } catch (t: Throwable) {
            android.util.Log.w("PlayerService", "Failed to start foreground for USB: ${t.message}")
        }
    }

    private fun shouldRunUsbBackgroundGuardian(): Boolean {
        val controller = playerController
        if (!controller.shouldSustainUsbBackgroundPlayback()) {
            return false
        }
        val controllerState = controller.playState.value
        val activelyPlaying =
            currentPlayState == PlayState.PLAYING ||
                currentPlayState == PlayState.PREPARING ||
                controllerState == PlayState.PLAYING ||
                controllerState == PlayState.PREPARING
        if (!activelyPlaying) {
            return false
        }
        return controller.currentSong.value != null || currentSong != null
    }

    private fun stopUsbBackgroundGuardian(reason: String) {
        val job = usbBackgroundGuardianJob ?: return
        if (job.isActive) {
            Log.i("PlayerService", "USB background guardian stop: reason=$reason")
        }
        job.cancel()
        usbBackgroundGuardianJob = null
    }

    private fun syncUsbBackgroundGuardian(reason: String) {
        if (!shouldRunUsbBackgroundGuardian()) {
            stopUsbBackgroundGuardian("sync_stop:$reason")
            return
        }
        if (usbBackgroundGuardianJob?.isActive == true) {
            return
        }
        usbBackgroundGuardianJob = lifecycleScope.launch(Dispatchers.Default) {
            Log.i("PlayerService", "USB background guardian start: reason=$reason")
            while (isActive) {
                if (!shouldRunUsbBackgroundGuardian()) {
                    break
                }
                playerController.reinforceUsbBackgroundPlayback("service_guardian:$reason")
                withContext(Dispatchers.Main.immediate) {
                    requestServiceAudioFocus("usb_background_guardian")
                    acquireWakeLockIfNeeded()
                    acquireWifiLockIfNeeded("usb_background_guardian")
                    ensureUsbForegroundThrottled("usb_background_guardian")
                    forceMediaSessionPlaying("usb_background_guardian")
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUsbBackgroundGuardianLogElapsed >= 10_000L) {
                    lastUsbBackgroundGuardianLogElapsed = now
                    Log.i(
                        "PlayerService",
                        "USB background guardian pulse: reason=$reason " +
                            "playState=$currentPlayState song=${currentSong?.title ?: playerController.currentSong.value?.title}"
                    )
                }
                delay(3_000L)
            }
            Log.i("PlayerService", "USB background guardian exit: reason=$reason")
        }
    }

    private fun ensureUsbForegroundThrottled(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUsbForegroundEnsureElapsed < 5_000L) return
        lastUsbForegroundEnsureElapsed = now
        ensureForegroundForUsb()
        acquireUsbWakeLock()
        updateForegroundServiceType()
        Log.i("PlayerService", "USB foreground keepalive ensured: reason=$reason force=$force")
    }

    /** USB 独占播放时获取专用 WakeLock，防止 OTG 被系统关闭 */
    fun acquireUsbWakeLock() {
        try {
            if (usbWakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            usbWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RawSMusic::UsbExclusivePlayback"
            ).apply {
                setReferenceCounted(false)
                // USB 播放期间不设超时，直到显式释放
                acquire()
            }
            Log.d("PlayerService", "USB WakeLock acquired")
        } catch (_: Exception) {}
    }

    /** 释放 USB 专用 WakeLock */
    fun releaseUsbWakeLock() {
        try {
            if (usbWakeLock?.isHeld == true) {
                usbWakeLock?.release()
            }
            usbWakeLock = null
            Log.d("PlayerService", "USB WakeLock released")
        } catch (_: Exception) {}
    }

    /** 获取 WakeLock — 防止CPU休眠导致后台播放被系统杀死 */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RawSMusic::PlayerWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("PlayerService", "Player WakeLock acquired")
        } catch (_: Exception) {}
    }

    private fun acquireWifiLockIfNeeded(reason: String) {
        try {
            if (wifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "RawSMusic::UsbExclusivePlayback").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("PlayerService", "WifiLock acquired: reason=$reason")
        } catch (t: Throwable) {
            Log.w("PlayerService", "WifiLock acquire failed: reason=$reason ${t.message}")
        }
    }

    private fun releaseWifiLock(reason: String) {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
            Log.d("PlayerService", "WifiLock released: reason=$reason")
        } catch (t: Throwable) {
            Log.w("PlayerService", "WifiLock release failed: reason=$reason ${t.message}")
        }
    }

    /** 公共方法 — 供 PlayerController 等外部调用，确保 WakeLock 持有 */
    fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
        }
    }

    // ========== USB 硬件音量 MediaSession VolumeProvider ==========

    private fun setupUsbVolumeProvider() {
        val ctrl = playerController
        usbVolumeProvider = UsbHardwareVolumeProvider(
            getCurrentStep = { ctrl.getUsbVolumeStepForMediaSession() },
            onSetStep = { step, reason -> ctrl.setUsbVolumeStepFromMediaSession(step, reason) },
            onAdjustStep = { direction, reason -> ctrl.adjustUsbVolumeStepFromMediaSession(direction, reason) }
        )
        android.util.Log.i("PlayerService", "USB VolumeProvider initialized")
    }

    /** USB 独占启用时，硬件音量和软件音量都使用 MediaSession remote volume 接管音量键。 */
    fun activateUsbRemoteVolume(reason: String) {
        val session = mediaSessionCompat ?: return
        val provider = usbVolumeProvider ?: return
        val ctrl = playerController

        if (!ctrl.shouldUseUsbRemoteVolume()) {
            android.util.Log.i("PlayerService", "activateUsbRemoteVolume: using local STREAM_MUSIC route, reason=$reason")
            session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            session.isActive = true
            return
        }

        // 从当前 UI 音量同步真实 DAC 步进，避免默认 0 导致 -60dB 静音
        val currentStep = ctrl.seedUsbHardwareVolumeStepFromUiVolume()

        android.util.Log.w("PlayerService", "activateUsbRemoteVolume: reason=$reason step=$currentStep")
        session.setPlaybackToRemote(provider)
        session.isActive = true
        provider.syncFromController()
    }

    /** 关闭 USB 独占时，切回本地音量 */
    fun deactivateUsbRemoteVolume(reason: String) {
        val session = mediaSessionCompat ?: return

        android.util.Log.i("PlayerService", "deactivateUsbRemoteVolume: reason=$reason")
        // STREAM_MUSIC = 3
        session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
    }

    private fun syncUsbMediaIdentity(
        song: AudioFile?,
        playing: Boolean,
        position: Long,
        reason: String
    ) {
        song?.let { newSong ->
            val changed = currentSong?.path != newSong.path ||
                currentSong?.title != newSong.title ||
                currentSong?.albumArtPath != newSong.albumArtPath ||
                currentSong?.duration != newSong.duration
            currentSong = newSong
            if (changed) {
                updateMediaSessionMetadata(
                    newSong.title,
                    newSong.artist,
                    newSong.album,
                    newSong.albumArtPath,
                    newSong.duration
                )
            }
        }

        val state = if (playing) PlayState.PLAYING else PlayState.PAUSED
        currentPlayState = state
        lastKnownPosition = position.coerceAtLeast(0L)
        lastPositionTime = SystemClock.elapsedRealtime()
        mediaSessionCompat?.isActive = true
        if (playing) {
            val isProgressPulse = reason == "progress_update"
            requestServiceAudioFocus("pulse:$reason")
            acquireWakeLockIfNeeded()
            acquireWifiLockIfNeeded("pulse:$reason")
            ensureUsbForegroundThrottled(reason, force = !isProgressPulse)
            startPositionUpdates()
            syncUsbBackgroundGuardian("media_identity:$reason")
        } else {
            stopUsbBackgroundGuardian("media_identity_pause:$reason")
            releaseWifiLock("pulse_pause:$reason")
            positionUpdateJob?.cancel()
        }
        updateMediaSessionPlaybackState(state, lastKnownPosition)
        if (reason != "progress_update") {
            updateNotification()
            Log.i("PlayerService", "USB media identity pulse: playing=$playing reason=$reason pos=$lastKnownPosition")
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUsbProgressPulseLogElapsed > 10_000L) {
                lastUsbProgressPulseLogElapsed = now
                Log.i("PlayerService", "USB media identity progress pulse: pos=$lastKnownPosition")
            }
        }
    }

    private fun clearUsbMediaIdentity(reason: String, releaseFocus: Boolean) {
        stopUsbBackgroundGuardian("clear_usb_media_identity:$reason")
        releaseWifiLock("clear_usb_media_identity:$reason")
        if (releaseFocus) {
            abandonServiceAudioFocus(reason)
        }
        updateForegroundServiceType()
        Log.i("PlayerService", "USB media identity cleared: reason=$reason releaseFocus=$releaseFocus")
    }

    /** 同步 MediaSession 播放状态（供 PlayerController 调用） */
    fun updateMediaSessionPlaybackStateForUsb(playing: Boolean, reason: String) {
        val session = mediaSessionCompat ?: return
        val provider = usbVolumeProvider ?: return

        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, lastKnownPosition, if (playing) 1.0f else 0.0f, SystemClock.elapsedRealtime())
            .setActions(actions)
            .build()

        session.isActive = true
        session.setPlaybackState(playbackState)
        provider.syncFromController()

        android.util.Log.i("PlayerService", "MediaSession playbackState: playing=$playing reason=$reason volume=${provider.currentVolume}")
    }

    /** 释放 WakeLock */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    /** 播放中时定期更新通知栏进度条位置 — 使用elapsedRealtime估算 */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        if (currentPlayState == PlayState.PLAYING) {
            positionUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    if (currentPlayState == PlayState.PLAYING) {
                        val elapsed = if (lastPositionTime > 0) SystemClock.elapsedRealtime() - lastPositionTime else 0L
                        val estimatedPosition = (lastKnownPosition + elapsed).coerceAtLeast(0L)
                        val duration = currentSong?.duration ?: 0L
                        if (duration > 0 && estimatedPosition <= duration) {
                            updateMediaSessionPlaybackState(PlayState.PLAYING, estimatedPosition)
                        }
                    }
                }
            }
        }
    }
}
