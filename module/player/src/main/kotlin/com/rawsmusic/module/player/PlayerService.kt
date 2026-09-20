package com.rawsmusic.module.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
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
import com.rawsmusic.core.common.model.isFileBackedArtworkSource
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.artwork.ArtworkResolutionPolicy
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.common.utils.PlayerSwitchTrace
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.AudioFocusPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.ColorOsDirectLyricBridge
import com.rawsmusic.module.player.lyrics.ColorOsLyricMetadata
import com.rawsmusic.module.player.lyrics.ColorOsNonModuleLyricBridge
import com.rawsmusic.module.player.lyrics.LiveLyricNotificationBridge
import com.rawsmusic.module.player.lyrics.buildLiveLyricNotificationText
import com.rawsmusic.module.player.lyrics.buildLiveLyricSecondaryText
import com.rawsmusic.module.player.lyrics.PlaybackTickerState
import com.rawsmusic.module.player.lyrics.PlayerServiceProxy
import com.rawsmusic.module.player.lyrics.TickerBridge
import com.rawsmusic.module.player.usb.UsbHardwareVolumeModel
import com.rawsmusic.module.player.usb.UsbHardwareVolumeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import org.json.JSONArray

class PlayerService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "rawsmusic_player_channel_silent_v2"
        private const val LEGACY_CHANNEL_ID = "rawsmusic_player_channel"
        const val NOTIFICATION_ID = 1001
        // Keep metadata and notification publication on a dedicated external-media thread.
        // Metadata artwork refresh is coalesced by 100 ms on Android 8+
        // (200 ms on older releases), while notification refresh uses its own 75/150 ms cadence.
        private val MEDIA_METADATA_COALESCE_MS: Long
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 100L else 200L
        private val NOTIFICATION_PUBLICATION_DELAY_MS: Long
            get() = if (Build.VERSION.SDK_INT >= 34) 150L else 75L
        private const val LIVE_LYRIC_PROGRESS_INTERVAL_MS = 220L
        private const val MEDIA_SESSION_PROGRESS_INTERVAL_MS = 1000L
        const val ACTION_PLAY = "com.rawsmusic.action.PLAY"
        const val ACTION_PAUSE = "com.rawsmusic.action.PAUSE"
        const val ACTION_TOGGLE_PLAYBACK = "com.rawsmusic.action.TOGGLE_PLAYBACK"
        const val ACTION_NEXT = "com.rawsmusic.action.NEXT"
        const val ACTION_PREVIOUS = "com.rawsmusic.action.PREVIOUS"
        const val ACTION_STOP = "com.rawsmusic.action.STOP"
        const val ACTION_UPDATE = "com.rawsmusic.action.UPDATE"
        const val ACTION_UPDATE_LYRICS = "com.rawsmusic.action.UPDATE_LYRICS"
        const val ACTION_ENSURE_WAKELOCK = "com.rawsmusic.action.ENSURE_WAKELOCK"
        const val ACTION_TOGGLE_FAVORITE = "com.rawsmusic.action.TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_SHUFFLE = "com.rawsmusic.action.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_DESKTOP_LYRIC = "com.rawsmusic.action.TOGGLE_DESKTOP_LYRIC"
        const val ACTION_REFRESH_NOTIFICATION = "com.rawsmusic.action.REFRESH_NOTIFICATION"
        const val ACTION_REFRESH_LYRIC_METADATA = "com.rawsmusic.action.REFRESH_LYRIC_METADATA"
        const val ACTION_SUPER_ISLAND_LYRIC = "com.rawsmusic.action.SUPER_ISLAND_LYRIC"
        const val ACTION_SUPER_ISLAND_LYRIC_CLEAR = "com.rawsmusic.action.SUPER_ISLAND_LYRIC_CLEAR"
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

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var _instance: PlayerService? = null

        private var cachedGetTokenMethod: java.lang.reflect.Method? = null
        private var cachedMTokenField: java.lang.reflect.Field? = null
        private var reflectionCacheInitialized = false
            private set

        /** 当前歌词数据 — 由MainActivity加载后设置 */
        private val _currentLyrics = MutableStateFlow<LyricData?>(null)
        val currentLyrics: StateFlow<LyricData?> = _currentLyrics.asStateFlow()

        /** 更新当前歌词 — 供外部（MainActivity）调用 */
        fun updateLyrics(lyricData: LyricData?, song: AudioFile? = null) {
            val service = _instance
            val activeSong = service?.currentSong
            // A lyric read can finish after a notification-bar skip. Do not let the old read
            // overwrite the service-owned song, but always accept the null clear used to open a
            // new song generation (the service metadata may still be one event behind the UI).
            if (lyricData != null && song != null && activeSong != null &&
                service.colorOsLyricIdentity(activeSong) != service.colorOsLyricIdentity(song)
            ) {
                Log.d(
                    "PlayerService",
                    "drop stale lyrics update: active=${activeSong.path} request=${song.path}"
                )
                return
            }
            _currentLyrics.value = lyricData
            service?.currentLyricsSongIdentity = song?.let(service::colorOsLyricIdentity)
            currentRuntimeController()?.onLyricsUpdated(song, lyricData)
        }

        fun pushLyricsToMediaSession() {
            _instance?.scheduleLyricsMediaPublication()
        }

        /**
         * In-process UI -> Service media identity handoff.
         *
         * reference player keeps track state on its player-service looper and moves only expensive external
         * media publication to a separate worker.  Avoid bouncing an already-running Raw service
         * through Context.startService()/onStartCommand for every track/state pulse: post the small
         * state mutation onto the Service/Main looper and let metadata/artwork/notification work
         * fan out to the dedicated media publisher from there.
         */
        fun syncSongUpdateFromUi(
            song: AudioFile,
            serviceArtworkPath: String,
            playState: PlayState,
            position: Long,
        ): Boolean {
            val service = _instance ?: return false
            service.runOnPlayerServiceThread {
                service.applySongUpdate(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    serviceArtworkPath = serviceArtworkPath.ifBlank { song.path },
                    path = song.path,
                    fileSize = song.fileSize,
                    dateModified = song.dateModified,
                    cueTrackIndex = song.cueTrackIndex,
                    duration = song.duration,
                    state = playState,
                    position = position,
                    reason = "in_process_ui",
                )
            }
            return true
        }

        fun syncPositionFromUi(position: Long): Boolean {
            val service = _instance ?: return false
            service.runOnPlayerServiceThread {
                service.applySyncedPosition(position)
            }
            return true
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
            service.runOnPlayerServiceThread {
                service.syncUsbMediaIdentity(song, playing, position, reason)
            }
            return true
        }

        fun clearUsbMediaIdentityFromController(
            reason: String,
            releaseFocus: Boolean
        ): Boolean {
            val service = _instance ?: return false
            service.runOnPlayerServiceThread {
                service.clearUsbMediaIdentity(reason, releaseFocus)
            }
            return true
        }

        fun shouldBootstrapRuntime(): Boolean {
            if (_instance != null || PlayerRuntimeRegistry.currentControllerOrNull() != null) {
                return true
            }
            val lastState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
                PlayState.IDLE
            }
            return AppPreferences.Player.usbExclusiveRequested ||
                lastState == PlayState.PLAYING ||
                lastState == PlayState.PREPARING
        }

        fun shouldRetainRuntimeOnUiDestroy(
            controller: PlayerController? = PlayerRuntimeRegistry.currentControllerOrNull()
        ): Boolean {
            val service = _instance
            return PlaybackRuntimeRetentionPolicy.shouldRetain(
                controllerState = controller?.playState?.value,
                serviceState = service?.currentPlayState,
                usbActive = controller?.isUsbExclusiveActive() == true,
                persistedUsbActive = service != null && AppPreferences.Player.usbExclusiveRequested,
                hasRequestedSong = controller?.hasRequestedSongForUi() == true,
            )
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
    /** reference player-equivalent owner for core MediaSession state and final metadata commits. */
    private var playerServiceThread: HandlerThread? = null
    private var playerServiceHandler: Handler? = null
    private val liveLyricNotificationBridge: LiveLyricNotificationBridge by lazy {
        LiveLyricNotificationBridge(this)
    }
    @Volatile
    private var currentSong: AudioFile? = null
    @Volatile
    private var currentPlayState: PlayState = PlayState.IDLE
    private var superIslandPublished = false
    private var superIslandSongPath: String? = null
    private var colorOsLyricRetryJob: Job? = null
    private var currentLyricsSongIdentity: String? = null
    private var colorOsDirectTrackIdentity: String? = null
    private var colorOsDirectTrackGeneration: Long = 0L
    private var colorOsDirectPayloadKey: String? = null
    /**
     * System media publication is intentionally isolated from Android's service/Main looper.
     * reference player 1026 uses a dedicated `external api thread` for MediaMetadata + notification work,
     * then serializes MediaSession state through its player-service handler. Raw previously did the
     * expensive lyric metadata/Bitmap parcel/PendingIntent rebuild inline from onStartCommand/Main,
     * which can otherwise stall Choreographer during track motion.
     */
    private var mediaPublicationThread: HandlerThread? = null
    private var mediaPublicationHandler: Handler? = null
    private val mediaPublicationLock = Any()
    private var pendingLyricsPublicationRetry = false
    private val lyricsMediaPublicationRunnable = Runnable {
        val retry = synchronized(mediaPublicationLock) {
            val requested = pendingLyricsPublicationRetry
            pendingLyricsPublicationRetry = false
            requested
        }
        updateLyricsInMetadataNow(retry)
    }
    private val notificationPublicationRunnable = Runnable {
        updateNotificationNow()
    }
    private val playbackWidgetRefreshRunnable = Runnable {
        notifyPlaybackWidgetIfChanged(force = true)
    }
    @Volatile
    private var coverBitmap: Bitmap? = null
    /** Identity that actually owns [coverBitmap]. A retained old bitmap must never follow new text metadata. */
    @Volatile
    private var coverBitmapSongIdentity: String? = null
    /** 记录当前正在加载封面的 albumArtPath，防止竞态 */
    @Volatile
    private var loadingArtPath: String? = null
    /** Monotonic artwork request id; path equality alone cannot distinguish rapid song changes. */
    @Volatile
    private var artworkRequestGeneration: Long = 0L
    /** 上次收到的播放位置，用于通知栏进度条更新 */
    @Volatile
    private var lastKnownPosition: Long = 0L
    /** 上次收到精确位置时的系统时间，用于估算当前播放位置 */
    @Volatile
    private var lastPositionTime: Long = 0L
    /** 播放位置更新协程 */
    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    /** WakeLock — 防止CPU休眠导致后台播放被杀 */
    private var wakeLock: PowerManager.WakeLock? = null
    /** USB 独占播放专用 WakeLock — 在 usbExclusiveMode 期间持有，防止 OTG 被系统关闭 */
    private var usbWakeLock: PowerManager.WakeLock? = null
    /** Cached result only. PlayerController is the single owner of Android audio focus. */
    private var serviceAudioFocusGranted = false
    private var lastPlaybackWidgetSignature = ""
    private var lastPlaybackWidgetProgressElapsed = 0L
    /** USB 硬件音量 VolumeProvider — 接收系统音量键事件 */
    private var usbVolumeProvider: UsbHardwareVolumeProvider? = null
    private var lastUsbRouteLogRemote: Boolean? = null
    private var lastUsbRouteLogElapsed = 0L
    /** WifiLock. Even for local playback, some OEM schedulers classify
     *  the app more leniently when both media FGS and a high-perf lock are active. */
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastUsbForegroundEnsureElapsed: Long = 0L
    private var lastUsbProgressPulseLogElapsed: Long = 0L
    private var usbBackgroundGuardianJob: Job? = null
    private var lastUsbBackgroundGuardianLogElapsed: Long = 0L
    /**
     * Service-owned playback intent. Controller/backend state briefly becomes PREPARING or PAUSED
     * during USB recovery and track switches; those transient values must not relinquish the
     * background session. Only an explicit user pause/stop or USB teardown clears this latch.
     */
    @Volatile
    private var usbBackgroundKeepAliveLatched: Boolean = false

    private fun runOnPlayerServiceThread(block: () -> Unit) {
        val handler = playerServiceHandler
        if (handler == null || Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post(block)
        }
    }

    /** reference player's ExternalAPI/notification helper is a Handler on the external-api looper. */
    private fun runOnExternalApiThread(block: () -> Unit) {
        val handler = mediaPublicationHandler
        if (handler == null || Looper.myLooper() == handler.looper) {
            block()
        } else {
            handler.post(block)
        }
    }

    /**
     * External-api work may build a metadata payload, but the final MediaSession commit belongs to
     * the player-service looper. This mirrors reference player B.P() -> PlayerService handler message 27.
     * Re-check the generation at the final commit boundary so queued rapid-skip payloads can never
     * publish after a newer song generation has become current.
     */
    private fun publishMediaMetadataOnPlayerServiceThread(
        metadata: MediaMetadataCompat,
        requestGeneration: Long,
        expectedSongIdentity: String,
        expectedArtworkPath: String? = null,
        traceName: String,
    ) {
        runOnPlayerServiceThread {
            if (!isRunning || requestGeneration != artworkRequestGeneration) return@runOnPlayerServiceThread
            if (currentSong?.mediaArtworkIdentity() != expectedSongIdentity) return@runOnPlayerServiceThread
            if (expectedArtworkPath != null && loadingArtPath != expectedArtworkPath) {
                return@runOnPlayerServiceThread
            }
            val startedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
            mediaSessionCompat?.setMetadata(metadata)
            if (startedNs != 0L) {
                PlayerSwitchTrace.duration(traceName, System.nanoTime() - startedNs)
                PlayerSwitchTrace.mark(
                    traceName.lowercase(),
                    "thread=${Thread.currentThread().name} generation=$requestGeneration",
                )
            }
        }
    }

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
    private var foregroundPromotionRejected = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        _instance = this

        playerServiceThread = HandlerThread(
            "player service thread",
            Process.THREAD_PRIORITY_DEFAULT,
        ).also { thread ->
            thread.start()
            playerServiceHandler = Handler(thread.looper)
        }
        mediaPublicationThread = HandlerThread(
            "external api thread",
            4,
        ).also { thread ->
            thread.start()
            mediaPublicationHandler = Handler(thread.looper)
        }

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
                    val event = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event?.action == KeyEvent.ACTION_DOWN && playerController.shouldUseUsbRemoteVolume()) {
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                playerController.stepUsbVolume(UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP)
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                playerController.stepUsbVolume(-UsbHardwareVolumeModel.DEFAULT_LINEAR_STEP)
                                return true
                            }
                            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                                playerController.setUsbVolumeLinear(0f)
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            // A service/USB manager owner is not itself a media item.  Starting the session as
            // active makes SystemUI/OEM media surfaces advertise RawSMusic as playing audio even
            // on an empty cold start.  Real metadata/playback publication activates it below.
            isActive = false
        }

        // 初始化 USB 硬件音量 VolumeProvider
        setupUsbVolumeProvider()

        // Android requires a service started through startForegroundService() to promote itself
        // promptly, but that transient startup promotion is not playback ownership.  Promote once
        // to satisfy the platform contract, then immediately reconcile below.  Keeping an empty
        // service permanently in MEDIA_PLAYBACK makes SystemUI/OEM schedulers report RawSMusic as
        // "playing audio" even though there is no current media item and no renderer output.
        isForegroundStarted = startForegroundCompat(NOTIFICATION_ID, buildNotification())
        if (!isForegroundStarted) {
            Log.w(
                "PlayerService",
                "Foreground promotion rejected during service creation; stopping without restart",
            )
            stopSelf()
            return
        }
        if (!shouldOwnPlaybackForeground()) {
            stopForegroundCompat(removeNotification = true)
        }
        notifyPlaybackWidgetIfChanged(force = true)

        TickerBridge.init(this)
        PlayerServiceProxy.setUpdateCallback {
            rebuildMetadataWithBluetoothLyric()
        }
        PlaybackTickerState.setRefreshCallback {
            scheduleNotificationPublication()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (foregroundPromotionRejected) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action.isNullOrBlank()) {
            val lastWasPlaying = AppPreferences.Player.lastPlayStateOrdinal == PlayState.PLAYING.ordinal
            if (lastWasPlaying) {
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
        val usbProtected = controller.isUsbExclusiveActive() || AppPreferences.Player.usbExclusiveRequested
        return if (activelyPlaying || usbProtected) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val controller = playerController
        if (controller.isUsbExclusiveActive() || AppPreferences.Player.usbExclusiveRequested) {
            val activelyPlaying = currentPlayState == PlayState.PLAYING ||
                controller.playState.value == PlayState.PLAYING ||
                controller.playState.value == PlayState.PREPARING ||
                controller.shouldSustainUsbBackgroundPlayback() ||
                AppPreferences.Player.lastPlayStateOrdinal == PlayState.PLAYING.ordinal
            if (!activelyPlaying) {
                Log.i("PlayerService", "onTaskRemoved: USB exclusive explicitly idle, stopping service without restart")
                clearUsbMediaIdentity("task_removed_idle_usb", releaseFocus = true)
                releaseUsbWakeLock()
                stopForegroundCompat(removeNotification = true)
                stopSelf()
            } else {
                Log.i("PlayerService", "onTaskRemoved: USB exclusive requested/playing, retaining manager owner service")
                ensureUsbForegroundThrottled("task_removed_usb_playing", force = true)
                syncUsbBackgroundGuardian("task_removed_usb_playing")
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
        // A running foreground playback service survives removal from Recents.
        // Scheduling another foreground-service start here is rejected by Android
        // 12+ because the app is already backgrounded. START_STICKY remains the
        // system-owned recovery path if the process itself is later reclaimed.
        Log.i("PlayerService", "onTaskRemoved: playing, keep existing foreground service")
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
        isForegroundStarted = false
    }

    private fun bootstrapRuntimeState(reason: String) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { bootstrapRuntimeState(reason) }
            return
        }
        val controller = playerController
        // Queue state is durable independently from now-playing. Restore it even when the last
        // session was stopped before a song became the current decoder item.
        val restoredSong = controller.restoreLastSong()
        val controllerSong = controller.currentSong.value ?: restoredSong
        if (controllerSong == null &&
            controller.queue.value.songs.isEmpty() &&
            AppPreferences.Player.lastSongPath.isBlank()
        ) {
            Log.i("PlayerService", "bootstrapRuntimeState skipped: no retained song reason=$reason")
            return
        }
        if (controllerSong == null) {
            currentSong = null
            currentPlayState = PlayState.IDLE
            updateNotification()
            Log.i(
                "PlayerService",
                "bootstrapRuntimeState: queue-only restore reason=$reason " +
                    "queue=${controller.queue.value.songs.size}",
            )
            return
        }
        // Reference keeps the selected queue row visible without treating it as resumable audio.
        // Do the same: expose metadata/controls, but never inherit a stale PLAYING preference or
        // acquire audio/USB resources before the user explicitly starts the selected row.
        if (controller.isQueueSelectionOnly()) {
            currentSong = controllerSong
            currentPlayState = PlayState.IDLE
            lastKnownPosition = 0L
            lastPositionTime = SystemClock.elapsedRealtime()
            updateMediaSessionMetadata(
                controllerSong.title,
                controllerSong.artist,
                controllerSong.album,
                controllerSong.albumArtPath,
                controllerSong.duration,
            )
            updateMediaSessionPlaybackState(PlayState.IDLE, 0L)
            updateNotification()
            Log.i(
                "PlayerService",
                "bootstrapRuntimeState: queue selection only reason=$reason " +
                    "song=${controllerSong.title} queue=${controller.queue.value.songs.size}",
            )
            return
        }

        val actualControllerState = controller.playState.value
        val persistedState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
            PlayState.IDLE
        }
        val restoredState = actualControllerState.takeIf { it != PlayState.IDLE }
            ?: persistedState.takeUnless {
                (it == PlayState.PLAYING || it == PlayState.PREPARING) &&
                    !AudioFocusPreferences.resumeOnStart
            }
            ?: PlayState.PAUSED

        currentSong = controllerSong
        currentPlayState = restoredState
        lastKnownPosition = controller.position.value.coerceAtLeast(0L)
        lastPositionTime = SystemClock.elapsedRealtime()

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

        if (controller.isUsbExclusiveActive() || AppPreferences.Player.usbExclusiveRequested) {
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
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { restoreStickyPlaybackState(reason, autoResume) }
            return
        }
        val controller = playerController
        val restoredSong = controller.restoreLastSong() ?: controller.currentSong.value
        if (restoredSong == null) {
            val queueSize = controller.queue.value.songs.size
            if (queueSize > 0) {
                currentSong = null
                currentPlayState = PlayState.IDLE
                updateNotification()
                Log.i(
                    "PlayerService",
                    "restoreStickyPlaybackState: queue-only restore reason=$reason queue=$queueSize",
                )
            } else {
                Log.w("PlayerService", "restoreStickyPlaybackState skipped: no retained state reason=$reason")
            }
            return
        }
        if (controller.isQueueSelectionOnly()) {
            currentSong = restoredSong
            currentPlayState = PlayState.IDLE
            lastKnownPosition = 0L
            lastPositionTime = SystemClock.elapsedRealtime()
            updateMediaSessionMetadata(
                restoredSong.title,
                restoredSong.artist,
                restoredSong.album,
                restoredSong.albumArtPath,
                restoredSong.duration,
            )
            updateMediaSessionPlaybackState(PlayState.IDLE, 0L)
            updateNotification()
            Log.i(
                "PlayerService",
                "restoreStickyPlaybackState: queue selection only reason=$reason " +
                    "song=${restoredSong.title} queue=${controller.queue.value.songs.size}",
            )
            return
        }

        val persistedState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
            PlayState.IDLE
        }
        val restoredState = if (!autoResume &&
            !AudioFocusPreferences.resumeOnStart &&
            (persistedState == PlayState.PLAYING || persistedState == PlayState.PREPARING)
        ) {
            PlayState.PAUSED
        } else {
            persistedState
        }
        val shouldProtectUsb = AppPreferences.Player.usbExclusiveRequested || controller.isUsbExclusiveActive()

        currentSong = restoredSong
        currentPlayState = restoredState
        lastKnownPosition = AppPreferences.Player.lastPosition.coerceAtLeast(0L)
        lastPositionTime = SystemClock.elapsedRealtime()

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
                if (
                    currentPlayState == PlayState.PLAYING ||
                    currentPlayState == PlayState.PREPARING ||
                    playerController.playState.value == PlayState.PLAYING ||
                    playerController.playState.value == PlayState.PREPARING ||
                    AppPreferences.Player.lastPlayStateOrdinal == PlayState.PLAYING.ordinal
                ) {
                    usbBackgroundKeepAliveLatched = true
                }
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
                if (playerController.playState.value != PlayState.PLAYING &&
                    playerController.playState.value != PlayState.PREPARING
                ) {
                    playerController.playPause()
                }
                schedulePlaybackWidgetRefresh()
            }
            ACTION_PAUSE -> {
                notifyActivity(action)
                usbBackgroundKeepAliveLatched = false
                playerController.pause()
                schedulePlaybackWidgetRefresh()
            }
            ACTION_TOGGLE_PLAYBACK -> {
                playerController.playPause()
                schedulePlaybackWidgetRefresh()
            }
            ACTION_NEXT -> {
                notifyActivity(action)
                playerController.next()
                schedulePlaybackWidgetRefresh(delayMs = 320L)
            }
            ACTION_PREVIOUS -> {
                notifyActivity(action)
                playerController.previous()
                schedulePlaybackWidgetRefresh(delayMs = 320L)
            }
            ACTION_STOP -> {
                notifyActivity(action)
                usbBackgroundKeepAliveLatched = false
                playerController.stop()
                stopUsbBackgroundGuardian("action_stop")
            }
            "com.rawsmusic.action.SYNC_POSITION" -> {
                val position = intent.getLongExtra("position", 0L)
                runOnPlayerServiceThread { applySyncedPosition(position) }
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
                val serviceArtworkPath = resolveServiceArtworkPath(albumArtPath, path)
                val duration = intent.getLongExtra("duration", 0L)
                val state = intent.getIntExtra("playState", PlayState.IDLE.ordinal)
                val position = intent.getLongExtra("position", 0L)

                runOnPlayerServiceThread {
                    applySongUpdate(
                        title = title,
                        artist = artist,
                        album = album,
                        serviceArtworkPath = serviceArtworkPath,
                        path = path,
                        fileSize = fileSize,
                        dateModified = dateModified,
                        cueTrackIndex = cueTrackIndex,
                        duration = duration,
                        state = PlayState.entries.getOrElse(state) { PlayState.IDLE },
                        position = position,
                        reason = "action_update",
                    )
                }
            }
            ACTION_UPDATE_LYRICS -> {
                // 歌词已更新，仅更新歌词和元数据（保留已有封面）
                updateLyricsInMetadata()
            }
            ACTION_REFRESH_NOTIFICATION -> updateNotification()
            ACTION_REFRESH_LYRIC_METADATA -> updateLyricsInMetadata()
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
                usbBackgroundKeepAliveLatched = false
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
            ACTION_TOGGLE_FAVORITE -> {
                currentSong?.let { song ->
                    lifecycleScope.launch {
                        PlaylistStore.getInstance(this@PlayerService).toggleFavorite(song)
                        updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
                        updateNotification()
                    }
                }
            }
            ACTION_TOGGLE_DESKTOP_LYRIC -> {
                val enabled = !AppPreferences.Lyrics.desktopLyricEnabled
                AppPreferences.Lyrics.desktopLyricEnabled = enabled
                runCatching {
                    val serviceIntent = Intent().apply {
                        setClassName(packageName, "com.rawsmusic.lyric.DesktopLyricService")
                        this.action = if (enabled) {
                            "com.rawsmusic.action.ENABLE_DESKTOP_LYRIC"
                        } else {
                            "com.rawsmusic.action.HIDE_DESKTOP_LYRIC"
                        }
                    }
                    startService(serviceIntent)
                }.onFailure { error ->
                    Log.w("PlayerService", "desktop lyric action failed enabled=$enabled", error)
                }
                updateNotification()
            }
        }
    }

    private fun applySyncedPosition(position: Long) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { applySyncedPosition(position) }
            return
        }
        // PlaybackState already contains position + speed + elapsedRealtime, so SystemUI advances
        // it without a 1 s Binder pulse. Use the UI pulse only to maintain our baseline. Re-publish
        // when it proves a discontinuity (seek), matching reference player's event-driven MediaSession path.
        if (position <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val expectedPosition = if (currentPlayState == PlayState.PLAYING && lastPositionTime > 0L) {
            lastKnownPosition + (now - lastPositionTime).coerceAtLeast(0L)
        } else {
            lastKnownPosition
        }
        val positionDiscontinuity = kotlin.math.abs(position - expectedPosition) >= 500L
        lastKnownPosition = position
        lastPositionTime = now
        if (currentPlayState == PlayState.PLAYING && positionDiscontinuity) {
            updateMediaSessionPlaybackState(PlayState.PLAYING, position)
        }
    }

    private fun applySongUpdate(
        title: String,
        artist: String,
        album: String,
        serviceArtworkPath: String,
        path: String,
        fileSize: Long,
        dateModified: Long,
        cueTrackIndex: Int,
        duration: Long,
        state: PlayState,
        position: Long,
        reason: String,
    ) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post {
                applySongUpdate(
                    title = title,
                    artist = artist,
                    album = album,
                    serviceArtworkPath = serviceArtworkPath,
                    path = path,
                    fileSize = fileSize,
                    dateModified = dateModified,
                    cueTrackIndex = cueTrackIndex,
                    duration = duration,
                    state = state,
                    position = position,
                    reason = reason,
                )
            }
            return
        }
        lastKnownPosition = position
        lastPositionTime = SystemClock.elapsedRealtime()

        val songChanged = currentSong?.albumArtPath != serviceArtworkPath ||
            currentSong?.path != path ||
            currentSong?.title != title ||
            currentSong?.artist != artist ||
            currentSong?.album != album ||
            currentSong?.duration != duration ||
            currentSong?.cueTrackIndex != cueTrackIndex

        currentSong = AudioFile(
            id = currentSong?.takeIf { it.path == path && it.cueTrackIndex == cueTrackIndex }?.id ?: 0L,
            path = path,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            albumArtPath = serviceArtworkPath,
            fileSize = fileSize,
            dateModified = dateModified,
            cueTrackIndex = cueTrackIndex,
        )
        if (songChanged) {
            invalidateLyricsForSongChange(reason)
        }

        currentPlayState = state
        if (currentPlayState == PlayState.PLAYING) {
            requestServiceAudioFocus("SONG_UPDATE_PLAYING:$reason")
            acquireWakeLockIfNeeded()
        }

        // reference player does not rebuild metadata/artwork on a pure play/pause state pulse. Keep the
        // expensive external-media lane tied to an actual media identity change only.
        if (songChanged) {
            updateMediaSessionMetadata(title, artist, album, serviceArtworkPath, duration)
        }
        updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
        if (!songChanged) {
            scheduleNotificationPublication()
        }
        startPositionUpdates()
    }

    /**
     * 通过广播通知MainActivity执行播放控制
     */
    private fun notifyActivity(action: String) {
        PlayerEventBus.emit(action)

        runOnPlayerServiceThread {
            applyNotifiedPlaybackAction(action)
        }
    }

    private fun applyNotifiedPlaybackAction(action: String) {
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
            ACTION_STOP -> {
                // STOP is an explicit terminal ownership decision. PlayerController serializes the
                // renderer teardown on its transport queue, so its StateFlow can still read PLAYING
                // for a short window here. Do not let that retiring backend keep MediaSession/FGS
                // advertising active audio after the user has already stopped playback.
                currentPlayState = PlayState.STOPPED
                usbBackgroundKeepAliveLatched = false
                positionUpdateJob?.cancel()
                stopUsbBackgroundGuardian("notify_stop")
                abandonServiceAudioFocus("ACTION_STOP")
                releaseUsbWakeLock()
                releaseWakeLock()
                releaseWifiLock("notify_stop")
                updateMediaSessionPlaybackState(PlayState.STOPPED, lastKnownPosition)
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
        val serviceArtworkPath = resolveServiceArtworkPath(albumArtPath, path)
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
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { forceMediaSessionPlaying(reason) }
            return
        }
        val controllerState = playerController.playState.value
        val controllerSong = playerController.currentSong.value
        val sustainedUsbPlayback = playerController.shouldSustainUsbBackgroundPlayback()
        if (controllerSong == null ||
            (controllerState != PlayState.PLAYING && !sustainedUsbPlayback)
        ) {
            val fallbackState = if (controllerSong != null || currentSong != null) {
                PlayState.PAUSED
            } else {
                PlayState.IDLE
            }
            currentPlayState = fallbackState
            updateMediaSessionPlaybackState(fallbackState, lastKnownPosition)
            Log.i(
                "PlayerService",
                "forceMediaSessionPlaying suppressed: reason=$reason controllerState=$controllerState " +
                    "controllerSong=${controllerSong?.title} serviceSong=${currentSong?.title} " +
                    "sustainUsb=$sustainedUsbPlayback",
            )
            return
        }
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
        return try {
            serviceAudioFocusGranted = playerController.ensureAudioFocusForService(reason)
            Log.i("PlayerService", "requestServiceAudioFocus: delegated reason=$reason granted=$serviceAudioFocusGranted")
            serviceAudioFocusGranted
        } catch (t: Throwable) {
            Log.w("PlayerService", "requestServiceAudioFocus failed: reason=$reason ${t.message}")
            false
        }
    }

    private fun abandonServiceAudioFocus(reason: String) {
        try {
            playerController.releaseAudioFocusForService(reason)
            Log.i("PlayerService", "abandonServiceAudioFocus: delegated reason=$reason")
        } catch (t: Throwable) {
            Log.w("PlayerService", "abandonServiceAudioFocus failed: reason=$reason ${t.message}")
        } finally {
            serviceAudioFocusGranted = false
        }
    }

    /**
     * 更新MediaSession的元数据（标题、艺术家、封面等）
     */
    private fun updateMediaSessionMetadata(title: String, artist: String, album: String, albumArtPath: String, duration: Long) {
        colorOsLyricRetryJob?.cancel()
        colorOsLyricRetryJob = null
        // Retain the displayed cover until its replacement is ready. Clearing it
        // here creates an empty artwork publication on every metadata refresh.
        val songSnapshot = currentSong
        val serviceArtworkPath = resolveServiceArtworkPath(
            albumArtPath,
            songSnapshot?.path.orEmpty(),
        )
        val songIdentity = songSnapshot?.mediaArtworkIdentity().orEmpty()
        val audioFallbackPath = songSnapshot?.path
            ?.takeIf { it.isFileBackedArtworkSource() }
            .orEmpty()
        val requestGeneration = ++artworkRequestGeneration
        loadingArtPath = serviceArtworkPath

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: artist
        // reference player builds the external payload on its external-api Handler, then posts the final
        // MediaMetadataCompat back to the player-service Handler. Keep that exact ownership split.
        runOnExternalApiThread {
            if (!isCurrentArtworkRequest(requestGeneration, songIdentity, serviceArtworkPath)) {
                return@runOnExternalApiThread
            }
            val metadata = MediaMetadataCompat.Builder().apply {
                putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            }.build()
            if (!isCurrentArtworkRequest(requestGeneration, songIdentity, serviceArtworkPath)) {
                return@runOnExternalApiThread
            }
            // Identity publication is deliberately text-only. Re-attaching the retained 1024/1536
            // bitmap here made every title update parcel several megabytes through MediaSession,
            // including the previous song's cover during a rapid switch. The stable artwork lane
            // below publishes the current song's bitmap once after motion settles.
            publishMediaMetadataOnPlayerServiceThread(
                metadata = metadata,
                requestGeneration = requestGeneration,
                expectedSongIdentity = songIdentity,
                expectedArtworkPath = serviceArtworkPath,
                traceName = "MEDIASESSION_METADATA_TEXT_BINDER",
            )
            scheduleNotificationPublication()
        }

        // 异步加载新封面
        if (songSnapshot != null) {
            loadCoverBitmap(
                albumArtPath = serviceArtworkPath,
                expectedSongIdentity = songIdentity,
                audioFallbackPath = audioFallbackPath,
                requestGeneration = requestGeneration
            )
        }

    }

    private fun isCurrentArtworkRequest(
        requestGeneration: Long,
        expectedSongIdentity: String,
        expectedArtworkPath: String,
    ): Boolean = requestGeneration == artworkRequestGeneration &&
        loadingArtPath == expectedArtworkPath &&
        currentSong?.mediaArtworkIdentity() == expectedSongIdentity

    private fun scheduleLyricsMediaPublication(
        retry: Boolean = true,
        delayMs: Long = MEDIA_METADATA_COALESCE_MS,
    ) {
        if (!isRunning) return
        val handler = mediaPublicationHandler ?: return
        synchronized(mediaPublicationLock) {
            pendingLyricsPublicationRetry = pendingLyricsPublicationRetry || retry
            handler.removeCallbacks(lyricsMediaPublicationRunnable)
            handler.postDelayed(lyricsMediaPublicationRunnable, delayMs.coerceAtLeast(0L))
        }
    }

    private fun scheduleNotificationPublication(
        delayMs: Long = NOTIFICATION_PUBLICATION_DELAY_MS,
    ) {
        if (!isRunning) return
        val handler = mediaPublicationHandler ?: return
        handler.removeCallbacks(notificationPublicationRunnable)
        handler.postDelayed(notificationPublicationRunnable, delayMs.coerceAtLeast(0L))
    }

    /** Service/Main callers only enqueue. Heavy media publication is worker-owned. */
    private fun updateLyricsInMetadata(retry: Boolean = true) {
        scheduleLyricsMediaPublication(retry = retry)
    }

    /**
     * 仅更新歌词到MediaSession元数据，保留已有封面（不清除coverBitmap）。
     * reference player 1026 builds external metadata on its own HandlerThread before handing the final
     * object to the player-service handler; keep Raw's Bitmap/Parcel/notification work off Main.
     */
    private fun updateLyricsInMetadataNow(retry: Boolean = true) {
        colorOsLyricRetryJob?.cancel()
        colorOsLyricRetryJob = null
        val song = currentSong ?: run {
            liveLyricNotificationBridge.clear()
            return
        }
        val lrcText = _currentLyrics.value
            ?.takeIf { currentLyricsSongIdentity == colorOsLyricIdentity(song) }
            ?.let { lyrics ->
                if (!lyrics.isEmpty) buildLrcText(lyrics) else null
            }

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist
        val songIdentity = song.mediaArtworkIdentity()
        val matchingArtwork = coverBitmap?.takeIf {
            coverBitmapSongIdentity == songIdentity && !it.isRecycled
        }
        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            // During a track switch coverBitmap intentionally still retains the outgoing pixels.
            // Never attach that bitmap to incoming lyric/text metadata. If artwork for this exact
            // song has already been published, preserving it here is safe because identity and
            // generation are checked again at the final player-service commit boundary.
            matchingArtwork?.let { putCompatibleArtwork(it) }
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
            putColorOsLyricMetadata(song)
        }.build()
        val expectedGeneration = artworkRequestGeneration
        if (expectedGeneration != artworkRequestGeneration ||
            currentSong?.mediaArtworkIdentity() != song.mediaArtworkIdentity()) return
        publishMediaMetadataOnPlayerServiceThread(
            metadata = metadata,
            requestGeneration = expectedGeneration,
            expectedSongIdentity = songIdentity,
            expectedArtworkPath = loadingArtPath,
            traceName = "MEDIASESSION_METADATA_LYRIC_BINDER",
        )
        if (expectedGeneration == artworkRequestGeneration &&
            currentSong?.mediaArtworkIdentity() == song.mediaArtworkIdentity()
        ) {
            publishColorOsDirectLyric(song)
            scheduleNotificationPublication()
        }

        val colorOsLyricInfo = buildColorOsLyricInfo(song)
        if (AppPreferences.Lyrics.colorOsBridgeLyricEnabled &&
            retry &&
            !colorOsLyricInfo.isNullOrBlank()
        ) {
            val expectedIdentity = colorOsLyricIdentity(song)
            colorOsLyricRetryJob = lifecycleScope.launch(Dispatchers.Default) {
                delay(800L)
                if (currentSong?.let(::colorOsLyricIdentity) == expectedIdentity) {
                    scheduleLyricsMediaPublication(retry = false, delayMs = 0L)
                }
            }
        }
    }

    /**
     * 更新MediaSession的播放状态
     */
    private fun updateMediaSessionPlaybackState(state: PlayState, position: Long) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { updateMediaSessionPlaybackState(state, position) }
            return
        }
        val controllerState = playerController.playState.value
        val hasMediaIdentity = playerController.currentSong.value != null || currentSong != null
        val sustainedUsbPlayback = hasMediaIdentity && playerController.shouldSustainUsbBackgroundPlayback()
        val effectiveState = when {
            !hasMediaIdentity -> PlayState.IDLE
            state == PlayState.PLAYING &&
                controllerState != PlayState.PLAYING &&
                !sustainedUsbPlayback -> if (controllerState == PlayState.PREPARING) {
                    PlayState.PREPARING
                } else {
                    PlayState.PAUSED
                }
            else -> state
        }
        val (stateCompat, speed) = when (effectiveState) {
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
            addCustomAction(
                ACTION_TOGGLE_SHUFFLE,
                "播放模式",
                R.drawable.ic_repeat
            )
        }.build()
        // A selected/paused item may keep metadata and a notification, but it is not active audio
        // ownership. Several OEM SystemUI/battery surfaces treat an active MediaSession as "playing
        // audio" even when PlaybackState is PAUSED, so keep the session active only for a real
        // renderer/preparation owner (or the live USB background owner).
        val sessionOwnsMedia = hasMediaIdentity && (
            effectiveState == PlayState.PLAYING ||
                effectiveState == PlayState.PREPARING ||
                sustainedUsbPlayback
            )
        mediaSessionCompat?.let { session ->
            val sessionStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
            session.setPlaybackState(playbackState)
            session.isActive = sessionOwnsMedia
            if (sessionStartedNs != 0L) {
                PlayerSwitchTrace.duration(
                    "MEDIASESSION_PLAYBACKSTATE_BINDER",
                    System.nanoTime() - sessionStartedNs,
                )
            }
        }
        if (sessionOwnsMedia) {
            reassertUsbRemoteVolumeRoute("playback_state:${effectiveState.name}")
        }
        if (state == PlayState.PLAYING && effectiveState != PlayState.PLAYING) {
            positionUpdateJob?.cancel()
            Log.i(
                "PlayerService",
                "MediaSession PLAYING downgraded: requested=$state effective=$effectiveState " +
                    "controllerState=$controllerState hasMedia=$hasMediaIdentity sustainUsb=$sustainedUsbPlayback",
            )
        }
    }

    /**
     * Some Android/OEM media-session implementations can rebuild the playback route while the
     * app is in the background. Reattach the existing VolumeProvider when USB hardware volume
     * is still the active route instead of silently falling back to STREAM_MUSIC.
     */
    private fun reassertUsbRemoteVolumeRoute(reason: String) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { reassertUsbRemoteVolumeRoute(reason) }
            return
        }
        val session = mediaSessionCompat ?: return
        val provider = usbVolumeProvider ?: return
        if (!playerController.shouldUseUsbRemoteVolume()) {
            session.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            logUsbVolumeRoute(false, reason)
            return
        }
        session.setPlaybackToRemote(provider)
        session.isActive = true
        provider.syncFromController()
        logUsbVolumeRoute(true, reason)
    }

    private fun logUsbVolumeRoute(remote: Boolean, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastUsbRouteLogRemote == remote && now - lastUsbRouteLogElapsed < 30_000L) return
        lastUsbRouteLogRemote = remote
        lastUsbRouteLogElapsed = now
        Log.d("PlayerService", "USB volume route confirmed: remote=$remote reason=$reason")
    }

    private fun rebuildMetadataWithBluetoothLyric() {
        scheduleLyricsMediaPublication()
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

    private fun resolveServiceArtworkPath(albumArtPath: String, audioPath: String): String {
        val explicitArtwork = albumArtPath.trim()
        if (explicitArtwork.isNotBlank()) {
            val remoteAudio = audioPath.startsWith("http://", ignoreCase = true) ||
                audioPath.startsWith("https://", ignoreCase = true)
            if (!remoteAudio || explicitArtwork != audioPath.trim()) {
                return explicitArtwork
            }
        }
        return audioPath.takeIf { it.isFileBackedArtworkSource() }.orEmpty()
    }

    /**
     * Android media-artwork resolution policy.
     *
     * The API switch selects the same high tier used by playback; otherwise media surfaces receive
     * the low tier. The experimental resolution option therefore also affects high-resolution API art.
     */
    private fun mediaSessionArtworkTargetSide(): Int {
        val metrics = resources.displayMetrics
        val shortSide = if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            minOf(metrics.widthPixels, metrics.heightPixels)
        } else {
            ArtworkResolutionPolicy.LARGE_DISPLAY_MIN_SIDE_PX
        }
        return if (AppPreferences.AlbumArt.sendHighResolutionArtwork) {
            ArtworkResolutionPolicy.highTargetSide(
                increaseResolution = AppPreferences.AlbumArt.useHigherRes,
                displayShortSidePx = shortSide,
                maxMemoryBytes = Runtime.getRuntime().maxMemory(),
            )
        } else {
            ArtworkResolutionPolicy.lowTargetSide(shortSide)
        }
    }

    private fun mediaSessionArtworkConfig(): Bitmap.Config =
        if (ArtworkResolutionPolicy.use24BitRgbEffective(
                requested = AppPreferences.AlbumArt.forceArgb8888,
                maxMemoryBytes = Runtime.getRuntime().maxMemory(),
            )
        ) {
            Bitmap.Config.ARGB_8888
        } else {
            Bitmap.Config.RGB_565
        }

    private fun serviceArtworkDecodeOptions(sampleSize: Int): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = mediaSessionArtworkConfig()
        }

    /**
     * 异步加载封面Bitmap
     * 支持：content:// URI（含 albumart 高清提取）、文件路径
     */
    private fun loadCoverBitmap(
        albumArtPath: String,
        expectedSongIdentity: String,
        audioFallbackPath: String,
        requestGeneration: Long
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // External artwork belongs to the external-media publication cadence, not to the
            // player-page animation duration. reference player coalesces external metadata for 100 ms on
            // Android 8+ (200 ms earlier); generation/identity invalidation handles rapid skips.
            val artworkCoalesceMs = MEDIA_METADATA_COALESCE_MS
            if (PlayerSwitchTrace.isActive()) {
                PlayerSwitchTrace.mark(
                    "service_artwork_deferred",
                    "generation=$requestGeneration delay=${artworkCoalesceMs}ms thread=${Thread.currentThread().name}",
                )
            }
            delay(artworkCoalesceMs)
            if (!isCurrentArtworkRequest(requestGeneration, expectedSongIdentity, albumArtPath)) {
                if (PlayerSwitchTrace.isActive()) {
                    PlayerSwitchTrace.mark(
                        "service_artwork_superseded",
                        "generation=$requestGeneration before_decode=true",
                    )
                }
                return@launch
            }
            val targetSide = mediaSessionArtworkTargetSide()
            var bitmap: Bitmap? = null
            var usedDefaultArtwork = false
            val decodeStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
            if (decodeStartedNs != 0L) {
                PlayerSwitchTrace.mark(
                    "service_artwork_decode_begin",
                    "generation=$requestGeneration side=$targetSide thread=${Thread.currentThread().name}",
                )
            }
            try {
                val rawPath = try { URLDecoder.decode(albumArtPath, "UTF-8") } catch (_: Exception) { albumArtPath }
                val path = normalizeServiceArtworkPath(rawPath)
                Log.d("StatusArtwork", "load targetSide=$targetSide apiHigh=${AppPreferences.AlbumArt.sendHighResolutionArtwork} labs=${AppPreferences.AlbumArt.useHigherRes} path=${path.takeLast(96)}")

                if (path.isBlank()) {
                    // Continue to the configured built-in fallback below.
                } else if (path.startsWith("file://")) {
                    val filePath = path.removePrefix("file://")
                    val file = File(filePath)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(filePath, targetSide, targetSide)
                    }
                } else if (path.startsWith("content://") && path.contains("albumart")) {
                    // albumart URI — 优先从音频文件内嵌封面提取高清原图
                    bitmap = extractEmbeddedArtwork(path, targetSide)
                    // 回退到 content URI 缩略图
                    if (bitmap == null) {
                        bitmap = loadFromContentUri(path, targetSide, targetSide)
                    }
                } else if (path.startsWith("content://")) {
                    // 其他 content URI
                    bitmap = loadFromContentUri(path, targetSide, targetSide)
                } else {
                    // 文件路径 — 尝试直接解码
                    val file = File(path)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(path, targetSide, targetSide)
                    }
                    // 文件路径解码失败时，尝试作为内嵌封面从音频文件提取
                    if (bitmap == null && path.isNotBlank()) {
                        bitmap = extractEmbeddedFromAudioFile(path, targetSide)
                    }
                }

                if (bitmap == null) {
                    if (audioFallbackPath.isNotBlank() && audioFallbackPath != path && File(audioFallbackPath).exists()) {
                        bitmap = extractEmbeddedFromAudioFile(audioFallbackPath, targetSide)
                    }
                }

                if (bitmap == null && AppPreferences.AlbumArt.useDefaultArtwork) {
                    bitmap = decodeDefaultServiceArtwork(targetSide)
                    usedDefaultArtwork = bitmap != null
                    Log.i(
                        "StatusArtwork",
                        "default_fallback song=$expectedSongIdentity decoded=$usedDefaultArtwork"
                    )
                }

                if (decodeStartedNs != 0L) {
                    PlayerSwitchTrace.duration(
                        "SERVICE_ARTWORK_DECODE",
                        System.nanoTime() - decodeStartedNs,
                    )
                    PlayerSwitchTrace.mark(
                        "service_artwork_decode_done",
                        "generation=$requestGeneration side=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} default=$usedDefaultArtwork",
                    )
                }

                // Decode is IO-owned; everything after decode is external-media publication state.
                // Do not bounce through Main. reference player's bitmap/metadata/notification helpers live
                // on the external-api looper and only hand the final MediaMetadata object back to
                // the player-service looper.
                val decodedBitmap = bitmap
                val decodedDefaultArtwork = usedDefaultArtwork
                runOnExternalApiThread {
                    try {
                        val songSnapshot = currentSong
                        if (songSnapshot == null ||
                            !isCurrentArtworkRequest(requestGeneration, expectedSongIdentity, albumArtPath)
                        ) {
                            decodedBitmap
                                ?.takeIf { it !== coverBitmap && !it.isRecycled }
                                ?.recycle()
                            return@runOnExternalApiThread
                        }

                        coverBitmap = decodedBitmap
                        coverBitmapSongIdentity = expectedSongIdentity

                        val lrcText = _currentLyrics.value
                            ?.takeIf { currentLyricsSongIdentity == colorOsLyricIdentity(songSnapshot) }
                            ?.let { lyrics -> if (!lyrics.isEmpty) buildLrcText(lyrics) else null }
                        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: songSnapshot.artist
                        val metadata = MediaMetadataCompat.Builder().apply {
                            putString(MediaMetadataCompat.METADATA_KEY_TITLE, songSnapshot.title)
                            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, songSnapshot.album)
                            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, songSnapshot.duration)
                            decodedBitmap?.let { putCompatibleArtwork(it) }
                            if (!lrcText.isNullOrBlank()) {
                                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
                            }
                            putColorOsLyricMetadata(songSnapshot)
                        }.build()
                        if (!isCurrentArtworkRequest(requestGeneration, expectedSongIdentity, albumArtPath)) {
                            return@runOnExternalApiThread
                        }
                        publishMediaMetadataOnPlayerServiceThread(
                            metadata = metadata,
                            requestGeneration = requestGeneration,
                            expectedSongIdentity = expectedSongIdentity,
                            expectedArtworkPath = albumArtPath,
                            traceName = "MEDIASESSION_METADATA_ART_BINDER",
                        )
                        Log.i(
                            "StatusArtwork",
                            "apply song=$expectedSongIdentity default=$decodedDefaultArtwork " +
                                "size=${decodedBitmap?.width ?: 0}x${decodedBitmap?.height ?: 0}"
                        )
                        scheduleNotificationPublication()
                    } catch (_: Exception) {
                        decodedBitmap
                            ?.takeIf { it !== coverBitmap && !it.isRecycled }
                            ?.recycle()
                    }
                }
            } catch (_: Exception) {
                bitmap?.takeIf { it !== coverBitmap && !it.isRecycled }?.recycle()
            }
        }
    }

    private fun AudioFile.mediaArtworkIdentity(): String =
        "$id|$path|$cueOffsetMs|$cueEndMs|$cueTrackIndex"

    private fun MediaMetadataCompat.Builder.putCompatibleArtwork(bitmap: Bitmap) {
        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
    }

    private fun MediaMetadataCompat.Builder.putColorOsLyricMetadata(song: AudioFile?) {
        if (!AppPreferences.Lyrics.colorOsBridgeLyricEnabled) return
        val lyricInfo = buildColorOsLyricInfo(song)
        if (!lyricInfo.isNullOrBlank()) {
            putString(ColorOsLyricMetadata.METADATA_KEY, lyricInfo)
            val hasRawLyric = lyricInfo.contains("rawLyric")
            Log.d(
                "ColorOsLyricMeta",
                "published song=${song?.title} chars=${lyricInfo.length} raw=$hasRawLyric",
            )
        }
    }

    private fun buildColorOsLyricInfo(song: AudioFile?): String? = song
        ?.let { currentSongValue ->
            if (currentLyricsSongIdentity != colorOsLyricIdentity(currentSongValue)) return@let null
            _currentLyrics.value
                ?.takeUnless { it.isEmpty }
                ?.let { ColorOsLyricMetadata.build(currentSongValue, it) }
        }

    private fun publishColorOsDirectLyric(song: AudioFile?) {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { publishColorOsDirectLyric(song) }
            return
        }
        if (!AppPreferences.Lyrics.colorOsBridgeLyricEnabled || song == null) return
        val lyricInfo = buildColorOsLyricInfo(song) ?: return
        if (AppPreferences.Lyrics.colorOsBridgeDeliveryMode ==
            AppPreferences.Lyrics.COLOROS_DELIVERY_MODE_NON_MODULE
        ) {
            val published = ColorOsNonModuleLyricBridge.publish(this, song, lyricInfo)
            if (published) {
                Log.i(
                    "ColorOsLyricDirect",
                    "published non-module compatibility payload song=${song.title}"
                )
            }
            return
        }
        val trackIdentity = colorOsLyricIdentity(song)
        if (trackIdentity != colorOsDirectTrackIdentity) {
            colorOsDirectTrackIdentity = trackIdentity
            colorOsDirectTrackGeneration += 1L
            colorOsDirectPayloadKey = null
        }
        val payloadKey = "$trackIdentity|${lyricInfo.length}|${lyricInfo.hashCode()}"
        if (payloadKey == colorOsDirectPayloadKey) return
        if (ColorOsDirectLyricBridge.publish(
                context = this,
                song = song,
                lyricInfo = lyricInfo,
                trackGeneration = colorOsDirectTrackGeneration,
            )
        ) {
            colorOsDirectPayloadKey = payloadKey
            Log.i(
                "ColorOsLyricDirect",
                "published source=lyricprovider/raws-music trackGeneration=" +
                    "$colorOsDirectTrackGeneration song=${song.title}",
            )
        }
    }

    private fun colorOsLyricIdentity(song: AudioFile): String =
        "${song.path}|${song.cueTrackIndex}|${song.fileSize}|${song.dateModified}"

    private fun decodeDefaultServiceArtwork(targetSide: Int): Bitmap? {
        val source = BitmapFactory.decodeResource(
            resources,
            com.rawsmusic.core.common.R.drawable.default_album_art
        ) ?: return null
        if (source.width == targetSide && source.height == targetSide) return source
        val scaled = Bitmap.createScaledBitmap(source, targetSide, targetSide, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    /**
     * 从音频文件内嵌封面提取高清原图
     */
    private fun extractEmbeddedArtwork(albumArtUri: String, targetSide: Int): Bitmap? {
        val uri = Uri.parse(albumArtUri)
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null

        // 查询该专辑的第一首音频文件路径
        val audioPath = queryFirstAudioPathForAlbum(albumId) ?: return null

        extractCoverWithTagLib(audioPath, targetSide, targetSide)?.let { return it }

        val ext = audioPath.substringAfterLast(".", "").uppercase()
        // WAV/DSF/DFF/AIFF 等格式：MediaMetadataRetriever 无法提取封面，使用 FFmpegKit
        if (ext in setOf("WAV", "DSF", "DFF", "AIFF", "AIF")) {
            return extractCoverWithFfmpeg(audioPath, targetSide)
        }

        // 其他格式：native TagLib 失败后才 fallback 到 MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, targetSide, targetSide)
            val decodeOptions = serviceArtworkDecodeOptions(sampleSize)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            // MediaMetadataRetriever 失败时回退到 FFmpegKit
            return extractCoverWithFfmpeg(audioPath, targetSide)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Notification/MediaSession artwork path: try native TagLib first so playback
     * metadata does not bypass the shared artwork policy by pulling embeddedPicture into Java heap.
     */
    private fun extractCoverWithTagLib(audioPath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (audioPath.startsWith("http://", true) || audioPath.startsWith("https://", true)) return null
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
            val decodeOptions = serviceArtworkDecodeOptions(sampleSize)
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
    private fun extractCoverWithFfmpeg(audioPath: String, targetSide: Int): Bitmap? {
        return try {
            val remoteHttp = audioPath.startsWith("http://", true) || audioPath.startsWith("https://", true)
            val audioFile = if (remoteHttp) null else File(audioPath)
            val version = if (remoteHttp) {
                "remote_${audioPath.hashCode()}"
            } else {
                "${audioPath.hashCode()}_${audioFile?.length() ?: 0L}_${audioFile?.lastModified() ?: 0L}"
            }
            val coverFile = java.io.File(cacheDir, "albumart/cover_$version.jpg")
            val coverDir = coverFile.parentFile
            if (coverDir != null && !coverDir.exists()) coverDir.mkdirs()

            if (!coverFile.exists() || coverFile.length() <= 1024) {
                val ret = if (remoteHttp) {
                    val remote = RemoteHttpStreamRegistry.lookup(audioPath) ?: return null
                    val resolvedUrl = remote.resolveUrl(audioPath)
                    FFmpegBridge.extractCover(
                        inputPath = resolvedUrl,
                        outputPath = coverFile.absolutePath,
                        headers = remote.resolveHeaders(audioPath),
                        userAgent = remote.userAgent,
                    )
                } else {
                    FFmpegBridge.extractCover(audioPath, coverFile.absolutePath)
                }
                if (ret != 0 || !coverFile.exists() || coverFile.length() <= 1024) {
                    if (coverFile.exists()) coverFile.delete()
                    return null
                }
            }
            decodeSampledFile(coverFile.absolutePath, targetSide, targetSide)
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
        val decodeOptions = serviceArtworkDecodeOptions(sampleSize)
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
            val decodeOptions = serviceArtworkDecodeOptions(sampleSize)
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (_: Exception) { null }
    }

    /** 从音频文件内嵌封面提取（albumArtPath可能是音频文件路径本身） */
    private fun extractEmbeddedFromAudioFile(audioPath: String, targetSide: Int): Bitmap? {
        val extensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "wma", "ape", "opus", "dsf", "dff", "aiff")
        val ext = audioPath.substringAfterLast(".", "").lowercase()
        if (ext !in extensions) return null

        if (audioPath.startsWith("http://", true) || audioPath.startsWith("https://", true)) {
            return extractCoverWithFfmpeg(audioPath, targetSide)
        }

        extractCoverWithTagLib(audioPath, targetSide, targetSide)?.let { return it }

        // WAV/DSF/DFF/AIFF：native TagLib 失败后直接用 FFmpegKit
        if (ext in setOf("wav", "dsf", "dff", "aiff", "aif")) {
            return extractCoverWithFfmpeg(audioPath, targetSide)
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, targetSide, targetSide)
            val decodeOptions = serviceArtworkDecodeOptions(sampleSize)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            return extractCoverWithFfmpeg(audioPath, targetSide)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    @Volatile
    private var isForegroundStarted = false

    private fun updateLiveLyricNotification(positionMs: Long = lastKnownPosition) {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { updateLiveLyricNotification(positionMs) }
            return
        }
        val enabled = AppPreferences.Lyrics.liveUpdateLyricEnabled
        liveLyricNotificationBridge.setEnabled(enabled)
        if (!enabled || currentPlayState != PlayState.PLAYING) {
            liveLyricNotificationBridge.clear()
            return
        }
        val song = currentSong ?: return
        if (currentLyricsSongIdentity != colorOsLyricIdentity(song)) {
            liveLyricNotificationBridge.clear()
            return
        }
        val lyrics = _currentLyrics.value ?: run {
            liveLyricNotificationBridge.clear()
            return
        }
        val index = lyrics.findCurrentLine(positionMs)
        val line = lyrics.getLine(index) ?: run {
            liveLyricNotificationBridge.clear()
            return
        }
        val display = buildLiveLyricNotificationText(
            line = line,
            mode = AppPreferences.Lyrics.liveUpdateLyricMode,
            positionMs = positionMs
        ) ?: run {
            liveLyricNotificationBridge.clear()
            return
        }
        val fullLine = AppPreferences.Lyrics.liveUpdateLyricDisplayMode ==
            AppPreferences.Lyrics.LIVE_UPDATE_LYRIC_DISPLAY_MODE_FULL
        val secondary = buildLiveLyricSecondaryText(
            line,
            AppPreferences.Lyrics.liveUpdateLyricSecondaryMode
        )
        liveLyricNotificationBridge.sendLyric(
            songTitle = song.title.ifBlank { song.displayName },
            lyric = if (fullLine) display.fullLyric else display.lyric,
            compactLyric = if (fullLine) display.fullLyric else display.compactLyric,
            allowLongCompactLyric = !fullLine && display.allowLongCompactLyric,
            preserveCompactLyric = fullLine,
            secondaryLyric = secondary,
            artwork = coverBitmap?.takeIf {
                coverBitmapSongIdentity == song.mediaArtworkIdentity() && !it.isRecycled
            }
        )
    }

    /**
     * Keep vendor-specific Focus payloads out of the player module. The event contains only
     * bounded lyric metadata; the app layer resolves artwork and owns the Xiaomi service.
     */
    private fun publishSuperIslandLyric(positionMs: Long = lastKnownPosition) {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { publishSuperIslandLyric(positionMs) }
            return
        }
        val shouldPublish = currentPlayState == PlayState.PLAYING &&
            AppPreferences.Lyrics.xiaomiSuperIslandLyricEnabled
        if (!shouldPublish) {
            clearSuperIslandLyricIfPublished()
            return
        }

        val song = currentSong ?: run {
            clearSuperIslandLyricIfPublished()
            return
        }
        if (currentLyricsSongIdentity != colorOsLyricIdentity(song)) {
            clearSuperIslandLyricIfPublished()
            return
        }
        val lyrics = _currentLyrics.value
        val index = lyrics?.findCurrentLine(positionMs) ?: -1
        val line = lyrics?.getLine(index)
        if (line == null) {
            // Lyrics can arrive after playback has started. Preserve the current island during
            // a temporary timing gap, but retire an island that belongs to the previous track.
            if (superIslandPublished && superIslandSongPath != song.path) {
                clearSuperIslandLyricIfPublished()
            }
            superIslandSongPath = song.path
            return
        }

        val intent = Intent(ACTION_SUPER_ISLAND_LYRIC).apply {
            setPackage(packageName)
            component = superIslandReceiverComponent()
            putExtra("title", song.title)
            putExtra("artist", song.artist)
            putExtra("album", song.album)
            putExtra("path", song.path)
            putExtra("albumArtPath", song.albumArtPath)
            putExtra("duration", song.duration)
            putExtra("position", positionMs)
            putExtra("lineTime", line.timeStamp)
            putExtra("lineEnd", line.endTime)
            putExtra("lineText", line.text)
            putExtra("lineTranslation", line.translation)
            putExtra("lineRomanization", line.romanization)
            putExtra("lineWords", encodeLyricWords(line.words))
            putExtra("linePronunciationWords", encodeLyricWords(line.pronunciationWords))
            putExtra("lineBackgroundWords", encodeLyricWords(line.backgroundWords))
            putExtra("lineBackgroundText", line.backgroundText.orEmpty())
            putExtra("lineBackgroundTranslation", line.backgroundTranslation.orEmpty())
            putExtra("lineBackgroundStart", line.backgroundStartTime ?: 0L)
            putExtra("lineBackgroundEnd", line.backgroundEndTime ?: 0L)
            putExtra("lineAgent", line.agent.orEmpty())
            putExtra("lineAgentName", line.agentName.orEmpty())
            putExtra("lineIsTtml", line.isTtml)
        }
        try {
            sendBroadcast(intent)
            superIslandPublished = true
            superIslandSongPath = song.path
        } catch (error: Exception) {
            Log.w("PlayerService", "Unable to publish Super Island lyric event", error)
        }
    }

    private fun clearSuperIslandLyricIfPublished(force: Boolean = false) {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { clearSuperIslandLyricIfPublished(force) }
            return
        }
        if (!force && !superIslandPublished) return
        runCatching {
            sendBroadcast(
                Intent(ACTION_SUPER_ISLAND_LYRIC_CLEAR).apply {
                    setPackage(packageName)
                    component = superIslandReceiverComponent()
                }
            )
        }.onFailure { error ->
            Log.w("PlayerService", "Unable to clear Super Island lyric event", error)
        }
        superIslandPublished = false
        superIslandSongPath = null
    }

    private fun superIslandReceiverComponent() = ComponentName(
        packageName,
        "com.rawsmusic.lyric.XiaomiSuperIslandLyricReceiver"
    )

    private fun encodeLyricWords(words: List<LyricWord>): String {
        val json = JSONArray()
        words.asSequence()
            .filter { it.text.isNotBlank() && it.end >= it.begin }
            .take(64)
            .forEach { word ->
                json.put(
                    org.json.JSONObject().apply {
                        put("text", word.text.take(96))
                        put("begin", word.begin)
                        put("end", word.end)
                    }
                )
            }
        return json.toString()
    }

    private fun updateNotification() {
        val handler = mediaPublicationHandler
        if (handler != null && Looper.myLooper() == handler.looper) {
            updateNotificationNow()
        } else {
            scheduleNotificationPublication()
        }
    }

    private fun updateNotificationNow() {
        updateLiveLyricNotification()
        publishSuperIslandLyric()
        val buildStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
        val notification = buildNotification()
        if (buildStartedNs != 0L) {
            PlayerSwitchTrace.duration(
                "NOTIFICATION_BUILD",
                System.nanoTime() - buildStartedNs,
            )
        }
        val hasMediaIdentity = hasPublishedMediaIdentity()
        if (shouldOwnPlaybackForeground()) {
            if (!isForegroundStarted) {
                val foregroundStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
                isForegroundStarted = startForegroundCompat(NOTIFICATION_ID, notification)
                if (foregroundStartedNs != 0L) {
                    PlayerSwitchTrace.duration(
                        "NOTIFICATION_START_FOREGROUND",
                        System.nanoTime() - foregroundStartedNs,
                    )
                }
            } else {
                // 后续更新使用 notify()，避免 startForeground() 可能的封面图更新问题
                val notifyStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                if (notifyStartedNs != 0L) {
                    PlayerSwitchTrace.duration(
                        "NOTIFICATION_BINDER",
                        System.nanoTime() - notifyStartedNs,
                    )
                }
            }
        } else {
            // Metadata/queue ownership is not audio ownership.  Keep a paused media notification
            // when there is a selected item, but never keep the process in MEDIA_PLAYBACK merely
            // because a song is selected or a lyric/artwork refresh rebuilt the notification.
            if (isForegroundStarted) {
                stopForegroundCompat(removeNotification = !hasMediaIdentity)
            }
            val manager = getSystemService(NotificationManager::class.java)
            if (hasMediaIdentity) {
                val notifyStartedNs = if (PlayerSwitchTrace.isActive()) System.nanoTime() else 0L
                manager?.notify(NOTIFICATION_ID, notification)
                if (notifyStartedNs != 0L) {
                    PlayerSwitchTrace.duration(
                        "NOTIFICATION_BINDER",
                        System.nanoTime() - notifyStartedNs,
                    )
                }
            } else {
                manager?.cancel(NOTIFICATION_ID)
            }
        }
        notifyPlaybackWidgetIfChanged()
    }

    private fun hasPublishedMediaIdentity(): Boolean =
        playerController.currentSong.value != null || currentSong != null

    /**
     * Foreground-service MEDIA_PLAYBACK is owned by a live/preparing renderer, not by persisted
     * metadata.  This is deliberately stricter than MediaSession activity: a paused session may
     * remain addressable by headset/SystemUI controls without advertising active audio work.
     */
    private fun shouldOwnPlaybackForeground(): Boolean {
        if (!hasPublishedMediaIdentity()) return false
        if (currentPlayState == PlayState.STOPPED) return false
        val controllerState = playerController.playState.value
        val backendState = playerController.ffmpegPlayerRef.state
        // currentPlayState is a service-side mirror fed by intents/publication and can legitimately
        // lag the transport. Do not let that stale mirror retain MEDIA_PLAYBACK foreground-service
        // ownership after the real controller/backend has stopped producing audio.
        return controllerState == PlayState.PLAYING ||
            controllerState == PlayState.PREPARING ||
            backendState == FfmpegAudioPlayer.State.PLAYING ||
            backendState == FfmpegAudioPlayer.State.PREPARING ||
            playerController.shouldSustainUsbBackgroundPlayback()
    }

    private fun schedulePlaybackWidgetRefresh(delayMs: Long = 180L) {
        val handler = mediaPublicationHandler ?: return
        handler.removeCallbacks(playbackWidgetRefreshRunnable)
        handler.postDelayed(playbackWidgetRefreshRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun notifyPlaybackWidgetIfChanged(force: Boolean = false) {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { notifyPlaybackWidgetIfChanged(force) }
            return
        }
        val controllerSong = playerController.currentSong.value
        val song = currentSong ?: controllerSong
        val controllerState = playerController.playState.value
        val resolvedState = if (controllerState != PlayState.IDLE) controllerState else currentPlayState
        val signature = buildString {
            append(song?.mediaArtworkIdentity().orEmpty())
            append('|')
            append(song?.title.orEmpty())
            append('|')
            append(song?.artist.orEmpty())
            append('|')
            append(song?.albumArtPath.orEmpty())
            append('|')
            append(resolvedState.name)
        }
        if (!force && signature == lastPlaybackWidgetSignature) return
        lastPlaybackWidgetSignature = signature
        val refresh = Intent("com.rawsmusic.action.REFRESH_PLAYBACK_WIDGET").setComponent(
            ComponentName(this, "com.rawsmusic.widget.PlaybackWidgetProvider")
        )
        sendBroadcast(refresh)
    }

    private fun notifyPlaybackWidgetProgress() {
        val externalHandler = mediaPublicationHandler
        if (externalHandler != null && Looper.myLooper() != externalHandler.looper) {
            externalHandler.post { notifyPlaybackWidgetProgress() }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaybackWidgetProgressElapsed < 400L) return
        lastPlaybackWidgetProgressElapsed = now
        val progress = Intent("com.rawsmusic.action.PROGRESS_PLAYBACK_WIDGET").setComponent(
            ComponentName(this, "com.rawsmusic.widget.PlaybackWidgetProvider")
        )
        sendBroadcast(progress)
    }

    private fun startForegroundCompat(id: Int, notification: Notification): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // USB exclusive playback needs both MEDIA_PLAYBACK and CONNECTED_DEVICE.
            // Using only MEDIA_PLAYBACK can still let Android throttle the libusb
            // event loop during repeated app launches/background transitions.
            val type = usbForegroundServiceType()
            startForegroundWithTypeFallback(id, notification, type).also { started ->
                if (started) {
                    Log.i(
                        "PlayerService",
                        "Foreground service started/updated type=$type usb=${playerController.isUsbExclusiveActive()}",
                    )
                }
            }
        } else {
            try {
                startForeground(id, notification)
                true
            } catch (error: Throwable) {
                foregroundPromotionRejected = true
                Log.e("PlayerService", "Foreground service start rejected", error)
                false
            }
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

    private fun startForegroundWithTypeFallback(
        id: Int,
        notification: Notification,
        type: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                startForeground(id, notification)
                true
            } catch (error: Throwable) {
                foregroundPromotionRejected = true
                Log.e("PlayerService", "Foreground service start rejected", error)
                false
            }
        }
        try {
            startForeground(id, notification, type)
            return true
        } catch (t: Throwable) {
            if (isForegroundStartPolicyRejection(t)) {
                foregroundPromotionRejected = true
                Log.e(
                    "PlayerService",
                    "Foreground service start rejected by background policy; no fallback restart",
                    t,
                )
                return false
            }
            // Some manifests/build variants may not yet declare CONNECTED_DEVICE.
            // Keep playback protected by falling back to MEDIA_PLAYBACK instead of
            // losing the foreground service entirely.
            Log.w("PlayerService", "startForeground type=$type failed, fallback to MEDIA_PLAYBACK: ${t.message}")
            return try {
                startForeground(
                    id,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                true
            } catch (fallbackError: Throwable) {
                foregroundPromotionRejected = true
                Log.e("PlayerService", "Foreground service fallback rejected", fallbackError)
                false
            }
        }
    }

    private fun isForegroundStartPolicyRejection(error: Throwable): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        // Android notification-channel sound/importance settings are immutable after the
        // channel is created.  Older RawSMusic builds used an IMPORTANCE_DEFAULT channel,
        // so keep the legacy channel only as a settings-history entry and post all playback
        // notifications through this new permanently silent channel.
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            Log.i("PlayerService", "Playback notification migrated to silent channel=$CHANNEL_ID")
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
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_LOW)
            .setDefaults(0)
            .setSound(null)
            .setVibrate(null)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (tickerText.isNotBlank()) {
            builder.setTicker(tickerText)
        }

        val ctrl = playerController
        val isShuffle = ctrl.isShuffle.value
        val repeatMode = ctrl.repeatMode.value
        val prevIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).setAction(ACTION_PREVIOUS),
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_rewind_fill),
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
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_speed_fill),
                "下一曲", nextIntent
            ).build()
        )

        val selectedButtons = AppPreferences.Lyrics.mediaNotificationButtonIds.toSet()
        if (AppPreferences.Lyrics.MEDIA_NOTIFICATION_BUTTON_PLAYBACK_MODE in selectedButtons) {
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
        }

        if (AppPreferences.Lyrics.MEDIA_NOTIFICATION_BUTTON_DESKTOP_LYRIC in selectedButtons) {
            val desktopIntent = PendingIntent.getService(
                this, 12,
                Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE_DESKTOP_LYRIC),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_flyme_ticker),
                    "桌面歌词", desktopIntent
                ).build()
            )
        }

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
                .setShowActionsInCompactView(0, 1, 2)
            if (frameworkToken != null) {
                mediaStyle.setMediaSession(frameworkToken)
            }
            builder.setStyle(mediaStyle)
        }

        val notificationArtwork = coverBitmap?.takeIf {
            song != null && coverBitmapSongIdentity == song.mediaArtworkIdentity() && !it.isRecycled
        }
        notificationArtwork?.let { builder.setLargeIcon(it) }

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

        // Some vendor SystemUI builds partially ignore Builder flags when a foreground
        // media notification is rebuilt after pause/next/previous.  Re-assert the silent
        // policy on the built object as well so an update can never inherit defaults.
        notification.flags = notification.flags or Notification.FLAG_ONLY_ALERT_ONCE
        notification.defaults = 0
        notification.sound = null
        notification.vibrate = null
        XiaomiMediaDragShare.attach(notification, song)

        return notification
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close publication admission before draining either Handler queue. This is the same
        // lifecycle boundary reference player enforces before quitting ExternalAPI/player-service threads.
        isRunning = false
        mediaPublicationHandler?.removeCallbacksAndMessages(null)
        mediaPublicationHandler = null
        mediaPublicationThread?.quitSafely()
        mediaPublicationThread = null
        playerServiceHandler?.removeCallbacksAndMessages(null)
        playerServiceHandler = null
        playerServiceThread?.quitSafely()
        playerServiceThread = null
        colorOsLyricRetryJob?.cancel()
        colorOsLyricRetryJob = null
        clearSuperIslandLyricIfPublished(force = true)
        liveLyricNotificationBridge.clear()
        stopUsbBackgroundGuardian("service_destroy")
        releaseRuntimeController("service_destroy")
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
        val externalOwner = mediaPublicationHandler
        if (externalOwner != null && Looper.myLooper() != externalOwner.looper) {
            externalOwner.post { updateForegroundServiceType() }
            return
        }
        if (!shouldOwnPlaybackForeground()) {
            if (isForegroundStarted) {
                stopForegroundCompat(removeNotification = !hasPublishedMediaIdentity())
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val type = usbForegroundServiceType()
                val notification = buildNotification()
                isForegroundStarted = startForegroundWithTypeFallback(NOTIFICATION_ID, notification, type)
                Log.d("PlayerService", "Foreground service type updated: $type (USB exclusive: ${playerController.isUsbExclusiveActive()})")
            } catch (e: Exception) {
                Log.w("PlayerService", "Failed to update foreground service type", e)
            }
        }
    }

    /** 后台冷启动保护：确保前台服务已启动 */
    private fun ensureForegroundForUsb() {
        val externalOwner = mediaPublicationHandler
        if (externalOwner != null && Looper.myLooper() != externalOwner.looper) {
            externalOwner.post { ensureForegroundForUsb() }
            return
        }
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
        val ownsBackgroundPlayback =
            controller.shouldSustainUsbBackgroundPlayback() ||
                (
                    usbBackgroundKeepAliveLatched &&
                        AppPreferences.Player.usbExclusiveRequested &&
                        AppPreferences.Player.lastPlayStateOrdinal == PlayState.PLAYING.ordinal
                )
        if (!ownsBackgroundPlayback) {
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
                playerController.verifyUsbBackgroundPlaybackHealth("service_guardian:$reason")
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

    /** USB 硬件 Feature Unit 使用 remote volume；软件音量保持本地 STREAM_MUSIC UI。 */
    fun activateUsbRemoteVolume(reason: String) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { activateUsbRemoteVolume(reason) }
            return
        }
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

        android.util.Log.w(
            "PlayerService",
            "activateUsbRemoteVolume: reason=$reason step=$currentStep"
        )
        session.setPlaybackToRemote(provider)
        session.isActive = true
        provider.syncFromController()
    }

    /** 关闭 USB 独占时，切回本地音量 */
    fun deactivateUsbRemoteVolume(reason: String) {
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { deactivateUsbRemoteVolume(reason) }
            return
        }
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
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { syncUsbMediaIdentity(song, playing, position, reason) }
            return
        }
        song?.let { newSong ->
            val changed = currentSong?.path != newSong.path ||
                currentSong?.title != newSong.title ||
                currentSong?.albumArtPath != newSong.albumArtPath ||
                currentSong?.duration != newSong.duration ||
                currentSong?.cueTrackIndex != newSong.cueTrackIndex
            currentSong = newSong
            if (changed) {
                invalidateLyricsForSongChange("usb_media_identity:$reason")
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
        if (playing) {
            val isProgressPulse = reason == "progress_update"
            requestServiceAudioFocus("pulse:$reason")
            acquireWakeLockIfNeeded()
            acquireWifiLockIfNeeded("pulse:$reason")
            ensureUsbForegroundThrottled(reason, force = !isProgressPulse)
            startPositionUpdates()
            syncUsbBackgroundGuardian("media_identity:$reason")
        } else {
            if (usbBackgroundKeepAliveLatched && AppPreferences.Player.usbExclusiveRequested) {
                // Recovery and track switches publish a short non-playing identity. Keep the
                // service-owned session alive until an explicit user pause/stop clears the latch.
                syncUsbBackgroundGuardian("media_identity_transient:$reason")
            } else {
                stopUsbBackgroundGuardian("media_identity_pause:$reason")
                releaseWifiLock("pulse_pause:$reason")
            }
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

    /** Clear every service-side lyric cache at the same boundary as a media identity change. */
    private fun invalidateLyricsForSongChange(reason: String) {
        colorOsLyricRetryJob?.cancel()
        colorOsLyricRetryJob = null
        _currentLyrics.value = null
        currentLyricsSongIdentity = null
        PlaybackTickerState.clear()
        liveLyricNotificationBridge.clear()
        Log.d("PlayerService", "lyrics invalidated: reason=$reason")
    }

    private fun clearUsbMediaIdentity(reason: String, releaseFocus: Boolean) {
        if (
            usbBackgroundKeepAliveLatched &&
            AppPreferences.Player.usbExclusiveRequested &&
            !reason.contains("detach", ignoreCase = true) &&
            !reason.contains("disable", ignoreCase = true) &&
            !reason.contains("stop_playback", ignoreCase = true)
        ) {
            Log.i("PlayerService", "USB media identity clear deferred by background owner: reason=$reason")
            syncUsbBackgroundGuardian("clear_deferred:$reason")
            return
        }
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
        val owner = playerServiceHandler
        if (owner != null && Looper.myLooper() != owner.looper) {
            owner.post { updateMediaSessionPlaybackStateForUsb(playing, reason) }
            return
        }
        val session = mediaSessionCompat ?: return
        val provider = usbVolumeProvider ?: return

        val controllerSong = playerController.currentSong.value
        val controllerState = playerController.playState.value
        val sustainedUsbPlayback = playerController.shouldSustainUsbBackgroundPlayback()
        val mayPublishPlaying = playing &&
            controllerSong != null &&
            (controllerState == PlayState.PLAYING || sustainedUsbPlayback)
        val hasMediaIdentity = controllerSong != null || currentSong != null
        val state = when {
            !hasMediaIdentity -> PlaybackStateCompat.STATE_NONE
            mayPublishPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, lastKnownPosition, if (mayPublishPlaying) 1.0f else 0.0f, SystemClock.elapsedRealtime())
            .setActions(actions)
            .build()

        val sessionOwnsMedia = hasMediaIdentity &&
            (mayPublishPlaying || controllerState == PlayState.PAUSED || controllerState == PlayState.PREPARING)
        session.isActive = sessionOwnsMedia
        session.setPlaybackState(playbackState)
        if (sessionOwnsMedia) {
            reassertUsbRemoteVolumeRoute("usb_playback_state:$reason")
        }
        provider.syncFromController()

        android.util.Log.i(
            "PlayerService",
            "MediaSession playbackState: requestedPlaying=$playing publishedPlaying=$mayPublishPlaying " +
                "reason=$reason controllerState=$controllerState hasMedia=$hasMediaIdentity " +
                "volume=${provider.currentVolume}",
        )
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
            positionUpdateJob = lifecycleScope.launch(Dispatchers.Default) {
                var slowTickElapsedMs = MEDIA_SESSION_PROGRESS_INTERVAL_MS
                while (isActive) {
                    val liveLyricEnabled = AppPreferences.Lyrics.liveUpdateLyricEnabled ||
                        AppPreferences.Lyrics.xiaomiSuperIslandLyricEnabled
                    val tickMs = if (liveLyricEnabled) LIVE_LYRIC_PROGRESS_INTERVAL_MS
                    else MEDIA_SESSION_PROGRESS_INTERVAL_MS
                    kotlinx.coroutines.delay(tickMs)
                    if (currentPlayState == PlayState.PLAYING) {
                        val elapsed = if (lastPositionTime > 0) SystemClock.elapsedRealtime() - lastPositionTime else 0L
                        val estimatedPosition = (lastKnownPosition + elapsed).coerceAtLeast(0L)
                        val duration = currentSong?.duration ?: 0L
                        if (duration > 0 && estimatedPosition <= duration) {
                            // High-rate external lyric publication is independent from MediaSession
                            // and widget progress. Do not drag their Binder/broadcast work onto every
                            // 220 ms lyric tick while Activity/RenderThread are trying to meet 120 Hz.
                            if (liveLyricEnabled) {
                                updateLiveLyricNotification(estimatedPosition)
                                publishSuperIslandLyric(estimatedPosition)
                            }

                            slowTickElapsedMs += tickMs
                            if (slowTickElapsedMs >= MEDIA_SESSION_PROGRESS_INTERVAL_MS) {
                                slowTickElapsedMs = 0L
                                // PlaybackStateCompat already carries position + speed +
                                // elapsedRealtime, so SystemUI advances position without a Binder
                                // pulse. reference player republishes on state/seek discontinuities instead.
                                // Keep only the widget's explicit progress broadcast on this tick.
                                notifyPlaybackWidgetProgress()
                            }
                        }
                    }
                }
            }
        }
    }

}
