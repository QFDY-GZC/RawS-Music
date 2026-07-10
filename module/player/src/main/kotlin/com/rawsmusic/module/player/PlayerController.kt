package com.rawsmusic.module.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.model.ShuffleMode
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.dsp.CompressorController
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.TrebleBoostController
import com.rawsmusic.module.player.dsp.Surround360Controller
import com.rawsmusic.module.player.dsp.Panoramic360Controller
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbDsdTransport
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities
import com.rawsmusic.module.player.usb.UsbPcmFormatCapability
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import com.rawsmusic.module.player.usb.UsbHardwareVolumeModel
import com.rawsmusic.module.player.usb.UsbVolumePath
import com.rawsmusic.module.player.usb.UsbLearnedPolicyStore
import com.rawsmusic.module.player.usb.UsbSilentKind
import com.rawsmusic.module.player.usb.UsbSelfTest
import com.rawsmusic.module.player.usb.UsbStreamRecoveryPlanner
import com.rawsmusic.module.player.usb.UsbRecoveryAction
import com.rawsmusic.module.player.usb.UsbRecoveryPlan
import com.rawsmusic.module.player.usb.UsbStatsSnapshot
import com.rawsmusic.module.player.usb.UsbExclusiveManager
import com.rawsmusic.module.player.usb.dsdMultiplierFromSourceRate
import com.rawsmusic.module.player.usb.dsdRateHzForMultiplier
import com.rawsmusic.module.player.usb.normalizeDsdSourceRateHz
import com.rawsmusic.module.player.usb.normalizeProbedDsdSourceRateHz
import com.rawsmusic.module.player.usb.buildSupportedPcmToDsdModeConfig
import com.rawsmusic.module.player.usb.buildSupportedDsdSourceDirectModeConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rawsmusic.module.player.statemachine.PlaybackStateMachine
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue.PlaybackEvent as PE
import com.rawsmusic.module.player.statemachine.PlaybackState as PBS

class PlayerController private constructor(context: Context) {

    private val context = context.applicationContext
    private val usbPlaybackWakeLock: PowerManager.WakeLock by lazy {
        (this.context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawSMusic:UsbExclusivePlayback").apply {
                setReferenceCounted(false)
            }
    }

    private fun acquireUsbPlaybackWakeLock(reason: String, timeoutMs: Long = 10 * 60 * 1000L) {
        try {
            if (!usbPlaybackWakeLock.isHeld) {
                usbPlaybackWakeLock.acquire(timeoutMs)
                AppLogger.i(TAG, "USB playback wakelock acquired: reason=$reason timeoutMs=$timeoutMs")
            } else {
                // Refresh timeout without releasing.
                usbPlaybackWakeLock.acquire(timeoutMs)
                AppLogger.i(TAG, "USB playback wakelock refreshed: reason=$reason timeoutMs=$timeoutMs")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "USB playback wakelock acquire failed: reason=$reason", t)
        }
    }

    private fun releaseUsbPlaybackWakeLock(reason: String) {
        try {
            if (usbPlaybackWakeLock.isHeld) {
                usbPlaybackWakeLock.release()
                AppLogger.i(TAG, "USB playback wakelock released: reason=$reason")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "USB playback wakelock release failed: reason=$reason", t)
        }
    }

    private var usbDetachRecoveryJob: Job? = null
    @Volatile
    private var appInBackground = false
    @Volatile
    private var appBackgroundEnteredAtElapsedMs = 0L
    @Volatile
    private var deferredUsbActivationDevice: UsbDevice? = null
    @Volatile
    private var deferredUsbActivationReason: String = ""
    @Volatile
    private var lastUsbBackgroundReinforceElapsedMs = 0L

    private fun shouldDeferUsbHardRecovery(reason: String): Boolean {
        if (!_usbExclusiveActive.value) return false
        val now = SystemClock.elapsedRealtime()
        val backgroundAgeMs =
            if (appBackgroundEnteredAtElapsedMs > 0L) {
                (now - appBackgroundEnteredAtElapsedMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }
        val recentlyBackgrounded = backgroundAgeMs in 0L..3000L
        val playingInBackground = appInBackground && _playState.value == PlayState.PLAYING
        val defer =
            playingInBackground ||
                recentlyBackgrounded ||
                transportTransitioning ||
                usbSeeking ||
                recoveringUsb.get()
        if (defer) {
            AppLogger.w(
                TAG,
                "Deferring destructive USB recovery: reason=$reason " +
                    "appInBackground=$appInBackground playingInBackground=$playingInBackground " +
                    "backgroundAgeMs=$backgroundAgeMs recentlyBackgrounded=$recentlyBackgrounded " +
                    "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking " +
                    "recovering=${recoveringUsb.get()}"
            )
        } else if (appInBackground) {
            AppLogger.w(
                TAG,
                "Allowing USB recovery in background: reason=$reason backgroundAgeMs=$backgroundAgeMs " +
                    "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking"
            )
        }
        return defer
    }

    private fun sendUsbMediaIdentityIntent(
        reason: String,
        song: AudioFile? = _currentSong.value,
        forcePosition: Long = _position.value,
        playing: Boolean = _playState.value == PlayState.PLAYING
    ) {
        try {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_USB_MEDIA_IDENTITY
                putExtra("reason", reason)
                song?.let { audio ->
                    putExtra("title", audio.title.ifBlank { audio.displayName })
                    putExtra("artist", audio.artist)
                    putExtra("album", audio.album)
                    putExtra("albumArtPath", audio.albumArtPath)
                    putExtra("duration", audio.duration)
                    putExtra("path", audio.path)
                }
                putExtra("position", forcePosition.coerceAtLeast(0L))
                putExtra("playing", playing)
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "sendUsbMediaIdentityIntent failed: reason=$reason", t)
        }
    }

    companion object {
        private const val TAG = "PlayerController"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        // USB exclusive software volume receives UI percent/index values, not
        // amplitude.  A cubic taper keeps the first few system volume steps very
        // quiet instead of mapping 1/15 to -23.5 dB.
        private const val USB_SOFTWARE_VOLUME_TAPER = 3.0f
        // v7i diagnostic policy: go back before TP55 learned/safe fallback loop.
        // Keep transport behavior explicit so root causes surface in logs instead
        // of being hidden by learned no-feedback/safe-alt profiles.
        private const val USB_EXPOSE_PRE_TP55_POLICY = true

        @Volatile
        private var instance: PlayerController? = null

        fun getInstance(context: Context): PlayerController {
            return instance ?: synchronized(this) {
                instance ?: PlayerController(context.applicationContext).also {
                    instance = it
                    PlayerRuntimeRegistry.attachController(it, "controller_singleton_create")
                    Log.i(TAG, "PlayerController singleton created: ${System.identityHashCode(it)}")
                }
            }
        }

        @JvmStatic
        fun getInstanceOrNull(): PlayerController? = instance
    }

    // ======================== USB critical startup window ========================
    @Volatile
    private var usbCriticalStartupUntil = 0L
    @Volatile
    private var lastUsbRemoteVolumeDesired: Boolean? = null
    @Volatile
    private var ignoreSystemVolumeObserverUntilMs: Long = 0L
    @Volatile
    private var suppressSystemVolumeObserverUntilMs: Long = 0L

    private val usbNoDataSafeDb = -35
    private val usbNoDataSafeLinear: Float
        get() = 10.0.pow(usbNoDataSafeDb / 20.0).toFloat()

    fun enterUsbCriticalStartup(reason: String, ms: Long = 2500L) {
        usbCriticalStartupUntil = SystemClock.elapsedRealtime() + ms
        AppLogger.i(TAG, "enterUsbCriticalStartup: reason=$reason ms=$ms")
    }

    fun isUsbCriticalStartup(): Boolean {
        return SystemClock.elapsedRealtime() < usbCriticalStartupUntil
    }

    private fun applyUsbNoDataSafetyVolume(reason: String) {
        if (!_usbExclusiveActive.value) return
        val handle = sharedUsbAudioEngine.currentHandle
        val safeLinear = usbNoDataSafeLinear
        val volumePath = resolveCurrentUsbOutputProfile()?.volumePath
        val hardwareRoute = volumePath == UsbVolumePath.HardwareUserVolume

        AppLogger.i(
            TAG,
            "applyUsbNoDataSafetyVolume: reason=$reason handle=0x${handle.toString(16)} " +
                "safeDb=${usbNoDataSafeDb} safeLinear=$safeLinear hardwareRoute=$hardwareRoute " +
                "volumePath=$volumePath"
        )

        if (hardwareRoute && handle != 0L && sharedUsbAudioEngine.nativeCanControlVolume(handle)) {
            // Transient safety must not overwrite the user's requested hardware
            // volume cache.  Otherwise resume/start restores to -35dB and then
            // jumps back when Kotlin reapplies the route.
            val targetDb = usbHwStepToDb(currentUsbHwStep())
            val safeDb = minOf(targetDb, usbNoDataSafeDb)
            sharedUsbAudioEngine.setTransientHardwareVolumeDb(safeDb, reason)
        } else {
            sharedUsbAudioEngine.nativeSetUsbSoftwareGain(safeLinear)
        }
    }


    private fun isUsbHardwareVolumeRouteActive(): Boolean {
        if (!_usbExclusiveActive.value) return false
        return resolveCurrentUsbOutputProfile()?.volumePath == UsbVolumePath.HardwareUserVolume
    }

    private fun usbWarmPauseSafeDb(): Int {
        val userDb = usbHwStepToDb(currentUsbHwStep())
        // Never make pause louder than the user's current hardware volume.
        return minOf(userDb, usbNoDataSafeDb)
    }

    private fun ensureUsbMediaIdentity(
        reason: String,
        song: AudioFile? = _currentSong.value,
        forcePosition: Long = _position.value
    ) {
        if (!_usbExclusiveActive.value) return
        val playing = _playState.value == PlayState.PLAYING
        val serviceAlive = PlayerService.syncUsbMediaIdentityFromController(
            song = song,
            playing = playing,
            position = forcePosition.coerceAtLeast(0L),
            reason = reason
        )
        if (!serviceAlive) {
            sendUsbMediaIdentityIntent(reason, song, forcePosition, playing = playing)
        } else if (reason.contains("heartbeat").not()) {
            sendUsbMediaIdentityIntent(reason, song, forcePosition, playing = playing)
        }
        AppLogger.i(TAG, "ensureUsbMediaIdentity: reason=$reason pos=$forcePosition title=${song?.title} serviceAlive=$serviceAlive")
    }


    /**
     * Hardware Feature Unit ramp used only for transient transition envelopes.
     * Intermediate writes are no-cache so they do not replace the user's stored
     * hardware volume.  The final resume write may update native's cache back to
     * the real user volume.
     */
    private suspend fun rampUsbHardwareVolumeDb(
        fromDb: Int,
        toDb: Int,
        reason: String,
        stepDelayMs: Long = 8L,
        cacheFinal: Boolean = false
    ) {
        if (!isUsbHardwareVolumeRouteActive()) return
        val handle = sharedUsbAudioEngine.currentHandle
        if (handle == 0L || !sharedUsbAudioEngine.nativeCanControlVolume(handle)) return

        val start = fromDb.coerceIn(USB_HW_MIN_DB, USB_HW_MAX_DB)
        val end = toDb.coerceIn(USB_HW_MIN_DB, USB_HW_MAX_DB)
        val direction = when {
            end > start -> 1
            end < start -> -1
            else -> 0
        }
        AppLogger.i(TAG, "USB HW transient ramp: reason=$reason from=${start}dB to=${end}dB cacheFinal=$cacheFinal")
        if (direction == 0) {
            if (cacheFinal) {
                sharedUsbAudioEngine.nativeSetHardwareVolumeDb(handle, end)
            } else {
                sharedUsbAudioEngine.setTransientHardwareVolumeDb(end, reason)
            }
            return
        }

        var db = start
        while (db != end && !isReleased && isUsbHardwareVolumeRouteActive()) {
            sharedUsbAudioEngine.setTransientHardwareVolumeDb(db, "$reason/ramp")
            db += direction
            delay(stepDelayMs)
        }
        if (cacheFinal) {
            sharedUsbAudioEngine.nativeSetHardwareVolumeDb(handle, end)
        } else {
            sharedUsbAudioEngine.setTransientHardwareVolumeDb(end, "$reason/final")
        }
    }

    /**
     * 音频会话变更回调 — 当播放器重建导致 audioSessionId 变化时触发
     * 外部（如 EqualizerViewModel）应监听此回调并重新初始化音效引擎
     */
    var onAudioSessionChanged: ((newSessionId: Int) -> Unit)? = null
    var onPcmWaveformFrame: ((buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) -> Unit)? = null

    // FFmpeg + AudioTrack 播放器
    private var ffmpegPlayer = FfmpegAudioPlayer(context)
    val ffmpegPlayerRef: FfmpegAudioPlayer get() = ffmpegPlayer

    init {
        ffmpegPlayer.onDspEngineReinit = {
            restoreStereoWidenSettings()
            ensurePEQConnected()
            ensureCompressorConnected()
            ensureBassBoostConnected()
            ensureTrebleBoostConnected()
            ensureSurround360Connected()
            ensurePanoramic360Connected()
            restoreCrossfeedSettings()
        }
    }

    // PEQ 控制器单例（延迟初始化，首次访问时从 DSP 引擎创建）
    private var _peqController: ParametricEQController? = null
    val peqController: ParametricEQController
        get() {
            if (_peqController == null) {
                val engine = ffmpegPlayer.dspEngine
                _peqController = if (engine != null) {
                    ParametricEQController(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for PEQ, creating stub")
                    ParametricEQController(NativeDSPEngine())
                }
            }
            return _peqController!!
        }

    /**
     * 确保 PEQ 控制器已连接到实际的 DSP 引擎
     * 在进入 PEQ 界面时调用，将 stub 引擎替换为已初始化的真实引擎
     */
    fun ensurePEQConnected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            val controller = _peqController ?: ParametricEQController(engine).also {
                _peqController = it
            }
            controller.connectEngine(engine)
        }
    }

    // ========== 压限器控制器 ==========
    private var _compressorController: CompressorController? = null
    val compressorController: CompressorController
        get() {
            if (_compressorController == null) {
                val engine = ffmpegPlayer.dspEngine
                _compressorController = if (engine != null) {
                    CompressorController(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for Compressor, creating stub")
                    CompressorController(NativeDSPEngine())
                }
            }
            return _compressorController!!
        }

    fun ensureCompressorConnected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            if (_compressorController == null) {
                _compressorController = CompressorController(engine)
            } else {
                _compressorController!!.connectEngine(engine)
            }
        }
    }

    // ========== 低音增强控制器 ==========
    private var _bassBoostController: BassBoostController? = null
    val bassBoostController: BassBoostController
        get() {
            if (_bassBoostController == null) {
                val engine = ffmpegPlayer.dspEngine
                _bassBoostController = if (engine != null) {
                    BassBoostController(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for BassBoost, creating stub")
                    BassBoostController(NativeDSPEngine())
                }
            }
            return _bassBoostController!!
        }

    fun ensureBassBoostConnected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            if (_bassBoostController == null) {
                _bassBoostController = BassBoostController(engine)
            } else {
                _bassBoostController!!.connectEngine(engine)
            }
        }
    }

    // ========== 高音增强控制器 ==========
    private var _trebleBoostController: TrebleBoostController? = null
    val trebleBoostController: TrebleBoostController
        get() {
            if (_trebleBoostController == null) {
                val engine = ffmpegPlayer.dspEngine
                _trebleBoostController = if (engine != null) {
                    TrebleBoostController(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for TrebleBoost, creating stub")
                    TrebleBoostController(NativeDSPEngine())
                }
            }
            return _trebleBoostController!!
        }

    fun ensureTrebleBoostConnected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            if (_trebleBoostController == null) {
                _trebleBoostController = TrebleBoostController(engine)
            } else {
                _trebleBoostController!!.connectEngine(engine)
            }
        }
    }

    // ========== 360° 环绕音控制器 ==========
    private var _surround360Controller: Surround360Controller? = null
    val surround360Controller: Surround360Controller
        get() {
            if (_surround360Controller == null) {
                val engine = ffmpegPlayer.dspEngine
                _surround360Controller = if (engine != null) {
                    Surround360Controller(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for Surround360, creating stub")
                    Surround360Controller(NativeDSPEngine())
                }
            }
            return _surround360Controller!!
        }

    fun ensureSurround360Connected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            if (_surround360Controller == null) {
                _surround360Controller = Surround360Controller(engine)
            } else {
                _surround360Controller!!.connectEngine(engine)
            }
        }
    }

    // ========== 360° 全景音控制器 ==========
    private var _panoramic360Controller: Panoramic360Controller? = null
    val panoramic360Controller: Panoramic360Controller
        get() {
            if (_panoramic360Controller == null) {
                val engine = ffmpegPlayer.dspEngine
                _panoramic360Controller = if (engine != null) {
                    Panoramic360Controller(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for Panoramic360, creating stub")
                    Panoramic360Controller(NativeDSPEngine())
                }
            }
            return _panoramic360Controller!!
        }

    fun ensurePanoramic360Connected() {
        val engine = ffmpegPlayer.dspEngine
        if (engine != null && engine.isInitialized()) {
            if (_panoramic360Controller == null) {
                _panoramic360Controller = Panoramic360Controller(engine)
            } else {
                _panoramic360Controller!!.connectEngine(engine)
            }
        }
    }

    val usbExclusiveManager = UsbExclusiveManager(context)
    private val sharedUsbAudioEngine = UsbAudioEngine
    private val usbSystemAudioKeepAlive = AndroidAudioIdentityTrack(context)
    private var currentUsbDevice: UsbDevice? = null
    private val _usbExclusiveActive = MutableStateFlow(false)
    val usbExclusiveActive: StateFlow<Boolean> = _usbExclusiveActive.asStateFlow()

    /** 渲染切换保护标志 — USB切换到AudioTrack时设置，防止立即播放导致资源冲突 */
    private val _isRenderSwitching = MutableStateFlow(false)
    val isRenderSwitching: StateFlow<Boolean> = _isRenderSwitching.asStateFlow()

    private val _usbOutputSampleRate = MutableStateFlow(0)
    private val _usbCapabilities = MutableStateFlow<com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?>(null)
    val usbCapabilities: StateFlow<com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?> = _usbCapabilities.asStateFlow()
    val usbOutputSampleRate: StateFlow<Int> = _usbOutputSampleRate.asStateFlow()

    /** USB 设备硬件音量 Feature Unit 信息（供 UI 提示） */
    val usbVolumeInfo: StateFlow<UsbExclusiveManager.VolumeInfo?> = usbExclusiveManager.volumeInfo

    private val _playState = MutableStateFlow(PlayState.IDLE)
    val playState: StateFlow<PlayState> = _playState.asStateFlow()

    /** Playback state machine — guards illegal state transitions. */
    private val stateMachine = PlaybackStateMachine()

    /** Helper: transition state machine + sync StateFlow. Returns false if illegal. */
    private fun smTransition(target: PlayState, tag: String = ""): Boolean {
        val pbsTarget = when (target) {
            PlayState.IDLE -> PBS.IDLE
            PlayState.PREPARING -> PBS.PREPARING
            PlayState.PLAYING -> PBS.PLAYING
            PlayState.PAUSED -> PBS.PAUSED
            PlayState.STOPPED -> PBS.STOPPED
            PlayState.ERROR -> PBS.ERROR
        }
        val ok = stateMachine.transition(pbsTarget, tag)
        if (ok) {
            _playState.value = target
            syncUsbSystemAudioKeepAlive("smTransition:$tag")
        }
        return ok
    }

    /** Helper: force transition (bypass legality) for recovery paths. */
    private fun smForceTransition(target: PlayState, tag: String = "") {
        val pbsTarget = when (target) {
            PlayState.IDLE -> PBS.IDLE
            PlayState.PREPARING -> PBS.PREPARING
            PlayState.PLAYING -> PBS.PLAYING
            PlayState.PAUSED -> PBS.PAUSED
            PlayState.STOPPED -> PBS.STOPPED
            PlayState.ERROR -> PBS.ERROR
        }
        stateMachine.forceTransition(pbsTarget, tag)
        _playState.value = target
        syncUsbSystemAudioKeepAlive("smForceTransition:$tag")
    }

    private fun syncUsbSystemAudioKeepAlive(reason: String) {
        val nativeStreamStateName = runCatching { sharedUsbAudioEngine.getNativeStreamState().name }.getOrNull().orEmpty()
        val shouldRun =
            _usbExclusiveActive.value &&
                ffmpegPlayer.usbExclusiveMode &&
                _playState.value == PlayState.PLAYING

        // USB 独占后台播放时，恢复流程会短暂 force PREPARING / PAUSED / recover_final_stop。
        // 这些过渡不是用户停止播放，不能把 AndroidAudioIdentity 和 native background guard 关掉。
        //
        // 之前日志里出现：
        //   AndroidAudioIdentity native stopped: reason=smForceTransition:recover_final_stop
        //   nativeSetBackgroundPlaybackActive: 0
        // 但 USB stats 仍然 completed=expected、underrun=0，说明真实 USB 流还健康。
        // 这时如果撤掉系统媒体身份/后台 USB guard，ColorOS/Hans/AudioHardening 仍可能把后续音频路径静音。
        val holdDuringUsbTransient =
            shouldHoldUsbIdentityDuringTransient(reason, nativeStreamStateName)

        if (shouldRun || holdDuringUsbTransient) {
            if (shouldAssertUsbBackgroundGuard(reason, holdDuringUsbTransient)) {
                sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(true, "syncUsbSystemAudioKeepAlive:$reason")
            }
            usbSystemAudioKeepAlive.start(
                if (holdDuringUsbTransient) "transient_usb_identity_hold:$reason" else reason
            )
        } else {
            usbSystemAudioKeepAlive.stop(reason)
            if (shouldReleaseUsbBackgroundGuard()) {
                sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(false, "syncUsbSystemAudioKeepAlive:$reason")
            }
        }
    }

    private fun shouldAssertUsbBackgroundGuard(
        reason: String,
        transientHold: Boolean
    ): Boolean {
        if (isReleased || !_usbExclusiveActive.value || !ffmpegPlayer.usbExclusiveMode) return false
        if (transientHold) return true
        if (appInBackground) return true
        val r = reason.lowercase()
        return r.contains("background") ||
            r.contains("guardian") ||
            r.contains("progress_update") ||
            r.contains("media_identity") ||
            r.contains("recover")
    }

    private fun shouldReleaseUsbBackgroundGuard(): Boolean {
        if (isReleased || !_usbExclusiveActive.value || !ffmpegPlayer.usbExclusiveMode) return true

        // 播放意图仍然存在时不要关闭 native background guard。
        // 前台/后台切换、recover_final_stop、pause_warm 都可能短暂让 _playState 不是 PLAYING，
        // 但 ffmpeg/native USB 仍在继续送包。
        if (_playState.value == PlayState.PLAYING ||
            _playState.value == PlayState.PREPARING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
            return false
        }

        val nativeStreamStateName = runCatching { sharedUsbAudioEngine.getNativeStreamState().name }.getOrNull().orEmpty()
        if (nativeStreamStateName == "STREAMING" || nativeStreamStateName == "STARTING") {
            return false
        }

        return true
    }

    private fun shouldHoldUsbIdentityDuringTransient(
        reason: String,
        nativeStreamStateName: String
    ): Boolean {
        if (isReleased || !_usbExclusiveActive.value || !ffmpegPlayer.usbExclusiveMode) return false

        val r = reason.lowercase()
        val transientReason =
            r.contains("recover") ||
                r.contains("pause_warm") ||
                r.contains("preparing") ||
                r.contains("progress_update") ||
                r.contains("media_identity") ||
                r.contains("guardian")

        if (!transientReason) return false

        val playIntentAlive =
            appInBackground ||
                _playState.value == PlayState.PLAYING ||
                _playState.value == PlayState.PREPARING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING ||
                nativeStreamStateName == "STREAMING" ||
                nativeStreamStateName == "STARTING"

        return playIntentAlive
    }

    private val _currentSong = MutableStateFlow<AudioFile?>(null)
    val currentSong: StateFlow<AudioFile?> = _currentSong.asStateFlow()

    fun updateCurrentSongIfSamePath(song: AudioFile) {
        if (_currentSong.value?.path == song.path && _currentSong.value?.cueOffsetMs == song.cueOffsetMs && _currentSong.value?.cueTrackIndex == song.cueTrackIndex) {
            _currentSong.value = song
            val q = _queue.value
            val idx = q.songs.indexOfFirst { it.path == song.path && it.cueOffsetMs == song.cueOffsetMs && it.cueTrackIndex == song.cueTrackIndex }
            if (idx >= 0) {
                val newList = q.songs.toMutableList()
                newList[idx] = song
                _queue.value = q.copy(songs = newList)
            }
        }
    }

    private val _queue = MutableStateFlow(PlayQueue())
    val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    val outputLatencyMs: Int
        get() = ffmpegPlayer.latencyMs

    val lyricManualOffsetMs: Int
        get() = AppPreferences.Lyrics.latencyOffset

    @Volatile
    var isBluetoothOutput: Boolean = false
        private set
    @Volatile
    private var lastDetectedCodecType: Int = -1
    private var bluetoothLatencyJob: Job? = null

    // 蓝牙 HFP-only 设备检测状态
    private val _hfpOnlyDeviceDetected = MutableStateFlow(false)
    val hfpOnlyDeviceDetected: StateFlow<Boolean> = _hfpOnlyDeviceDetected.asStateFlow()

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemaining = MutableStateFlow(0L)
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining.asStateFlow()
    private var sleepTimerEndTime: Long = 0L
    private var stopAfterCurrentSong: Boolean = false
    private var songsUntilStop: Int = 0

    // 缓存 A2DP 代理，避免重复获取
    private var cachedA2dpProxy: android.bluetooth.BluetoothProfile? = null
    private var a2dpProxyListener: android.bluetooth.BluetoothProfile.ServiceListener? = null

    private fun startBluetoothLatencyMonitor() {
        bluetoothLatencyJob?.cancel()
        bluetoothLatencyJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Bluetooth latency monitor started")
            while (isActive) {
                try {
                    checkBluetoothOutput()
                } catch (e: Exception) {
                    Log.e(TAG, "checkBluetoothOutput error: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private suspend fun checkBluetoothOutput() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val isBluetooth = if (android.os.Build.VERSION.SDK_INT >= 23) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btDevice = devices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == 26
            }
            if (btDevice != null) {
                Log.d(TAG, "BT device found: type=${btDevice.type} name=${btDevice.productName}")
            }
            btDevice != null
        } else false

        if (isBluetooth != isBluetoothOutput) {
            isBluetoothOutput = isBluetooth
            if (isBluetooth) {
                val codecType = detectBluetoothCodecTypeAsync()
                lastDetectedCodecType = codecType
                Log.d(TAG, "BT connected, codecType=$codecType (${codecTypeName(codecType)}), AudioTrack latency=${ffmpegPlayer.latencyMs}ms")

                // 蓝牙 SCO 自动检测逻辑
                val scoMode = AppPreferences.Player.bluetoothScoMode
                if (scoMode == 1) {  // 自动检测模式
                    val isHfpOnly = AudioOutputManager.isBluetoothHfpOnlyDevice(context)
                    if (isHfpOnly) {
                        Log.i(TAG, "HFP-only device detected, SCO mode will be activated on next playback")
                        // 标记需要在下次播放时启用 SCO
                        _hfpOnlyDeviceDetected.value = true
                    }
                }
            } else {
                lastDetectedCodecType = -1
                releaseA2dpProxy()
                _hfpOnlyDeviceDetected.value = false
                Log.d(TAG, "BT disconnected, using AudioTrack latency")

                // 停止 SCO 连接
                stopBluetoothSco()

                // 蓝牙断开后，如果 AudioTrack 仍使用 SCO 属性（USAGE_VOICE_COMMUNICATION），
                // 需要重建为 USAGE_MEDIA 属性，否则扬声器无法播放
                val playerState = ffmpegPlayer.state
                if (playerState == FfmpegAudioPlayer.State.PLAYING || playerState == FfmpegAudioPlayer.State.PAUSED) {
                    Log.i(TAG, "BT disconnected, rebuilding AudioTrack with MEDIA attributes, state=$playerState")
                    scope.launch(Dispatchers.Main) {
                        try {
                            ffmpegPlayer.rebuildAudioTrackForScoDisconnected()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to rebuild AudioTrack after BT disconnect: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun releaseA2dpProxy() {
        try {
            val proxy = cachedA2dpProxy
            val listener = a2dpProxyListener
            if (proxy != null && listener != null) {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                adapter?.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, proxy)
            }
        } catch (_: Exception) {}
        cachedA2dpProxy = null
        a2dpProxyListener = null
    }

    private suspend fun detectBluetoothCodecTypeAsync(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    Log.w(TAG, "detectCodec: BluetoothAdapter is null")
                    return@withContext -1
                }

                // 优先使用缓存的代理
                var a2dp = cachedA2dpProxy
                if (a2dp == null) {
                    val profileProxy = arrayOfNulls<android.bluetooth.BluetoothProfile>(1)
                    val lock = java.util.concurrent.CountDownLatch(1)

                    val listener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            profileProxy[0] = proxy
                            lock.countDown()
                        }
                        override fun onServiceDisconnected(profile: Int) {
                            lock.countDown()
                        }
                    }

                    adapter.getProfileProxy(context, listener, android.bluetooth.BluetoothProfile.A2DP)

                    // 缩短超时到 1 秒，减少阻塞
                    if (!lock.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "detectCodec: A2DP profile proxy timeout (1s)")
                        return@withContext -1
                    }

                    a2dp = profileProxy[0]
                    if (a2dp != null) {
                        cachedA2dpProxy = a2dp
                        a2dpProxyListener = listener
                    }
                }

                if (a2dp == null) {
                    Log.w(TAG, "detectCodec: A2DP proxy is null")
                    return@withContext -1
                }

                val connectedDevices = a2dp.connectedDevices
                if (connectedDevices.isNullOrEmpty()) {
                    Log.w(TAG, "detectCodec: no connected A2DP devices")
                    return@withContext -1
                }

                val codecType = try {
                    val method = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                    val codecStatus = method.invoke(a2dp, connectedDevices[0])
                    if (codecStatus != null) {
                        val getConfig = codecStatus.javaClass.getMethod("getCodecConfig")
                        val codecConfig = getConfig.invoke(codecStatus)
                        if (codecConfig != null) {
                            val getType = codecConfig.javaClass.getMethod("getCodecType")
                            getType.invoke(codecConfig) as? Int ?: -1
                        } else -1
                    } else -1
                } catch (e: Exception) {
                    Log.w(TAG, "detectCodec: getCodecStatus failed: ${e.message}")
                    -1
                }

                Log.d(TAG, "detectCodec: result=$codecType (${codecTypeName(codecType)})")
                codecType
            } catch (e: Exception) {
                Log.e(TAG, "detectCodec: unexpected error: ${e.message}")
                -1
            }
        }
    }

    private fun codecTypeName(codecType: Int): String = when (codecType) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LHDC"
        6 -> "LC3"
        7 -> "aptX Adaptive"
        8 -> "LHDC V5"
        1000 -> "LHDC"
        else -> "Unknown"
    }

    fun getBluetoothLatencyInfo(): String {
        if (!isBluetoothOutput) return ""
        val codecName = codecTypeName(lastDetectedCodecType)
        val trackLatency = ffmpegPlayer.latencyMs
        return if (lastDetectedCodecType >= 0) "$codecName" else "BT ${trackLatency}ms"
    }

    private val _repeatMode = MutableStateFlow(AppPreferences.Player.repeatMode)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(AppPreferences.Player.isShuffle)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _playMode = MutableStateFlow(AppPreferences.Player.playMode)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private var progressJob: Job? = null
    private var usbStartupVolumeJob: Job? = null
    private var usbSelfTestJob: Job? = null
    @Volatile
    private var lastUsbSelfTestSessionKey: String = ""
    private val usbSelfTestEpoch = AtomicLong(0L)
    @Volatile
    private var lastAcceptedUsbSessionId: Long = 0L
    @Volatile
    private var lastAcceptedUsbRuntimeKey: String? = null
    @Volatile
    private var usbStartupVolumeGuardUntilMs: Long = 0L
    @Volatile
    private var explicitUsbExclusiveSoftwareMuteThisProcess = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serialized playback event queue (PlayOpEventHandlerThread). */
    private val eventQueue = PlaybackEventQueue()

    init {
        eventQueue.start(scope)
        scope.launch(Dispatchers.IO) {
            startBluetoothLatencyMonitor()
        }
    }

    private var isReleased = false
    private var consecutiveFailures = 0
    private var enrichJob: Job? = null
    @Volatile
    private var lastPlayerError: String? = null
    private val transportMutex = Mutex()

    /** 均衡器控制器（含立体声扩展 Virtualizer） */
    var equalizerController: EqualizerController? = null
        private set
    /** 播放历史栈，用于上一首回退 */
    private val playHistory = ArrayDeque<AudioFile>()

    /** 优先播放队列 — 队列歌曲优先于"下一首播放" */
    private val priorityQueue = ArrayDeque<AudioFile>()

    /** shuffle 前保存原始队列顺序，关闭 shuffle 时恢复 */
    private var queueBeforeShuffle: List<AudioFile> = emptyList()
    /** 当前随机播放队列（仅包含参与 shuffle 的索引，排除自动播放内容） */
    private val shuffleIndices = mutableListOf<Int>()
    /** 已播放的随机索引历史，用于"上一首"回退 */
    private val shuffleHistory = ArrayDeque<Int>()
    /** 随机数生成器 */
    private val shuffleRandom = java.util.Random()

    /** 回放增益音量系数 — 由 applyReplayGain() 计算后与用户音量合成 */
    private var replayGainVolumeModifier = 1.0f
    private val recoveringUsb = java.util.concurrent.atomic.AtomicBoolean(false)
    private val usbExclusiveFullRecoveryAttemptsMs = java.util.ArrayDeque<Long>()
    @Volatile
    private var stickyUsbHardwareVolumeValidated = false
    private var wasPlayingBeforeFocusLoss = false
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var audioFocusStartupGraceUntilMs = 0L
    /** User-initiated cold-start play window. Some OPlus/ColorOS builds dispatch a full LOSS
     * immediately after the first AudioTrack/MediaSession update even though our session just
     * became PLAYING. Treat that as stale only inside this short window. */
    private var userPlayStartFocusGuardUntilMs = 0L
    /** 音频焦点是否已获得 */
    private var audioFocusGranted = false
    /** Duck 模式音量因子 — AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK 时降低到 20% */
    private var duckVolumeFactor = 1.0f

    // NoisyAudio 耳机拔出检测 — 耳机拔出瞬间自动暂停
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                Log.w(TAG, "NoisyAudio: headphone unplugged, pausing playback and repairing speaker route")
                pause()
                // Some Android 16/HyperOS builds leave the current AudioTrack bound to
                // the removed wired/USB route.  A USB attach->deny/no-auth->detach bounce
                // can make speaker audio come back because AudioPolicy re-routes the track.
                // Do that explicitly so external speaker does not remain silent.
                scope.launch(Dispatchers.Main) {
                    delay(220)
                    ffmpegPlayer.repairAndroidOutputRouteAfterDeviceChange("audio_becoming_noisy", forceRebuild = true)
                }
            }
        }
    }
    private var noisyRegistered = false

    // 蓝牙 SCO 状态监听
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED == intent.action) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d(TAG, "SCO state changed: $state")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        _scoConnected.value = true
                        Log.i(TAG, "SCO connected, isPlayingNow=${ffmpegPlayer.isPlayingNow}, state=${ffmpegPlayer.state}")
                        // SCO 连接成功后，重建 AudioTrack 以确保音频路由到 SCO 通道
                        // 不检查 isPlayingNow，因为 rebuildAudioTrackForSco 内部会检查 _state
                        if (AudioOutputManager.shouldUseScoMode(context)) {
                            scope.launch(Dispatchers.Main) {
                                try {
                                    ffmpegPlayer.rebuildAudioTrackForSco()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to rebuild AudioTrack for SCO: ${e.message}")
                                }
                            }
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        _scoConnected.value = false
                        Log.i(TAG, "SCO disconnected, rebuilding AudioTrack with MEDIA attributes")
                        // SCO 断开后重建 AudioTrack，切回 USAGE_MEDIA 属性
                        // 否则 AudioTrack 仍使用 VOICE_COMMUNICATION + SCO preferredDevice，导致无声
                        scope.launch(Dispatchers.Main) {
                            try {
                                ffmpegPlayer.rebuildAudioTrackForScoDisconnected()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to rebuild AudioTrack after SCO disconnect: ${e.message}")
                            }
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        Log.d(TAG, "SCO connecting...")
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        _scoConnected.value = false
                        Log.e(TAG, "SCO error")
                    }
                }
            }
        }
    }
    private var scoReceiverRegistered = false
    private val _scoConnected = MutableStateFlow(false)
    val scoConnected: StateFlow<Boolean> = _scoConnected.asStateFlow()

    private var lastPlayRequestPath: String? = null
    private var lastPlayRequestCueOffset: Long = 0L
    private var lastPlayRequestCueIndex: Int = 0
    private var lastPlayRequestTime = 0L
    private val latestPlayRequestToken = AtomicLong(0L)
    private var seekJustPerformed = false

    /** 切歌/暂停进行中，禁止 applyUsbVolume 写入 */
    @Volatile
    private var transportTransitioning = false

    /** Track switch boundary: keep Feature Unit at transient safe level until new PCM is really flowing. */
    @Volatile
    private var usbTrackSwitchVolumeHoldUntilMs = 0L
    @Volatile
    private var usbTrackSwitchVolumeHoldReason: String = ""
    private var cachedCueOffsetMs = 0L
    private var cachedCueEndMs = 0L
    private var cachedSongDuration = 0L
    /** 恢复播放位置：当 restoreLastSong() 恢复了位置后，在下次播放同一首歌时自动 seek */
    private var pendingSeekPosition: Long = -1L
    private var pendingSeekPath: String? = null
    private var pendingRestoreSeekJob: kotlinx.coroutines.Job? = null

    // USB policy restart deferral: self-test/learned-policy should not hard-stop
    // playback while streaming.  Mark dirty and apply on next legitimate restart.
    @Volatile
    private var pendingUsbPolicyRestart = false
    @Volatile
    private var pendingUsbRecoveryPlan: UsbRecoveryPlan? = null

    // Feedback model cache.  This is not a VID/PID quirk: when the
    // current transport proves that its explicit feedback endpoint is unusable,
    // subsequent prepares for the same transport are modeled as no-feedback from
    // the beginning.  A healthy explicit-feedback session may clear it, and a
    // device/transport change naturally invalidates it.
    @Volatile
    private var usbFeedbackRejectedTransportKey: String? = null
    @Volatile
    private var usbFeedbackRejectedReason: String = ""

    private var lastUsbOutputApplyMs = 0L
    private var lastUsbAppliedKey: String? = null

    /** 取消旧的 pending restore seek，手动切歌时必须调用 */
    private fun cancelPendingRestoreSeek(reason: String) {
        if (pendingSeekPosition > 0) {
            AppLogger.i(TAG, "cancelPendingRestoreSeek: reason=$reason oldPos=$pendingSeekPosition oldPath=$pendingSeekPath")
        }
        pendingRestoreSeekJob?.cancel()
        pendingRestoreSeekJob = null
        pendingSeekPosition = -1L
        pendingSeekPath = null
    }

    private fun queueRendererRestartSeek(song: AudioFile, displayPositionMs: Long, reason: String) {
        cancelPendingRestoreSeek(reason)
        val realPositionMs = if (song.cueOffsetMs > 0L) {
            song.cueOffsetMs + displayPositionMs.coerceAtLeast(0L)
        } else {
            displayPositionMs.coerceAtLeast(0L)
        }
        ffmpegPlayer.queueStartSeekPosition(
            positionMs = if (realPositionMs > 0L) realPositionMs else -1L,
            reason = reason
        )
        AppLogger.i(
            TAG,
            "queueRendererRestartSeek: reason=$reason song=${song.title} display=$displayPositionMs real=$realPositionMs cue=${song.cueOffsetMs}"
        )
    }

    private fun clearPlayRequestDedup(reason: String) {
        if (lastPlayRequestPath != null || lastPlayRequestTime != 0L) {
            AppLogger.i(
                TAG,
                "clearPlayRequestDedup: reason=$reason oldPath=$lastPlayRequestPath " +
                    "oldCueOffset=$lastPlayRequestCueOffset oldCueIndex=$lastPlayRequestCueIndex " +
                    "oldTime=$lastPlayRequestTime"
            )
        }
        lastPlayRequestPath = null
        lastPlayRequestCueOffset = 0L
        lastPlayRequestCueIndex = 0
        lastPlayRequestTime = 0L
    }


    private fun armUsbTrackSwitchVolumeHold(reason: String, holdMs: Long = 2200L) {
        usbTrackSwitchVolumeHoldReason = reason
        usbTrackSwitchVolumeHoldUntilMs = SystemClock.elapsedRealtime() + holdMs
        AppLogger.w(TAG, "USB track-switch volume hold armed: reason=$reason holdMs=$holdMs")
    }

    private fun isUsbTrackSwitchVolumeHeld(): Boolean {
        return SystemClock.elapsedRealtime() < usbTrackSwitchVolumeHoldUntilMs
    }

    private fun clearUsbTrackSwitchVolumeHold(reason: String) {
        if (usbTrackSwitchVolumeHoldUntilMs > 0L || usbTrackSwitchVolumeHoldReason.isNotBlank()) {
            AppLogger.i(TAG, "USB track-switch volume hold cleared: reason=$reason oldReason=$usbTrackSwitchVolumeHoldReason")
        }
        usbTrackSwitchVolumeHoldUntilMs = 0L
        usbTrackSwitchVolumeHoldReason = ""
    }

    private fun canUseUsbSoftNextFor(nextSong: AudioFile): Boolean {
        val cur = _currentSong.value ?: return false
        if (!_usbExclusiveActive.value || _playState.value != PlayState.PLAYING) return false
        if (!sharedUsbAudioEngine.isRunning()) return false
        val sameRate = cur.sampleRate > 0 && nextSong.sampleRate > 0 && cur.sampleRate == nextSong.sampleRate
        val sameBits = cur.bitsPerSample > 0 && nextSong.bitsPerSample > 0 && cur.bitsPerSample == nextSong.bitsPerSample
        val sameChannels = cur.channelCount > 0 && nextSong.channelCount > 0 && cur.channelCount == nextSong.channelCount
        val profile = resolveCurrentUsbOutputProfile()
        val sameDeviceRoute = profile?.volumePath != null
        val ok = sameRate && sameBits && sameChannels && sameDeviceRoute
        AppLogger.i(
            TAG,
            "canUseUsbSoftNextFor: ok=$ok cur=${cur.sampleRate}/${cur.bitsPerSample}/${cur.channelCount} " +
                "next=${nextSong.sampleRate}/${nextSong.bitsPerSample}/${nextSong.channelCount} route=${profile?.volumePath}"
        )
        return ok
    }

    private var playCountRecordedKey: String = ""
    private var currentTrackMaxPositionMs: Long = 0L

    data class UsbDeviceStatus(
        val deviceName: String,
        val vendorProductId: String,
        val managerState: String,
        val connected: Boolean,
        val permissionGranted: Boolean,
        val exclusiveActive: Boolean,
        val initialized: Boolean,
        val running: Boolean,
        val bitPerfect: Boolean,
        val playbackMode: String,
        val sourceFormat: String,
        val targetFormat: String,
        val actualOutputFormat: String,
        val outputChain: String,
        val dsdActive: Boolean,
        val dsdSourceDirect: Boolean,
        val interfaceInfo: String,
        val endpointInfo: String,
        val bufferInfo: String,
        val hardwareVolumeInfo: String,
        val dsdInfo: String,
        val transportDiagnostics: String = "",
        val audibleDiagnostics: String = "",
        val feedbackDiagnostics: String = "",
        val clockDiagnostics: String = "",
        val featureUnitDiagnostics: String = "",
        val profileDiagnostics: String = "",
        val recoveryDiagnostics: String = "",
        val uac20PreviewDiagnostics: String = "",
        val uac20GrayDecisionDiagnostics: String = "",
        val nativeStatsRaw: String = ""
    )

    /** USB 独占模式激活后回调：用于引导用户加入电池优化白名单 */
    var onUsbExclusiveActivated: (() -> Unit)? = null

    // FFmpeg 播放器回调
    private val playerListener = object : FfmpegAudioPlayer.Listener {
        override fun onStateChanged(state: FfmpegAudioPlayer.State) {
            if (isReleased) return
            scope.launch {
                handlePlayerStateChanged(state)
            }
        }

        override fun onPositionChanged(positionMs: Long, durationMs: Long) {
            if (cachedCueEndMs > 0) return
            val prev = _position.value
            _position.value = positionMs
            _duration.value = durationMs
            if (kotlin.math.abs(positionMs - prev) > 2000) {
                Log.w(TAG, ">>> onPositionChanged: JUMP! prev=$prev -> newPos=$positionMs")
            }
        }

        override fun onError(message: String) {
            lastPlayerError = message
            scope.launch {
                Log.e(TAG, "FfmpegAudioPlayer error: $message")
            }
        }

        override fun onGaplessSongChanged(newPath: String) {
            if (isReleased) return
            scope.launch {
                transportMutex.withLock {
                    val songs = _queue.value.songs
                    val curIdx = _queue.value.currentIndex
                    val newIndex = songs.indexOfFirst { it.path == newPath }
                    if (newIndex >= 0 && newIndex != curIdx) {
                        Log.d(TAG, "Gapless: song changed, index $curIdx -> $newIndex")
                        _queue.value = _queue.value.copy(currentIndex = newIndex)
                        _currentSong.value = songs[newIndex]
                        AppLogger.markPlaybackReportStart(
                            title = songs[newIndex].title,
                            artist = songs[newIndex].artist,
                            album = songs[newIndex].album,
                            path = songs[newIndex].path,
                            cueOffsetMs = songs[newIndex].cueOffsetMs
                        )
                        FFmpegBridge.resetDebugLog("gapless_playback_report_start:${songs[newIndex].path}")
                        playCountRecordedKey = ""
                        currentTrackMaxPositionMs = 0L
                        setupNextSongForGapless()
                    }
                }
            }
        }
    }

    init {
        Log.i(TAG, "PlayerController created: ${System.identityHashCode(this)}")
        ffmpegPlayer.listener = playerListener
        ffmpegPlayer.onPcmWaveformFrame = { buffer, read, channels, sampleRate, bitsPerSample ->
            onPcmWaveformFrame?.invoke(buffer, read, channels, sampleRate, bitsPerSample)
        }
        ffmpegPlayer.onAndroidUsbAudioRouteAdded = {
            AppLogger.i(TAG, "Android USB audio route callback: wait for USB attach/explicit user action before permission")
        }
        initDspPipeline()
        restoreState()
        usbExclusiveManager.onDeviceReady = { device ->
            val playbackNeedsImmediateUsb =
                _playState.value == PlayState.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
            val prefetchGrant = usbAttachPermissionPrefetchDeviceId == device.deviceId
            if (pendingEnableUsbExclusiveAfterPermission) {
                AppLogger.i(TAG, "USB device ready with explicit pending enable: ${device.productName}")
            } else if (appInBackground && playbackNeedsImmediateUsb) {
                deferUsbExclusiveActivationUntilForeground(device, "device_ready_background")
                AppLogger.i(
                    TAG,
                    "USB permission/device ready while app is background; defer active playback USB cutover: ${device.productName}"
                )
            } else if (!playbackNeedsImmediateUsb) {
                prepareUsbDevice(device)
                ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "usb_permission_prefetch_ready_idle")
                AppLogger.i(
                    TAG,
                    "USB permission/device ready while idle; remember device only, prefetchGrant=$prefetchGrant " +
                        "prefetchDeviceId=$usbAttachPermissionPrefetchDeviceId deviceId=${device.deviceId}: ${device.productName}"
                )
            } else {
                AppLogger.i(TAG, "USB permission/device ready during playback: arm exclusive renderer for ${device.productName}")
                usbAttachPermissionPrefetchDeviceId = -1
                scope.launch {
                    delay(350)
                    activateUsbExclusiveAfterPermission(device, reason = "onDeviceReady_playing")
                }
            }
        }
        usbExclusiveManager.onDeviceAttached = { device ->
            scheduleUsbAttachPermissionPrompt(deviceHint = device, reason = "usb_attach_broadcast")
        }
        usbExclusiveManager.onPermissionResult = { device, granted ->
            onUsbPermissionResult(device, granted)
        }
        usbExclusiveManager.onDeviceDetached = { device ->
            AppLogger.w(TAG, "USB device detached callback: ${device?.deviceName}")
            handleUsbDeviceDetached(device)
        }
        usbExclusiveManager.register()
        
        // Initialize HID key event listener for USB remote control support
        initHidKeyListener()
        
        // Do not run USB permission / exclusive activation synchronously during
        // PlayerController construction. On MIUI/Android 16 a connected DAC can
        // make cold launch wait on USB permission / attach callbacks before the
        // first Compose frame. Startup only remembers the device first;
        // optional exclusive restoration is deferred until the UI is alive.
        scope.launch(Dispatchers.Default) {
            delay(900)
            scanForUsbDevice(startup = true)
        }
    }

    /**
     * Initialize HID key event listener for USB remote control support
     */
    private fun initHidKeyListener() {
        if (!UsbAudioEngine.initHidSafely()) {
            AppLogger.w(TAG, "USB HID remote key listener disabled: native HID bridge unavailable")
            return
        }
        
        UsbAudioEngine.setHidKeyEventListener(object : UsbAudioEngine.HidKeyEventListener {
            override fun onHidKeyEvent(keyCode: Int, pressed: Boolean) {
                if (!pressed) return // Only handle key down events
                
                AppLogger.i(TAG, "HID key event: keyCode=0x${keyCode.toString(16)}, pressed=$pressed")
                
                when (keyCode) {
                    0xCD -> { // Play/Pause
                        AppLogger.i(TAG, "HID: Play/Pause")
                        if (ffmpegPlayer.isPlayingNow) {
                            pause()
                        } else {
                            resume()
                        }
                    }
                    0xB5 -> { // Next Track
                        AppLogger.i(TAG, "HID: Next Track")
                        // Get next song from queue and play it
                        val nextSong = getNextSong()
                        if (nextSong != null) {
                            playNext(nextSong)
                        }
                    }
                    0xB6 -> { // Previous Track
                        AppLogger.i(TAG, "HID: Previous Track")
                        // Get previous song from queue and play it
                        val prevSong = getPreviousSong()
                        if (prevSong != null) {
                            playNext(prevSong)
                        }
                    }
                    0xB7 -> { // Stop
                        AppLogger.i(TAG, "HID: Stop")
                        stop()
                    }
                    0xE9 -> { // Volume Up
                        AppLogger.i(TAG, "HID: Volume Up")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            stepUsbVolume(+0.04f)
                        } else {
                            val currentVolume = AppPreferences.Player.volume
                            setVolume((currentVolume + 0.05f).coerceAtMost(1.0f))
                        }
                    }
                    0xEA -> { // Volume Down
                        AppLogger.i(TAG, "HID: Volume Down")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            stepUsbVolume(-0.04f)
                        } else {
                            val currentVolume = AppPreferences.Player.volume
                            setVolume((currentVolume - 0.05f).coerceAtLeast(0.0f))
                        }
                    }
                    0xE2 -> { // Mute
                        AppLogger.i(TAG, "HID: Mute")
                        if (isUsbExclusiveActive() && canControlUsbVolume()) {
                            setUsbVolumeLinear(0f)
                        } else {
                            val currentVolume = AppPreferences.Player.volume
                            if (currentVolume > 0f) {
                                setVolume(0f)
                            } else {
                                setVolume(0.5f)
                            }
                        }
                    }
                    else -> {
                        AppLogger.d(TAG, "HID: Unknown key 0x${keyCode.toString(16)}")
                    }
                }
            }
        })
    }
    
    /**
     * Get next song from queue
     */
    private fun getNextSong(): AudioFile? {
        val queue = _queue.value
        if (queue.songs.isEmpty()) return null
        
        val nextIndex = when (queue.repeatMode) {
            RepeatMode.ONE -> queue.currentIndex
            RepeatMode.ALL -> (queue.currentIndex + 1) % queue.songs.size
            RepeatMode.OFF -> {
                if (queue.currentIndex < queue.songs.size - 1) {
                    queue.currentIndex + 1
                } else {
                    return null // End of queue
                }
            }
        }
        
        return queue.songs.getOrNull(nextIndex)
    }
    
    /**
     * Get previous song from queue
     */
    private fun getPreviousSong(): AudioFile? {
        val queue = _queue.value
        if (queue.songs.isEmpty()) return null
        
        val prevIndex = when (queue.repeatMode) {
            RepeatMode.ONE -> queue.currentIndex
            RepeatMode.ALL -> (queue.currentIndex - 1 + queue.songs.size) % queue.songs.size
            RepeatMode.OFF -> {
                if (queue.currentIndex > 0) {
                    queue.currentIndex - 1
                } else {
                    return null // Beginning of queue
                }
            }
        }
        
        return queue.songs.getOrNull(prevIndex)
    }

    fun scanForUsbDevice(startup: Boolean = false) {
        if (_usbExclusiveActive.value || usbExclusiveManager.hasOpenConnection()) {
            AppLogger.d(TAG, "USB already active/open, skip startup scan")
            return
        }
        val device = usbExclusiveManager.findUsbAudioDevice()
        if (device != null) {
            AppLogger.i(TAG, "Found already-connected USB audio device: ${device.deviceName}")
            if (startup) {
                // Cold launch path: remember the attached DAC but do not request
                // permission or auto-activate exclusive before the UI is visible.
                // When the user explicitly enables USB exclusive, or playback
                // starts with a remembered active route, the normal permission
                // path will run. This avoids MIUI launch stalls when the dongle
                // is left attached after the task was swiped away.
                usbExclusiveManager.rememberDeviceOnly(device, reason = "startup_deferred_scan")
                if (AppPreferences.Player.lastUsbExclusiveActive) {
                    AppLogger.i(
                        TAG,
                        "USB exclusive was active in previous task, but cold startup will not auto-request " +
                            "permission or auto-activate while a DAC is attached; wait for explicit play/user action"
                    )
                }
            } else {
                usbExclusiveManager.requestPermissionSafely(device)
            }
        } else {
            AppLogger.d(TAG, "No USB audio device found at startup")
        }
    }

    /** 设备插入/授权成功：只记住设备和兼容设置，不设 exclusive policy。 */
    private fun prepareUsbDevice(device: UsbDevice) {
        val sameDevice = currentUsbDevice?.deviceId == device.deviceId

        if (!sameDevice) {
            sharedUsbAudioEngine.nativeResetUsbPolicyForNewDevice()
            usbFeedbackRejectedTransportKey = null
            usbFeedbackRejectedReason = ""
        }

        currentUsbDevice = device
        usbExclusiveManager.rememberDeviceOnly(device, reason = "prepare_usb_device")

        // Safe core keeps descriptor quirks disabled, but packet cadence is a
        // transport-level scheduler choice. Respect the persisted/manual 1ms
        // pacing flag so a bad no-feedback profile does not reopen forever
        // with the exact same microframe cadence.
        sharedUsbAudioEngine.setUsbDacSettings(
            false, false, false, false,
            AppPreferences.Player.usbForce1MsPacket
        )
        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        sharedUsbAudioEngine.setDsdConversion(
            effectiveDsdMode != null,
            currentEffectiveUsbDsdRate(),
            AppPreferences.Player.dsdConversionType,
            AppPreferences.Player.dsdDitherEnabled,
            effectiveDsdMode?.transport == UsbDsdTransport.DOP
        )

        val deviceKey = usbLearnedPolicyKeyFor(device)
        if (UsbLearnedPolicyStore.resetRunawayUnprovenFallbacks(deviceKey, "prepare_usb_device")) {
            pendingUsbRecoveryPlan = null
            pendingUsbPolicyRestart = false
            resetUsbExclusiveRecoveryFuse("learned_policy_runaway_reset")
            AppLogger.w(
                TAG,
                "USB learned fallback reset for ${device.productName}: too many failures without a single accepted run"
            )
        }

        AppLogger.i(TAG, "USB DAC prepared only, waiting for exclusive enable: ${device.productName}")
    }

    private fun resetUsbExclusiveRecoveryFuse(reason: String) {
        synchronized(usbExclusiveFullRecoveryAttemptsMs) {
            usbExclusiveFullRecoveryAttemptsMs.clear()
        }
        AppLogger.i(TAG, "USB exclusive full-recovery fuse reset: reason=$reason")
    }

    private fun allowUsbExclusiveFullRecovery(reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(usbExclusiveFullRecoveryAttemptsMs) {
            while (usbExclusiveFullRecoveryAttemptsMs.isNotEmpty() &&
                now - usbExclusiveFullRecoveryAttemptsMs.first() > 15_000L
            ) {
                usbExclusiveFullRecoveryAttemptsMs.removeFirst()
            }
            if (usbExclusiveFullRecoveryAttemptsMs.size >= 1) {
                AppLogger.e(
                    TAG,
                    "USB exclusive full-recovery fuse open: reason=$reason recent=${usbExclusiveFullRecoveryAttemptsMs.size}; " +
                        "falling back instead of reopening the DAC again"
                )
                return false
            }
            usbExclusiveFullRecoveryAttemptsMs.addLast(now)
            return true
        }
    }


    private data class UsbFeedbackModelDecision(
        val noFeedback: Boolean,
        val reason: String,
        val format: UsbPcmFormatCapability?
    )

    private fun currentUsbTransportKeyOrNull(): String? {
        val device = currentUsbDevice ?: return null
        return runCatching { usbLearnedPolicyKeyFor(device) }.getOrNull()
    }

    private fun rememberUsbFeedbackRejected(reason: String) {
        val key = currentUsbTransportKeyOrNull() ?: return
        if (usbFeedbackRejectedTransportKey != key) {
            AppLogger.w(TAG, "USB feedback model: explicit feedback rejected for transport=$key reason=$reason")
        }
        usbFeedbackRejectedTransportKey = key
        usbFeedbackRejectedReason = reason
    }

    private fun clearUsbNoFeedbackFallback(reason: String) {
        val key = currentUsbTransportKeyOrNull() ?: return
        if (usbFeedbackRejectedTransportKey == key) {
            AppLogger.w(TAG, "USB feedback model: clearing in-memory no-feedback rejection for transport=$key reason=$reason")
            usbFeedbackRejectedTransportKey = null
            usbFeedbackRejectedReason = ""
        }
        if (UsbLearnedPolicyStore.clearNoFeedbackFallback(key, reason)) {
            AppLogger.w(TAG, "USB learned policy: cleared no-feedback fallback for transport=$key reason=$reason")
        }
    }

    private fun matchingPcmFormatScore(
        format: UsbPcmFormatCapability,
        targetRate: Int,
        targetBits: Int,
        targetSubslot: Int,
        targetChannels: Int,
        pcmMode: UsbPcmOutputMode
    ): Int {
        var score = 0
        if (!format.isPcm || format.isRawData) return Int.MIN_VALUE / 4
        if (targetRate > 0) {
            if (format.sampleRate == targetRate) score += 4000 else score -= kotlin.math.abs(format.sampleRate - targetRate) / 10
        }
        if (targetChannels > 0) {
            if (format.channels == targetChannels) score += 900 else if (format.channels >= targetChannels) score += 200 else score -= 2000
        }
        if (targetBits > 0) {
            when {
                format.validBits == targetBits -> score += 1400
                format.validBits > targetBits -> score += 250
                else -> score -= 1000
            }
        }
        if (targetSubslot > 0) {
            if (format.subslotBytes == targetSubslot) score += 800 else score -= 250
        }
        when (pcmMode) {
            UsbPcmOutputMode.AUTO -> Unit
            UsbPcmOutputMode.PCM_16 -> if (format.validBits == 16 && format.subslotBytes == 2) score += 1500 else score -= 2500
            UsbPcmOutputMode.PCM_24_PACKED -> if (format.validBits == 24 && format.subslotBytes == 3) score += 1800 else score -= 2500
            UsbPcmOutputMode.PCM_24_IN_32 -> if (format.validBits >= 24 && format.subslotBytes == 4) score += 1500 else score -= 2000
            UsbPcmOutputMode.PCM_32 -> if (format.validBits == 32 && format.subslotBytes == 4) score += 1500 else score -= 2000
        }
        if (format.capacityRatioPermille >= 1400) score += 250
        if (format.feedbackEndpoint != 0 && format.feedbackUsage == 1) score += 180
        if (format.feedbackEndpoint != 0 && format.feedbackUsage != 1) score -= 700
        if (format.outSync == 2) score += 120 // adaptive OUT is a normal no-feedback-capable model.
        return score
    }

    private fun chooseModeledPcmFormat(
        caps: UsbDeviceAudioCapabilities?,
        fmt: UsbPcmFormatRequest
    ): UsbPcmFormatCapability? {
        val formats = caps?.pcmFormats.orEmpty()
        if (formats.isEmpty()) return null
        val song = _currentSong.value
        val sourceRate = song?.sampleRate?.takeIf { it > 0 } ?: 0
        val targetRate = AppPreferences.Player.usbTargetSampleRate.takeIf { it > 0 } ?: sourceRate
        val sourceBits = song?.bitsPerSample?.takeIf { it > 0 } ?: 0
        val targetBits = fmt.targetValidBits.takeIf { it > 0 } ?: sourceBits
        val targetSubslot = fmt.targetSubslotBytes
        val targetChannels = song?.channelCount?.takeIf { it > 0 } ?: 2
        return formats.maxByOrNull {
            matchingPcmFormatScore(
                format = it,
                targetRate = targetRate,
                targetBits = targetBits,
                targetSubslot = targetSubslot,
                targetChannels = targetChannels,
                pcmMode = fmt.mode
            )
        }
    }

    private fun decideUsbFeedbackModel(
        caps: UsbDeviceAudioCapabilities?,
        fmt: UsbPcmFormatRequest,
        learned: com.rawsmusic.module.player.usb.UsbLearnedPolicy?,
        pendingPlan: UsbRecoveryPlan?
    ): UsbFeedbackModelDecision {
        val modeledFormat = chooseModeledPcmFormat(caps, fmt)
        val descriptorNoFeedback = when {
            modeledFormat == null -> false
            modeledFormat.feedbackEndpoint == 0 -> true
            modeledFormat.feedbackUsage != 1 -> true
            (modeledFormat.profileRiskFlags and (1 shl 2)) != 0 -> true // PROFILE_RISK_FEEDBACK_NONSTANDARD
            else -> false
        }

        if (USB_EXPOSE_PRE_TP55_POLICY &&
            (learned?.noFeedback == true ||
                learned?.preferSafeAlt == true ||
                learned?.lastGoodNoFeedback == true ||
                pendingPlan?.disableFeedback == true ||
                pendingPlan?.preferSafeAlt == true ||
                pendingPlan?.preferLastGoodProfile == true)
        ) {
            AppLogger.w(
                TAG,
                "USB_EXPOSE_PRE_TP55: ignoring learned/pending feedback fallback " +
                    "learnedNoFb=${learned?.noFeedback} learnedSafeAlt=${learned?.preferSafeAlt} " +
                    "lastGoodAlt=${learned?.lastGoodAlt} lastGoodNoFb=${learned?.lastGoodNoFeedback} " +
                    "pendingDisableFb=${pendingPlan?.disableFeedback} pendingSafeAlt=${pendingPlan?.preferSafeAlt} " +
                    "pendingLastGood=${pendingPlan?.preferLastGoodProfile}"
            )
        }

        val reason = when {
            descriptorNoFeedback && modeledFormat?.feedbackEndpoint == 0 -> "descriptor has no feedback endpoint"
            descriptorNoFeedback -> "descriptor feedback is not explicit/eligible"
            else -> "descriptor explicit feedback eligible; learned/pending TP55 fallback ignored"
        }
        return UsbFeedbackModelDecision(
            noFeedback = descriptorNoFeedback,
            reason = reason,
            format = modeledFormat
        )
    }

    private fun stopUsbExclusiveAfterFatalFailure(
        reason: String,
        releaseManager: Boolean = true,
        notifyNativeDetached: Boolean = true
    ) {
        AppLogger.e(TAG, "USB exclusive fatal failure: reason=$reason; stop playback without Android fallback")

        pendingUsbRecoveryPlan = null
        pendingUsbPolicyRestart = false
        recoveringUsb.set(false)

        scope.launch(Dispatchers.Main) {
            runCatching { ffmpegPlayer.stop() }
            runCatching {
                clearUsbExclusiveState(
                    releaseManager = releaseManager,
                    notifyNativeDetached = notifyNativeDetached
                )
            }
            _isRenderSwitching.value = false
            _usbExclusiveActive.value = false
            ffmpegPlayer.usbExclusiveMode = false
            sharedUsbAudioEngine.nativeSetUsbExclusiveActive(false)
            smTransition(PlayState.PAUSED, "usb_exclusive_fatal_failure")
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    "USB 独占输出启动失败，已停止播放，请导出诊断日志",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Apply USB DAC advanced settings from UI. Only force1ms is wired through. */
    fun setUsbDacSettings(
        noControlIface: Boolean,
        forceUac1: Boolean,
        linearVolume: Boolean,
        replaceVolume: Boolean,
        force1ms: Boolean
    ) {
        sharedUsbAudioEngine.setUsbDacSettings(noControlIface, forceUac1, linearVolume, replaceVolume, force1ms)
    }

    // ======================== 音量路由（Volume Route）========================
    // 三条明确路径：
    // 1. 非独占 → Android 系统音量，native gain = 1.0
    // 2. 独占 + 无硬件音量 → 系统媒体音量映射成 USB 软件增益
    // 3. 独占 + 硬件音量 → USB Feature Unit，PCM gain = 1.0

    private enum class VolumeRoute { SYSTEM, USB_HARDWARE, USB_FIXED }

    private fun resolveCurrentUsbOutputProfile(): UsbOutputProfile? {
        if (!_usbExclusiveActive.value) return null
        return buildUsbOutputProfile(exclusive = true)
    }

    private fun resolveVolumeRoute(): VolumeRoute {
        val profile = resolveCurrentUsbOutputProfile()
        return when (profile?.volumePath) {
            UsbVolumePath.HardwareUserVolume -> VolumeRoute.USB_HARDWARE
            UsbVolumePath.Fixed -> VolumeRoute.USB_FIXED
            else -> VolumeRoute.SYSTEM
        }
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun getSystemMusicVolumeLinear(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return vol.toFloat() / max.toFloat()
    }

    private fun setSystemMusicVolumeLinear(linear: Float, flags: Int = AudioManager.FLAG_SHOW_UI) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (linear.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, flags)
    }

    private fun suppressSystemVolumeObserver(windowMs: Long, reason: String) {
        val now = SystemClock.elapsedRealtime()
        suppressSystemVolumeObserverUntilMs = maxOf(
            suppressSystemVolumeObserverUntilMs,
            now + windowMs.coerceAtLeast(0L)
        )
        AppLogger.i(
            TAG,
            "suppressSystemVolumeObserver: until=$suppressSystemVolumeObserverUntilMs " +
                "windowMs=$windowMs reason=$reason"
        )
    }

    private fun mirrorUsbExclusiveSoftwareVolumeToSystem(reason: String) {
        if (!isUsbExclusiveSoftwareVolumeActive()) return
        val target = AppPreferences.Player.volume.coerceIn(0f, 1f)
        val current = getSystemMusicVolumeLinear().coerceIn(0f, 1f)
        if (abs(current - target) < 0.01f) return
        ignoreSystemVolumeObserverUntilMs = SystemClock.elapsedRealtime() + 500L
        AppLogger.i(
            TAG,
            "mirrorUsbExclusiveSoftwareVolumeToSystem: current=$current target=$target reason=$reason"
        )
        setSystemMusicVolumeLinear(target, 0)
    }

    private fun isUsbExclusiveSoftwareVolumeActive(): Boolean {
        if (!_usbExclusiveActive.value || resolveVolumeRoute() != VolumeRoute.SYSTEM) return false
        return buildUsbOutputProfile(exclusive = true).volumePath ==
            com.rawsmusic.module.player.usb.UsbVolumePath.Software
    }

    private fun normalizeUsbExclusiveSoftwareEntryVolume(systemLinear: Float, reason: String): Float {
        val current = AppPreferences.Player.volume.coerceIn(0f, 1f)
        if (current > 0.0001f || systemLinear <= 0.0001f || explicitUsbExclusiveSoftwareMuteThisProcess) {
            return current
        }
        val seeded = systemLinear.coerceIn(0f, 1f)
        AppPreferences.Player.volume = seeded
        AppLogger.w(
            TAG,
            "Seeding USB exclusive software volume from system: old=$current seeded=$seeded reason=$reason"
        )
        return seeded
    }

    private fun applyUsbExclusiveSoftwareUserVolume(linear: Float, reason: String) {
        val target = linear.coerceIn(0f, 1f)
        val pcmGain = usbSoftwareUiVolumeToPcmGain(target)
        AppPreferences.Player.volume = target
        explicitUsbExclusiveSoftwareMuteThisProcess = target <= 0.0001f
        AppLogger.i(TAG, "applyUsbExclusiveSoftwareUserVolume: ui=$target pcmGain=$pcmGain reason=$reason")
        sharedUsbAudioEngine.nativeSetUsbSoftwareGain(pcmGain)
        applyComposedVolume()
        mirrorUsbExclusiveSoftwareVolumeToSystem("applyUsbExclusiveSoftwareUserVolume:$reason")
    }

    private fun forceUsbFixedVolume0Db(reason: String) {
        AppPreferences.Player.volume = 1.0f
        explicitUsbExclusiveSoftwareMuteThisProcess = false
        sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
        val handle = sharedUsbAudioEngine.currentHandle
        if (handle != 0L) {
            sharedUsbAudioEngine.setSessionVolumeScale(handle, 1.0f, 0)
        }
        suppressSystemVolumeObserver(650L, "fixed_0db:$reason")
        runCatching { setSystemMusicVolumeLinear(1.0f, 0) }
        AppLogger.w(TAG, "USB fixed digital 0dB volume enforced: reason=$reason")
    }

    // ======================== 硬件音量 step/dB 工具 ========================
    // 1 step = 1 dB，范围 [-60, 0] dB，共 60 步

    private val USB_HW_MIN_DB = -60
    private val USB_HW_MAX_DB = 0
    private val USB_HW_MAX_STEP = USB_HW_MAX_DB - USB_HW_MIN_DB // keep legacy persisted step count
    private val USB_SESSION_DEFAULT_FADE_MS = 80
    @Volatile private var pendingManualTrackStartFadeMs: Int = 0

    private fun consumePendingManualTrackStartFadeMs(): Int {
        val value = pendingManualTrackStartFadeMs
        pendingManualTrackStartFadeMs = 0
        return value.coerceAtLeast(0)
    }

    private fun usbExclusiveStartupFadeInMs(profile: UsbOutputProfile): Int {
        if (profile.bitPerfect || profile.volumePath == UsbVolumePath.HardwareUserVolume) {
            pendingManualTrackStartFadeMs = 0
            return 0
        }
        val manualFadeMs = consumePendingManualTrackStartFadeMs()
        if (manualFadeMs > 0) return manualFadeMs
        return TransitionPreferences.transportDurationOrZero().takeIf { it > 0 } ?: USB_SESSION_DEFAULT_FADE_MS
    }

    private fun usbExclusiveManualTrackFadeMs(): Int {
        return when (TransitionPreferences.manualTrackTransitionMode) {
            TransitionPreferences.ManualTrackTransitionMode.NONE -> 0
            // USB exclusive keeps transport ownership in one native session.  A true
            // decoded crossfade would mix two decoded streams and breaks bit-perfect;
            // use the configured manual fade envelope instead.
            TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
            TransitionPreferences.ManualTrackTransitionMode.CROSSFADE -> TransitionPreferences.manualTrackFadeMs
        }
    }

    private fun canUseUsbSessionPcmEnvelope(profile: UsbOutputProfile = buildUsbOutputProfile(exclusive = true)): Boolean =
        !profile.bitPerfect && profile.volumePath != UsbVolumePath.HardwareUserVolume

    private suspend fun fadeUsbExclusiveSessionTo(
        target: Float,
        fadeMs: Int,
        reason: String,
        waitForEnvelope: Boolean = true
    ) {
        val bounded = fadeMs.coerceIn(0, 1000)
        if (bounded <= 0) return
        val profile = buildUsbOutputProfile(exclusive = true)
        if (!canUseUsbSessionPcmEnvelope(profile)) {
            AppLogger.i(TAG, "USB_SESSION_FADE_SKIP reason=$reason bitPerfect=${profile.bitPerfect} volumePath=${profile.volumePath}")
            return
        }
        val handle = sharedUsbAudioEngine.currentHandle
        if (handle == 0L) {
            AppLogger.w(TAG, "USB_SESSION_FADE_SKIP reason=$reason handle=0")
            return
        }
        AppLogger.i(TAG, "USB_SESSION_FADE target=$target fadeMs=$bounded reason=$reason")
        sharedUsbAudioEngine.setSessionVolumeScale(handle, target.coerceIn(0f, 1f), bounded)
        if (waitForEnvelope) delay(bounded.toLong())
    }

    private fun clampUsbHwStep(step: Int): Int = step.coerceIn(0, USB_HW_MAX_STEP)

    private fun usbHwStepToDb(step: Int): Int = USB_HW_MIN_DB + clampUsbHwStep(step)

    private fun usbHwDbToStep(db: Int): Int =
        (db.coerceIn(USB_HW_MIN_DB, USB_HW_MAX_DB) - USB_HW_MIN_DB).coerceIn(0, USB_HW_MAX_STEP)

    private fun usbHwStepToUiVolume(step: Int): Float =
        clampUsbHwStep(step).toFloat() / USB_HW_MAX_STEP.toFloat()

    private fun uiVolumeToUsbHwStep(volume: Float): Int =
        (volume.coerceIn(0f, 1f) * USB_HW_MAX_STEP).roundToInt().coerceIn(0, USB_HW_MAX_STEP)

    private fun reconcileUsbHardwareStepPreference(reason: String): Int {
        val persistedStep = AppPreferences.Player.usbHardwareVolumeStep.coerceIn(0, USB_HW_MAX_STEP)
        val persistedLinear = AppPreferences.Player.usbHardwareVolume.coerceIn(0f, 1f)
        val linearStep = uiVolumeToUsbHwStep(persistedLinear)
        val persistedUi = usbHwStepToUiVolume(persistedStep)
        val isLegacyDefaultStep = persistedStep == 25
        val appearsOutOfSync = kotlin.math.abs(persistedUi - persistedLinear) > 0.20f
        val shouldPreferLinear =
            (isLegacyDefaultStep && persistedLinear > 0.45f) ||
                (appearsOutOfSync && persistedLinear > 0.10f)
        if (!shouldPreferLinear) return persistedStep

        AppPreferences.Player.usbHardwareVolumeStep = linearStep
        AppLogger.w(
            TAG,
            "Reconciled USB HW volume pref: oldStep=$persistedStep oldUi=$persistedUi " +
                "storedLinear=$persistedLinear newStep=$linearStep reason=$reason"
        )
        return linearStep
    }

    private fun currentUsbHwStep(): Int =
        reconcileUsbHardwareStepPreference("currentUsbHwStep")

    /** 硬件音量写入：只写 dB，不写 linear */
    private fun setUsbHardwareVolumeStep(step: Int, reason: String): Int {
        val boundedStep = clampUsbHwStep(step)
        val db = usbHwStepToDb(boundedStep)
        val uiVolume = usbHwStepToUiVolume(boundedStep)

        AppPreferences.Player.usbHardwareVolumeStep = boundedStep
        AppPreferences.Player.usbHardwareVolume = uiVolume
        if (resolveVolumeRoute() == VolumeRoute.USB_HARDWARE) {
            AppPreferences.Player.volume = uiVolume
        }

        val handle = sharedUsbAudioEngine.currentHandle
        if (handle == 0L) {
            AppLogger.w(TAG, "setUsbHardwareVolumeStep: no handle step=$boundedStep db=$db reason=$reason")
            return -1
        }
        if (!sharedUsbAudioEngine.nativeCanControlVolume(handle)) {
            AppLogger.w(TAG, "setUsbHardwareVolumeStep: native cannot control volume step=$boundedStep db=$db reason=$reason")
            return -2
        }

        AppLogger.i(TAG, "USB HW volume step apply: step=$boundedStep db=${db}dB ui=$uiVolume reason=$reason")
        return sharedUsbAudioEngine.nativeSetHardwareVolumeDb(handle, db)
    }

    private fun applyVolumeRoute(reason: String) {
        val exclusive = _usbExclusiveActive.value
        val systemLinear = getSystemMusicVolumeLinear()
        val profile = resolveCurrentUsbOutputProfile()
        val volumePath = profile?.volumePath
        val route = when (volumePath) {
            UsbVolumePath.HardwareUserVolume -> VolumeRoute.USB_HARDWARE
            UsbVolumePath.Fixed -> VolumeRoute.USB_FIXED
            else -> VolumeRoute.SYSTEM
        }

        AppLogger.i(
            TAG,
            "applyVolumeRoute: reason=$reason route=$route exclusive=$exclusive " +
                "systemLinear=$systemLinear hwPref=${AppPreferences.Player.hardwareFeatureUnitEnabled} " +
                "volumePath=$volumePath hwValidated=${profile?.hardwareVolumeValidated}"
        )

        when {
            !exclusive || profile == null -> {
                sharedUsbAudioEngine.nativeSetPolicy(
                    exclusive = false,
                    bitPerfect = false,
                    hwVol = false
                )
                sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
                AppPreferences.Player.volume = systemLinear
                if (systemLinear > 0.0001f) {
                    explicitUsbExclusiveSoftwareMuteThisProcess = false
                }
                AppLogger.i(TAG, "Non-exclusive playback uses Android system volume directly")
            }
            volumePath == UsbVolumePath.HardwareUserVolume -> {
                sharedUsbAudioEngine.nativeSetPolicy(
                    exclusive = true,
                    bitPerfect = profile.bitPerfect,
                    hwVol = true
                )
                sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
                if (isUsbTrackSwitchVolumeHeld()) {
                    AppLogger.w(
                        TAG,
                        "applyVolumeRoute: hardware FU restore deferred during track switch reason=$reason holdReason=$usbTrackSwitchVolumeHoldReason"
                    )
                } else {
                    setUsbHardwareVolumeStep(currentUsbHwStep(), "applyVolumeRoute:$reason")
                }
                // Session envelope: hardware volume mode, PCM gain = unity
                val handle = sharedUsbAudioEngine.currentHandle
                if (handle != 0L) {
                    sharedUsbAudioEngine.setSessionVolumeScale(handle, 1.0f, 0)
                }
            }
            volumePath == UsbVolumePath.Fixed -> {
                sharedUsbAudioEngine.nativeSetPolicy(
                    exclusive = true,
                    bitPerfect = true,
                    hwVol = false
                )
                forceUsbFixedVolume0Db("applyVolumeRoute:$reason")
                AppLogger.i(TAG, "USB exclusive fixed-output path active; user volume locked at 0dB")
            }
            else -> {
                sharedUsbAudioEngine.nativeSetPolicy(
                    exclusive = true,
                    bitPerfect = profile.bitPerfect,
                    hwVol = AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled && exclusive
                )
                val userLinear = normalizeUsbExclusiveSoftwareEntryVolume(systemLinear, reason)
                val handle = sharedUsbAudioEngine.currentHandle
                if (handle != 0L) {
                    sharedUsbAudioEngine.setSessionVolumeScale(handle, 1.0f, 0)
                    applyUsbVolume(profile, "applyVolumeRoute:$reason")
                } else {
                    sharedUsbAudioEngine.nativeSetUsbSoftwareGain(usbSoftwareUiVolumeToPcmGain(userLinear))
                }
                AppLogger.i(TAG, "USB exclusive software gain follows app volume: linear=$userLinear")
                mirrorUsbExclusiveSoftwareVolumeToSystem("applyVolumeRoute:$reason")
            }
        }

        syncSystemVolumeObserverForRoute("applyVolumeRoute:$reason")
        syncUsbRemoteVolumeRoute("applyVolumeRoute:$reason")
    }

    /** 用户调节音量统一入口（UI 滑条 / +/- 按钮 / 后台音量键） */
    fun setUserVolume(linear: Float) {
        val route = resolveVolumeRoute()
        val v = linear.coerceIn(0f, 1f)
        AppLogger.i(TAG, "setUserVolume: route=$route linear=$v")

        when (route) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("setUserVolume_ignored")
            }
            VolumeRoute.USB_HARDWARE -> {
                setUsbHardwareVolumeStep(uiVolumeToUsbHwStep(v), "setUserVolume")
            }
            VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(v, "setUserVolume")
                } else {
                    setSystemMusicVolumeLinear(v)
                    val actual = getSystemMusicVolumeLinear()
                    AppPreferences.Player.volume = actual
                    if (actual > 0.0001f) {
                        explicitUsbExclusiveSoftwareMuteThisProcess = false
                    }
                }
            }
        }
    }

    // ======================== 系统音量观察器 ========================

    // Lazy-created to avoid init-order NPE: PlayerController.init{} may trigger
    // USB auto-activation (scanForUsbDevice → onDeviceReady → activateUsbExclusive)
    // before this field is initialized if it were a plain val. Using nullable +
    // lazy creation makes registerSystemVolumeObserver safe at any point.
    private var systemVolumeObserver: ContentObserver? = null

    private var systemVolumeObserverRegistered = false
    private var systemVolumeReceiverRegistered = false

    private val systemVolumeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED) return
            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
            if (streamType != AudioManager.STREAM_MUSIC) return
            onSystemVolumeChanged()
        }
    }

    private fun ensureSystemVolumeObserver(): ContentObserver {
        return systemVolumeObserver ?: object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                onSystemVolumeChanged()
            }
        }.also {
            systemVolumeObserver = it
        }
    }

    private fun registerSystemVolumeObserver() {
        if (systemVolumeObserverRegistered) return
        val observer = ensureSystemVolumeObserver()
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, observer
        )
        systemVolumeObserverRegistered = true
        if (!systemVolumeReceiverRegistered) {
            runCatching {
                context.registerReceiver(systemVolumeChangedReceiver, IntentFilter(ACTION_VOLUME_CHANGED))
                systemVolumeReceiverRegistered = true
            }.onFailure { e ->
                AppLogger.w(TAG, "System volume broadcast receiver register failed", e)
            }
        }
        AppLogger.i(TAG, "System volume observer registered")
    }

    private fun unregisterSystemVolumeObserver() {
        if (!systemVolumeObserverRegistered && !systemVolumeReceiverRegistered) return
        systemVolumeObserver?.let { observer ->
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
        if (systemVolumeReceiverRegistered) {
            runCatching { context.unregisterReceiver(systemVolumeChangedReceiver) }
        }
        systemVolumeObserverRegistered = false
        systemVolumeReceiverRegistered = false
    }

    private fun syncSystemVolumeObserverForRoute(reason: String) {
        // In USB software-volume mode MediaSession remote volume owns the keys;
        // observing STREAM_MUSIC here makes the app follow system volume instead
        // of the native USB software gain.
        val shouldObserve = _usbExclusiveActive.value &&
            resolveVolumeRoute() == VolumeRoute.SYSTEM &&
            !isUsbExclusiveSoftwareVolumeActive()
        if (shouldObserve) {
            registerSystemVolumeObserver()
        } else {
            unregisterSystemVolumeObserver()
        }
        AppLogger.i(TAG, "syncSystemVolumeObserverForRoute: observe=$shouldObserve reason=$reason")
    }

    private fun onSystemVolumeChanged() {
        val route = resolveVolumeRoute()
        val linear = getSystemMusicVolumeLinear()
        AppLogger.i(TAG, "onSystemVolumeChanged: route=$route linear=$linear")

        if (SystemClock.elapsedRealtime() < ignoreSystemVolumeObserverUntilMs) {
            AppLogger.i(TAG, "onSystemVolumeChanged ignored by mirror guard")
            return
        }
        if (SystemClock.elapsedRealtime() < suppressSystemVolumeObserverUntilMs) {
            AppLogger.i(TAG, "onSystemVolumeChanged ignored by usb transition guard")
            return
        }

        when (route) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("system_volume_changed_fixed_0db")
                return
            }
            VolumeRoute.USB_HARDWARE -> {
                if (shouldUseUsbRemoteVolume()) {
                    AppLogger.i(TAG, "onSystemVolumeChanged ignored in USB_HARDWARE route: remote volume owns the DAC step")
                    return
                }
                if (linear <= 0.0001f && currentUsbHwStep() > 0) {
                    AppLogger.w(
                        TAG,
                        "onSystemVolumeChanged ignored suspicious zero in USB_HARDWARE route: " +
                            "linear=$linear currentStep=${currentUsbHwStep()}"
                    )
                    return
                }
                if (_usbExclusiveActive.value && canControlUsbVolume()) {
                    val targetStep = uiVolumeToUsbHwStep(linear)
                    val oldStep = currentUsbHwStep()
                    if (targetStep != oldStep) {
                        AppLogger.i(
                            TAG,
                            "onSystemVolumeChanged bridge to USB_HARDWARE: linear=$linear oldStep=$oldStep targetStep=$targetStep"
                        )
                        setUsbHardwareVolumeStep(targetStep, "system_volume_changed_usb_hw")
                    } else {
                        AppLogger.i(TAG, "onSystemVolumeChanged USB_HARDWARE already in sync: step=$oldStep")
                    }
                } else {
                    AppLogger.i(TAG, "onSystemVolumeChanged ignored in USB_HARDWARE route: exclusive=${_usbExclusiveActive.value} canHw=${canControlUsbVolume()}")
                }
            }
            VolumeRoute.SYSTEM -> {
                if (
                    _usbExclusiveActive.value &&
                    linear <= 0.0001f &&
                    isUsbExclusiveSoftwareVolumeActive() &&
                    AppPreferences.Player.volume > 0.0001f &&
                    !explicitUsbExclusiveSoftwareMuteThisProcess &&
                    (usbSeeking || _isRenderSwitching.value || ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING)
                ) {
                    AppLogger.w(
                        TAG,
                        "onSystemVolumeChanged ignored suspicious zero in USB software route: " +
                            "appVolume=${AppPreferences.Player.volume} usbSeeking=$usbSeeking " +
                            "renderSwitching=${_isRenderSwitching.value} ffState=${ffmpegPlayer.state}"
                    )
                    return
                }
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(linear, "system_volume_changed")
                } else {
                    AppPreferences.Player.volume = linear
                    if (linear > 0.0001f) {
                        explicitUsbExclusiveSoftwareMuteThisProcess = false
                    }
                }
            }
        }
    }

    /** 真正切换到 USB 独占播放：设 exclusive policy → 释放 AudioTrack → 激活 USB 渲染器。 */
    private fun activateUsbEngineForPlayback(device: UsbDevice) {
        prepareUsbDevice(device)

        val exclusive = true
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled
        val hwVol = AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled

        sharedUsbAudioEngine.nativeSetUsbExclusiveActive(true)
        sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hwVol)

        val profile = buildUsbOutputProfile(exclusive = true)
        applyUsbOutputProfile(profile)

        AppLogger.i(TAG, "activateUsbEngineForPlayback: exclusive=true bitPerfect=$bitPerfect hwVol=$hwVol")

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (_usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode) {
                return@OnAudioFocusChangeListener
            }
            handleAudioFocusChange(focusChange)
        }
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        am.requestAudioFocus(audioFocusRequest!!)

        try {
            ffmpegPlayer.releaseAudioTrackForUsb()
        } catch (_: Exception) {}

        ffmpegPlayer.usbPrepareForPlayback = { sr, bits, ch, srcFilePath ->
            AppLogger.i(TAG, "usbPrepareForPlayback callback: sr=$sr bits=$bits ch=$ch file=$srcFilePath")
            val recoveryProfile = buildUsbOutputProfile(exclusive = true)
            applyUsbOutputProfile(recoveryProfile)
            AppLogger.i(TAG, "usbPrepareForPlayback applied recovery profile: $recoveryProfile")
            currentUsbDevice?.let { device ->
                usbExclusiveManager.rememberDeviceOnly(device, reason = "usb_prepare_callback")
            }
            val ok = usbExclusiveManager.prepareForPlayback(sr, bits, ch, srcFilePath)
            if (!ok) {
                AppLogger.e(
                    TAG,
                    "USB prepare failed; stop exclusive playback without Android fallback"
                )
                stopUsbExclusiveAfterFatalFailure(
                    reason = "usb_prepare_failed",
                    notifyNativeDetached = false
                )
            }
            ok
        }
        ffmpegPlayer.onUsbTransportLost = {
            AppLogger.w(TAG, "USB transport lost")
            if (shouldDeferUsbHardRecovery("usb_transport_lost")) {
                pendingUsbPolicyRestart = true
            } else {
                recoverUsbExclusiveAsync()
            }
        }
        ffmpegPlayer.shouldDeferUsbHardRecovery = { reason ->
            shouldDeferUsbHardRecovery(reason)
        }
        ffmpegPlayer.onUsbStreamHealthFailure = { kind, reason ->
            AppLogger.w(TAG, "USB stream health failure: kind=$kind reason=$reason")
            val device = currentUsbDevice
            if (device != null) {
                val deviceKey = usbLearnedPolicyKeyFor(device)
                UsbLearnedPolicyStore.recordFailure(deviceKey, kind)
                pendingUsbPolicyRestart = true
                AppLogger.w(TAG, "USB learned policy updated for generic fallback: key=$deviceKey kind=$kind")
            }
        }
        ffmpegPlayer.onUsbPlaybackStarted = {
            AppLogger.i(TAG, "onUsbPlaybackStarted")
            if (sharedUsbAudioEngine.isHardwareVolumeValidated()) {
                stickyUsbHardwareVolumeValidated = true
            }
            invalidateUsbSelfTest("usb_playback_started", clearSessionKey = true)
            lastAcceptedUsbSessionId = 0L
            lastAcceptedUsbRuntimeKey = null
            sharedUsbAudioEngine.refreshRuntimeSnapshotFromNative()
            refreshUsbCapabilities("usb_playback_started")
            _usbOutputSampleRate.value = ffmpegPlayer.usbActualOutputSampleRate
            onUsbExclusiveStreamingStarted()
            syncUsbRemoteVolumeRoute("usb_playback_started", force = true)
        }
        ffmpegPlayer.onUsbStreamHealthFailure = { kind, detail ->
            handleUsbStreamHealthFailure(kind, detail)
        }
        ffmpegPlayer.onUsbPlaybackDataFlowing = {
            AppLogger.i(TAG, "onUsbPlaybackDataFlowing: restoring steady-state USB gain")
            usbExclusiveManager.setStreamingState(true)
            val profile = buildUsbOutputProfile(exclusive = true)
            if (isUsbTrackSwitchVolumeHeld() && profile.volumePath == UsbVolumePath.HardwareUserVolume) {
                val holdReason = usbTrackSwitchVolumeHoldReason
                clearUsbTrackSwitchVolumeHold("usb_first_audio_flowing")
                scope.launch {
                    // Let new decoder PCM and native prefill settle before returning the DAC FU
                    // to user volume.  Restoring within the first few completions caused a
                    // brief loud edge/current noise on some DACs.
                    delay(240)
                    rampUsbHardwareVolumeDb(
                        usbNoDataSafeDb,
                        usbHwStepToDb(currentUsbHwStep()),
                        "usb_track_switch_warm_restore:$holdReason",
                        stepDelayMs = 10L,
                        cacheFinal = true
                    )
                }
            } else {
                applyUsbVolume(profile, "usb_first_audio_flowing")
            }
            scheduleUsbSelfTest("usb_first_audio_flowing")
            // Bit-perfect: the USB PCM payload must not be faded or
            // multiplied by a session envelope. Hardware volume remains available
            // only when the user explicitly selected and native validated it.
            val handle = sharedUsbAudioEngine.currentHandle
            if (handle != 0L) {
                val fadeMs = usbExclusiveStartupFadeInMs(profile)
                sharedUsbAudioEngine.setSessionVolumeScale(handle, 1.0f, fadeMs)
            } else {
                AppLogger.w(TAG, "onUsbPlaybackDataFlowing: currentHandle=0, skip session fade")
            }
        }
        ffmpegPlayer.onUsbPlaybackStopped = {
            usbExclusiveManager.setStreamingState(false)
        }
        ffmpegPlayer.usbExclusiveMode = true
        ffmpegPlayer.usbBitPerfectMode = AppPreferences.Player.bitPerfectEnabled
        ffmpegPlayer.onBeforeUsbNativeStart = {
            val profile = buildUsbOutputProfile(exclusive = true)
            AppLogger.i(TAG, "onBeforeUsbNativeStart: arming native session envelope bitPerfect=${profile.bitPerfect}")
            applyVolumeRoute("before_native_start")
            val handle = sharedUsbAudioEngine.currentHandle
            if (handle != 0L) {
                if (profile.bitPerfect || profile.volumePath == UsbVolumePath.HardwareUserVolume) {
                    // Strict bit-perfect and hardware-volume routes keep PCM at unity.
                    // In hardware-volume mode the transient startup guard lives in
                    // the Feature Unit and native restores the user's FU level on
                    // first real audio data.
                    sharedUsbAudioEngine.setSessionVolumeScale(handle, 1.0f, 0)
                } else {
                    // Software-volume path: start silent and fade in on first audio data.
                    sharedUsbAudioEngine.setSessionVolumeScale(handle, 0.0f, 0)
                }
            }
        }
        _usbExclusiveActive.value = true
        AppPreferences.Player.lastUsbExclusiveActive = true
        syncUsbSystemAudioKeepAlive("usb_exclusive_enabled")

        // 切换 MediaSession 到 remote volume 控制（后台音量键可用）
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
            }
            context.startService(intent)
        } catch (_: Exception) {}

        val shouldEnsureUsbForeground =
            _playState.value == PlayState.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        if (shouldEnsureUsbForeground) {
            try {
                val intent = android.content.Intent(context, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_USB_PLAYBACK_FOREGROUND
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        } else {
            AppLogger.i(TAG, "USB exclusive armed while not playing; skip foreground playback assertion")
        }

        AppLogger.i(TAG, "USB exclusive playback engine activated: ${device.productName}")

        // 通知：USB DAC 初始化成功
        android.widget.Toast.makeText(
            context,
            "USB DAC 初始化成功！",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        // 引导用户加入电池优化白名单（异步，不阻塞）
        try { onUsbExclusiveActivated?.invoke() } catch (_: Exception) {}

        // 激活独占后先保持无数据窗口安全音量，等真正 nativeStart/有数据后再恢复用户音量。
        applyUsbNoDataSafetyVolume("usb_exclusive_activated")
    }

    private var pendingEnableUsbExclusiveAfterPermission = false
    private var usbAttachPermissionPrefetchDeviceId = -1
    private var usbAttachPermissionPromptJob: Job? = null

    fun handleUsbDeviceAttachIntent(device: UsbDevice, reason: String = "activity_usb_attach_intent") {
        usbExclusiveManager.rememberDeviceOnly(device, reason = reason)
        scheduleUsbAttachPermissionPrompt(deviceHint = device, reason = reason)
    }

    fun requestUsbAttachPermissionIfPresent(reason: String = "foreground_usb_scan") {
        scheduleUsbAttachPermissionPrompt(deviceHint = null, reason = reason)
    }

    private fun setUsbAttachAliasEnabled(enabled: Boolean, reason: String) {
        val component = android.content.ComponentName(context.packageName, "${context.packageName}.UsbAttachActivityAlias")
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            AppLogger.i(TAG, "USB attach alias enabled=$enabled reason=$reason")
        } catch (e: Exception) {
            AppLogger.w(TAG, "USB attach alias toggle failed enabled=$enabled reason=$reason: ${e.message}")
        }
    }

    private fun scheduleUsbAttachPermissionPrompt(deviceHint: UsbDevice?, reason: String) {
        usbAttachPermissionPromptJob?.cancel()
        usbAttachPermissionPromptJob = scope.launch {
            if (deviceHint == null) {
                delay(80)
            } else {
                delay(30)
            }

            var device = deviceHint ?: usbExclusiveManager.findUsbAudioDevice()
            val deadline = SystemClock.elapsedRealtime() + 650L
            while (!isReleased && !appInBackground && device == null && SystemClock.elapsedRealtime() < deadline) {
                delay(50)
                device = usbExclusiveManager.findUsbAudioDevice()
            }

            val target = device
            val shouldPrompt = target != null && !appInBackground
            AppLogger.i(
                TAG,
                "USB attach permission prompt candidate: reason=$reason device=${target?.deviceName} " +
                    "shouldPrompt=$shouldPrompt playing=${_playState.value} " +
                    "lastExclusive=${AppPreferences.Player.lastUsbExclusiveActive} " +
                    "hasPerm=${target?.let { usbExclusiveManager.hasPermission(it) }}"
            )

            if (isReleased || target == null || !shouldPrompt || usbExclusiveManager.hasPermission(target)) {
                return@launch
            }

            if (_playState.value == PlayState.PLAYING) {
                usbAttachPermissionPrefetchDeviceId = -1
            } else if (!AppPreferences.Player.lastUsbExclusiveActive && !pendingEnableUsbExclusiveAfterPermission) {
                usbAttachPermissionPrefetchDeviceId = target.deviceId
            }
            AppLogger.i(TAG, "USB attach permission prefetch: request dialog for ${target.deviceName}")
            setUsbAttachAliasEnabled(true, "before_usb_permission:$reason")
            usbExclusiveManager.requestPermissionSafely(target)
        }
    }

    private fun deferUsbExclusiveActivationUntilForeground(device: UsbDevice, reason: String) {
        deferredUsbActivationDevice = device
        deferredUsbActivationReason = reason
        AppLogger.i(
            TAG,
            "Deferring USB exclusive activation until foreground: reason=$reason device=${device.deviceName}"
        )
    }

    private fun clearDeferredUsbExclusiveActivation(reason: String) {
        val device = deferredUsbActivationDevice
        deferredUsbActivationDevice = null
        deferredUsbActivationReason = ""
        if (device != null) {
            AppLogger.i(
                TAG,
                "Cleared deferred USB activation: reason=$reason device=${device.deviceName}"
            )
        }
    }

    private fun tryActivateDeferredUsbExclusiveOnForeground(reason: String) {
        val deferred = deferredUsbActivationDevice ?: return
        val originalReason = deferredUsbActivationReason
        deferredUsbActivationDevice = null
        deferredUsbActivationReason = ""
        val resolved = usbExclusiveManager.findUsbAudioDevice()
            ?.takeIf { it.deviceId == deferred.deviceId }
            ?: deferred.takeIf { usbExclusiveManager.hasPermission(it) }
        if (resolved == null || !usbExclusiveManager.hasPermission(resolved)) {
            AppLogger.w(
                TAG,
                "Deferred USB activation dropped: reason=$reason original=$originalReason device=${deferred.deviceName}"
            )
            return
        }
        AppLogger.i(
            TAG,
            "Resuming deferred USB activation on foreground: reason=$reason original=$originalReason device=${resolved.deviceName}"
        )
        scope.launch { enableUsbExclusiveAfterPermission(resolved) }
    }

    /** USB 独占入口：有权限直接切，没权限先请求，等回调续接。 */
    fun enableUsbExclusive() {
        AppLogger.i(TAG, "enableUsbExclusive ENTER")
        ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(true, "enable_usb_exclusive_request")

        if (_usbExclusiveActive.value && usbExclusiveManager.isDeviceConnected()) {
            if (ffmpegPlayer.usbExclusiveMode) {
                AppLogger.i(TAG, "USB exclusive already enabled")
                return
            }
            // 旧版本可能只设置了 _usbExclusiveActive/policy，却没有把 FfmpegAudioPlayer 切到 USB renderer。
            // 这种半激活状态不能直接 return，否则授权后看起来“没有反应”。
            AppLogger.w(TAG, "USB exclusive flag is active but playerUsb=false; continue activation")
            _usbExclusiveActive.value = false
            syncUsbSystemAudioKeepAlive("usb_exclusive_half_active_reset")
        }

        val device = usbExclusiveManager.findUsbAudioDevice()
        if (device == null) {
            AppLogger.w(TAG, "enableUsbExclusive: no USB audio device found")
            return
        }

        AppLogger.i(TAG, "enableUsbExclusive: device=${device.deviceName} vid=${device.vendorId} pid=${device.productId}")

        if (!usbExclusiveManager.hasPermission(device)) {
            AppLogger.i(TAG, "enableUsbExclusive: no permission, requesting USB permission")
            pendingEnableUsbExclusiveAfterPermission = true
            usbExclusiveManager.requestPermissionSafely(device)
            return
        }

        pendingEnableUsbExclusiveAfterPermission = false
        scope.launch { enableUsbExclusiveAfterPermission(device) }
    }

    /** USB 权限回调：授权成功后续接 enableUsbExclusive。 */
    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        AppLogger.i(TAG, "USB permission result: granted=$granted pendingEnable=$pendingEnableUsbExclusiveAfterPermission device=${device.deviceName}")

        if (!granted) {
            pendingEnableUsbExclusiveAfterPermission = false
            clearDeferredUsbExclusiveActivation("usb_permission_denied")
            ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "usb_permission_denied")
            AppLogger.w(TAG, "USB permission denied")
            return
        }

        if (pendingEnableUsbExclusiveAfterPermission) {
            pendingEnableUsbExclusiveAfterPermission = false
            if (appInBackground) {
                deferUsbExclusiveActivationUntilForeground(device, "permission_granted_background_pending_enable")
            } else {
                scope.launch { enableUsbExclusiveAfterPermission(device) }
            }
        }
    }

    /** 切歌前 USB 淡出：避免旧流尾巴和新流第一包硬切产生爆音。 */
    private suspend fun fadeOutUsbBeforeCutover() {
        val h = sharedUsbAudioEngine.currentHandle
        if (h != 0L && _usbExclusiveActive.value) {
            runCatching { sharedUsbAudioEngine.armStopFade(12) }
            kotlinx.coroutines.delay(16)
        }
    }

    private fun prepareUsbExclusiveColdActivation(reason: String) {
        invalidateUsbSelfTest(reason, clearSessionKey = true)
        lastAcceptedUsbSessionId = 0L
        lastAcceptedUsbRuntimeKey = null
        suppressSystemVolumeObserver(1800L, "prepareUsbExclusiveColdActivation:$reason")
        AppLogger.w(
            TAG,
            "USB exclusive cold activation: reason=$reason handle=0x${sharedUsbAudioEngine.currentHandle.toString(16)} " +
                "connOpen=${usbExclusiveManager.hasOpenConnection()} state=${usbExclusiveManager.getCurrentState()}"
        )
        usbExclusiveManager.resetPlaybackPipeline("cold_activation:$reason")
        sharedUsbAudioEngine.clearState()
    }

    /** 真正的渲染器切换逻辑，权限确认后调用。 */
    private suspend fun activateUsbExclusiveAfterPermission(device: UsbDevice, reason: String = ""): Boolean {
        AppLogger.i(TAG, "activateUsbExclusiveAfterPermission ENTER: reason=$reason device=${device.productName}")
        return enableUsbExclusiveAfterPermission(device)
    }

    private suspend fun enableUsbExclusiveAfterPermission(device: UsbDevice): Boolean {
        AppLogger.i(TAG, "enableUsbExclusiveAfterPermission ENTER")

        // 防止重复激活：已激活则跳过，正在切换则跳过
        if (_usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode) {
            AppLogger.i(TAG, "enableUsbExclusiveAfterPermission: already active, skip")
            return true
        }
        if (_isRenderSwitching.value) {
            AppLogger.w(TAG, "enableUsbExclusiveAfterPermission: render switching in progress, skip")
            return false
        }

        val song = _currentSong.value
        val queue = _queue.value
        val pos = _position.value.coerceAtLeast(0L)

        val wasPlaying =
            _playState.value == PlayState.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING

        val wasActive = wasPlaying ||
            _playState.value == PlayState.PAUSED ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED

        AppLogger.i(TAG, "USB exclusive cutover: wasPlaying=$wasPlaying wasActive=$wasActive pos=${pos}ms song=${song?.title}")

        _isRenderSwitching.value = true
        return try {
            if (wasActive) {
                // 切歌前淡出，避免爆音
                fadeOutUsbBeforeCutover()
                runCatching { ffmpegPlayer.stopForUsbExclusiveCutover() }
                    .onFailure { AppLogger.w(TAG, "USB cutover: stop+drain old renderer failed", it) }
                stopProgressUpdate()
                delay(160)
            }

            if (song != null) {
                queueRendererRestartSeek(
                    song = song,
                    displayPositionMs = pos,
                    reason = "usb_exclusive_permission_cutover"
                )
            }

            prepareUsbExclusiveColdActivation(
                reason = "permission_cutover playing=$wasPlaying active=$wasActive device=${device.deviceId}"
            )
            activateUsbEngineForPlayback(device)

            AppLogger.i(TAG, "USB exclusive activated: song=${song?.title} wasActive=$wasActive wasPlaying=$wasPlaying")

            if (song != null && wasActive) {
                clearPlayRequestDedup("usb_exclusive_cutover_replay:${song.path}")
                smTransition(PlayState.PREPARING, "enableUsbExclusive")
                if (queue.songs.isNotEmpty() && queue.currentIndex in queue.songs.indices) {
                    play(song, queue.songs, queue.currentIndex)
                } else {
                    play(song)
                }
                if (!wasPlaying) {
                    delay(500)
                    pause()
                }
            } else {
                AppLogger.i(TAG, "USB exclusive renderer is armed; playback will init USB when a song starts")
            }

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to restart playback after USB exclusive enable", e)
            smTransition(PlayState.PAUSED, "enableUsbExclusive_paused")
            false
        } finally {
            if (!_usbExclusiveActive.value || !ffmpegPlayer.usbExclusiveMode) {
                ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "enable_usb_exclusive_after_permission_finally")
            }
            delay(600)
            _isRenderSwitching.value = false
        }
    }

    fun disableUsbExclusive() {
        AppLogger.i(TAG, "Disabling USB exclusive mode")
        usbSystemAudioKeepAlive.stop("disableUsbExclusive")

        val wasUsingUsb = ffmpegPlayer.usbExclusiveMode || _usbExclusiveActive.value
        if (wasUsingUsb) {
            try {
                // 停止解码
                ffmpegPlayer.stop()
            } catch (_: Exception) {
            }
            // Hard stop：仅在关闭独占时使用
            sharedUsbAudioEngine.hardStopUsb("disableUsbExclusive")
        }

        // 切回本地音量控制
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME"
            }
            context.startService(intent)
        } catch (_: Exception) {}

        clearUsbExclusiveState(
            releaseManager = true,
            notifyNativeDetached = true,
            clearLastExclusivePreference = true
        )

        // 渲染切换延迟保护，确保USB资源完全释放后再允许AudioTrack播放
        if (wasUsingUsb) {
            AppLogger.i(TAG, "USB exclusive mode disabled, applying render switch delay protection")
            // 标记渲染切换中，防止立即播放导致资源冲突
            _isRenderSwitching.value = true
        }

        // 关闭独占时关闭硬件音量（没有独占就没有意义）
        AppPreferences.Player.hardwareFeatureUnitEnabled = false
        unregisterSystemVolumeObserver()

        // 音量路由切回系统音量
        sharedUsbAudioEngine.nativeSetPolicy(exclusive = false, bitPerfect = false, hwVol = false)
        sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
        applyVolumeRoute("usb_exclusive_disabled")

        AppLogger.i(TAG, "USB exclusive mode disabled")
    }

    private fun handleUsbDeviceDetached(detachedDevice: UsbDevice?) {
        AppLogger.i(TAG, "Handling USB device detached: ${detachedDevice?.deviceName}")
        val songBeforeDetach = _currentSong.value
        val queueBeforeDetach = _queue.value
        val positionBeforeDetach = _position.value.coerceAtLeast(0L)
        val wasPlaying =
            _playState.value == PlayState.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        val wasActive =
            wasPlaying ||
                _playState.value == PlayState.PAUSED ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED
        // USB断开时立即暂停播放，确保状态同步
        if (_playState.value == PlayState.PLAYING) {
            smTransition(PlayState.PAUSED, "auto_pause")
            stopProgressUpdate()
        }
        try {
            ffmpegPlayer.stop()
        } catch (_: Exception) {
        }
        // Hard stop：设备拔出
        sharedUsbAudioEngine.hardStopUsb("usb_device_detached")
        unregisterSystemVolumeObserver()
        clearUsbExclusiveState(
            releaseManager = false,
            notifyNativeDetached = false,
            clearLastExclusivePreference = false
        )
        if (wasActive && songBeforeDetach != null && AppPreferences.Player.lastUsbExclusiveActive) {
            scheduleUsbDetachedAutoRecover(
                detachedDevice = detachedDevice,
                song = songBeforeDetach,
                queue = queueBeforeDetach,
                positionMs = positionBeforeDetach,
                resumePlayback = wasPlaying
            )
        } else {
            notifyUsbDetachConfirmed("usb_detached_inactive")
        }
        // 释放 USB WakeLock
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.RELEASE_USB_WAKELOCK"
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    private fun scheduleUsbDetachedAutoRecover(
        detachedDevice: UsbDevice?,
        song: AudioFile,
        queue: PlayQueue,
        positionMs: Long,
        resumePlayback: Boolean
    ) {
        usbDetachRecoveryJob?.cancel()
        usbDetachRecoveryJob = scope.launch {
            AppLogger.w(
                TAG,
                "USB detached recovery armed: detached=${detachedDevice?.deviceName} " +
                    "song=${song.title} pos=$positionMs resume=$resumePlayback"
            )

            val deadline = SystemClock.elapsedRealtime() + 2_200L
            var recoveredDevice: UsbDevice? = null
            while (!isReleased && SystemClock.elapsedRealtime() < deadline) {
                delay(180)
                val candidate = usbExclusiveManager.findUsbAudioDevice() ?: continue
                if (!usbExclusiveManager.hasPermission(candidate)) {
                    AppLogger.w(
                        TAG,
                        "USB detached recovery found device without permission: ${candidate.deviceName}"
                    )
                    continue
                }
                recoveredDevice = candidate
                break
            }

            val device = recoveredDevice
            if (device == null) {
                AppLogger.w(TAG, "USB detached recovery failed: no permitted USB DAC returned")
                notifyUsbDetachConfirmed("usb_detach_recovery_timeout")
                return@launch
            }

            runCatching {
                AppLogger.w(
                    TAG,
                    "USB detached recovery: DAC returned as ${device.productName}; rearming exclusive"
                )
                currentUsbDevice = device
                usbExclusiveManager.rememberDeviceOnly(device, reason = "usb_detach_auto_recover")
                prepareUsbExclusiveColdActivation("usb_detach_auto_recover:${song.path}")
                activateUsbEngineForPlayback(device)
                queueRendererRestartSeek(
                    song = song,
                    displayPositionMs = positionMs,
                    reason = "usb_detach_auto_recover"
                )
                clearPlayRequestDedup("usb_detach_auto_recover")

                if (resumePlayback) {
                    smForceTransition(PlayState.PREPARING, "usb_detach_auto_recover")
                    if (queue.songs.isNotEmpty() && queue.currentIndex in queue.songs.indices) {
                        play(song, queue.songs, queue.currentIndex)
                    } else {
                        play(song)
                    }
                } else {
                    _currentSong.value = song
                    _queue.value = queue
                    _position.value = positionMs
                    smForceTransition(PlayState.PAUSED, "usb_detach_auto_recover_paused")
                    saveState()
                }

                android.widget.Toast.makeText(
                    context,
                    "USB DAC 已恢复",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                AppLogger.w(TAG, "USB detached recovery failed while rearming", error)
                notifyUsbDetachConfirmed("usb_detach_rearm_failed")
            }
        }
    }

    private fun notifyUsbDetachConfirmed(reason: String) {
        AppLogger.w(TAG, "USB detach confirmed: reason=$reason")
        android.widget.Toast.makeText(
            context,
            "DAC 已拔出",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun clearUsbExclusiveState(
        releaseManager: Boolean,
        notifyNativeDetached: Boolean,
        clearLastExclusivePreference: Boolean = false
    ) {
        ffmpegPlayer.usbExclusiveMode = false
        ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "clear_usb_exclusive_state")
        ffmpegPlayer.usbActualOutputSampleRate = 0
        ffmpegPlayer.usbPrepareForPlayback = null
        ffmpegPlayer.onUsbTransportLost = null
        ffmpegPlayer.onUsbPlaybackStarted = null
        ffmpegPlayer.onUsbPlaybackDataFlowing = null
        ffmpegPlayer.onUsbStreamHealthFailure = null
        ffmpegPlayer.onUsbPlaybackStopped = null
        ffmpegPlayer.shouldDeferUsbHardRecovery = null
        ffmpegPlayer.onBeforeUsbNativeStart = null
        _usbOutputSampleRate.value = 0
        runCatching { unregisterSystemVolumeObserver() }

        if (notifyNativeDetached) {
            try {
                sharedUsbAudioEngine.nativeOnUsbDetached()
            } catch (_: Exception) {
            }
        }

        try {
            sharedUsbAudioEngine.release()
        } catch (_: Exception) {
        }

        currentUsbDevice = null
        usbFeedbackRejectedTransportKey = null
        usbFeedbackRejectedReason = ""
        clearDeferredUsbExclusiveActivation("clear_usb_exclusive_state")
        if (releaseManager) {
            usbExclusiveManager.release("disableUsbExclusive")
        }
        _usbExclusiveActive.value = false
        syncUsbSystemAudioKeepAlive("clear_usb_exclusive_state")
        if (clearLastExclusivePreference) {
            AppPreferences.Player.lastUsbExclusiveActive = false
        }
        appInBackground = false
        appBackgroundEnteredAtElapsedMs = 0L
        stickyUsbHardwareVolumeValidated = false
        invalidateUsbSelfTest("clear_usb_exclusive_state", clearSessionKey = true)
        lastUsbRemoteVolumeDesired = null
        syncUsbRemoteVolumeRoute("clear_usb_exclusive_state", force = true)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            audioFocusGranted = false
            duckVolumeFactor = 1.0f
        } catch (_: Exception) {
        }

        releaseUsbPlaybackWakeLock("clear_usb_exclusive_state")
        PlayerService.clearUsbMediaIdentityFromController("clear_usb_exclusive_state", true)
        sendUsbMediaIdentityIntent(
            reason = "clear_usb_exclusive_state",
            song = _currentSong.value,
            forcePosition = _position.value,
            playing = false
        )

        // 释放 USB 专用 WakeLock
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.RELEASE_USB_WAKELOCK"
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    // ========== USB 策略 UI 接口 ==========

    /** USB 独占模式是否激活 */
    fun isUsbExclusiveActive(): Boolean = _usbExclusiveActive.value

    fun getUsbOutputSampleRate(): Int {
        return _usbOutputSampleRate.value
    }

    fun getUsbDeviceName(): String? {
        if (!_usbExclusiveActive.value) return null
        return currentUsbDevice?.productName
    }

    /** 设置完美比特模式。需要独占模式已激活，否则返回 -1。
     *  开启时会自动暂停当前播放，等待用户再次播放后重新初始化生效。 */
    fun setUsbBitPerfectEnabled(enabled: Boolean): Int {
        if (enabled && !_usbExclusiveActive.value) return -1

        val exclusive = _usbExclusiveActive.value

        // 硬件音量不是 bit-perfect 专属：非完美比特也可以用 USB Feature Unit。
        // 切换 bit-perfect 时只改变 PCM/DSP/重采样策略，不隐式关闭用户选择的硬件音量。
        val hwVol = AppPreferences.Player.hardwareFeatureUnitEnabled && exclusive

        AppPreferences.Player.bitPerfectEnabled = enabled
        ffmpegPlayer.usbBitPerfectMode = enabled

        sharedUsbAudioEngine.nativeSetPolicy(exclusive, enabled, hwVol)

        if (sharedUsbAudioEngine.isInitialized() && currentUsbDevice != null) {
            AppLogger.i(TAG, "Bit-perfect change requires full USB restart: enabled=$enabled hwVol=$hwVol")
            val wasPlaying = _playState.value == PlayState.PLAYING
            if (wasPlaying) pause()

            val ok = restartUsbWithPolicy(exclusive, enabled, hwVol)
            if (ok && enabled) {
                android.widget.Toast.makeText(context, "完美比特已开启", android.widget.Toast.LENGTH_SHORT).show()
            }
            return if (ok) 0 else -2
        }

        AppLogger.i(TAG, "Bit-perfect set to $enabled, will take effect on next playback")
        return 0
    }

    /** 设置 USB DAC 音量模式：0=软件音量, 1=硬件音量, 2=数字固定 0dB。 */
    fun setUsbVolumeMode(mode: Int): Int {
        val normalized = mode.coerceIn(0, 2)
        val exclusive = _usbExclusiveActive.value
        AppPreferences.Player.usbVolumeMode = normalized
        AppPreferences.Player.hardwareFeatureUnitEnabled = normalized == 1
        if (normalized == 2) {
            AppPreferences.Player.volume = 1.0f
            AppPreferences.Player.bitPerfectEnabled = true
            ffmpegPlayer.usbBitPerfectMode = true
            forceUsbFixedVolume0Db("setUsbVolumeMode")
        }
        AppLogger.i(TAG, "setUsbVolumeMode: mode=$normalized exclusive=$exclusive")

        if (!exclusive) {
            sharedUsbAudioEngine.nativeSetPolicy(false, false, false)
            return 0
        }

        val profile = buildUsbOutputProfile(exclusive = true)
        sharedUsbAudioEngine.nativeSetPolicy(
            exclusive = true,
            bitPerfect = profile.bitPerfect || profile.fixedDigitalVolume,
            hwVol = profile.hardwareVolumeRequested
        )
        applyVolumeRoute("setUsbVolumeMode:$normalized")
        return applyUsbOutputSettingsChanged(userInitiated = true)
    }

    /** 设置硬件 Feature Unit 音量控制。非完美比特和完美比特都可用。
     *  注意：当前 handle 如果还在软件音量模式，不能在旧 handle 上预验证；必须先把
     *  hwVolRequested 写入 policy，再完整重建 native handle，让 nativeInit 解析 AC
     *  topology 后验证 Feature Unit。 */
    fun setUsbHardwareFeatureUnitEnabled(enabled: Boolean): Int {
        val exclusive = _usbExclusiveActive.value

        AppLogger.i(TAG, "setUsbHardwareFeatureUnitEnabled: enabled=$enabled exclusive=$exclusive")

        if (enabled && !exclusive) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
            stickyUsbHardwareVolumeValidated = false
            AppLogger.w(TAG, "Hardware volume can only be enabled in USB exclusive mode")
            android.widget.Toast.makeText(context, "硬件音量仅在 USB 独占模式下可用", android.widget.Toast.LENGTH_SHORT).show()
            applyVolumeRoute("hardware_volume_rejected_non_exclusive")
            return -1
        }

        AppPreferences.Player.hardwareFeatureUnitEnabled = enabled
        AppPreferences.Player.usbVolumeMode = if (enabled) 1 else 0

        val hwRequested = exclusive && enabled
        val liveHandle = sharedUsbAudioEngine.currentHandle

        // Route switch: turning hardware volume OFF should not force
        // a USB profile rebuild.  The stream profile, clock, alt setting and ISO
        // runtime are still valid; only the volume controller path changes.
        // Keep playback running and move gain control back to the software path.
        if (!enabled) {
            stickyUsbHardwareVolumeValidated = false
            sharedUsbAudioEngine.nativeSetPolicy(
                exclusive = exclusive,
                bitPerfect = AppPreferences.Player.bitPerfectEnabled && exclusive,
                hwVol = false
            )
            applyVolumeRoute("hardware_volume_disabled_live")
            syncUsbRemoteVolumeRoute("hardware_volume_disabled_live", force = true)
            android.widget.Toast.makeText(context, "硬件音量已关闭", android.widget.Toast.LENGTH_SHORT).show()
            AppLogger.i(TAG, "Hardware volume disabled without USB profile restart")
            return 0
        }

        sharedUsbAudioEngine.nativeSetPolicy(
            exclusive = exclusive,
            bitPerfect = AppPreferences.Player.bitPerfectEnabled && exclusive,
            hwVol = hwRequested
        )

        if (hwRequested) {
            // Route switch: if this handle already validated a
            // Feature Unit earlier, nativeSetPolicy can re-enable the cached
            // controller immediately.  Do not restart the USB stream just to
            // switch volume routing.
            sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
            val canLiveHw = liveHandle != 0L && sharedUsbAudioEngine.nativeCanControlVolume(liveHandle)
            val policy = sharedUsbAudioEngine.getHardwareVolumePolicyString()
            AppLogger.i(TAG, "Hardware volume requested; liveCanControl=$canLiveHw policy=$policy")
            if (exclusive && canLiveHw) {
                val step = currentUsbHwStep()
                setUsbHardwareVolumeStep(step, "hardware_volume_enabled_live")
                applyVolumeRoute("hardware_volume_enabled_live")
                try {
                    val intent = android.content.Intent(context, PlayerService::class.java).apply {
                        action = "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
                    }
                    context.startService(intent)
                } catch (_: Exception) {}
                android.widget.Toast.makeText(context, "硬件音量已启用", android.widget.Toast.LENGTH_SHORT).show()
                return 0
            }
        }

        if (exclusive && liveHandle != 0L) {
            scope.launch {
                val h0 = sharedUsbAudioEngine.currentHandle
                val canLiveHwBeforeRestart = hwRequested && h0 != 0L && sharedUsbAudioEngine.nativeCanControlVolume(h0)
                if (canLiveHwBeforeRestart) {
                    val step = currentUsbHwStep()
                    AppLogger.i(TAG, "Hardware volume live enable succeeded before restart, applying step=$step db=${usbHwStepToDb(step)}")
                    setUsbHardwareVolumeStep(step, "hardware_volume_live_before_restart")
                    applyVolumeRoute("hardware_volume_live_before_restart")
                    try {
                        val intent = android.content.Intent(context, PlayerService::class.java).apply {
                            action = "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
                        }
                        context.startService(intent)
                    } catch (_: Exception) {}
                    return@launch
                }

                val ok = restartUsbWithPolicy(exclusive, AppPreferences.Player.bitPerfectEnabled, hwRequested)
                kotlinx.coroutines.delay(300)
                val h = sharedUsbAudioEngine.currentHandle
                val canHw = hwRequested && h != 0L && sharedUsbAudioEngine.nativeCanControlVolume(h)
                val policy = sharedUsbAudioEngine.getHardwareVolumePolicyString()
                val requestStillOn = AppPreferences.Player.hardwareFeatureUnitEnabled
                AppLogger.i(TAG, "Hardware volume post-restart: ok=$ok canHw=$canHw requestStillOn=$requestStillOn policy=$policy")
                if (hwRequested && !canHw) {
                    // Separation: keep the user's hardware-volume
                    // request even if this particular check races with a full
                    // reinit or the controller is not validated yet.  The
                    // current audio path will remain Fixed/Software until native
                    // proves a usable controller, but we must not flip the
                    // preference back to false; otherwise the next native init
                    // receives hwVolRequested=0 and never probes Feature Unit.
                    sharedUsbAudioEngine.nativeSetPolicy(
                        exclusive = exclusive,
                        bitPerfect = AppPreferences.Player.bitPerfectEnabled && exclusive,
                        hwVol = true
                    )
                    confirmUsbHardwareVolumeEnabledLater()
                    val pendingMessage = if (h == 0L || !ok) {
                        "硬件音量请求已保留，将在 USB 重新初始化后继续验证"
                    } else {
                        "硬件音量控制器暂未验证，已保留请求并继续使用固定/软件音量"
                    }
                    android.widget.Toast.makeText(context, pendingMessage, android.widget.Toast.LENGTH_SHORT).show()
                } else if (canHw) {
                    val step = currentUsbHwStep()
                    AppLogger.i(TAG, "Hardware volume validated, applying step=$step db=${usbHwStepToDb(step)}")
                    setUsbHardwareVolumeStep(step, "hardware_volume_validated")
                    android.widget.Toast.makeText(context, "硬件音量已启用", android.widget.Toast.LENGTH_SHORT).show()
                } else if (!enabled) {
                    android.widget.Toast.makeText(context, "硬件音量已关闭", android.widget.Toast.LENGTH_SHORT).show()
                }
                applyVolumeRoute("hardware_volume_after_restart")
                try {
                    val intent = android.content.Intent(context, PlayerService::class.java).apply {
                        action = if (canHw) {
                            "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
                        } else {
                            "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME"
                        }
                    }
                    context.startService(intent)
                } catch (_: Exception) {}
            }
        } else {
            applyVolumeRoute("hardware_volume_changed_non_exclusive")
            try {
                val intent = android.content.Intent(context, PlayerService::class.java).apply {
                    action = "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME"
                }
                context.startService(intent)
            } catch (_: Exception) {}
            android.widget.Toast.makeText(
                context,
                if (enabled) "硬件音量将在下次 USB 初始化后验证" else "硬件音量已关闭",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return 0
    }

    private fun confirmUsbHardwareVolumeEnabledLater() {
        scope.launch {
            repeat(10) { index ->
                delay(300)
                val h = sharedUsbAudioEngine.currentHandle
                if (h != 0L && sharedUsbAudioEngine.nativeCanControlVolume(h)) {
                    AppLogger.i(TAG, "Hardware volume confirmed after restart: attempt=${index + 1}")
                    applyComposedVolume()
                    android.widget.Toast.makeText(context, "硬件音量已启用", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            val policy = sharedUsbAudioEngine.getHardwareVolumePolicyString()
            AppLogger.w(TAG, "Hardware volume not confirmed yet; request kept for next init. policy=$policy")
            android.widget.Toast.makeText(context, "硬件音量请求已保留，等待下一次 USB 初始化验证", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 后台冷启动保护：在 USB nativeStart 之前确保前台服务 + WakeLock 已就位。 */
    private fun ensureUsbForegroundImmediate(reason: String) {
        AppLogger.i(TAG, "ensureUsbForegroundImmediate: $reason")
        acquireUsbPlaybackWakeLock(reason)
        try {
            val intent = android.content.Intent(context, com.rawsmusic.module.player.PlayerService::class.java).apply {
                action = PlayerService.ACTION_USB_PLAYBACK_FOREGROUND
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to start USB foreground service immediately: reason=$reason", t)
        }
    }

    private suspend fun ensureUsbForegroundBeforeStart(reason: String) {
        withContext(Dispatchers.Main.immediate) {
            AppLogger.i(TAG, "ensureUsbForegroundBeforeStart: $reason")
            acquireUsbPlaybackWakeLock(reason)
            try {
                val intent = android.content.Intent(context, com.rawsmusic.module.player.PlayerService::class.java).apply {
                    action = PlayerService.ACTION_USB_PLAYBACK_FOREGROUND
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to start USB foreground service before start", t)
            }
        }
        // 给 Service 时间进入 foreground
        kotlinx.coroutines.delay(150)
    }


    private data class UsbPcmFormatRequest(
        val mode: UsbPcmOutputMode,
        val targetValidBits: Int,
        val targetSubslotBytes: Int
    )

    private fun resolveUsbPcmFormatRequest(): UsbPcmFormatRequest {
        val mode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode)
        return when (mode) {
            UsbPcmOutputMode.AUTO -> UsbPcmFormatRequest(mode, 0, 0)
            UsbPcmOutputMode.PCM_16 -> UsbPcmFormatRequest(mode, 16, 2)
            UsbPcmOutputMode.PCM_24_PACKED -> UsbPcmFormatRequest(mode, 24, 3)
            UsbPcmOutputMode.PCM_24_IN_32 -> UsbPcmFormatRequest(mode, 24, 4)
            UsbPcmOutputMode.PCM_32 -> UsbPcmFormatRequest(mode, 32, 4)
        }
    }

    private fun currentSongIsDsdSource(): Boolean {
        val song = _currentSong.value ?: return false
        return song.isDsdSourceFile() || song.bitsPerSample == 1 || song.sampleRate >= 2_822_400
    }

    private fun currentSongDsdSourceRate(): Int {
        val song = _currentSong.value
        val metadataRate = song?.sampleRate?.takeIf { it > 0 }
        if (metadataRate != null) return normalizeDsdSourceRateHz(metadataRate)
        val path = song?.path
        val probedRate = path?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) }
            ?.takeIf { it > 0 }
        return probedRate?.let(::normalizeProbedDsdSourceRateHz) ?: 2_822_400
    }

    private fun currentPcmSourceRateForDsd(): Int {
        val song = _currentSong.value
        val path = song?.path?.takeIf { it.isNotBlank() }
        val metadataRate = song?.sampleRate?.takeIf { it > 0 && it < 2_822_400 }
        if (metadataRate != null) return metadataRate
        val probedRate = path?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) }
            ?.takeIf { it > 0 && it < 2_822_400 }
        if (probedRate != null) return probedRate
        return sharedUsbAudioEngine.currentSampleRate.takeIf { it > 0 } ?: 44_100
    }

    private data class UsbPolicyRestartSource(
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val sourcePath: String?
    )

    private fun resolveUsbPolicyRestartSource(): UsbPolicyRestartSource {
        val song = _currentSong.value
        val sourcePath = song?.path?.takeIf { it.isNotBlank() }
        val probedRate = sourcePath?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) } ?: 0
        val probedBits = sourcePath?.let { runCatching { FFmpegBridge.probeBitsPerSample(it) }.getOrDefault(0) } ?: 0
        val probedChannels = sourcePath?.let { runCatching { FFmpegBridge.probeChannelCount(it) }.getOrDefault(0) } ?: 0
        val runtimeRate = sharedUsbAudioEngine.currentSampleRate
        val runtimeBits = sharedUsbAudioEngine.currentBits
        val runtimeChannels = sharedUsbAudioEngine.currentChannels
        val inferredDsd =
            song?.isDsdSourceFile() == true ||
                song?.bitsPerSample == 1 ||
                probedBits == 1 ||
                song?.sampleRate?.let { it >= 2_822_400 } == true ||
                probedRate >= 2_822_400
        val sampleRate = listOf(song?.sampleRate ?: 0, probedRate, runtimeRate, 48_000)
            .firstOrNull { it > 0 } ?: 48_000
        val bitsPerSample = if (inferredDsd) {
            listOf(song?.bitsPerSample ?: 0, probedBits, 1).firstOrNull { it > 0 } ?: 1
        } else {
            listOf(song?.bitsPerSample ?: 0, probedBits, runtimeBits, 16).firstOrNull { it > 0 } ?: 16
        }
        val channels = listOf(song?.channelCount ?: 0, probedChannels, runtimeChannels, 2)
            .firstOrNull { it > 0 } ?: 2
        AppLogger.i(
            TAG,
            "resolveUsbPolicyRestartSource: path=$sourcePath inferredDsd=$inferredDsd " +
                "metadata=${song?.sampleRate ?: 0}/${song?.bitsPerSample ?: 0}/${song?.channelCount ?: 0} " +
                "probed=$probedRate/$probedBits/$probedChannels runtime=$runtimeRate/$runtimeBits/$runtimeChannels " +
                "resolved=$sampleRate/$bitsPerSample/$channels"
        )
        return UsbPolicyRestartSource(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            sourcePath = sourcePath
        )
    }
    private fun currentEffectiveUsbDsdMode(): com.rawsmusic.module.player.usb.UsbDsdModeConfig? {
        val caps = _usbCapabilities.value ?: sharedUsbAudioEngine.getDeviceCapabilities()
        val requestedTransport = UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode)
        return if (currentSongIsDsdSource()) {
            buildSupportedDsdSourceDirectModeConfig(
                sourceDsdRateHz = currentSongDsdSourceRate(),
                requestedTransport = requestedTransport,
                capabilities = caps
            )
        } else {
            buildSupportedPcmToDsdModeConfig(
                // PCM→DSD is an output transform and must override PCM bit-perfect.
                // Blocking it here created a circular failure: selecting DSD256 while
                // bit-perfect was still true made the profile report rate=DSD256 but
                // nativeSetDsdConversion(enabled=0), so playback silently fell back to
                // plain PCM.  Keep the user preference active and make buildUsbOutputProfile()
                // turn PCM bit-perfect off for this session.
                enabled = AppPreferences.Player.dsdConversionEnabled,
                multiplier = AppPreferences.Player.dsdRate,
                requestedTransport = requestedTransport,
                capabilities = caps,
                sourceSampleRate = currentPcmSourceRateForDsd()
            )
        }
    }

    private fun currentEffectiveUsbDsdRate(): Int {
        return if (currentSongIsDsdSource()) {
            dsdMultiplierFromSourceRate(currentSongDsdSourceRate())
        } else {
            AppPreferences.Player.dsdRate
        }
    }

    fun refreshUsbCapabilities(reason: String) {
        val caps = sharedUsbAudioEngine.getDeviceCapabilities()
        _usbCapabilities.value = caps
        AppLogger.i(TAG, "USB capabilities refreshed: reason=$reason rates=${caps?.supportedSampleRates} modes=${caps?.supportedPcmModes}")
        reconcileUsbOutputSettingsWithCapabilities(caps)
    }

    private fun reconcileUsbOutputSettingsWithCapabilities(
        caps: com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?
    ) {
        if (caps == null) return
        val selectedRate = AppPreferences.Player.usbTargetSampleRate
        if (selectedRate != 0 && selectedRate !in caps.supportedSampleRates) {
            AppLogger.w(TAG, "Selected USB sample rate unsupported: $selectedRate, fallback to AUTO")
            AppPreferences.Player.usbTargetSampleRate = 0
        }
        val selectedMode = com.rawsmusic.module.player.usb.UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode)
        if (selectedMode != com.rawsmusic.module.player.usb.UsbPcmOutputMode.AUTO &&
            selectedMode !in caps.supportedPcmModes) {
            AppLogger.w(TAG, "Selected USB PCM mode unsupported: $selectedMode, fallback to AUTO")
            AppPreferences.Player.usbPcmOutputMode = com.rawsmusic.module.player.usb.UsbPcmOutputMode.AUTO.id
        }
        if (AppPreferences.Player.dsdConversionEnabled) {
            val dsdRate = AppPreferences.Player.dsdRate
            val exactNativeSupported = caps.supportsNativeDsd(dsdRate)
            if (!exactNativeSupported && !caps.hasAnyNativeDsdDescriptor) {
                AppLogger.w(
                    TAG,
                    "PCM→DSD Native DSD not confirmed by current capability snapshot: DSD$dsdRate; " +
                        "preserve user preference and let runtime/native re-verify on playback"
                )
            } else if (!exactNativeSupported) {
                AppLogger.w(
                    TAG,
                    "PCM→DSD Native DSD rate DSD$dsdRate not explicitly confirmed by descriptors; " +
                        "Native DSD descriptor exists, keep conversion enabled and let native init verify"
                )
            } else if (UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode) != UsbDsdTransport.NATIVE) {
                AppLogger.i(TAG, "PCM→DSD uses Native DSD transport; preserving DoP preference only for DSD source files")
            }
        }
    }

    private fun usbLearnedPolicyKeyFor(device: android.hardware.usb.UsbDevice): String {
        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        val sourceDsd = currentSongIsDsdSource()
        val pcmToDsd = effectiveDsdMode != null && !sourceDsd
        return UsbLearnedPolicyStore.keyOfTransport(
            device.vendorId, device.productId, null,
            dsdEnabled = effectiveDsdMode != null || pcmToDsd,
            dsdRate = currentEffectiveUsbDsdRate(),
            dsdTransportMode = effectiveDsdMode?.transport?.prefValue ?: UsbDsdTransport.NATIVE.prefValue
        )
    }

    private fun buildUsbOutputProfile(exclusive: Boolean): UsbOutputProfile {
        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        val effectiveDsdActive = effectiveDsdMode != null
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled &&
            exclusive &&
            !effectiveDsdActive
        if (exclusive && effectiveDsdActive && AppPreferences.Player.bitPerfectEnabled) {
            AppLogger.w(TAG, "USB profile: PCM→DSD/DSD transport overrides PCM bit-perfect for this session")
        }
        val fmt = resolveUsbPcmFormatRequest()
        val rawLearned = runCatching {
            val device = currentUsbDevice
            if (device != null) {
                UsbLearnedPolicyStore.readForPlayback(
                    device.vendorId, device.productId, null,
                    effectiveDsdActive,
                    currentEffectiveUsbDsdRate(),
                    effectiveDsdMode?.transport?.prefValue ?: UsbDsdTransport.NATIVE.prefValue
                )
            } else null
        }.getOrNull()
        val rawPendingPlan = pendingUsbRecoveryPlan?.takeIf { it.requiresProfileRestart }
        val learned = if (USB_EXPOSE_PRE_TP55_POLICY) null else rawLearned
        val pendingPlan = if (USB_EXPOSE_PRE_TP55_POLICY) null else rawPendingPlan
        if (USB_EXPOSE_PRE_TP55_POLICY && (rawLearned != null || rawPendingPlan != null)) {
            AppLogger.w(
                TAG,
                "USB_EXPOSE_PRE_TP55: build profile ignores learned/pending fallback " +
                    "learned=$rawLearned pending=$rawPendingPlan"
            )
        }
        val effectiveLastGoodAlt = 0
        val effectiveLastGoodSampleRate = 0
        val effectiveLastGoodBitDepth = 0
        val effectiveLastGoodSubslot = 0
        val effectiveLastGoodFeedbackEndpoint = 0
        val caps = _usbCapabilities.value ?: sharedUsbAudioEngine.getDeviceCapabilities()
        val feedbackModel = decideUsbFeedbackModel(caps, fmt, rawLearned, rawPendingPlan)
        if (exclusive && feedbackModel.noFeedback) {
            val f = feedbackModel.format
            AppLogger.w(
                TAG,
                "USB stream config: noFeedback=true reason=${feedbackModel.reason} " +
                    "fmt=${f?.sampleRate}/${f?.validBits}/${f?.subslotBytes} " +
                    "iface=${f?.interfaceNumber} alt=${f?.altSetting} " +
                    "out=0x${(f?.outEndpoint ?: 0).toString(16)} fb=0x${(f?.feedbackEndpoint ?: 0).toString(16)} " +
                    "outSync=${f?.outSync} fbUsage=${f?.feedbackUsage}"
            )
        }
        val usbVolumeMode = AppPreferences.Player.usbVolumeMode
        val hardwareRequested = exclusive && usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled
        val fixedDigitalVolume = exclusive && usbVolumeMode == 2
        val nativeHardwareValidated = runCatching {
            sharedUsbAudioEngine.isHardwareVolumeValidated()
        }.getOrDefault(false)
        if (exclusive && hardwareRequested && nativeHardwareValidated) {
            stickyUsbHardwareVolumeValidated = true
        }
        val effectiveHardwareValidated =
            nativeHardwareValidated ||
                (exclusive && hardwareRequested && stickyUsbHardwareVolumeValidated)

        return UsbOutputProfile(
            exclusive = exclusive,
            bitPerfect = bitPerfect,
            hardwareVolumeRequested = hardwareRequested,
            hardwareVolumeValidated = effectiveHardwareValidated,
            targetSampleRate = AppPreferences.Player.usbTargetSampleRate,
            targetBitDepth = fmt.targetValidBits,
            targetSubslotBytes = fmt.targetSubslotBytes,
            pcmOutputMode = fmt.mode,
            dsdConversionEnabled = effectiveDsdActive,
            dsdDoPEnabled = effectiveDsdMode?.transport == UsbDsdTransport.DOP,
            dsdSourceDirect = currentSongIsDsdSource() && effectiveDsdActive,
            safeMode = AppPreferences.Player.usbSafeExclusiveMode,
            noClockSet = AppPreferences.Player.usbDisableDacClockInfo,
            noFeedback = feedbackModel.noFeedback,
            noFeatureUnit = false,
            force1msPacket = AppPreferences.Player.usbForce1MsPacket,
            preferSafeAlt = AppPreferences.Player.usbSafeExclusiveMode,
            forceSoftwareVolume = false,
            fixedDigitalVolume = fixedDigitalVolume,
            lastGoodAlt = effectiveLastGoodAlt,
            lastGoodSampleRate = effectiveLastGoodSampleRate,
            lastGoodBitDepth = effectiveLastGoodBitDepth,
            lastGoodSubslot = effectiveLastGoodSubslot,
            lastGoodFeedbackEndpoint = effectiveLastGoodFeedbackEndpoint
        )
    }

    private fun applyUsbOutputProfile(profile: UsbOutputProfile) {
        ffmpegPlayer.usbBitPerfectMode = profile.bitPerfect
        val effectiveNoFeedback = profile.noFeedback
        val effectiveLastGoodFeedbackEndpoint =
            if (effectiveNoFeedback) 0 else profile.lastGoodFeedbackEndpoint

        sharedUsbAudioEngine.setPcmOutputMode(profile.pcmOutputMode)
        sharedUsbAudioEngine.setUsbDacSettings(
            noControlIface = false,
            forceUac1 = false,
            linearVolume = false,
            replaceVolume = false,
            force1ms = profile.force1msPacket
        )

        sharedUsbAudioEngine.nativeSetPolicy(
            exclusive = profile.exclusive,
            bitPerfect = profile.bitPerfect || profile.fixedDigitalVolume,
            // 这里必须传"用户请求"，不能传 hardwareVolumeEffective。
            // effective 需要 nativeInit 验证后才会变 true；若这里传 effective，
            // 首次初始化永远 hwVolRequested=0，Feature Unit 永远不会被 probe。
            hwVol = profile.hardwareVolumeRequested
        )

        val effectiveDsdRate = currentEffectiveUsbDsdRate()
        sharedUsbAudioEngine.setDsdConversion(
            enabled = profile.dsdConversionEnabled,
            rate = effectiveDsdRate,
            type = AppPreferences.Player.dsdConversionType,
            dither = AppPreferences.Player.dsdDitherEnabled,
            dop = profile.dsdConversionEnabled && profile.dsdDoPEnabled
        )
        AppLogger.i(
            TAG,
            "USB DSD transport apply: sourceDsd=${currentSongIsDsdSource()} pcmToDsd=${AppPreferences.Player.dsdConversionEnabled && !currentSongIsDsdSource()} " +
                "active=${profile.dsdConversionEnabled} rate=DSD$effectiveDsdRate transport=${if (profile.dsdDoPEnabled) "DoP" else "Native"}"
        )

        sharedUsbAudioEngine.nativeSetLastGoodProfile(
            alt = profile.lastGoodAlt,
            sampleRate = profile.lastGoodSampleRate,
            validBits = profile.lastGoodBitDepth,
            subslotBytes = profile.lastGoodSubslot,
            feedbackEndpoint = effectiveLastGoodFeedbackEndpoint
        )

        // Once the route model has decided "no explicit feedback",
        // that decision must reach native candidate scoring AND stream startup
        // before prepareForPlayback runs. Otherwise Kotlin says noFeedback=true
        // while native still discovers/validates the old feedback endpoint and
        // the stream falls into a false-active state.
        sharedUsbAudioEngine.nativeSetCompatFlags(
            noClockSet = profile.noClockSet,
            noFeedback = effectiveNoFeedback,
            noFeatureUnit = profile.noFeatureUnit,
            preferSafeAlt = profile.preferSafeAlt,
            safeMode = profile.safeMode
        )

        AppLogger.i(
            TAG,
            "USB profile applied: noFeedback=${profile.noFeedback} effectiveNoFeedback=$effectiveNoFeedback " +
                "lastGoodFeedbackEndpoint=0x${profile.lastGoodFeedbackEndpoint.toString(16)} " +
                "effectiveLastGoodFeedbackEndpoint=0x${effectiveLastGoodFeedbackEndpoint.toString(16)} " +
                "alt=${profile.lastGoodAlt} sr=${profile.lastGoodSampleRate} bits=${profile.lastGoodBitDepth} subslot=${profile.lastGoodSubslot}"
        )
    }

    private fun handleUsbStreamHealthFailure(kind: UsbSilentKind, detail: String) {
        val profile = buildUsbOutputProfile(exclusive = true)
        val stats = runCatching {
            val h = sharedUsbAudioEngine.currentHandle
            if (h != 0L) parseUsbStats(sharedUsbAudioEngine.nativeGetStatsString(h)) else null
        }.getOrNull()
        val plan = UsbStreamRecoveryPlanner.plan(kind, stats, profile, detail)
        AppLogger.w(TAG, "USB stream health failure: kind=$kind detail=$detail plan=$plan")
        if (plan.disableFeedback || kind == UsbSilentKind.FeedbackInvalid || stats?.isFeedbackDegradedFixedPacer == true) {
            rememberUsbFeedbackRejected("$kind/$detail")
        }
        if (plan.forceFullReopen &&
            plan.reason == UsbSilentKind.UsbNotOutputting &&
            !plan.disableFeedback
        ) {
            clearUsbNoFeedbackFallback("$kind/$detail")
        }
        if (plan.shouldRecordLearnedPolicy) {
            val device = currentUsbDevice
            if (device != null) {
                val deviceKey = usbLearnedPolicyKeyFor(device)
                UsbLearnedPolicyStore.recordRecoveryPlan(deviceKey, plan)
            }
        }
        if (plan.requiresProfileRestart) {
            pendingUsbRecoveryPlan = plan
            pendingUsbPolicyRestart = true
            if (plan.forceFullReopen && plan.action == UsbRecoveryAction.FullReopen) {
                if (shouldDeferUsbHardRecovery("usb_health_failure:${kind.name}")) {
                    AppLogger.w(TAG, "USB full reopen postponed while playback is background-protected: action=${plan.action} message=${plan.message}")
                } else {
                    recoverUsbExclusiveAsync()
                }
            } else {
                AppLogger.w(TAG, "USB recovery deferred until next safe restart: action=${plan.action} message=${plan.message}")
            }
        }
    }


    private fun recordUsbLastGoodProfile(reason: String) {
        val device = currentUsbDevice ?: return
        val runtime = runCatching { sharedUsbAudioEngine.getRuntimeFormat() }.getOrNull() ?: return
        if (!runtime.isValid) return
        val profile = buildUsbOutputProfile(exclusive = true)
        val deviceKey = usbLearnedPolicyKeyFor(device)
        if (USB_EXPOSE_PRE_TP55_POLICY) {
            AppLogger.w(
                TAG,
                "USB_EXPOSE_PRE_TP55: last-good profile NOT recorded: reason=$reason " +
                    "key=$deviceKey iface=${runtime.iface} alt=${runtime.alt} " +
                    "sr=${runtime.sampleRate} bits=${runtime.validBits} subslot=${runtime.subslotBytes} " +
                    "fb=0x${runtime.feedbackEndpoint.toString(16)} profile=$profile"
            )
            return
        }
        UsbLearnedPolicyStore.recordSuccess(
            deviceKey = deviceKey,
            alt = runtime.alt,
            sampleRate = runtime.sampleRate,
            bitDepth = runtime.validBits,
            subslot = runtime.subslotBytes,
            feedbackEndpoint = runtime.feedbackEndpoint,
            profile = profile
        )
        AppLogger.i(
            TAG,
            "USB last-good profile recorded: reason=$reason iface=${runtime.iface} alt=${runtime.alt} " +
                "sr=${runtime.sampleRate} bits=${runtime.validBits} subslot=${runtime.subslotBytes} fb=0x${runtime.feedbackEndpoint.toString(16)}"
        )
    }

    private fun acceptUsbRuntimeSession(reason: String, sessionId: Long, stats: UsbStatsSnapshot) {
        val runtimeKey = currentUsbRuntimeKey()
        lastAcceptedUsbSessionId = sessionId
        lastAcceptedUsbRuntimeKey = runtimeKey
        recordUsbLastGoodProfile(reason)
        if (stats.feedbackEnabled && stats.feedbackState in 2..3 && stats.usbOutBytesPerSec > stats.expectedBytesPerSec * 70 / 100) {
            val key = currentUsbTransportKeyOrNull()
            if (key != null && key == usbFeedbackRejectedTransportKey) {
                AppLogger.i(TAG, "USB feedback model: explicit feedback accepted again, clearing rejected marker key=$key")
                usbFeedbackRejectedTransportKey = null
                usbFeedbackRejectedReason = ""
            }
        }

        val completedRatio = if (stats.expectedBytesPerSec > 0) {
            stats.usbOutBytesPerSec.toDouble() / stats.expectedBytesPerSec.toDouble()
        } else {
            0.0
        }

        // Acceptance gate: once a stream proves that completed ISO
        // output, clock/profile and volume policy are coherent, do not keep a
        // stale recovery plan around. Manual/user-initiated policy changes can
        // still restart explicitly.
        if (pendingUsbPolicyRestart && pendingUsbRecoveryPlan?.forceFullReopen != true) {
            AppLogger.i(TAG, "USB runtime acceptance clears deferred recovery: oldPlan=$pendingUsbRecoveryPlan")
            pendingUsbPolicyRestart = false
            pendingUsbRecoveryPlan = null
        }

        AppLogger.i(
            TAG,
            "USB runtime accepted: reason=$reason session=$sessionId key=$runtimeKey " +
                "completed=${stats.usbOutBytesPerSec}/${stats.expectedBytesPerSec} " +
                "ratio=${String.format(java.util.Locale.US, "%.3f", completedRatio)} " +
                "fbState=${stats.feedbackState} clockRate=${stats.clockRate} targetRate=${stats.targetRate}"
        )
    }


    private fun scheduleUsbSelfTest(reason: String) {
        val handle = sharedUsbAudioEngine.currentHandle
        if (handle == 0L) {
            AppLogger.w(TAG, "USB self-test skipped: no handle, reason=$reason")
            return
        }
        val epoch = usbSelfTestEpoch.get()
        val sessionId = sharedUsbAudioEngine.getStreamSessionId()
        val sessionKey = "${handle}:${sessionId}"
        val startPositionMs = ffmpegPlayer.positionMs.coerceAtLeast(0L)
        if (lastUsbSelfTestSessionKey == sessionKey) {
            AppLogger.i(TAG, "USB self-test skipped: session already tested, reason=$reason handle=0x${handle.toString(16)} session=$sessionId")
            return
        }
        usbSelfTestJob?.cancel()
        usbSelfTestJob = scope.launch(Dispatchers.IO) {
            val maxAttempts = 3
            repeat(maxAttempts) { attempt ->
                delay(
                    when (attempt) {
                        0 -> 1500L
                        1 -> 900L
                        else -> 900L
                    }
                )
                if (usbSelfTestEpoch.get() != epoch) {
                    AppLogger.i(TAG, "USB self-test aborted: epoch changed, reason=$reason oldEpoch=$epoch newEpoch=${usbSelfTestEpoch.get()}")
                    return@launch
                }
                if (_playState.value != PlayState.PLAYING ||
                    ffmpegPlayer.state != FfmpegAudioPlayer.State.PLAYING ||
                    transportTransitioning ||
                    usbSeeking
                ) {
                    AppLogger.i(
                        TAG,
                        "USB self-test skipped: transitional state, reason=$reason " +
                            "playState=${_playState.value} ffmpegState=${ffmpegPlayer.state} " +
                            "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking"
                    )
                    return@launch
                }
                val liveHandle = sharedUsbAudioEngine.currentHandle
                val liveSessionId = sharedUsbAudioEngine.getStreamSessionId()
                if (liveHandle != handle || liveHandle == 0L || liveSessionId != sessionId) {
                    AppLogger.i(TAG, "USB self-test aborted: session changed, reason=$reason old=0x${handle.toString(16)}/$sessionId new=0x${liveHandle.toString(16)}/$liveSessionId")
                    return@launch
                }
                lastUsbSelfTestSessionKey = sessionKey
                val statsStr = runCatching { sharedUsbAudioEngine.nativeGetStatsString(handle) }.getOrNull()
                if (statsStr.isNullOrBlank()) {
                    AppLogger.w(TAG, "USB self-test skipped: empty stats, reason=$reason attempt=${attempt + 1}")
                    return@launch
                }
                val stats = parseUsbStats(statsStr) ?: return@launch
                val audibleState = runCatching { sharedUsbAudioEngine.nativeGetAudibleStateString(handle) }.getOrDefault("")
                val audibleAccepted = isNativeAudibleAccepted(audibleState)
                val result = UsbSelfTest.run(stats)
                AppLogger.i(
                    TAG,
                    "USB self-test reason=$reason attempt=${attempt + 1}/$maxAttempts result=$result stats=$stats audible=$audibleState"
                )

                if (!result.shouldRestart) {
                    if (result.kind == UsbSilentKind.None) {
                        if (audibleAccepted) {
                            val livePositionMs = ffmpegPlayer.positionMs.coerceAtLeast(0L)
                            val progressedMs = (livePositionMs - startPositionMs).coerceAtLeast(0L)
                            val minThroughputPercent = when {
                                stats.isFixedNoFeedbackPacer -> 85L
                                stats.isFeedbackDegradedFixedPacer -> 82L
                                else -> 80L
                            }
                            val throughputHealthy = stats.expectedBytesPerSec > 0 &&
                                stats.usbOutBytesPerSec >= (stats.expectedBytesPerSec * minThroughputPercent / 100L)
                            val minProgressMs = when {
                                stats.isFixedNoFeedbackPacer -> 3000L
                                stats.isFeedbackDegradedFixedPacer -> 1800L
                                else -> 700L
                            }
                            val progressAccepted = progressedMs >= minProgressMs
                            if (throughputHealthy && progressAccepted) {
                                acceptUsbRuntimeSession(reason, liveSessionId.takeIf { it != 0L } ?: sessionId, stats)
                            } else if (attempt < maxAttempts - 1) {
                                AppLogger.i(
                                    TAG,
                                    "USB self-test waiting for stable motion: reason=$reason progressedMs=$progressedMs/$minProgressMs " +
                                        "completed=${stats.usbOutBytesPerSec}/${stats.expectedBytesPerSec} minThroughput=${minThroughputPercent}% " +
                                        "pacing=${stats.pacingMode}"
                                )
                                return@repeat
                            } else {
                                AppLogger.w(
                                    TAG,
                                    "USB self-test throughput/audible passed but stability gate not ready; " +
                                        "skip last-good acceptance: reason=$reason progressedMs=$progressedMs/$minProgressMs " +
                                        "completed=${stats.usbOutBytesPerSec}/${stats.expectedBytesPerSec} minThroughput=${minThroughputPercent}% " +
                                        "pacing=${stats.pacingMode}"
                                )
                            }
                        } else if (attempt < maxAttempts - 1) {
                            AppLogger.i(TAG, "USB self-test waiting for audible gate: reason=$reason audible=$audibleState")
                            return@repeat
                        } else {
                            AppLogger.w(
                                TAG,
                                "USB throughput healthy but audible gate not accepted; repairing cold-start volume route: reason=$reason audible=$audibleState"
                            )
                            applyVolumeRoute("audible_gate_cold_start_repair:$reason")
                            delay(180)
                            val repairedState = runCatching { sharedUsbAudioEngine.nativeGetAudibleStateString(handle) }.getOrDefault("")
                            AppLogger.i(TAG, "USB audible gate after cold-start repair: reason=$reason audible=$repairedState")
                            if (isNativeAudibleAccepted(repairedState)) {
                                acceptUsbRuntimeSession(reason, liveSessionId.takeIf { it != 0L } ?: sessionId, stats)
                            } else {
                                AppLogger.w(
                                    TAG,
                                    "USB throughput healthy but audible gate still not accepted; not recording last-good yet: reason=$reason audible=$repairedState"
                                )
                            }
                        }
                    }
                    return@launch
                }

                val severeTransportFailure = result.kind == UsbSilentKind.TransportError &&
                    (stats.submitErr > 0 || stats.xferErr > 0 || stats.packetErr > 4 || stats.underrun > 4)
                val confirmedFailure = severeTransportFailure || attempt == maxAttempts - 1
                if (!confirmedFailure) {
                    AppLogger.w(TAG, "USB self-test suspicious but not confirmed yet: kind=${result.kind} reason=$reason")
                    return@repeat
                }

                // State can change between the first gate and the final retry
                // (manual pause, track switch, USB cutover).  Self
                // tests must never turn a paused/warm-transition stream into a
                // persistent fallback profile.
                if (_playState.value != PlayState.PLAYING ||
                    ffmpegPlayer.state != FfmpegAudioPlayer.State.PLAYING ||
                    transportTransitioning ||
                    usbSeeking
                ) {
                    AppLogger.i(
                        TAG,
                        "USB self-test final failure ignored: stream no longer actively playing, " +
                            "reason=$reason playState=${_playState.value} ffmpegState=${ffmpegPlayer.state} " +
                            "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking result=$result"
                    )
                    return@launch
                }

                val profile = buildUsbOutputProfile(exclusive = true)
                AppLogger.w(
                    TAG,
                    "USB runtime acceptance gate failed: reason=$reason kind=${result.kind} " +
                        "message=${result.message} stats=$stats"
                )
                val plan = UsbStreamRecoveryPlanner.plan(result.kind, stats, profile, result.message)
                if (USB_EXPOSE_PRE_TP55_POLICY) {
                    pendingUsbRecoveryPlan = null
                    pendingUsbPolicyRestart = false
                    AppLogger.e(
                        TAG,
                        "USB_EXPOSE_FAILURE: self-test confirmed failure, no learned fallback/no forced recovery. " +
                            "kind=${result.kind} action=${plan.action} reason=$reason message=${result.message} " +
                            "stats=$stats profile=$profile playState=${_playState.value} ffmpeg=${ffmpegPlayer.state}"
                    )
                    return@launch
                }
                if (plan.disableFeedback || result.kind == UsbSilentKind.FeedbackInvalid || stats.isFeedbackDegradedFixedPacer) {
                    rememberUsbFeedbackRejected("self_test:$reason:${result.message}")
                }
                if (plan.forceFullReopen &&
                    plan.reason == UsbSilentKind.UsbNotOutputting &&
                    !plan.disableFeedback
                ) {
                    clearUsbNoFeedbackFallback("self_test:$reason:${result.message}")
                }
                if (result.shouldFallbackProfile || plan.shouldRecordLearnedPolicy) {
                    val device = currentUsbDevice ?: return@launch
                    val deviceKey = usbLearnedPolicyKeyFor(device)
                    UsbLearnedPolicyStore.recordRecoveryPlan(deviceKey, plan)
                }

                // Do NOT hard-stop playback during self-test. Mark dirty and
                // apply the planned fallback on next legitimate restart.
                pendingUsbRecoveryPlan = plan
                pendingUsbPolicyRestart = plan.requiresProfileRestart
                AppLogger.w(
                    TAG,
                    "USB self-test recovery planned, DEFERRED: kind=${result.kind} action=${plan.action} reason=$reason " +
                        "playing=${_playState.value == PlayState.PLAYING} message=${plan.message}"
                )
                return@launch
            }
        }
    }

    private fun invalidateUsbSelfTest(reason: String, clearSessionKey: Boolean = false) {
        val newEpoch = usbSelfTestEpoch.incrementAndGet()
        usbSelfTestJob?.cancel()
        usbSelfTestJob = null
        if (clearSessionKey) {
            lastUsbSelfTestSessionKey = ""
        }
        AppLogger.i(
            TAG,
            "USB self-test invalidated: reason=$reason epoch=$newEpoch clearSessionKey=$clearSessionKey"
        )
    }

    private fun parseUsbStatsParts(str: String): Map<String, String> {
        if (str.isBlank()) return emptyMap()
        // Native stats are a whitespace-separated key=value stream, but some
        // diagnostic values such as fuReason may contain spaces.  Extract known
        // key=value tokens with a regex so later fields, especially pacingMode,
        // are never lost because an earlier value had spaces.
        return Regex("""(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=([^\s]+)""")
            .findAll(str)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun parseUsbBool(value: String?): Boolean? {
        if (value == null) return null
        return when (value.lowercase()) {
            "1", "true", "yes", "y" -> true
            "0", "false", "no", "n" -> false
            else -> null
        }
    }

    private fun isNativeAudibleAccepted(str: String): Boolean {
        if (str.isBlank()) return false
        return parseUsbBool(parseUsbStatsParts(str)["audible"]) == true
    }

    private fun buildAudibleDiagnostics(raw: String): String {
        if (raw.isBlank()) return "native audible state unavailable"
        val p = parseUsbStatsParts(raw)
        return "audible=${p["audible"] ?: "unknown"}, completed=${p["completed"] ?: "0"}/${p["expected"] ?: "0"}, " +
            "volumeReady=${p["volumeReady"] ?: p["audibleVolReady"] ?: "unknown"}, hwSafeActive=${p["hwSafeActive"] ?: "unknown"}, " +
            "firstCompletionMs=${p["firstCompletionMs"] ?: p["audibleFirstMs"] ?: "0"}, acceptedMs=${p["acceptedMs"] ?: p["audibleAcceptedMs"] ?: "0"}, " +
            "ageMs=${p["ageMs"] ?: "0"}, session=${p["session"] ?: "0"}"
    }

    private fun parseUsbStats(str: String): UsbStatsSnapshot? {
        return try {
            val parts = parseUsbStatsParts(str)
            val clockVerifiedRate = parts["clockVerified"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            UsbStatsSnapshot(
                appInBytesPerSec = parts["app"]?.toLongOrNull() ?: 0,
                usbOutBytesPerSec = parts["completed"]?.toLongOrNull()
                    ?: parts["usbCompleted"]?.toLongOrNull()
                    ?: parts["usbOut"]?.toLongOrNull()
                    ?: parts["usb"]?.toLongOrNull()
                    ?: 0,
                scheduledUsbBytesPerSec = parts["scheduled"]?.toLongOrNull() ?: 0,
                expectedBytesPerSec = parts["expected"]?.toLongOrNull() ?: 0,
                bufferUsedBytes = parts["buf"]?.split("/")?.getOrNull(0)?.toLongOrNull() ?: 0,
                bufferCapacityBytes = parts["buf"]?.split("/")?.getOrNull(1)?.toLongOrNull() ?: 0,
                underrun = parts["underrun"]?.toIntOrNull() ?: 0,
                submitErr = parts["submitErr"]?.toIntOrNull() ?: 0,
                packetErr = parts["pktErr"]?.toIntOrNull() ?: 0,
                xferErr = parts["xferErr"]?.toIntOrNull() ?: 0,
                clockRate = clockVerifiedRate.takeIf { it > 0 } ?: (parts["clock"]?.toIntOrNull() ?: 0),
                targetRate = parts["target"]?.toIntOrNull() ?: 0,
                finalVolume = parts["volume"]?.toFloatOrNull() ?: 0f,
                feedbackEnabled = (parts["feedback"]?.toIntOrNull() ?: 0) != 0,
                sessionId = parts["session"]?.toLongOrNull() ?: 0L,
                feedbackState = parts["fbState"]?.toIntOrNull() ?: 0,
                feedbackValidCount = parts["fbValid"]?.toIntOrNull() ?: 0,
                feedbackInvalidCount = parts["fbInvalid"]?.toIntOrNull() ?: 0,
                feedbackEmptyCount = parts["fbEmpty"]?.toIntOrNull() ?: 0,
                feedbackSampleRateMilli = parts["fbRateMilli"]?.toIntOrNull() ?: 0,
                pacingMode = parts["pacingMode"]
                    ?: when (parts["pacingModeId"]?.toIntOrNull()) {
                        0 -> "NoFeedbackFixed"
                        1 -> "ExplicitFeedback"
                        2 -> "FeedbackDegradedFixed"
                        else -> ""
                    },
                pacingModeId = parts["pacingModeId"]?.toIntOrNull() ?: -1,
                clockSource = parts["clockSrc"] ?: "",
                clockSelector = parts["clockSel"] ?: "",
                clockInterface = parts["clockIface"]?.toIntOrNull() ?: -1,
                clockVerified = parseUsbBool(parts["clockVerified"]) ?: (clockVerifiedRate > 0),
                clockVerifiedRate = clockVerifiedRate,
                clockValidKnown = parseUsbBool(parts["clockValidKnown"]) == true,
                clockValid = parseUsbBool(parts["clockValid"]) == true,
                featureUnitPolicy = parts["fuPolicy"] ?: "",
                featureUnitPath = parts["fuPath"] ?: "",
                featureUnitResult = parts["fuResult"]?.toIntOrNull() ?: 0,
                featureUnitRangeVerified = parseUsbBool(parts["fuRange"]) == true,
                featureUnitReadbackVerified = parseUsbBool(parts["fuReadback"]) == true,
                featureUnitReason = parts["fuReason"] ?: "",
                featureUnitDescriptorMaster = parseUsbBool(parts["fuDescM"]) == true,
                featureUnitDescriptorLeft = parseUsbBool(parts["fuDescL"]) == true,
                featureUnitDescriptorRight = parseUsbBool(parts["fuDescR"]) == true,
                featureUnitEffectiveMaster = parseUsbBool(parts["fuEffM"]) == true,
                featureUnitEffectiveLeft = parseUsbBool(parts["fuEffL"]) == true,
                featureUnitEffectiveRight = parseUsbBool(parts["fuEffR"]) == true,
                featureUnitSingleChannel = parts["fuSingleCh"]?.toIntOrNull() ?: 0,
                raw = str
            )
        } catch (_: Exception) { null }
    }

    private fun currentUsbRuntimeKey(): String {
        val r = sharedUsbAudioEngine.getRuntimeFormat()
        return listOf(
            r.sampleRate,
            r.channels,
            r.validBits,
            r.subslotBytes,
            r.iface,
            r.alt,
            r.outEndpoint,
            r.feedbackEndpoint,
            AppPreferences.Player.bitPerfectEnabled,
            AppPreferences.Player.hardwareFeatureUnitEnabled,
            AppPreferences.Player.usbPcmOutputMode,
            currentSongIsDsdSource(),
            currentEffectiveUsbDsdMode()?.transport?.prefValue ?: -1,
            currentEffectiveUsbDsdRate()
        ).joinToString("/")
    }

    fun applyUsbOutputSettingsChanged(userInitiated: Boolean = false): Int {
        if (!_usbExclusiveActive.value) return 0
        reconcileUsbOutputSettingsWithCapabilities(_usbCapabilities.value)

        // Reentrancy guard: prevent overlapping stop/release/play cycles when
        // user rapidly changes sample rate or bit depth in the settings UI.
        if (_isRenderSwitching.value) {
            pendingUsbPolicyRestart = true
            AppLogger.w(TAG, "applyUsbOutputSettingsChanged DEFERRED: render switching in progress")
            return 0
        }

        // Same-config no-restart protection: if live runtime already matches
        // target profile and was applied recently, skip the hard stop.
        val now = android.os.SystemClock.elapsedRealtime()
        val key = currentUsbRuntimeKey()
        if (!userInitiated && sharedUsbAudioEngine.isRunning() &&
            key == lastUsbAppliedKey &&
            now - lastUsbOutputApplyMs < 10_000L
        ) {
            AppLogger.w(TAG, "applyUsbOutputSettingsChanged ignored: same runtime key=$key")
            return 0
        }

        // Defer auto-restart while playing: self-test / learned-policy should
        // not hard-stop a healthy stream.
        val playing = _playState.value == PlayState.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        if (playing && !userInitiated) {
            pendingUsbPolicyRestart = true
            AppLogger.w(TAG, "applyUsbOutputSettingsChanged DEFERRED: playing=true userInitiated=false key=$key")
            return 0
        }

        lastUsbAppliedKey = key
        lastUsbOutputApplyMs = now
        pendingUsbPolicyRestart = false

        // 同步 bit-perfect 状态到 native
        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        val dsdActive = effectiveDsdMode != null
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled
        val hwVol = AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled
        val fixedDigital = AppPreferences.Player.usbVolumeMode == 2
        ffmpegPlayer.usbBitPerfectMode = bitPerfect || fixedDigital
        sharedUsbAudioEngine.nativeSetPolicy(exclusive = true, bitPerfect = bitPerfect || fixedDigital, hwVol = hwVol)
        sharedUsbAudioEngine.setDsdConversion(
            enabled = dsdActive,
            rate = currentEffectiveUsbDsdRate(),
            type = AppPreferences.Player.dsdConversionType,
            dither = AppPreferences.Player.dsdDitherEnabled,
            dop = effectiveDsdMode?.transport == UsbDsdTransport.DOP
        )

        AppLogger.w(
            TAG,
            "USB_RESAMPLE_APPLY prefRate=${AppPreferences.Player.usbTargetSampleRate} " +
                "bitPerfect=$bitPerfect dsd=$dsdActive"
        )

        if (bitPerfect && !dsdActive) {
            AppLogger.i(TAG, "applyUsbOutputSettingsChanged: bit-perfect is ON, resample settings saved but not applied")
            return 0
        }

        val song = _currentSong.value ?: return 0
        val position = _position.value
        val wasPlaying = _playState.value == PlayState.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        val queue = _queue.value
        val queueSongs = queue.songs
        val queueIndex = queue.currentIndex

        Log.i(
            TAG,
            "applyUsbOutputSettingsChanged: wasPlaying=$wasPlaying pos=${position}ms " +
                "usbTargetSr=${AppPreferences.Player.usbTargetSampleRate} usbTargetBits=${AppPreferences.Player.usbTargetBitDepth}"
        )

        _isRenderSwitching.value = true
        try {
            try {
                ffmpegPlayer.stop()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "applyUsbOutputSettingsChanged: ffmpeg stop failed", t)
            }
            try {
                usbExclusiveManager.stopStreaming("usb_output_settings_changed")
            } catch (_: Throwable) {
            }
            try {
                sharedUsbAudioEngine.release()
            } catch (_: Throwable) {
            }
            try {
                usbExclusiveManager.resetPlaybackPipeline("usb_output_settings_changed")
            } catch (_: Throwable) {
            }

            queueRendererRestartSeek(
                song = song,
                displayPositionMs = position,
                reason = "usb_output_settings_changed"
            )
            smForceTransition(PlayState.PREPARING, "applyUsbSettings")
            if (queueSongs.isNotEmpty() && queueIndex in queueSongs.indices) {
                play(song, queueSongs, queueIndex)
            } else {
                play(song)
            }
            if (!wasPlaying) {
                scope.launch {
                    delay(500)
                    if (_playState.value == PlayState.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
                    ) {
                        pause()
                    }
                }
            }
        } finally {
            scope.launch {
                delay(600)
                _isRenderSwitching.value = false
            }
        }
        return 0
    }

    fun applyAudioOutputSettingsChanged(): Int {
        if (_usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode) {
            AppLogger.i(TAG, "applyAudioOutputSettingsChanged: USB exclusive is active, ignore non-USB PCM output setting change")
            return 0
        }
        val song = _currentSong.value ?: return 0
        val position = _position.value
        val wasActive = _playState.value == PlayState.PLAYING ||
            _playState.value == PlayState.PAUSED ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        val wasPlaying = _playState.value == PlayState.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        if (!wasActive) return 0

        val queue = _queue.value
        val queueSongs = queue.songs
        val queueIndex = queue.currentIndex
        Log.i(
            TAG,
            "applyAudioOutputSettingsChanged: wasPlaying=$wasPlaying pos=${position}ms " +
                "mode=${AppPreferences.Player.audioOutputMode} targetSr=${AppPreferences.Player.targetSampleRate} " +
                "targetBits=${AppPreferences.Player.targetBitDepth}"
        )

        try {
            ffmpegPlayer.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "applyAudioOutputSettingsChanged: ffmpeg stop failed", t)
        }
        queueRendererRestartSeek(
            song = song,
            displayPositionMs = position,
            reason = "audio_output_settings_changed"
        )
        smForceTransition(PlayState.PREPARING, "applyAudioSettings")
        if (queueSongs.isNotEmpty() && queueIndex in queueSongs.indices) {
            play(song, queueSongs, queueIndex)
        } else {
            play(song)
        }
        if (!wasPlaying) {
            scope.launch {
                delay(500)
                if (_playState.value == PlayState.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
                ) {
                    pause()
                }
            }
        }
        return 0
    }

    /** 检查硬件音量是否安全可用 */
    fun isHardwareVolumeSafe(): Boolean {
        return try {
            sharedUsbAudioEngine.nativeIsHardwareVolumeSafe()
        } catch (_: Throwable) {
            false
        }
    }

    /** 修复 USB 设备左右硬件音量不均衡 */
    fun repairUsbHardwareVolume(safeVolumeLinear: Float = 0.25f): Int {
        return try {
            val result = sharedUsbAudioEngine.nativeRepairHardwareVolumeBalance(safeVolumeLinear)
            if (result == 0) {
                AppLogger.i(TAG, "USB hardware volume balance repaired")
            } else {
                AppLogger.w(TAG, "USB hardware volume repair failed: $result")
            }
            result
        } catch (e: Throwable) {
            AppLogger.e(TAG, "USB hardware volume repair exception", e)
            -99
        }
    }

    /** 当前模式下音量是否可控（bit-perfect + 无 FU = 不可控） */
    fun canControlUsbVolume(): Boolean {
        return try {
            val h = sharedUsbAudioEngine.currentHandle
            if (h == 0L) return false
            sharedUsbAudioEngine.nativeCanControlVolume(h)
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldUseUsbRemoteVolume(): Boolean {
        if (!_usbExclusiveActive.value) return false
        return when (resolveCurrentUsbOutputProfile()?.volumePath) {
            UsbVolumePath.HardwareUserVolume -> canControlUsbVolume()
            UsbVolumePath.Software -> true
            else -> false
        }
    }

    private fun readDisplayedUsbHardwareVolumeDb(reason: String): Int? {
        val h = sharedUsbAudioEngine.currentHandle
        if (h == 0L || !isUsbHardwareVolumeRouteActive()) return null
        if (!sharedUsbAudioEngine.nativeCanControlVolume(h)) return null

        val prefDb = usbHwStepToDb(currentUsbHwStep())
        val nativeDb = runCatching { sharedUsbAudioEngine.nativeGetVolumeDb(h) }.getOrNull() ?: return prefDb
        val roundedNativeDb = nativeDb.roundToInt().coerceIn(USB_HW_MIN_DB, USB_HW_MAX_DB)

        if (roundedNativeDb == 0 && prefDb != 0) {
            AppLogger.w(
                TAG,
                "readDisplayedUsbHardwareVolumeDb: native returned 0dB, fallback to pref=$prefDb reason=$reason"
            )
            return prefDb
        }
        return roundedNativeDb
    }

    private fun syncUsbHardwareVolumePrefsFromDb(db: Int, reason: String): Int {
        val boundedDb = db.coerceIn(USB_HW_MIN_DB, USB_HW_MAX_DB)
        val step = usbHwDbToStep(boundedDb)
        val uiVolume = usbHwStepToUiVolume(step)
        AppPreferences.Player.usbHardwareVolumeStep = step
        AppPreferences.Player.usbHardwareVolume = uiVolume
        AppPreferences.Player.volume = uiVolume
        AppLogger.i(TAG, "syncUsbHardwareVolumePrefsFromDb: db=${boundedDb}dB step=$step ui=$uiVolume reason=$reason")
        return step
    }

    private fun syncUsbRemoteVolumeRoute(reason: String, force: Boolean = false) {
        val desired = shouldUseUsbRemoteVolume()
        if (!force && lastUsbRemoteVolumeDesired == desired) return
        lastUsbRemoteVolumeDesired = desired

        AppLogger.i(TAG, "syncUsbRemoteVolumeRoute: desired=$desired reason=$reason")
        try {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = if (desired) {
                    "com.rawsmusic.action.ACTIVATE_USB_REMOTE_VOLUME"
                } else {
                    "com.rawsmusic.action.DEACTIVATE_USB_REMOTE_VOLUME"
                }
            }
            context.startService(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "syncUsbRemoteVolumeRoute failed: reason=$reason", e)
        }
    }

    fun seedUsbHardwareVolumeStepFromUiVolume(): Int {
        readDisplayedUsbHardwareVolumeDb("seed_remote_volume")?.let { db ->
            return syncUsbHardwareVolumePrefsFromDb(db, "seed_remote_volume")
        }
        val persistedLinear = AppPreferences.Player.usbHardwareVolume.coerceIn(0f, 1f)
        val fallbackLinear = if (persistedLinear > 0.0001f) {
            persistedLinear
        } else {
            AppPreferences.Player.volume.coerceIn(0f, 1f)
        }
        val step = uiVolumeToUsbHwStep(fallbackLinear)
        AppPreferences.Player.usbHardwareVolumeStep = step
        AppPreferences.Player.usbHardwareVolume = fallbackLinear
        AppLogger.i(
            TAG,
            "seedUsbHardwareVolumeStepFromUiVolume: fallbackLinear=$fallbackLinear step=$step"
        )
        return step
    }

    private fun userVolumeToUsbHardwareDb(volume: Float): Int {
        val v = volume.coerceIn(0f, 1f)
        return (-60 + (0 - -60) * v)
            .toInt()
            .coerceIn(-60, 0)
    }

    private fun usbHardwareDbToUserVolume(db: Int): Float {
        val d = db.coerceIn(-60, 0)
        return ((d - -60).toFloat() /
            (0 - -60).toFloat())
            .coerceIn(0f, 1f)
    }

    private fun setUsbHardwareVolumeDbFromUserVolume(reason: String): Int {
        val h = sharedUsbAudioEngine.currentHandle
        if (h == 0L) return -1
        if (!sharedUsbAudioEngine.nativeCanControlVolume(h)) {
            AppLogger.w(TAG, "USB HW volume not available, reason=$reason")
            return -2
        }
        val user = AppPreferences.Player.volume.coerceIn(0f, 1f)
        val db = userVolumeToUsbHardwareDb(user)
        AppLogger.i(TAG, "USB HW volume apply: reason=$reason user=$user db=${db}dB")
        return sharedUsbAudioEngine.nativeSetHardwareVolumeDb(h, db)
    }

    /**
     * UI +/- 按钮统一入口。
     * 不直接写硬件 dB，只调用 setUserVolumeUnified → applyComposedVolume → applyUsbVolume。
     */
    fun adjustVolumeFromUiButton(deltaStep: Int) {
        if (deltaStep == 0) return

        when (resolveVolumeRoute()) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("adjustVolumeFromUiButton_ignored")
            }
            VolumeRoute.USB_HARDWARE -> {
                val old = currentUsbHwStep()
                val delta = if (deltaStep > 0) 1 else -1
                val next = clampUsbHwStep(old + delta)
                AppLogger.i(TAG, "adjustVolumeFromUiButton USB_HW: old=$old next=$next delta=$deltaStep")
                setUsbHardwareVolumeStep(next, "ui_button delta=$deltaStep")
            }
            VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    val old = AppPreferences.Player.volume.coerceIn(0f, 1f)
                    val delta = if (deltaStep > 0) {
                        com.rawsmusic.module.player.usb.UsbVolumeController.DEFAULT_STEP
                    } else {
                        -com.rawsmusic.module.player.usb.UsbVolumeController.DEFAULT_STEP
                    }
                    applyUsbExclusiveSoftwareUserVolume(old + delta, "ui_button_system delta=$deltaStep")
                } else {
                    val direction = if (deltaStep > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    applyVolumeRoute("ui_button_system delta=$deltaStep")
                }
            }
        }
    }

    /** 步进 USB 音量（实体键映射）。走统一入口。 */
    fun stepUsbVolume(delta: Float) {
        adjustVolumeFromUiButton(if (delta >= 0f) 1 else -1)
    }

    /** 获取当前 USB 硬件音量分贝值（从 DAC 读取，仅用于诊断，不回写 UI） */
    fun getUsbVolumeDb(): Float {
        return readDisplayedUsbHardwareVolumeDb("volume_overlay")?.toFloat() ?: 0f
    }

    /** 直接设置 USB 音量（0..1 线性值）— 走统一入口 */
    fun setUsbVolumeLinear(linear: Float) {
        setUserVolume(linear.coerceIn(0f, 1f))
    }

    // ========== MediaSession 后台硬件音量接口 ==========

    fun setUsbVolumeStepFromMediaSession(step: Int, reason: String) {
        when (resolveVolumeRoute()) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("media_session_set_fixed:$reason")
            }
            VolumeRoute.USB_HARDWARE -> {
                val mappedStep = uiVolumeToUsbHwStep(
                    com.rawsmusic.module.player.usb.UsbHardwareVolumeModel.stepToUiVolume(step)
                )
                setUsbHardwareVolumeStep(mappedStep, "media_session_set:$reason rawStep=$step mappedStep=$mappedStep")
            }
            VolumeRoute.SYSTEM -> {
                val v = com.rawsmusic.module.player.usb.UsbHardwareVolumeModel.stepToUiVolume(step)
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    applyUsbExclusiveSoftwareUserVolume(v, "media_session_set_system:$reason")
                } else {
                    setSystemMusicVolumeLinear(v)
                    applyVolumeRoute("media_session_set_system:$reason")
                }
            }
        }
    }

    fun adjustUsbVolumeStepFromMediaSession(direction: Int, reason: String) {
        if (direction == 0) return

        when (resolveVolumeRoute()) {
            VolumeRoute.USB_FIXED -> {
                forceUsbFixedVolume0Db("media_session_adjust_fixed:$reason")
            }
            VolumeRoute.USB_HARDWARE -> {
                val old = currentUsbHwStep()
                val delta = if (direction > 0) 1 else -1
                val next = clampUsbHwStep(old + delta)
                AppLogger.i(TAG, "adjustUsbVolumeStepFromMediaSession USB_HW: old=$old next=$next delta=$delta reason=$reason")
                setUsbHardwareVolumeStep(next, "$reason direction=$direction old=$old next=$next")
            }
            VolumeRoute.SYSTEM -> {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    val old = AppPreferences.Player.volume.coerceIn(0f, 1f)
                    val delta = if (direction > 0) {
                        com.rawsmusic.module.player.usb.UsbVolumeController.DEFAULT_STEP
                    } else {
                        -com.rawsmusic.module.player.usb.UsbVolumeController.DEFAULT_STEP
                    }
                    applyUsbExclusiveSoftwareUserVolume(old + delta, "$reason system direction=$direction")
                } else {
                    val adjust = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
                    applyVolumeRoute("$reason system direction=$direction")
                }
            }
        }
    }

    fun getUsbVolumeStepForMediaSession(): Int {
        return when (resolveVolumeRoute()) {
            VolumeRoute.USB_FIXED ->
                com.rawsmusic.module.player.usb.UsbHardwareVolumeModel.uiVolumeToStep(1.0f)
            VolumeRoute.USB_HARDWARE ->
                com.rawsmusic.module.player.usb.UsbHardwareVolumeModel.uiVolumeToStep(
                    usbHwStepToUiVolume(currentUsbHwStep())
                )
            VolumeRoute.SYSTEM ->
                com.rawsmusic.module.player.usb.UsbHardwareVolumeModel.uiVolumeToStep(
                    if (isUsbExclusiveSoftwareVolumeActive()) {
                        AppPreferences.Player.volume.coerceIn(0f, 1f)
                    } else {
                        getSystemMusicVolumeLinear()
                    }
                )
        }
    }

    val isPlaying: Boolean
        get() = _playState.value == PlayState.PLAYING

    /** USB 引擎是否需要 reinit */
    fun requiresUsbReinit(): Boolean {
        return try {
            sharedUsbAudioEngine.nativeRequiresReinit()
        } catch (_: Throwable) {
            false
        }
    }

    /** 获取当前 USB 播放模式名称 */
    fun getUsbPlaybackModeName(): String {
        return try {
            sharedUsbAudioEngine.getPlaybackModeName()
        } catch (_: Throwable) {
            "Unknown"
        }
    }

    private fun describeDescriptorVolumeHint(volume: UsbExclusiveManager.VolumeInfo?): String {
        return when {
            volume == null -> "DescriptorHint: 未发现 Feature Unit 或尚未扫描"
            volume.hasMasterVolume -> "DescriptorHint: Feature Unit ${volume.entityId}, Master=true"
            volume.hasLeftVolume || volume.hasRightVolume ->
                "DescriptorHint: Feature Unit ${volume.entityId}, L=${volume.hasLeftVolume} R=${volume.hasRightVolume} (仅描述符快速扫描)"
            else -> "DescriptorHint: Feature Unit ${volume.entityId}, 无音量控制"
        }
    }

    private fun describeNativeVolumeController(stats: UsbStatsSnapshot?, nativePolicy: String): String {
        val parts = parseUsbStatsParts(nativePolicy)
        val state = stats?.featureUnitPolicy?.takeIf { it.isNotBlank() } ?: parts["state"] ?: "unknown"
        val path = stats?.featureUnitPath?.takeIf { it.isNotBlank() } ?: parts["path"] ?: "none"
        val reason = stats?.featureUnitReason?.takeIf { it.isNotBlank() } ?: parts["reason"] ?: "unknown"
        val range = stats?.featureUnitRangeVerified ?: (parseUsbBool(parts["range"]) == true)
        val readback = stats?.featureUnitReadbackVerified ?: (parseUsbBool(parts["readback"]) == true)
        val enabled = parseUsbBool(parts["enabled"])
        val nativeDescM = stats?.featureUnitDescriptorMaster ?: (parseUsbBool(parts["descM"]) == true)
        val nativeDescL = stats?.featureUnitDescriptorLeft ?: (parseUsbBool(parts["descL"]) == true)
        val nativeDescR = stats?.featureUnitDescriptorRight ?: (parseUsbBool(parts["descR"]) == true)
        val effM = stats?.featureUnitEffectiveMaster ?: (parseUsbBool(parts["effM"]) == true)
        val effL = stats?.featureUnitEffectiveLeft ?: (parseUsbBool(parts["effL"]) == true)
        val effR = stats?.featureUnitEffectiveRight ?: (parseUsbBool(parts["effR"]) == true)
        val singleCh = stats?.featureUnitSingleChannel?.takeIf { it > 0 } ?: parts["singleCh"]?.toIntOrNull() ?: 0
        val enabledText = enabled?.let { ", enabled=$it" } ?: ""
        val effective = when {
            effM -> "Master"
            effL && effR -> "L/R linked"
            effL || effR -> "single ch=$singleCh"
            else -> "none"
        }
        return "NativeVolumeController: state=$state, path=$path, effective=$effective, " +
            "nativeDesc(M=$nativeDescM L=$nativeDescL R=$nativeDescR), " +
            "verified(range=$range readback=$readback)$enabledText, reason=$reason"
    }

    private fun formatDsdBitRateHz(rateHz: Int): String {
        if (rateHz <= 0) return "unknown"
        return if (rateHz >= 1_000_000) {
            String.format(java.util.Locale.US, "%.4f MHz", rateHz / 1_000_000.0)
        } else {
            "$rateHz Hz"
        }
    }

    private fun buildDsdFormatText(
        multiplier: Int,
        rateHz: Int,
        channels: Int = 0,
        includeChannels: Boolean = true
    ): String = buildString {
        append("DSD$multiplier")
        append(" / 1bit")
        val rateText = formatDsdBitRateHz(rateHz)
        if (rateText != "unknown") append(" / $rateText")
        if (includeChannels && channels > 0) append(" / ${channels}ch")
    }

    private fun buildUsbDsdInfoText(
        sourceIsDsd: Boolean,
        dsdMode: UsbDsdModeConfig?,
        dsdRateHz: Int
    ): String {
        if (dsdMode == null) return "关闭"
        val modeText = buildDsdFormatText(
            multiplier = dsdMode.multiplier,
            rateHz = dsdRateHz,
            includeChannels = false
        )
        return if (sourceIsDsd) {
            "DSD源直通：$modeText"
        } else {
            "PCM→DSD：$modeText"
        }
    }

    private fun buildUsbOutputChainText(
        sourceIsDsd: Boolean,
        dsdMode: UsbDsdModeConfig?,
        bitPerfect: Boolean,
        needsPcmAdapter: Boolean
    ): String {
        return when {
            sourceIsDsd && dsdMode != null -> "DSD源直通 → DAC"
            dsdMode != null -> "PCM → DSD${dsdMode.multiplier} → DAC"
            bitPerfect -> "PCM 直通 → DAC"
            sourceIsDsd -> "DSD → PCM 解码 → DAC"
            needsPcmAdapter -> "PCM → 重采样/格式适配 → DAC"
            else -> "PCM 直通 → DAC"
        }
    }

    fun getUsbDeviceStatus(): UsbDeviceStatus {
        sharedUsbAudioEngine.refreshRuntimeSnapshotFromNative()
        val runtime = sharedUsbAudioEngine.getRuntimeFormat()
        val cfg = usbExclusiveManager.getCurrentConfig()
        val deviceName = usbExclusiveManager.getCurrentDeviceName() ?: "未检测到 USB DAC"
        val vid = usbExclusiveManager.getCurrentDeviceVendorId()
        val pid = usbExclusiveManager.getCurrentDeviceProductId()
        val vidPid = if (vid > 0 || pid > 0) {
            "VID ${vid.toString(16).uppercase().padStart(4, '0')} / PID ${pid.toString(16).uppercase().padStart(4, '0')}"
        } else {
            "未知"
        }
        val initialized = sharedUsbAudioEngine.isInitialized()
        val running = sharedUsbAudioEngine.isRunning()
        val sourceSr = usbExclusiveManager.getCurrentSourceSampleRate()
        val sourceBits = usbExclusiveManager.getCurrentSourceBits()
        val currentSong = _currentSong.value
        val songBits = currentSong?.bitsPerSample?.takeIf { it > 0 } ?: 0
        val sourceIsDsd = currentSongIsDsdSource() || sourceBits == 1
        val displaySourceSr = currentSong?.sampleRate?.takeIf { it > 0 } ?: sourceSr
        val displaySourceBits = when {
            sourceIsDsd -> 1
            songBits > 0 -> songBits
            else -> sourceBits
        }
        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        val effectiveDsdRate = currentEffectiveUsbDsdRate()
        val dsdMode = effectiveDsdMode
        val dsdBitRateHz = dsdMode?.let { dsdRateHzForMultiplier(it.multiplier) } ?: if (sourceIsDsd) currentSongDsdSourceRate() else 0
        val actualChannels = runtime.channels.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentChannels.takeIf { it > 0 } ?: cfg?.channels ?: 2
        val actualBits = runtime.validBits.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentBits.takeIf { it > 0 } ?: cfg?.bits ?: 0
        val sourceFormat = if (displaySourceSr > 0 && displaySourceBits > 0) {
            if (sourceIsDsd) {
                buildDsdFormatText(
                    multiplier = effectiveDsdRate,
                    rateHz = dsdBitRateHz,
                    channels = actualChannels
                )
            } else {
                "${displaySourceSr}Hz / ${displaySourceBits}bit / ${actualChannels}ch"
            }
        } else {
            "等待播放初始化"
        }
        val targetFormat = if (dsdMode != null) {
            buildDsdFormatText(
                multiplier = dsdMode.multiplier,
                rateHz = dsdBitRateHz,
                channels = actualChannels
            )
        } else if (cfg != null) {
            "${cfg.sampleRate}Hz / ${cfg.bits}bit / ${cfg.channels}ch / subslot ${cfg.subslot * 8}bit"
        } else {
            val sr = AppPreferences.Player.usbTargetSampleRate
            val bits = AppPreferences.Player.usbTargetBitDepth
            "偏好: ${AudioOutputManager.SAMPLE_RATE_LABELS[sr] ?: "自动"} / ${AudioOutputManager.BIT_DEPTH_LABELS[bits] ?: "自动"}"
        }
        val actualSr = runtime.sampleRate.takeIf { it > 0 }
            ?: sharedUsbAudioEngine.getOutputSampleRate().takeIf { it > 0 }
            ?: ffmpegPlayer.usbActualOutputSampleRate.takeIf { it > 0 }
            ?: sharedUsbAudioEngine.currentSampleRate
        val subslot = runtime.subslotBytes.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentSubslotSize.takeIf { it > 0 } ?: cfg?.subslot ?: 0
        val frameBytes = runtime.frameBytes.takeIf { it > 0 } ?: actualChannels * subslot
        val actualOutput = if (actualSr > 0 && actualChannels > 0 && actualBits > 0) {
            if (dsdMode != null) {
                buildDsdFormatText(
                    multiplier = dsdMode.multiplier,
                    rateHz = dsdBitRateHz,
                    channels = actualChannels
                )
            } else {
                buildString {
                    append("${actualSr}Hz / ${actualBits}bit / ${actualChannels}ch")
                    if (subslot > 0) {
                        append(" / subslot ${subslot * 8}bit")
                        append(" / frame ${frameBytes}B")
                    }
                }
            }
        } else {
            "未开始输出"
        }
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled
        val dsdInfo = buildUsbDsdInfoText(
            sourceIsDsd = sourceIsDsd,
            dsdMode = dsdMode,
            dsdRateHz = dsdBitRateHz
        )
        val needsPcmAdapter = cfg != null &&
            (
                displaySourceSr > 0 && displaySourceSr != cfg.sampleRate ||
                    displaySourceBits > 0 && displaySourceBits != cfg.bits
                )
        val chain = buildUsbOutputChainText(
            sourceIsDsd = sourceIsDsd,
            dsdMode = dsdMode,
            bitPerfect = bitPerfect,
            needsPcmAdapter = needsPcmAdapter
        )
        val nativeIface = runtime.iface.takeIf { it >= 0 } ?: sharedUsbAudioEngine.currentInterfaceNumber
        val nativeAlt = runtime.alt.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentAltSetting
        val nativeOutEp = runtime.outEndpoint.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentOutEndpoint
        val nativeFbEp = runtime.feedbackEndpoint.takeIf { it > 0 } ?: sharedUsbAudioEngine.currentFeedbackEndpoint
        val iface = nativeIface.takeIf { it >= 0 } ?: cfg?.iface ?: -1
        val alt = nativeAlt.takeIf { it > 0 } ?: cfg?.alt ?: 0
        val outEp = nativeOutEp.takeIf { it > 0 } ?: cfg?.outEp ?: 0
        val fbEp = nativeFbEp.takeIf { it > 0 } ?: cfg?.fbEp ?: 0
        val interfaceText = if (iface >= 0 && alt > 0) "Interface $iface / Alt $alt" else "等待 USB 流初始化"
        val endpointText = if (outEp > 0) {
            "OUT 0x${outEp.toString(16).uppercase().padStart(2, '0')}" +
                if (fbEp > 0) " / Feedback 0x${fbEp.toString(16).uppercase().padStart(2, '0')}" else " / Feedback 无"
        } else {
            "等待端点初始化"
        }
        val bufferUsed = sharedUsbAudioEngine.getBufferUsedBytes()
        val runtimeBps = if (actualSr > 0 && actualChannels > 0 && subslot > 0) {
            (actualSr.toLong() * actualChannels.toLong() * subslot.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else 0
        val outputBps = runtimeBps.takeIf { it > 0 } ?: sharedUsbAudioEngine.getOutputBytesPerSecond()
        val bufferMs = if (outputBps > 0) bufferUsed.toLong() * 1000L / outputBps else 0L
        val nativeStatsRaw = runCatching {
            val h = sharedUsbAudioEngine.currentHandle
            if (h != 0L) sharedUsbAudioEngine.nativeGetStatsString(h) else ""
        }.getOrDefault("")
        val audibleStateRaw = runCatching {
            val h = sharedUsbAudioEngine.currentHandle
            if (h != 0L) sharedUsbAudioEngine.nativeGetAudibleStateString(h) else ""
        }.getOrDefault("")
        val stats = parseUsbStats(nativeStatsRaw)
        val nativeFuPolicy = runCatching { sharedUsbAudioEngine.getHardwareVolumePolicyString() }.getOrDefault("")
        val volume = usbExclusiveManager.volumeInfo.value
        val descriptorVolumeHint = describeDescriptorVolumeHint(volume)
        val nativeVolumeController = if (nativeFuPolicy.isNotBlank() && nativeFuPolicy != "no-handle") {
            describeNativeVolumeController(stats, nativeFuPolicy)
        } else {
            "NativeVolumeController: unavailable"
        }
        val hardwareVolume = "$descriptorVolumeHint / $nativeVolumeController"
        val outputRatio = if (stats != null && stats.expectedBytesPerSec > 0L) {
            stats.usbOutBytesPerSec.toDouble() / stats.expectedBytesPerSec.toDouble()
        } else 0.0
        val transportDiagnostics = if (stats != null) {
            "appIn=${stats.appInBytesPerSec} B/s, completedOut=${stats.usbOutBytesPerSec} B/s, " +
                "expected=${stats.expectedBytesPerSec} B/s, ratio=${"%.3f".format(outputRatio)}, " +
                "errors underrun=${stats.underrun} submit=${stats.submitErr} packet=${stats.packetErr} xfer=${stats.xferErr}"
        } else {
            "native stats unavailable"
        }
        val audibleDiagnostics = buildAudibleDiagnostics(audibleStateRaw)
        val feedbackDiagnostics = if (stats != null) {
            buildString {
                append("enabled=${stats.feedbackEnabled}")
                append(", state=${stats.feedbackStateName}")
                if (stats.pacingModeName.isNotBlank()) append(", pacing=${stats.pacingModeName}")
                if (stats.feedbackValidCount > 0) append(", valid=${stats.feedbackValidCount}")
                if (stats.feedbackSampleRateMilli > 0) append(", rate=${stats.feedbackSampleRateMilli / 1000.0}Hz")
                if (stats.feedbackInvalidCount > 0 || stats.feedbackEmptyCount > 0) {
                    append(", invalid=${stats.feedbackInvalidCount}, empty=${stats.feedbackEmptyCount}")
                }
            }
        } else {
            if (fbEp > 0) "endpoint present, runtime stats unavailable" else "none"
        }
        val clockDiagnostics = if (stats != null) {
            "src=${stats.clockSource.ifBlank { "n/a" }}, sel=${stats.clockSelector.ifBlank { "n/a" }}, " +
                "iface=${stats.clockInterface}, verified=${stats.clockVerifiedRate.takeIf { it > 0 } ?: stats.clockVerified ?: "unknown"}, " +
                "validKnown=${stats.clockValidKnown}, valid=${stats.clockValid}, " +
                "deviceRate=${stats.clockRate}, targetRate=${stats.targetRate}"
        } else {
            "native stats unavailable"
        }
        val featureUnitDiagnostics = if (stats != null) {
            buildString {
                append(descriptorVolumeHint)
                append(", ")
                append(nativeVolumeController)
                if (stats.featureUnitPolicy.isNotBlank()) append(", policy=${stats.featureUnitPolicy}")
                if (stats.featureUnitPath.isNotBlank()) append(", path=${stats.featureUnitPath}")
                append(", result=${stats.featureUnitResult}")
                append(", range=${stats.featureUnitRangeVerified}, readback=${stats.featureUnitReadbackVerified}")
                if (stats.featureUnitReason.isNotBlank()) append(", reason=${stats.featureUnitReason}")
                if (nativeFuPolicy.isNotBlank()) append(", native=$nativeFuPolicy")
            }
        } else {
            buildString {
                append(descriptorVolumeHint)
                append(", ")
                append(nativeVolumeController)
                if (nativeFuPolicy.isNotBlank()) append(" / native=$nativeFuPolicy")
            }
        }
        val deviceKey = currentUsbDevice?.let { usbLearnedPolicyKeyFor(it) }
        val learnedPolicy = deviceKey?.let { runCatching { UsbLearnedPolicyStore.read(it) }.getOrNull() }
        val learnedRunawayUnproven = UsbLearnedPolicyStore.isRunawayUnprovenFallback(learnedPolicy)
        val profileDiagnostics = buildString {
            append("runtime iface=$iface alt=$alt sr=$actualSr bits=$actualBits subslot=$subslot out=0x${outEp.toString(16)} fb=0x${fbEp.toString(16)}")
            if (learnedPolicy != null) {
                append(", learned lastGoodAlt=${learnedPolicy.lastGoodAlt}")
                append(" lastGoodSr=${learnedPolicy.lastGoodSampleRate}")
                append(" lastGoodBits=${learnedPolicy.lastGoodBitDepth}")
                append(" lastGoodSubslot=${learnedPolicy.lastGoodSubslot}")
                append(" lastGoodFb=0x${learnedPolicy.lastGoodFeedbackEndpoint.toString(16)}")
                append(" flags noFb=${learnedPolicy.noFeedback} noClock=${learnedPolicy.noClockSet} noFU=${learnedPolicy.noFeatureUnit} force1ms=${learnedPolicy.force1msPacket} safeAlt=${learnedPolicy.preferSafeAlt}")
                append(" success=${learnedPolicy.successCount} failure=${learnedPolicy.failureCount}")
                if (learnedRunawayUnproven) append(" ignoredRunawayHints=true")
            } else {
                append(", learned=none")
            }
        }
        val recoveryDiagnostics = pendingUsbRecoveryPlan?.let { plan ->
            "pending action=${plan.action}, reason=${plan.reason}, disableFb=${plan.disableFeedback}, " +
                "disableClock=${plan.disableClockSet}, disableFU=${plan.disableFeatureUnit}, " +
                "force1ms=${plan.force1msPacket}, safeAlt=${plan.preferSafeAlt}, fullReopen=${plan.forceFullReopen}"
        } ?: "none"

        return UsbDeviceStatus(
            deviceName = deviceName,
            vendorProductId = vidPid,
            managerState = usbExclusiveManager.getCurrentState().name,
            connected = usbExclusiveManager.isDeviceConnected(),
            permissionGranted = usbExclusiveManager.hasCurrentDevicePermission(),
            exclusiveActive = _usbExclusiveActive.value,
            initialized = initialized,
            running = running,
            bitPerfect = bitPerfect,
            playbackMode = getUsbPlaybackModeName(),
            sourceFormat = sourceFormat,
            targetFormat = targetFormat,
            actualOutputFormat = actualOutput,
            outputChain = chain,
            dsdActive = dsdMode != null,
            dsdSourceDirect = sourceIsDsd && dsdMode != null,
            interfaceInfo = interfaceText,
            endpointInfo = endpointText,
            bufferInfo = if (outputBps > 0) "$bufferUsed B / ${bufferMs} ms / ${outputBps} B/s" else "$bufferUsed B",
            hardwareVolumeInfo = hardwareVolume,
            dsdInfo = dsdInfo,
            transportDiagnostics = transportDiagnostics,
            audibleDiagnostics = audibleDiagnostics,
            feedbackDiagnostics = feedbackDiagnostics,
            clockDiagnostics = clockDiagnostics,
            featureUnitDiagnostics = featureUnitDiagnostics,
            profileDiagnostics = profileDiagnostics,
            recoveryDiagnostics = recoveryDiagnostics,
            nativeStatsRaw = nativeStatsRaw,
            uac20PreviewDiagnostics = usbExclusiveManager.getLastUac20DebugProbeReport(),
            uac20GrayDecisionDiagnostics = usbExclusiveManager.getLastUac20GrayPlaybackDecisionReport()
        )
    }

    // ========== UAC20 v2 diagnostic probe (shadow mode) ==========

    fun runDefaultUac20DebugProbe(): Boolean {
        val result = usbExclusiveManager.runDefaultUac20DebugProbe()
        AppLogger.i(TAG, "runDefaultUac20DebugProbe ok=${result.ok} reason=${result.reason}")
        return result.ok
    }

    fun runUac20DebugProbe(
        sampleRate: Int = 192000,
        bits: Int = 24,
        channels: Int = 2,
        requestedSubslotBytes: Int = 4
    ): Boolean {
        val result = usbExclusiveManager.runUac20DebugProbe(
            sampleRate = sampleRate,
            bits = bits,
            channels = channels,
            requestedSubslotBytes = requestedSubslotBytes
        )
        AppLogger.i(TAG, "runUac20DebugProbe ok=${result.ok} reason=${result.reason}")
        return result.ok
    }

    fun getLastUac20DebugProbeReport(): String {
        return usbExclusiveManager.getLastUac20DebugProbeReport()
    }

    fun getLastUac20GrayPlaybackDecisionReport(): String =
        usbExclusiveManager.getLastUac20GrayPlaybackDecisionReport()

    fun setUac20DebugPlaybackSwitchEnabled(enabled: Boolean) {
        usbExclusiveManager.setUac20DebugPlaybackSwitchEnabled(enabled)
    }

    fun isUac20DebugPlaybackSwitchEnabled(): Boolean =
        usbExclusiveManager.isUac20DebugPlaybackSwitchEnabled()

    fun runCurrentUac20DebugDecodedPlaybackIfSwitchEnabled(
        maxDurationMs: Int = 5_000
    ): UsbExclusiveManager.Uac20DebugProbeResult {
        val song = _currentSong.value
            ?: return UsbExclusiveManager.Uac20DebugProbeResult(
                ok = false,
                reason = "no-current-song",
                pumpSource = "decoded-pcm",
                pumpStopReason = "invalid-source"
            )
        return usbExclusiveManager.runUac20DebugDecodedPlaybackIfSwitchEnabled(
            sourcePath = song.path,
            startPositionMs = _position.value,
            maxDurationMs = maxDurationMs
        )
    }

    /**
     * 0107: Generated 1 kHz tone smoke test. Bypasses FFmpeg/file decoding and
     * feeds deterministic PCM into the same v2 USB OUT path.
     */
    fun runUac20DebugGeneratedToneSmoke(
        sampleRate: Int = 44_100,
        bits: Int = 16,
        channels: Int = 2,
        requestedSubslotBytes: Int = 2,
        frequencyHz: Int = 1_000,
        maxDurationMs: Int = 5_000,
        pumpChunkMs: Int = 5,
        toneChannelMode: String = "dual"
    ): UsbExclusiveManager.Uac20DebugProbeResult {
        if (!prepareUac20DebugExclusiveSlot("manual_tone_smoke")) {
            return UsbExclusiveManager.Uac20DebugProbeResult(
                ok = false,
                reason = "legacy-usb-idle-timeout",
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                pumpSource = "generated-tone",
                pumpStopReason = "legacy-active"
            )
        }
        return usbExclusiveManager.runUac20DebugGeneratedToneSmoke(
            sampleRate = sampleRate,
            bits = bits,
            channels = channels,
            requestedSubslotBytes = requestedSubslotBytes,
            frequencyHz = frequencyHz,
            maxDurationMs = maxDurationMs,
            pumpChunkMs = pumpChunkMs,
            toneChannelMode = toneChannelMode
        )
    }

    /** 0108: 48 kHz generated-tone smoke. Use when 44.1 kHz sounds wrong to isolate clock-rate handling. */
    fun runUac20DebugGeneratedTone48kSmoke(
        frequencyHz: Int = 1_000,
        maxDurationMs: Int = 5_000,
        pumpChunkMs: Int = 5
    ): UsbExclusiveManager.Uac20DebugProbeResult = runUac20DebugGeneratedToneSmoke(
        sampleRate = 48_000,
        bits = 16,
        channels = 2,
        requestedSubslotBytes = 2,
        frequencyHz = frequencyHz,
        maxDurationMs = maxDurationMs,
        pumpChunkMs = pumpChunkMs,
        toneChannelMode = "dual"
    )

    /** 0108: left-only generated tone, useful for checking stereo interleave/channel routing. */
    fun runUac20DebugGeneratedToneLeftOnlySmoke(
        sampleRate: Int = 44_100,
        frequencyHz: Int = 1_000,
        maxDurationMs: Int = 5_000,
        pumpChunkMs: Int = 5
    ): UsbExclusiveManager.Uac20DebugProbeResult = runUac20DebugGeneratedToneSmoke(
        sampleRate = sampleRate,
        bits = 16,
        channels = 2,
        requestedSubslotBytes = 2,
        frequencyHz = frequencyHz,
        maxDurationMs = maxDurationMs,
        pumpChunkMs = pumpChunkMs,
        toneChannelMode = "left"
    )

    /** 0108: right-only generated tone, useful for checking stereo interleave/channel routing. */
    fun runUac20DebugGeneratedToneRightOnlySmoke(
        sampleRate: Int = 44_100,
        frequencyHz: Int = 1_000,
        maxDurationMs: Int = 5_000,
        pumpChunkMs: Int = 5
    ): UsbExclusiveManager.Uac20DebugProbeResult = runUac20DebugGeneratedToneSmoke(
        sampleRate = sampleRate,
        bits = 16,
        channels = 2,
        requestedSubslotBytes = 2,
        frequencyHz = frequencyHz,
        maxDurationMs = maxDurationMs,
        pumpChunkMs = pumpChunkMs,
        toneChannelMode = "right"
    )

    /**
     * 0048-0051: Debug-only decoded PCM playback smoke test.
     * Opens an FFmpeg decoder for the given source and pumps decoded PCM
     * into the v2 debug real-OUT path. Does NOT replace legacy playback.
     */
    fun runUac20DebugDecodedPlaybackSmoke(
        sourcePath: String,
        startPositionMs: Long = _position.value,
        maxDurationMs: Int = 5_000,
        targetSampleRate: Int = 0,
        // 0106: 0 means auto/probed source bit depth. Do not force the
        // decoded smoke path into 24-in-32; 16-bit sources must be allowed to
        // select the native 16/subslot2 alt.
        targetBits: Int = 0,
        targetChannels: Int = 2,
        // 0 means native matcher chooses the exact/lossless USB subslot.
        requestedSubslotBytes: Int = 0,
        pumpChunkMs: Int = 5
    ): UsbExclusiveManager.Uac20DebugProbeResult =
        usbExclusiveManager.runUac20DebugDecodedPlaybackSmoke(
            sourcePath = sourcePath,
            startPositionMs = startPositionMs,
            maxDurationMs = maxDurationMs,
            targetSampleRate = targetSampleRate,
            targetBits = targetBits,
            targetChannels = targetChannels,
            requestedSubslotBytes = requestedSubslotBytes,
            pumpChunkMs = pumpChunkMs
        )

    /**
     * 0048-0051: Convenience entry: decode the current song from the current
     * position into the v2 debug real-OUT path.
     */
    fun runCurrentUac20DebugDecodedPlaybackSmoke(
        maxDurationMs: Int = 5_000,
        // 0106: default to auto/probed source bit depth for the current song.
        // The old default forced 24/subslot4, so 16/44.1 sources were widened
        // to 32-bit USB alt and could sound distorted while stats looked fine.
        targetBits: Int = 0,
        requestedSubslotBytes: Int = 0,
        pumpChunkMs: Int = 5
    ): UsbExclusiveManager.Uac20DebugProbeResult {
        val song = _currentSong.value
            ?: return UsbExclusiveManager.Uac20DebugProbeResult(
                ok = false,
                reason = "no-current-song",
                pumpSource = "decoded-pcm",
                pumpStopReason = "invalid-source"
            )
        val startPosition = _position.value
        val targetRate = song.sampleRate.takeIf { it > 0 } ?: 0
        val targetChannels = song.channelCount.takeIf { it > 0 } ?: 2
        if (!prepareUac20DebugExclusiveSlot("manual_current_smoke")) {
            return UsbExclusiveManager.Uac20DebugProbeResult(
                ok = false,
                reason = "legacy-usb-idle-timeout",
                deviceName = null,
                vendorProductId = null,
                sampleRate = targetRate,
                bits = targetBits,
                channels = targetChannels,
                requestedSubslotBytes = requestedSubslotBytes,
                pumpSource = "decoded-pcm",
                pumpStopReason = "legacy-active"
            )
        }
        return runUac20DebugDecodedPlaybackSmoke(
            sourcePath = song.path,
            startPositionMs = startPosition,
            maxDurationMs = maxDurationMs,
            targetSampleRate = targetRate,
            targetBits = targetBits,
            targetChannels = targetChannels,
            requestedSubslotBytes = requestedSubslotBytes,
            pumpChunkMs = pumpChunkMs
        )
    }

    /**
     * 完整策略切换：stop + deinit + 设置策略 + init
     * 适用于 UI 切换 bit-perfect / hardwareFeatureUnit 等场景
     * 不会自动恢复播放，需要用户手动再次播放
     */
    fun restartUsbWithPolicy(
        exclusive: Boolean,
        bitPerfect: Boolean,
        hardwareVolumeRequested: Boolean
    ): Boolean {
        if (currentUsbDevice == null) {
            AppLogger.e(TAG, "restartUsbWithPolicy: no USB device")
            return false
        }
        if (exclusive && hardwareVolumeRequested) {
            // Keep the user request sticky across stop/release callbacks triggered
            // by the reconfigure itself.  Some callbacks re-apply volume routing
            // while the old handle is being torn down; they must not turn the
            // next native init into hwVolRequested=0.
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
        }
        sharedUsbAudioEngine.refreshRuntimeSnapshotFromNative()
        val restartSource = resolveUsbPolicyRestartSource()
        val sr = restartSource.sampleRate
        val ch = restartSource.channels
        val bits = restartSource.bitsPerSample

        // 1. 完全停止 ffmpeg 播放器（不只是暂停）
        try { ffmpegPlayer.stop() } catch (_: Throwable) {}
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hwVol = true)
            AppLogger.i(TAG, "restartUsbWithPolicy: hardware-volume request re-asserted after player stop")
        }
        smTransition(PlayState.STOPPED, "internal_stop")

        // 2. 停止 USB 流
        usbExclusiveManager.stopStreaming("policy_change")

        // 3. 设置新策略
        sharedUsbAudioEngine.nativeSetUsbExclusiveActive(exclusive)
        sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hardwareVolumeRequested)
        ffmpegPlayer.usbBitPerfectMode = bitPerfect

        // 4. 释放旧 native，通过 prepareForPlayback 重新初始化
        sharedUsbAudioEngine.release()
        usbExclusiveManager.release()
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hwVol = true)
            AppLogger.i(TAG, "restartUsbWithPolicy: hardware-volume request re-asserted before prepareForPlayback")
        }
        val ok = usbExclusiveManager.prepareForPlayback(sr, bits, ch, restartSource.sourcePath)
        if (exclusive && hardwareVolumeRequested) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = true
            sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hwVol = true)
            AppLogger.i(TAG, "restartUsbWithPolicy: hardware-volume request re-asserted after prepareForPlayback ok=$ok")
        }

        if (ok) {
            AppLogger.i(TAG, "restartUsbWithPolicy OK: exclusive=$exclusive bitPerfect=$bitPerfect hwVol=$hardwareVolumeRequested")
            // 5. 刷新音量路由
            applyVolumeRoute("restart_usb_with_policy")
        }

        return ok
    }

    private fun recoverUsbExclusiveAsync() {
        if (shouldDeferUsbHardRecovery("recoverUsbExclusiveAsync")) {
            pendingUsbPolicyRestart = true
            AppLogger.w(TAG, "USB recovery postponed by background/transition guard")
            return
        }
        if (!recoveringUsb.compareAndSet(false, true)) {
            AppLogger.d(TAG, "USB recovery already in progress, skip")
            return
        }
        if (!allowUsbExclusiveFullRecovery("recoverUsbExclusiveAsync")) {
            recoveringUsb.set(false)
            stopUsbExclusiveAfterFatalFailure("usb_recovery_reopen_fuse")
            return
        }
        val wasPlaying = _playState.value == PlayState.PLAYING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        val currentSong = _currentSong.value
        scope.launch(Dispatchers.IO) {
            try {
                AppLogger.e(TAG, "USB transport lost, full recovery required")
                try { ffmpegPlayer.stop() } catch (_: Throwable) {}
                try { sharedUsbAudioEngine.release() } catch (_: Throwable) {}
                try { usbExclusiveManager.release() } catch (_: Throwable) {}

                // ① 清除旧状态，否则 activateUsbEngine 的 sameDeviceAlreadyActive 检查会直接跳过
                currentUsbDevice = null
                _usbExclusiveActive.value = false
                syncUsbSystemAudioKeepAlive("usb_recovery_clear_old_state")

                delay(600)
                val device = usbExclusiveManager.findUsbAudioDevice()
                if (device == null) {
                    AppLogger.e(TAG, "USB recovery failed: no USB audio device")
                    withContext(Dispatchers.Main) {
                        stopUsbExclusiveAfterFatalFailure("usb_recovery_no_device")
                    }
                    return@launch
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                if (!usbManager.hasPermission(device)) {
                    AppLogger.e(TAG, "USB recovery failed: no permission")
                    usbExclusiveManager.requestPermissionSafely(device)
                    withContext(Dispatchers.Main) {
                        stopUsbExclusiveAfterFatalFailure("usb_recovery_no_permission")
                    }
                    return@launch
                }
                // ② 通过 requestPermissionSafely 设置 currentDevice（权限已有时同步完成）
                //    然后直接激活 USB 引擎（恢复场景无需用户确认）
                usbExclusiveManager.requestPermissionSafely(device)
                withContext(Dispatchers.Main) {
                    activateUsbEngineForPlayback(device)
                }
                AppLogger.i(TAG, "USB recovery success, device ready for playback")
                if (wasPlaying && currentSong != null) {
                    try {
                        play(currentSong)
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to resume playback after USB recovery", e)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "USB recovery exception", e)
                withContext(Dispatchers.Main) {
                    stopUsbExclusiveAfterFatalFailure("usb_recovery_exception")
                }
            } finally {
                recoveringUsb.set(false)
            }
        }
    }

    // ==================== 全路径音频焦点管理 ====================

    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) return true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { fc -> handleAudioFocusChange(fc) }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener)
            .setAcceptsDelayedFocusGain(true)
            .build()
        val result = am.requestAudioFocus(req)
        audioFocusRequest = req
        audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (audioFocusGranted) {
            // Some OEM builds can deliver a stale/full LOSS from the previous focus owner in the
            // first seconds of a cold-start play request.  Keep this slightly longer than the
            // AudioTrack + MediaSession bootstrap because ColorOS can emit LOSS after PLAYING.
            audioFocusStartupGraceUntilMs = SystemClock.elapsedRealtime() + 2_500L
        } else {
            Log.w(TAG, "AudioFocus: denied (result=$result)")
        }
        return audioFocusGranted
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        audioFocusGranted = false
        duckVolumeFactor = 1.0f
        wasPlayingBeforeFocusLoss = false
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        if (_usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode) {
            AppLogger.w(TAG, "AudioFocus: USB exclusive active, ignore focusChange=$focusChange")
            duckVolumeFactor = 1.0f
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AudioFocus: DUCK -> 0.2")
                duckVolumeFactor = 0.2f
                applyComposedVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AudioFocus: LOSS_TRANSIENT, pause")
                wasPlayingBeforeFocusLoss = ffmpegPlayer.isPlayingNow
                ffmpegPlayer.pause()
                smTransition(PlayState.PAUSED, "auto_pause_url_check")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                val now = SystemClock.elapsedRealtime()
                val playerState = ffmpegPlayer.state
                val playState = _playState.value
                val insideStartupGuard = now < audioFocusStartupGraceUntilMs
                val insideUserStartGuard = now < userPlayStartFocusGuardUntilMs

                // ColorOS/OPlus can dispatch a full LOSS right after our first MediaSession/
                // AudioTrack update during a user-initiated cold start.  In the logs this happens
                // after PLAYING is already reported, so checking PREPARING only is not enough.
                if ((insideStartupGuard || insideUserStartGuard) &&
                    (playerState == FfmpegAudioPlayer.State.PREPARING ||
                        playerState == FfmpegAudioPlayer.State.PLAYING ||
                        playState == PlayState.PREPARING ||
                        playState == PlayState.PLAYING ||
                        ffmpegPlayer.isPlayingNow)
                ) {
                    Log.w(
                        TAG,
                        "AudioFocus: LOSS ignored during user start guard " +
                            "playerState=$playerState playState=$playState " +
                            "startupRemaining=${audioFocusStartupGraceUntilMs - now}ms " +
                            "userRemaining=${userPlayStartFocusGuardUntilMs - now}ms"
                    )
                    return
                }
                Log.d(TAG, "AudioFocus: LOSS, pause permanently")
                wasPlayingBeforeFocusLoss = false
                ffmpegPlayer.pause()
                smTransition(PlayState.PAUSED, "auto_pause_bluetooth")
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AudioFocus: GAIN, wasPlaying=$wasPlayingBeforeFocusLoss")
                duckVolumeFactor = 1.0f
                applyComposedVolume()
                if (wasPlayingBeforeFocusLoss) {
                    val usbBlocked = _usbExclusiveActive.value &&
                        !usbExclusiveManager.isDeviceConnected()
                    if (usbBlocked) {
                        AppLogger.w(TAG, "AudioFocus: GAIN but USB disconnected, skip resume")
                        wasPlayingBeforeFocusLoss = false
                    } else {
                        wasPlayingBeforeFocusLoss = false
                        ffmpegPlayer.resume()
                        smTransition(PlayState.PLAYING, "auto_resume")
                    }
                }
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        try {
            context.registerReceiver(
                noisyAudioReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            noisyRegistered = true
            Log.d(TAG, "NoisyAudio: registered")
        } catch (e: Exception) {
            Log.w(TAG, "NoisyAudio: register failed", e)
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        try { context.unregisterReceiver(noisyAudioReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    private fun registerScoReceiver() {
        if (scoReceiverRegistered) return
        try {
            context.registerReceiver(
                scoStateReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            scoReceiverRegistered = true
            Log.d(TAG, "SCO receiver: registered")
        } catch (e: Exception) {
            Log.w(TAG, "SCO receiver: register failed", e)
        }
    }

    private fun unregisterScoReceiver() {
        if (!scoReceiverRegistered) return
        try { context.unregisterReceiver(scoStateReceiver) } catch (_: Exception) {}
        scoReceiverRegistered = false
    }

    /**
     * 启动蓝牙 SCO 连接（主线程调用）
     * @return true 如果成功启动
     */
    fun startBluetoothSco(): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (!am.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "startBluetoothSco: SCO not available off call")
                return false
            }
            // 先注册 SCO 状态监听
            registerScoReceiver()
            // 启动 SCO
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            Log.i(TAG, "startBluetoothSco: SCO start requested")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startBluetoothSco failed: ${e.message}")
            false
        }
    }

    /**
     * 停止蓝牙 SCO 连接
     */
    fun stopBluetoothSco() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            unregisterScoReceiver()
            _scoConnected.value = false
            Log.i(TAG, "stopBluetoothSco: SCO stopped")
            // SCO 停止后重建 AudioTrack，切回 USAGE_MEDIA 属性
            scope.launch(Dispatchers.Main) {
                try {
                    ffmpegPlayer.rebuildAudioTrackForScoDisconnected()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rebuild AudioTrack after SCO stop: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopBluetoothSco failed: ${e.message}")
        }
    }

    private fun setupNextSongForGapless() {
        try {
            val gaplessOn = AppPreferences.Player.gaplessPlaybackEnabled
            val crossfadeSec = AppPreferences.Player.crossfadeDuration
            if (!gaplessOn && crossfadeSec <= 0) {
                ffmpegPlayer.nextSongPath = null
                ffmpegPlayer.crossfadeDurationMs = 0
                return
            }
            val songs = _queue.value.songs
            if (songs.isEmpty()) return
            val curIdx = _queue.value.currentIndex
            if (curIdx < 0) return
            val nextIdx = calculateNextIndexForGapless(curIdx, songs.size)
            if (nextIdx < 0 || nextIdx >= songs.size) return
            val nextSong = songs[nextIdx]
            if (nextSong.path.isBlank()) return
            ffmpegPlayer.nextSongPath = nextSong.path
            ffmpegPlayer.crossfadeDurationMs = if (crossfadeSec > 0) crossfadeSec * 1000 else 0
            Log.d(TAG, "Gapless: nextSong='${nextSong.title}', gapless=$gaplessOn, crossfade=${crossfadeSec}s")
        } catch (e: Exception) {
            Log.w(TAG, "setupNextSongForGapless failed", e)
        }
    }

    private fun calculateNextIndexForGapless(currentIdx: Int, size: Int): Int {
        if (size <= 0) return -1
        return when (playMode.value) {
            PlayMode.REPEAT_ONE -> currentIdx
            PlayMode.SEQUENTIAL -> {
                val next = currentIdx + 1
                if (next >= size) -1 else next
            }
            PlayMode.SHUFFLE_ALL -> {
                val shuffleIdx = shuffleIndices.indexOf(currentIdx)
                if (shuffleIdx >= 0 && shuffleIdx + 1 < shuffleIndices.size) {
                    shuffleIndices[shuffleIdx + 1]
                } else if (shuffleIndices.isNotEmpty()) {
                    shuffleIndices[0]
                } else {
                    val next = currentIdx + 1
                    if (next >= size) 0 else next
                }
            }
            PlayMode.SHUFFLE_ONCE -> {
                val shuffleIdx = shuffleIndices.indexOf(currentIdx)
                if (shuffleIdx >= 0 && shuffleIdx + 1 < shuffleIndices.size) {
                    shuffleIndices[shuffleIdx + 1]
                } else -1
            }
        }
    }

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        val token = latestPlayRequestToken.incrementAndGet()
        eventQueue.submit(PE.PlayEvent(song, queue, index) { s, q, i ->
            if (token != latestPlayRequestToken.get()) {
                AppLogger.w(TAG, "play() skipped stale queued request: title=${s.title} token=$token latest=${latestPlayRequestToken.get()}")
            } else {
                transportMutex.withLock {
                    if (token != latestPlayRequestToken.get()) {
                        AppLogger.w(TAG, "play() skipped stale request after mutex: title=${s.title} token=$token latest=${latestPlayRequestToken.get()}")
                    } else if (shouldRouteExplicitPlayThroughManualSwitch(s)) {
                        val (switchQueue, switchIndex) = resolveExplicitPlayQueue(s, q, i)
                        AppLogger.w(
                            TAG,
                            "play(): routing explicit song selection through manual switch " +
                                "title=${s.title} index=$switchIndex queueSize=${switchQueue.size}"
                        )
                        playManualSwitchFromStartLocked(s, switchQueue, switchIndex, "manual_select")
                    } else {
                        playInternal(s, q, i)
                    }
                }
            }
        })
    }

    private fun samePlaybackItem(a: AudioFile, b: AudioFile): Boolean =
        a.path == b.path &&
            a.cueOffsetMs == b.cueOffsetMs &&
            a.cueTrackIndex == b.cueTrackIndex

    private fun resolveExplicitPlayQueue(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int
    ): Pair<List<AudioFile>, Int> {
        if (queue.isNotEmpty()) {
            val safeIndex = index.coerceIn(0, queue.lastIndex)
            return queue to safeIndex
        }
        val currentQueue = _queue.value.songs
        val existingIndex = currentQueue.indexOfFirst { samePlaybackItem(it, song) }
        return if (existingIndex >= 0) {
            currentQueue to existingIndex
        } else {
            listOf(song) to 0
        }
    }

    private fun shouldRouteExplicitPlayThroughManualSwitch(song: AudioFile): Boolean {
        val current = _currentSong.value ?: return false
        if (samePlaybackItem(current, song)) return false
        val controllerPlaying = _playState.value == PlayState.PLAYING
        val enginePlaying = ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING
        if (!controllerPlaying && !enginePlaying) return false

        // USB exclusive has its own track-switch fade path.  For normal output,
        // only route through the manual-switch lane when the configured manual
        // transition actually requests a short fade.  This keeps cold list taps,
        // paused list selection and non-fade transition modes unchanged.
        return _usbExclusiveActive.value || configuredManualShortFadeMs() > 0
    }

    private fun isLegacyUsbActiveForUac20Debug(): Boolean {
        return sharedUsbAudioEngine.currentHandle != 0L ||
            sharedUsbAudioEngine.isInitialized() ||
            UsbAudioEngine.currentHandle != 0L ||
            UsbAudioEngine.isInitialized()
    }

    private fun waitForUac20DebugUsbIdle(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() <= deadline) {
            if (!isLegacyUsbActiveForUac20Debug()) return true
            SystemClock.sleep(40)
        }
        return !isLegacyUsbActiveForUac20Debug()
    }

    private fun tryAutoRearmUsbExclusiveForPlayback(song: AudioFile): Boolean {
        if (_usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode) return false
        if (_isRenderSwitching.value || recoveringUsb.get()) return false

        val device = currentUsbDevice ?: usbExclusiveManager.findUsbAudioDevice() ?: return false
        val permissionPrefetchedForDevice = usbAttachPermissionPrefetchDeviceId == device.deviceId
        if (!AppPreferences.Player.lastUsbExclusiveActive && !permissionPrefetchedForDevice) {
            AppLogger.i(
                TAG,
                "USB exclusive auto-rearm skipped: lastExclusive=false prefetchDeviceId=$usbAttachPermissionPrefetchDeviceId " +
                    "device=${device.deviceName} song=${song.title}"
            )
            return false
        }
        if (!usbExclusiveManager.hasPermission(device)) {
            AppLogger.w(
                TAG,
                "USB exclusive auto-rearm skipped: no permission device=${device.deviceName} song=${song.title}"
            )
            return false
        }

        return runCatching {
            AppLogger.i(
                TAG,
                "USB exclusive auto-rearm before playback: device=${device.productName} song=${song.title}"
            )
            currentUsbDevice = device
            usbExclusiveManager.rememberDeviceOnly(device, reason = "play_request_auto_rearm")
            prepareUsbExclusiveColdActivation("play_request_auto_rearm:${song.path}")
            activateUsbEngineForPlayback(device)
            if (permissionPrefetchedForDevice && _usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode) {
                usbAttachPermissionPrefetchDeviceId = -1
            }
            _usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode
        }.getOrElse { error ->
            AppLogger.w(TAG, "USB exclusive auto-rearm failed; continue with shared playback", error)
            false
        }
    }

    /**
     * Debug-only v2 playback requires exclusive ownership before creating a v2
     * session. PlayerController.stop() is queued and ffmpegPlayer.stop() leaves
     * USB lifecycle ownership to PlayerController, so the manual smoke/gray path
     * needs a synchronous preflight that drains decoder writes and closes the
     * legacy native handle before UsbExclusiveManager checks for legacy-usb-active.
     */
    private fun prepareUac20DebugExclusiveSlot(
        reason: String,
        timeoutMs: Long = 2_400L
    ): Boolean {
        if (!isLegacyUsbActiveForUac20Debug()) return true
        AppLogger.w(
            TAG,
            "UAC20 debug prepare: stopping legacy USB before v2 reason=$reason " +
                "playerState=${ffmpegPlayer.state} playState=${_playState.value} " +
                "handle=0x${java.lang.Long.toUnsignedString(sharedUsbAudioEngine.currentHandle, 16)} " +
                "initialized=${sharedUsbAudioEngine.isInitialized()}"
        )
        runCatching { savePosition() }
        runCatching { ffmpegPlayer.stop() }
            .onFailure { AppLogger.w(TAG, "UAC20 debug prepare: ffmpegPlayer.stop failed", it) }
        SystemClock.sleep(160)
        runCatching { sharedUsbAudioEngine.closeNative("uac20_debug_prepare:$reason") }
            .onFailure { AppLogger.w(TAG, "UAC20 debug prepare: closeNative failed", it) }
        val idle = waitForUac20DebugUsbIdle(timeoutMs)
        if (!idle) {
            AppLogger.w(
                TAG,
                "UAC20 debug prepare: legacy USB still active after wait reason=$reason " +
                    "handle=0x${java.lang.Long.toUnsignedString(sharedUsbAudioEngine.currentHandle, 16)} " +
                    "initialized=${sharedUsbAudioEngine.isInitialized()}"
            )
            return false
        }
        AppLogger.i(TAG, "UAC20 debug prepare: legacy USB idle, v2 may start reason=$reason")
        return true
    }

    fun setPlayQueue(songs: List<AudioFile>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        play(songs[index], songs, index)
    }

    /**
     * 0060-0063 gray playback hook.
     *
     * Default behavior is unchanged because the hidden switch is false. When the
     * switch is enabled, this runs a bounded v2 decoded-PCM debug playback before
     * legacy FFmpeg playback starts. If the v2 runtime guard passes, the current
     * request is considered consumed by the debug v2 path. If anything fails, the
     * method returns false and playInternal continues through the legacy path.
     */
    private fun tryConsumeWithUac20GrayPlayback(song: AudioFile): Boolean {
        if (!_usbExclusiveActive.value) return false
        if (!usbExclusiveManager.isUac20DebugPlaybackSwitchEnabled()) return false
        if (song.path.isBlank()) return false

        val startPos = _position.value.coerceAtLeast(0L)
        val targetRate = song.sampleRate.takeIf { it > 0 } ?: 0
        val targetChannels = song.channelCount.takeIf { it > 0 } ?: 2
        val targetBits = song.bitsPerSample.takeIf { it > 0 } ?: 24
        val requestedSubslot = if (targetBits == 24) 4 else ((targetBits + 7) / 8).coerceAtLeast(2)

        AppLogger.w(
            TAG,
            "UAC20 gray playback decision: attempting debug v2 route before legacy " +
                "title=${song.title} fmt=${targetRate}/${targetBits}/${targetChannels}/subslot=$requestedSubslot"
        )
        if (!prepareUac20DebugExclusiveSlot("gray_playback_preflight")) {
            AppLogger.w(TAG, "UAC20 gray playback fallback to legacy: legacy USB could not become idle")
            return false
        }
        val decision = usbExclusiveManager.runUac20GrayPlaybackDecision(
            sourcePath = song.path,
            startPositionMs = startPos,
            maxDurationMs = 30_000,
            targetSampleRate = targetRate,
            targetBits = targetBits,
            targetChannels = targetChannels,
            requestedSubslotBytes = requestedSubslot,
            pumpChunkMs = 5
        )
        if (!decision.consumedByV2) {
            AppLogger.w(
                TAG,
                "UAC20 gray playback fallback to legacy: reason=${decision.fallbackReason} route=${decision.route}"
            )
            return false
        }

        AppLogger.w(TAG, "UAC20 gray playback consumed by debug v2 path: ${decision.reportLine}")
        smForceTransition(PlayState.PLAYING, "uac20_gray_playback")
        startProgressUpdate()
        return true
    }

    private fun playInternal(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        Log.d(TAG, "play() called: title=${song.title}, path=${song.path}, isReleased=$isReleased")
        if (isReleased) {
            Log.w(TAG, "play() skip: isReleased")
            return
        }
        if (_usbExclusiveActive.value) {
            invalidateUsbSelfTest("play_request:${song.path}", clearSessionKey = true)
            // A previous warm-pause/self-test may have left an in-memory retry
            // plan.  User-initiated playback/track switch should start from the
            // modeled default/last-good profile, not from a stale unaccepted
            // failure symptom.
            if (pendingUsbRecoveryPlan?.forceFullReopen != true) {
                pendingUsbRecoveryPlan = null
                pendingUsbPolicyRestart = false
            }
        }
        // Consume deferred USB policy restart before starting fresh playback.
        if (pendingUsbPolicyRestart) {
            AppLogger.i(TAG, "playInternal: consuming pendingUsbPolicyRestart before new track plan=$pendingUsbRecoveryPlan")
            pendingUsbPolicyRestart = false
            // Force-apply with userInitiated to bypass the playing-deferral guard.
            applyUsbOutputSettingsChanged(userInitiated = true)
        }
        // USB recovery 进行中，等待完成后再播放
        if (recoveringUsb.get()) {
            AppLogger.w(TAG, "play() deferred: USB recovery in progress")
            scope.launch(Dispatchers.IO) {
                var waitMs = 0
                while (recoveringUsb.get() && waitMs < 5000) {
                    delay(100)
                    waitMs += 100
                }
                if (!recoveringUsb.get() && !isReleased) {
                    playInternal(song, queue, index)
                }
            }
            return
        }
        // 播放请求风暴防护：列表点击、场景切换和恢复回调可能在 1 秒内重复发同一首。
        // 若旧请求已进入 PREPARING/PLAYING/PAUSED，再重启同一路径会制造 obsolete 循环，
        // 表现为点歌后先跳播放器页、又被切回列表页，甚至误判为流式解码失败。
        val now = SystemClock.elapsedRealtime()
        val sameCueTrack = song.path == lastPlayRequestPath &&
            song.cueOffsetMs == lastPlayRequestCueOffset && song.cueTrackIndex == lastPlayRequestCueIndex
        val ffState = ffmpegPlayer.state
        if (sameCueTrack &&
            now - lastPlayRequestTime < 1200 &&
            (ffState == FfmpegAudioPlayer.State.PREPARING ||
                ffState == FfmpegAudioPlayer.State.PLAYING ||
                ffState == FfmpegAudioPlayer.State.PAUSED)
        ) {
            Log.w(TAG, "Duplicate play request ignored: state=$ffState path=${song.path}")
            return
        }
        lastPlayRequestPath = song.path
        lastPlayRequestCueOffset = song.cueOffsetMs
        lastPlayRequestCueIndex = song.cueTrackIndex
        lastPlayRequestTime = now

        lastPlayerError = null
        precacheJob?.cancel()

        _currentSong.value?.let { current ->
            if (current.id != song.id && playHistory.lastOrNull()?.id != current.id) {
                playHistory.addLast(current)
                if (playHistory.size > 30) playHistory.removeFirst()
            }
        }

        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_ENSURE_WAKELOCK
            }
            context.startService(intent)
        } catch (_: Exception) {
        }

        val isRemoteUrl = song.path.startsWith("http://") || song.path.startsWith("https://")
        if (song.path.isBlank() || (!isRemoteUrl && !java.io.File(song.path).exists())) {
            Log.w(TAG, "play() skip: file not found: ${song.path}")
            consecutiveFailures++
            if (consecutiveFailures > 5) {
                stop()
                try {
                    android.widget.Toast.makeText(context, "连续播放失败，已停止", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
                consecutiveFailures = 0
                return
            }
            if (queue.size > 1) {
                val nextIndex = (index + 1) % queue.size
                play(queue[nextIndex], queue, nextIndex)
            }
            return
        }

        try {
            if (queue.isNotEmpty()) {
                val safeIndex = index.coerceIn(0, queue.size - 1)
                _queue.value = PlayQueue(songs = queue, currentIndex = safeIndex)
                // 新队列，shuffle 模式下重新初始化随机顺序
                if (_isShuffle.value) {
                    enableShuffle()
                }
            } else {
                val currentQueue = _queue.value.songs.toMutableList()
                val existingIndex = currentQueue.indexOfFirst { it.path == song.path && it.cueOffsetMs == song.cueOffsetMs && it.cueTrackIndex == song.cueTrackIndex }
                if (existingIndex >= 0) {
                    _queue.value = _queue.value.copy(currentIndex = existingIndex)
                } else {
                    currentQueue.add(song)
                    _queue.value = PlayQueue(songs = currentQueue, currentIndex = currentQueue.size - 1)
                }
            }

            _currentSong.value = song
            AppLogger.markPlaybackReportStart(
                title = song.title,
                artist = song.artist,
                album = song.album,
                path = song.path,
                cueOffsetMs = song.cueOffsetMs
            )
            FFmpegBridge.resetDebugLog("playback_report_start:${song.path}")
            _position.value = 0L
            playCountRecordedKey = ""
            currentTrackMaxPositionMs = 0L
            cachedCueOffsetMs = song.cueOffsetMs
            cachedCueEndMs = song.cueEndMs
            cachedSongDuration = song.duration
            if (song.duration > 0) _duration.value = song.duration
            applyReplayGain(song)
            tryAutoRearmUsbExclusiveForPlayback(song)

            enrichJob?.cancel()
            if (!isRemoteUrl) {
                enrichJob = scope.launch(Dispatchers.IO) {
                    // USB critical startup 期间延迟元数据扫描，避免抢 CPU
                    if (isUsbCriticalStartup()) {
                        AppLogger.i(TAG, "enrichJob delayed by USB critical startup")
                        delay(2500)
                    }
                    try {
                        val enriched = com.rawsmusic.module.scanner.MediaStoreScanner.enrichSong(song)
                        val isCueTrack = song.cueEndMs > 0 || song.cueTrackIndex > 0
                        val finalEnriched = if (isCueTrack) {
                            enriched.copy(
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                albumArtist = song.albumArtist,
                                duration = song.duration,
                                trackNumber = song.trackNumber,
                                cueOffsetMs = song.cueOffsetMs,
                                cueEndMs = song.cueEndMs,
                                cueTrackIndex = song.cueTrackIndex
                            )
                        } else {
                            enriched
                        }
                        val changed = finalEnriched != song
                        if (changed) {
                            com.rawsmusic.module.data.repository.MusicRepository.updateSong(finalEnriched)
                            withContext(Dispatchers.Main) {
                                // 校验当前仍然是同一首歌，避免异步 enrich 污染下一首
                                if (_currentSong.value?.path == song.path &&
                                    _currentSong.value?.cueOffsetMs == song.cueOffsetMs &&
                                    _currentSong.value?.cueTrackIndex == song.cueTrackIndex
                                ) {
                                    _currentSong.value = finalEnriched
                                }
                            }
                            val q = _queue.value
                            val idx = q.songs.indexOfFirst { it.path == song.path && it.cueOffsetMs == song.cueOffsetMs && it.cueTrackIndex == song.cueTrackIndex }
                            if (idx >= 0) {
                                val newList = q.songs.toMutableList()
                                newList[idx] = finalEnriched
                                _queue.value = q.copy(songs = newList)
                            }
                        }

                        val nextIdx = _queue.value.currentIndex + 1
                        if (nextIdx < _queue.value.songs.size) {
                            val nextSong = _queue.value.songs[nextIdx]
                            if (nextSong.sampleRate == 0 || nextSong.bitsPerSample == 0) {
                                try {
                                    val nextEnriched = com.rawsmusic.module.scanner.MediaStoreScanner.enrichSong(nextSong)
                                    val nextChanged = nextEnriched.sampleRate != nextSong.sampleRate ||
                                        nextEnriched.bitsPerSample != nextSong.bitsPerSample
                                    if (nextChanged) {
                                        com.rawsmusic.module.data.repository.MusicRepository.updateSong(nextEnriched)
                                        withContext(Dispatchers.Main) {
                                            val q2 = _queue.value
                                            val idx2 = q2.songs.indexOfFirst { it.path == nextSong.path && it.cueOffsetMs == nextSong.cueOffsetMs && it.cueTrackIndex == nextSong.cueTrackIndex }
                                            if (idx2 >= 0) {
                                                val newList2 = q2.songs.toMutableList()
                                                newList2[idx2] = nextEnriched
                                                _queue.value = q2.copy(songs = newList2)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        scope.launch(Dispatchers.IO) {
                            if (isUsbCriticalStartup()) delay(2500)
                            val q3 = _queue.value
                            for (( idx, s) in q3.songs.withIndex()) {
                                if (idx == _queue.value.currentIndex || idx == nextIdx) continue
                                if (s.sampleRate > 0 && s.bitsPerSample > 0) continue
                                try {
                                    val e = com.rawsmusic.module.scanner.MediaStoreScanner.enrichSong(s)
                                    if (e.sampleRate != s.sampleRate || e.bitsPerSample != s.bitsPerSample) {
                                        com.rawsmusic.module.data.repository.MusicRepository.updateSong(e)
                                        withContext(Dispatchers.Main) {
                                            val q4 = _queue.value
                                            val idx4 = q4.songs.indexOfFirst { it.path == s.path && it.cueOffsetMs == s.cueOffsetMs && it.cueTrackIndex == s.cueTrackIndex }
                                            if (idx4 >= 0) {
                                                val nl = q4.songs.toMutableList()
                                                nl[idx4] = e
                                                _queue.value = q4.copy(songs = nl)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            Log.d(TAG, "Starting FFmpeg playback: ${song.path}")
            if (!requestAudioFocus()) {
                Log.w(TAG, "AudioFocus denied, playback may be interrupted")
            }
            registerNoisyReceiver()

            // 蓝牙 SCO 模式：启动 SCO 连接（异步，不阻塞主线程）
            // AudioTrack 会立即使用 SCO AudioAttributes 创建
            // SCO 连接成功后 scoStateReceiver 会重建 AudioTrack 确保路由正确
            if (AudioOutputManager.shouldUseScoMode(context)) {
                Log.i(TAG, "SCO mode enabled, starting Bluetooth SCO...")
                if (startBluetoothSco()) {
                    Log.i(TAG, "SCO start requested, AudioTrack will be rebuilt on SCO connected")
                } else {
                    Log.w(TAG, "Failed to start SCO, proceeding with normal playback")
                }
            }

            // USB 独占后台冷启动保护：先确保前台服务 + WakeLock + critical startup window
            if (_usbExclusiveActive.value) {
                enterUsbCriticalStartup("play_usb_exclusive")
                ensureUsbMediaIdentity("play_usb_exclusive_before_native", song, _position.value)
                scope.launch(Dispatchers.IO) {
                    ensureUsbForegroundBeforeStart("play_usb_exclusive")
                }
            }

            if (tryConsumeWithUac20GrayPlayback(song)) {
                AppLogger.w(TAG, "playInternal: legacy playback skipped because debug v2 gray path consumed request")
                saveState()
                return
            }

            if (!_usbExclusiveActive.value) {
                ffmpegPlayer.armDefaultStartFadeIn(
                    durationMs = TransitionPreferences.transportDurationOrZero(),
                    reason = "play_internal_start"
                )
            }
            ffmpegPlayer.play(song.path)

            // AndroidAudioIdentity 不能早于 native USB claim/streaming 启动。
            // 否则 AudioTrack 可能被系统默认路由到 USB DAC，导致系统 USB Audio HAL
            // 先占住 AS interface，nativeInitUsbDevice 最终 claim iface=2 BUSY。
            if (_usbExclusiveActive.value) {
                scope.launch {
                    repeat(10) { attempt ->
                        delay(if (attempt == 0) 250L else 500L)
                        syncUsbSystemAudioKeepAlive("post_native_usb_start#$attempt")
                        if (usbSystemAudioKeepAlive.isRunning()) return@launch
                    }
                }
            }

            // Gapless / Crossfade: 设置下一首歌信息
            setupNextSongForGapless()

            scope.launch {
                delay(1000)
                checkBluetoothOutput()
            }

            if (pendingSeekPosition > 0 && pendingSeekPath == song.path) {
                val seekPos = pendingSeekPosition
                val seekPath = pendingSeekPath
                pendingSeekPosition = -1L
                pendingSeekPath = null
                Log.d(TAG, "Restoring playback position: ${seekPos}ms for ${song.title}")
                pendingRestoreSeekJob?.cancel()
                pendingRestoreSeekJob = scope.launch {
                    delay(300)
                    // 安全检查：确保 300ms 后还是同一首歌
                    val cur = _currentSong.value
                    if (cur == null || cur.path != seekPath || cur.cueOffsetMs != song.cueOffsetMs) {
                        AppLogger.w(TAG, "restore seek ignored: song changed during delay")
                        return@launch
                    }
                    if (ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
                        if (song.cueOffsetMs > 0) {
                            ffmpegPlayer.seekTo(seekPos)
                            _position.value = (seekPos - song.cueOffsetMs).coerceAtLeast(0L)
                            seekJustPerformed = true
                        } else {
                            seekTo(seekPos)
                        }
                    }
                }
            } else if (song.cueOffsetMs > 0) {
                val cueOffset = song.cueOffsetMs
                Log.d(TAG, "CUE track: seeking to ${cueOffset}ms for ${song.title}")
                pendingRestoreSeekJob?.cancel()
                pendingRestoreSeekJob = scope.launch {
                    delay(300)
                    val cur = _currentSong.value
                    if (cur?.path != song.path || cur.cueOffsetMs != song.cueOffsetMs) {
                        AppLogger.w(TAG, "cue seek ignored: song changed during delay")
                        return@launch
                    }
                    if (ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
                        ffmpegPlayer.seekTo(cueOffset)
                        _position.value = 0L
                        seekJustPerformed = true
                    }
                }
            } else if (pendingSeekPosition > 0) {
                pendingSeekPosition = -1L
                pendingSeekPath = null
            }

            precacheNextSong()

            saveState()
        } catch (e: Exception) {
            Log.e(TAG, "play() failed", e)
            if (queue.size > 1) {
                val nextIndex = (index + 1) % queue.size
                play(queue[nextIndex], queue, nextIndex)
            }
        }
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0) {
        if (songs.isEmpty() || isReleased) return
        val safeIndex = startIndex.coerceIn(0, songs.size - 1)
        _queue.value = PlayQueue(songs = songs, currentIndex = safeIndex)
        // 新队列，shuffle 模式下重新初始化随机顺序
        if (_isShuffle.value) {
            enableShuffle()
        }
        play(songs[safeIndex], songs, safeIndex)
    }

    private var precacheJob: Job? = null

    fun precacheNextSong() {
        precacheJob?.cancel()
        if (isReleased) return
        if (!com.rawsmusic.module.data.prefs.AppPreferences.Player.gaplessPlaybackEnabled) return
        val q = _queue.value
        if (q.songs.isEmpty()) return
        val nextIdx = if (_isShuffle.value) {
            q.songs.indices.filter { it != q.currentIndex }.randomOrNull() ?: return
        } else {
            (q.currentIndex + 1) % q.songs.size
        }
        if (nextIdx !in q.songs.indices) return
        val nextSong = q.songs[nextIdx]
        precacheJob = scope.launch(Dispatchers.IO) {
            // 流式解码器模式（USB 和 AudioTrack 均使用），无需预缓存文件
            Log.d(TAG, "precacheNextSong: streaming decoder mode, skip file-based precache")
        }
    }

    /**
     * Resolve the song that should be started by a mini-player/capsule play button.
     *
     * On a cold app launch the capsule can become visible before the controller has fully restored
     * its in-memory currentSong.  A plain `_currentSong.value?.let { play(it) }` then drops the
     * first tap and the user has to tap a second time.  Keep this fallback local to play/pause so
     * tapping list artwork still follows the explicit queue path.
     */
    private fun resolvePlayPauseSeedSong(): AudioFile? {
        _currentSong.value?.let { return it }

        restoreLastSong()?.let { restored ->
            AppLogger.d(TAG, "playPause seed: restored last song ${restored.path}")
            return restored
        }

        val q = _queue.value
        if (q.songs.isNotEmpty()) {
            val idx = q.currentIndex.coerceIn(0, q.songs.lastIndex)
            val song = q.songs[idx]
            _currentSong.value = song
            if (song.duration > 0) _duration.value = song.duration
            AppLogger.d(TAG, "playPause seed: using queue index=$idx ${song.path}")
            return song
        }

        val repoLoadStartMs = SystemClock.elapsedRealtime()
        val repoSongs = runCatching {
            com.rawsmusic.module.data.repository.MusicRepository.getAllSongs()
        }.getOrDefault(emptyList())
        PowerTraceLogger.playerStartup(
            stage = "play_pause_seed_repo_load",
            detail = "songs=${repoSongs.size}",
            elapsedMs = SystemClock.elapsedRealtime() - repoLoadStartMs
        )
        if (repoSongs.isNotEmpty()) {
            val song = repoSongs.first()
            _queue.value = PlayQueue(songs = repoSongs, currentIndex = 0)
            _currentSong.value = song
            if (song.duration > 0) _duration.value = song.duration
            AppLogger.d(TAG, "playPause seed: using first repository song size=${repoSongs.size} path=${song.path}")
            return song
        }

        AppLogger.w(TAG, "playPause seed missing: currentSong=null queue=empty repo=empty")
        return null
    }

    fun playPause() {
        if (isReleased) return
        val state = ffmpegPlayer.state
        Log.w(TAG, "=== playPause called, state=$state ===")
        when (state) {
            FfmpegAudioPlayer.State.PLAYING -> {
                Log.w(TAG, "=== playPause: PAUSING ===")
                pause()
            }
            FfmpegAudioPlayer.State.PAUSED -> {
                Log.w(TAG, "=== playPause: RESUMING ===")
                resume()
            }
            FfmpegAudioPlayer.State.PREPARING -> {
                Log.w(TAG, "=== playPause: already PREPARING, ignoring duplicate tap ===")
                smTransition(PlayState.PREPARING, "play_pause_preparing")
            }
            else -> {
                Log.w(TAG, "=== playPause: state=$state, falling back to play() ===")
                val seedSong = resolvePlayPauseSeedSong()
                if (seedSong != null) {
                    val now = SystemClock.elapsedRealtime()
                    userPlayStartFocusGuardUntilMs = now + 2_800L
                    audioFocusStartupGraceUntilMs = maxOf(audioFocusStartupGraceUntilMs, now + 2_800L)
                    smTransition(PlayState.PREPARING, "play_pause_start")
                    play(seedSong)
                } else {
                    Log.w(TAG, "=== playPause: no song available for cold-start capsule tap ===")
                    smTransition(PlayState.IDLE, "play_pause_no_seed")
                }
            }
        }
    }

    fun pause() {
        if (isReleased) return
        Log.w(TAG, "=== pause() called, state=${ffmpegPlayer.state} usbExclusive=${_usbExclusiveActive.value} ===")

        if (_usbExclusiveActive.value && _playState.value == PlayState.PLAYING) {
            eventQueue.submit(PE.PauseEvent {
                transportTransitioning = true
                AppLogger.w(TAG, "USB_WARM_PAUSE_START")
                try {
                    if (isUsbHardwareVolumeRouteActive()) {
                        val userDb = usbHwStepToDb(currentUsbHwStep())
                        val safeDb = usbWarmPauseSafeDb()
                        rampUsbHardwareVolumeDb(userDb, safeDb, "manual_pause_warm_down", stepDelayMs = 7L, cacheFinal = false)
                    } else {
                        fadeUsbExclusiveSessionTo(
                            target = 0.0f,
                            fadeMs = TransitionPreferences.transportDurationOrZero(),
                            reason = "manual_pause_warm_down"
                        )
                        applyUsbNoDataSafetyVolume("before_usb_pause")
                    }
                    // Do not enter native standby here.  Releasing alt/interface on
                    // ordinary pause caused short current noise and resume volume
                    // jumps.  Keep the USB engine warm and only pause the decoder.
                    ffmpegPlayer.pauseDecoderOnly("manual_pause_warm")
                    if (AppPreferences.Player.usbReleaseBandwidthAfterPlayback) {
                        runCatching { sharedUsbAudioEngine.enterStandby("manual_pause_release_bandwidth") }
                            .onFailure { AppLogger.w(TAG, "USB standby after pause failed", it) }
                        AppLogger.i(TAG, "USB warm pause: decoder paused, USB bandwidth released to Alt 0")
                    } else {
                        AppLogger.i(TAG, "USB warm pause: decoder paused, USB kept streaming")
                    }
                    smTransition(PlayState.PAUSED, "pause_warm")
                    PlayerService.syncUsbMediaIdentityFromController(
                        song = _currentSong.value,
                        playing = false,
                        position = _position.value.coerceAtLeast(0L),
                        reason = "manual_pause_warm"
                    )
                    sendUsbMediaIdentityIntent(
                        reason = "manual_pause_warm",
                        song = _currentSong.value,
                        forcePosition = _position.value,
                        playing = false
                    )
                    stopProgressUpdate()
                    savePosition()
                    saveState()
                } finally {
                    transportTransitioning = false
                }
            })
            return
        }

        // UI should react immediately to a user pause.  The output backend may still
        // run a short gain fade before it actually pauses its AudioTrack/AAudio/OpenSL
        // stream, but PlayState/MediaSession/notification must not wait for that fade.
        smTransition(PlayState.PAUSED, "pause_immediate_ui")
        stopProgressUpdate()
        PlayerService.syncUsbMediaIdentityFromController(
            song = _currentSong.value,
            playing = false,
            position = _position.value.coerceAtLeast(0L),
            reason = "manual_pause_immediate_ui"
        )
        eventQueue.submit(PE.PauseEvent {
            ffmpegPlayer.pauseWithFadeBlocking(
                durationMs = TransitionPreferences.transportDurationOrZero(),
                reason = "manual_pause"
            )
            savePosition()
            saveState()
        })
    }

    private fun releaseIdleUsbExclusiveInBackground(reason: String) {
        if (!_usbExclusiveActive.value) return
        if (_playState.value == PlayState.PLAYING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            _playState.value == PlayState.PREPARING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
            return
        }
        scope.launch(Dispatchers.Default) {
            delay(1200)
            if (!appInBackground || isReleased || !_usbExclusiveActive.value) return@launch
            if (_playState.value == PlayState.PLAYING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                _playState.value == PlayState.PREPARING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
                return@launch
            }
            AppLogger.i(TAG, "USB exclusive idle in background; releasing DAC resources: reason=$reason")
            runCatching { ffmpegPlayer.stop() }
            runCatching { sharedUsbAudioEngine.hardStopUsb("${reason}_idle_background") }
            runCatching { unregisterSystemVolumeObserver() }
            runCatching { clearUsbExclusiveState(releaseManager = true, notifyNativeDetached = true) }
        }
    }

    /**
     * App 进入后台时调用。
     * - 正在播放：保持前台服务 + WakeLock，不 standby
     * - 已暂停：进入 standby 释放 USB 资源
     */
    fun onAppWentBackground() {
        AppLogger.i(TAG, "onAppWentBackground usbExclusive=${_usbExclusiveActive.value} playState=${_playState.value}")
        appInBackground = true
        appBackgroundEnteredAtElapsedMs = SystemClock.elapsedRealtime()

        if (!_usbExclusiveActive.value) {
            sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(false, "app_background_not_usb_exclusive")
            return
        }

        if (_playState.value != PlayState.PLAYING && _playState.value != PlayState.PREPARING) {
            sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(false, "app_background_idle_or_paused")
            // App switch / recents-swipe while idle or paused should not keep a
            // USB exclusive controller, receiver, permission flow, or volume
            // observer alive. MIUI can otherwise relaunch into a stale USB
            // route and block until the dongle is unplugged.
            releaseIdleUsbExclusiveInBackground("app_background_idle")
        }

        if (_playState.value == PlayState.PLAYING) {
            reinforceUsbBackgroundPlayback("app_background_playing")
            ensureUsbForegroundImmediate("app_background_playing")
            AppLogger.i(TAG, "USB playing in background: keeping media identity + foreground + wakelock")
            return
        }

        if (_playState.value == PlayState.PAUSED) {
            AppLogger.i(TAG, "USB paused in background: scheduled idle USB release")
        }
    }

    fun onAppMaybeLeavingForeground() {
        if (!_usbExclusiveActive.value) return
        if (_playState.value != PlayState.PLAYING && ffmpegPlayer.state != FfmpegAudioPlayer.State.PLAYING) return
        reinforceUsbBackgroundPlayback("activity_paused_usb_playing")
        ensureUsbForegroundImmediate("activity_paused_usb_playing")
        AppLogger.i(TAG, "USB playing while activity paused: foreground protection asserted early")
    }

    fun shouldSustainUsbBackgroundPlayback(): Boolean {
        if (isReleased || !_usbExclusiveActive.value || !ffmpegPlayer.usbExclusiveMode || !appInBackground) {
            return false
        }
        return _playState.value == PlayState.PLAYING ||
            _playState.value == PlayState.PREPARING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
    }

    fun reinforceUsbBackgroundPlayback(reason: String) {
        if (!shouldSustainUsbBackgroundPlayback()) return
        sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(true, "reinforceUsbBackgroundPlayback:$reason")
        syncUsbSystemAudioKeepAlive(reason)
        ensureUsbMediaIdentity(reason, _currentSong.value, _position.value)
        acquireUsbPlaybackWakeLock(reason)

        val now = SystemClock.elapsedRealtime()
        if (now - lastUsbBackgroundReinforceElapsedMs >= 10_000L) {
            lastUsbBackgroundReinforceElapsedMs = now
            AppLogger.i(
                TAG,
                "reinforceUsbBackgroundPlayback: reason=$reason playState=${_playState.value} " +
                    "ffState=${ffmpegPlayer.state} pos=${_position.value}"
            )
        }
    }

    fun resume() {
        if (isReleased) return
        appInBackground = false
        Log.w(TAG, "=== resume() called, state=${ffmpegPlayer.state} usbExclusive=${_usbExclusiveActive.value} nativeState=${sharedUsbAudioEngine.getNativeStreamState()} ===")

        eventQueue.submit(PE.ResumeEvent {
            resumeInternal()
        })
    }

    private suspend fun resumeInternal() {
        // USB 独占模式恢复
        if (_usbExclusiveActive.value) {
            val nativeState = sharedUsbAudioEngine.getNativeStreamState()

            // session broken：需要重 init
            if (sharedUsbAudioEngine.isNativeSessionBroken()) {
                AppLogger.w(TAG, "resume: USB session broken, recovering")
                val song = _currentSong.value
                if (song != null) {
                    scope.launch {
                        recoverUsbExclusiveAsync()
                    }
                }
                return
            }

            // Legacy standby may still be seen after older builds or explicit resource
            // release paths.  Do not pre-write -35dB here; nativeStart owns startup
            // safety and the warm pause path no longer uses standby for user pause.
            if (nativeState == UsbAudioEngine.NativeStreamState.STANDBY) {
                AppLogger.w(TAG, "resume: legacy standby detected, recovering with full USB reopen")
                scope.launch { recoverUsbExclusiveAsync() }
                return
            }

            // Warm-pause path: USB is still streaming.  Only unpause the decoder and
            // ramp the Feature Unit back to the user's level.  No resetSession and no
            // nativeStart, so there is no safe-volume bounce.
            if (nativeState == UsbAudioEngine.NativeStreamState.STREAMING) {
                val userDb = usbHwStepToDb(currentUsbHwStep())
                val safeDb = usbWarmPauseSafeDb()
                val resumed = ffmpegPlayer.resume()
                if (resumed && isUsbHardwareVolumeRouteActive()) {
                    scope.launch {
                        delay(20)
                        rampUsbHardwareVolumeDb(safeDb, userDb, "user_resume_warm_up", stepDelayMs = 8L, cacheFinal = true)
                    }
                }
                if (!resumed) {
                    AppLogger.w(TAG, "resume: warm decoder resume failed, recovering")
                    scope.launch { recoverUsbExclusiveAsync() }
                    return
                }
                smTransition(PlayState.PLAYING, "resume_warm")
                startProgressUpdate()
                return
            }

            if (nativeState == UsbAudioEngine.NativeStreamState.PREPARED) {
                // Prepared means USB is not currently streaming; let FfmpegAudioPlayer
                // start native once.  Avoid Kotlin-side safe write to prevent volume
                // double-bounce; nativeStart provides transient startup safety.
                sharedUsbAudioEngine.resetSessionForPlayback("user_resume_prepared")
                ffmpegPlayer.resume()
                smTransition(PlayState.PLAYING, "resume_prepared")
                startProgressUpdate()
                return
            }

            AppLogger.w(TAG, "resume: unexpected nativeState=$nativeState, recovering")
            scope.launch { recoverUsbExclusiveAsync() }
            return
        }

        // 渲染切换延迟保护 — USB切换到AudioTrack时等待资源完全释放
        if (_isRenderSwitching.value) {
            Log.i(TAG, "Render switching in progress, delaying resume")
            scope.launch {
                // 等待渲染切换完成（最多500ms）
                var waitCount = 0
                while (_isRenderSwitching.value && waitCount < 50) {
                    delay(10)
                    waitCount++
                }
                _isRenderSwitching.value = false
                // 切换完成后执行播放
                withContext(Dispatchers.Main) {
                    if (!isReleased) {
                        resumeSystemAudio()
                    }
                }
            }
            return
        }

        resumeSystemAudio()
    }

    /**
     * System audio resume
     * resume 前主动检测 AudioTrack 有效性，无论什么触发 resume 都保证 track 可用
     */
    private fun resumeSystemAudio() {
        // 1. SOS: 先检查并修复 AudioTrack，确保恢复播放时 track 有效
        // 无论 resume 来源（UI、通知栏、蓝牙、音频焦点），都经过此检查
        val wasRebuilt = ffmpegPlayer.ensureTrackValidAfterBackground()
        if (wasRebuilt) {
            Log.i(TAG, "resume(SOS): AudioTrack rebuilt before resume")
            return  // ensureTrackValidAfterBackground 已触发重建并 resume
        }

        // 2. 执行正常 resume
        if (!ffmpegPlayer.resume()) {
            Log.w(TAG, "=== resume: ffmpegPlayer.resume() failed, falling back to play() ===")
            _currentSong.value?.let { play(it) }
        }
    }

    fun stop() {
        if (isReleased) return
        eventQueue.submit(PE.StopEvent {
            AppLogger.i(TAG, "stop() called usbExclusive=${_usbExclusiveActive.value}")
            savePosition() // 停止前保存当前播放位置
            unregisterNoisyReceiver()
            abandonAudioFocus()
            ffmpegPlayer.stop()
            // 如果 USB 独占激活，也需要 hard stop
            if (_usbExclusiveActive.value) {
                sharedUsbAudioEngine.hardStopUsb("player_stop")
            }
            smTransition(PlayState.STOPPED, "stop")
            stopProgressUpdate()
            consecutiveFailures = 0
            saveState()
        })
    }

    /**
     * 应用从后台恢复时调用
     * 仅处理 WakeLock 持有，AudioTrack 检查已移入 resume() 流程
     */
    fun onAppForegroundResumed() {
        if (isReleased) return
        Log.i(TAG, "App resumed from background, ensuring WakeLock")
        appInBackground = false
        sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(false, "app_foreground_resumed")
        tryActivateDeferredUsbExclusiveOnForeground("app_foreground_resumed")

        // 1. 确保 WakeLock 持有
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_ENSURE_WAKELOCK
            }
            context.startService(intent)
        } catch (_: Exception) {}

        // 2. USB 独占模式：确保 USB WakeLock 持有
        if (_usbExclusiveActive.value) {
            try {
                val intent = android.content.Intent(context, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_USB_PLAYBACK_FOREGROUND
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
            recoverDeferredUsbOnForegroundIfNeeded()
        } else if (AppPreferences.Player.lastUsbExclusiveActive) {
            val song = _currentSong.value
            if (song != null) {
                AppLogger.i(TAG, "App foreground rearm: last USB exclusive active, current inactive; attempting rearm")
                tryAutoRearmUsbExclusiveForPlayback(song)
                if (_usbExclusiveActive.value) {
                    ensureUsbMediaIdentity("foreground_rearm_after_cold_resume", song, _position.value)
                    applyVolumeRoute("foreground_rearm_after_cold_resume")
                }
            } else {
                AppLogger.i(TAG, "App foreground rearm deferred: last USB exclusive active but no current song yet")
            }
        }
    }

    private fun recoverDeferredUsbOnForegroundIfNeeded() {
        if (!pendingUsbPolicyRestart) return
        val nativeState = sharedUsbAudioEngine.getNativeStreamState()
        val playing = _playState.value == PlayState.PLAYING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING
        if (playing && nativeState == UsbAudioEngine.NativeStreamState.STREAMING) {
            AppLogger.i(TAG, "Deferred USB recovery cleared on foreground: stream is healthy")
            pendingUsbPolicyRestart = false
            pendingUsbRecoveryPlan = null
            return
        }
        AppLogger.w(
            TAG,
            "Running deferred USB recovery on foreground: nativeState=$nativeState playing=$playing"
        )
        scope.launch { recoverUsbExclusiveAsync() }
    }

    private var usbSeekJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var usbSeeking = false

    fun isUsbSeekingNow(): Boolean = usbSeeking

    private fun isSameSongIdentity(a: AudioFile?, b: AudioFile?): Boolean {
        if (a == null || b == null) return false
        return a.path == b.path &&
            a.cueOffsetMs == b.cueOffsetMs &&
            a.cueTrackIndex == b.cueTrackIndex
    }

    private fun isUsbSeekRuntimeReady(): Boolean {
        if (!_usbExclusiveActive.value) return false
        if (_playState.value == PlayState.PREPARING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING ||
            transportTransitioning ||
            usbSeeking
        ) {
            return false
        }
        val handle = sharedUsbAudioEngine.currentHandle
        return handle != 0L && sharedUsbAudioEngine.isInitialized()
    }

    private fun deferUsbSeekUntilReady(
        song: AudioFile,
        realSeekMs: Long,
        displaySeekMs: Long,
        keepPaused: Boolean,
        reason: String
    ) {
        usbSeekJob?.cancel()
        usbSeekJob = scope.launch {
            repeat(12) { attempt ->
                if (isReleased) return@launch
                val current = _currentSong.value
                if (!isSameSongIdentity(current, song)) {
                    AppLogger.i(TAG, "deferUsbSeekUntilReady cancelled: song changed reason=$reason")
                    return@launch
                }
                if (isUsbSeekRuntimeReady()) {
                    AppLogger.i(
                        TAG,
                        "deferUsbSeekUntilReady retry success: attempt=${attempt + 1} " +
                            "real=$realSeekMs display=$displaySeekMs reason=$reason"
                    )
                    transportMutex.withLock {
                        seekUsbExclusiveInternal(realSeekMs, displaySeekMs, keepPaused)
                    }
                    return@launch
                }
                delay(if (attempt < 4) 120L else 180L)
            }

            pendingSeekPosition = realSeekMs
            pendingSeekPath = song.path
            AppLogger.w(
                TAG,
                "deferUsbSeekUntilReady timed out: keep pending real=$realSeekMs " +
                    "display=$displaySeekMs reason=$reason"
            )
        }
    }

    /**
     * Called when transition settings (fade durations, crossfade, etc.) are changed
     * in the UI. The player reads TransitionPreferences directly at each use site,
     * so this is primarily a hook for any cache invalidation or immediate reapply.
     */
    fun applyTransitionSettingsChanged() {
        if (isReleased) return
        android.util.Log.d("PlayerController", "applyTransitionSettingsChanged: transition settings updated")
    }

    fun seekTo(positionMs: Long) {
        if (isReleased) return
        val song = _currentSong.value ?: return
        val keepPaused = _playState.value == PlayState.PAUSED ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED

        val realSeekMs = if (song.cueOffsetMs > 0) {
            song.cueOffsetMs + positionMs
        } else {
            positionMs
        }.coerceAtLeast(0L)

        val displaySeekMs = if (song.cueOffsetMs > 0) positionMs else realSeekMs

        _position.value = displaySeekMs
        seekJustPerformed = true

        AppLogger.i(TAG, "seekTo: pos=$positionMs real=$realSeekMs usbExclusive=${_usbExclusiveActive.value} keepPaused=$keepPaused")

        if (_usbExclusiveActive.value) {
            if (!isUsbSeekRuntimeReady()) {
                AppLogger.w(
                    TAG,
                    "seekTo deferred: pos=$positionMs real=$realSeekMs handle=0x${
                        java.lang.Long.toUnsignedString(sharedUsbAudioEngine.currentHandle, 16)
                    } playState=${_playState.value} ffmpegState=${ffmpegPlayer.state}"
                )
                deferUsbSeekUntilReady(
                    song = song,
                    realSeekMs = realSeekMs,
                    displaySeekMs = displaySeekMs,
                    keepPaused = keepPaused,
                    reason = "usb_startup_not_ready"
                )
                return
            }
            usbSeekJob?.cancel()
            usbSeekJob = scope.launch {
                transportMutex.withLock {
                    seekUsbExclusiveInternal(realSeekMs, displaySeekMs, keepPaused)
                }
            }
            return
        }

        ffmpegPlayer.seekTo(realSeekMs, keepPaused = keepPaused)
        if (keepPaused) {
            scope.launch(Dispatchers.Main) {
                delay(80)
                if (!isReleased && _currentSong.value?.path == song.path &&
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING
                ) {
                    ffmpegPlayer.pause()
                    smTransition(PlayState.PAUSED, "seek_paused")
                }
            }
        }
    }

    private suspend fun seekUsbExclusiveInternal(
        realSeekMs: Long,
        displaySeekMs: Long,
        keepPaused: Boolean
    ) {
        if (isReleased) return
        suppressSystemVolumeObserver(1400L, "seekUsbExclusiveInternal real=$realSeekMs")
        usbSeeking = true
        UsbAudioEngine.usbSeekingFlag = true

        val handle = UsbAudioEngine.currentHandle
        AppLogger.i(TAG, "seekUsbExclusiveInternal ENTER real=$realSeekMs display=$displaySeekMs handle=0x${handle.toString(16)} keepPaused=$keepPaused")

        try {
            // 1. nativePrepareForSeek: soft stop + clear ring + set fade-in, 不标 BROKEN
            val usbPrepared = handle != 0L
            if (handle != 0L) {
                val seekFadeMs = TransitionPreferences.seekDurationOrZero()
                sharedUsbAudioEngine.nativePrepareForSeek(handle, seekFadeMs.coerceIn(0, TransitionPreferences.SEEK_DURATION_MAX_MS), "player_seek")
                // Phase 22: nativePrepareForSeek now owns the transient safe FU
                // envelope and restores the captured user volume on fresh post-seek
                // PCM.  Do not call nativeSetHardwareVolumeDb(-35) here: that
                // overwrites the user hardware-volume cache and prolongs current noise.
            }

            // 2. seek decoder。Controller 已完成 nativePrepareForSeek，避免内部重复 flush。
            ffmpegPlayer.seekTo(realSeekMs, usbPrepareAlreadyDone = usbPrepared, keepPaused = keepPaused)

            _position.value = displaySeekMs
            seekJustPerformed = true

            if (!keepPaused) {
                smTransition(PlayState.PLAYING, "seek_resumed")
                startProgressUpdate()
            } else {
                ffmpegPlayer.keepPausedAfterUsbSeek("player_seek_keep_paused")
                smTransition(PlayState.PAUSED, "seek_kept_paused")
            }

            AppLogger.i(TAG, "seekUsbExclusiveInternal DONE")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "seekUsbExclusiveInternal failed", t)
        } finally {
            delay(120)
            usbSeeking = false
            UsbAudioEngine.usbSeekingFlag = false
        }
    }

    /**
     * 手动切歌专用入口：从 0 开始播放，不恢复旧位置。
     * 先 fade-out USB → stop decoder → 清除 pending seek → playInternal
     */
    private fun configuredManualShortFadeMs(): Int {
        return when (TransitionPreferences.manualTrackTransitionMode) {
            TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE -> TransitionPreferences.manualTrackFadeMs
            else -> 0
        }.coerceAtLeast(0)
    }

    private suspend fun fadeOutCurrentTrackForManualShortFade(fadeMs: Int, reason: String) {
        if (fadeMs <= 0) return
        if (_playState.value != PlayState.PLAYING) return
        withContext(Dispatchers.IO) {
            ffmpegPlayer.fadeOutForTransitionBlocking(fadeMs, reason)
        }
    }

    private suspend fun playManualSwitchFromStartLocked(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        reason: String
    ) {
        val oldPos = _position.value
        AppLogger.w(
            TAG,
            "MANUAL_SWITCH_START oldPos=$oldPos newSong=${song.title} start=0 reason=$reason"
        )

        var playInternalAfterSwitch = true
        transportTransitioning = true
        try {
            cancelPendingRestoreSeek(reason)
            // 清除 pending seek，确保 playInternal 不会恢复旧位置
            pendingSeekPosition = -1L
            pendingSeekPath = null

            val manualShortFadeMs = configuredManualShortFadeMs()
            if (_usbExclusiveActive.value && _playState.value == PlayState.PLAYING) {
                armUsbTrackSwitchVolumeHold(reason)
                val usbFadeMs = usbExclusiveManualTrackFadeMs()
                pendingManualTrackStartFadeMs = usbFadeMs
                fadeUsbExclusiveSessionTo(
                    target = 0.0f,
                    fadeMs = usbFadeMs,
                    reason = reason
                )
                val softNext = canUseUsbSoftNextFor(song)
                ffmpegPlayer.stopForManualTrackSwitch(reason)
                if (softNext) {
                    ffmpegPlayer.usbReuseEngineForNextStart = true
                    // 显式 flush native ring，确保旧音频完全清零 + 设淡入
                    sharedUsbAudioEngine.flushForNextTrack(reason)
                } else {
                    ffmpegPlayer.usbReuseEngineForNextStart = false
                    applyUsbNoDataSafetyVolume("manual_switch_full_reinit:$reason")
                    AppLogger.w(TAG, "manual switch full-reinit path: softNext=false reason=$reason")
                }
            } else if (manualShortFadeMs > 0) {
                // Normal output should not wait for the old track to fade all the way out
                // before the next track begins.  First try the existing prepared-next
                // decoder lane so the next song is played inside the fade window.  If the
                // next file is not mix-compatible, fall back to an immediate cut with the
                // new track fading in, instead of blocking the UI/audio handoff for the full
                // fade-out duration.
                val inlineStarted = ffmpegPlayer.requestManualCrossfadeTo(
                    nextPath = song.path,
                    durationMs = manualShortFadeMs,
                    reason = reason
                )
                if (inlineStarted) {
                    playInternalAfterSwitch = false
                    AppLogger.i(TAG, "manual switch inline crossfade started: title=${song.title} fadeMs=$manualShortFadeMs reason=$reason")
                } else {
                    ffmpegPlayer.armNextStartFadeIn(manualShortFadeMs, reason)
                    AppLogger.i(TAG, "manual switch immediate cut + next fade-in: title=${song.title} fadeMs=$manualShortFadeMs reason=$reason")
                }
            }

            _queue.value = PlayQueue(queue, index)
            _currentSong.value = song
            _position.value = 0L
            _duration.value = song.duration
            smForceTransition(if (playInternalAfterSwitch) PlayState.PREPARING else PlayState.PLAYING, "manual_switch_preparing")
        } finally {
            transportTransitioning = false
        }

        if (playInternalAfterSwitch) {
            playInternal(song, queue, index)
        }
    }

    private fun playManualSwitchFromStart(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        reason: String
    ) {
        scope.launch {
            playManualSwitchFromStartLocked(song, queue, index, reason)
        }
    }

    fun next() {
        if (isReleased) return

        if (priorityQueue.isNotEmpty()) {
            val nextSong = priorityQueue.removeFirst()
            val currentQueue = _queue.value.songs.toMutableList()
            val insertIndex = (_queue.value.currentIndex + 1).coerceAtMost(currentQueue.size)
            currentQueue.add(insertIndex, nextSong)
            val newIndex = insertIndex
            _queue.value = _queue.value.copy(songs = currentQueue, currentIndex = newIndex)
            savePosition()
            play(nextSong, currentQueue, newIndex)
            return
        }

        val q = _queue.value
        if (q.songs.isEmpty()) return

        val nextIndex = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> (q.currentIndex + 1) % q.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> {
                getNextShuffledIndex()
            }
            PlayMode.REPEAT_ONE -> {
                q.currentIndex
            }
        }

        if (nextIndex < 0 || nextIndex !in q.songs.indices) return
        savePosition()
        val nextSong = q.songs[nextIndex]
        _queue.value = q.copy(currentIndex = nextIndex)

        playManualSwitchFromStart(nextSong, q.songs, nextIndex, "manual_next")
    }

    fun previous() {
        if (isReleased) return
        val q = _queue.value
        if (q.songs.isEmpty()) return

        if (ffmpegPlayer.positionMs > 3000) {
            seekTo(0)
            return
        }

        if (playHistory.isNotEmpty()) {
            val prev = playHistory.removeLast()
            val prevIdx = q.songs.indexOfFirst { it.id == prev.id }.takeIf { it >= 0 } ?: 0
            _queue.value = q.copy(currentIndex = prevIdx)
            _currentSong.value = null
            playManualSwitchFromStart(prev, q.songs, prevIdx, "manual_previous_history")
            return
        }

        val prevIndex = when (_playMode.value) {
            PlayMode.SEQUENTIAL -> {
                if (q.currentIndex > 0) q.currentIndex - 1 else q.songs.size - 1
            }
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_ONCE -> {
                getPreviousShuffledIndex()
            }
            PlayMode.REPEAT_ONE -> {
                q.currentIndex
            }
        }

        if (prevIndex < 0 || prevIndex !in q.songs.indices) return
        savePosition()
        val prevSong = q.songs[prevIndex]
        _queue.value = q.copy(currentIndex = prevIndex)
        _currentSong.value = null

        playManualSwitchFromStart(prevSong, q.songs, prevIndex, "manual_previous")
    }

    fun toggleRepeatMode() {
        val modes = RepeatMode.entries
        val currentIndex = modes.indexOf(_repeatMode.value)
        val nextMode = modes[(currentIndex + 1) % modes.size]
        _repeatMode.value = nextMode
        AppPreferences.Player.repeatMode = nextMode
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        AppPreferences.Player.repeatMode = mode
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle
        AppPreferences.Player.isShuffle = newShuffle
        _playMode.value = PlayMode.from(
            ShuffleMode.fromBoolean(newShuffle),
            _repeatMode.value
        )
        AppPreferences.Player.playMode = _playMode.value
        if (newShuffle) {
            enableShuffle()
        } else {
            disableShuffle()
        }
    }

    fun cyclePlayMode() {
        val newMode = PlayMode.cycle(_playMode.value)
        setPlayMode(newMode)
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        AppPreferences.Player.playMode = mode
        applyPlayMode(mode)
    }

    private fun applyPlayMode(mode: PlayMode) {
        val wasShuffle = _isShuffle.value
        _isShuffle.value = mode.shuffleMode.isOn
        _repeatMode.value = mode.repeatMode
        AppPreferences.Player.isShuffle = _isShuffle.value
        AppPreferences.Player.repeatMode = _repeatMode.value

        if (_isShuffle.value && !wasShuffle && _queue.value.songs.size > 1) {
            enableShuffle()
        } else if (!_isShuffle.value && wasShuffle) {
            disableShuffle()
        }
    }

    fun setVolume(volume: Float) {
        if (isReleased) return
        setUserVolume(volume.coerceIn(0f, 1f))
    }

    /** 统一音量入口：后台音量键和软件内滑条都走这里 */
    private fun setUserVolumeUnified(volume: Float, reason: String) {
        val uiVolume = volume.coerceIn(0f, 1f)
        AppPreferences.Player.volume = uiVolume
        AppPreferences.Player.usbHardwareVolume = uiVolume
        AppPreferences.Player.usbHardwareVolumeStep = uiVolumeToUsbHwStep(uiVolume)
        AppLogger.w(TAG, "SET_USER_VOLUME_UNIFIED volume=$uiVolume step=${AppPreferences.Player.usbHardwareVolumeStep} reason=$reason")
        applyComposedVolume()
    }

    fun addToQueue(song: AudioFile) {
        if (priorityQueue.any { it.path == song.path }) return
        priorityQueue.addLast(song)
        saveState()
    }

    fun getPriorityQueue(): List<AudioFile> = priorityQueue.toList()

    fun clearPriorityQueue() {
        priorityQueue.clear()
        saveState()
    }

    fun playNext(song: AudioFile) {
        val currentQueue = _queue.value.songs.toMutableList()
        currentQueue.removeAll { it.path == song.path }
        val insertIndex = (_queue.value.currentIndex + 1).coerceAtMost(currentQueue.size)
        currentQueue.add(insertIndex, song)
        _queue.value = _queue.value.copy(songs = currentQueue)
        saveState()
    }

    fun removeFromQueue(index: Int) {
        val currentQueue = _queue.value.songs.toMutableList()
        if (index !in currentQueue.indices) return
        currentQueue.removeAt(index)
        var currentIndex = _queue.value.currentIndex
        when {
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = (currentIndex - 1).coerceAtLeast(0)
        }
        if (currentQueue.isEmpty()) currentIndex = -1
        _queue.value = _queue.value.copy(songs = currentQueue, currentIndex = currentIndex)
    }

    private fun handlePlayerStateChanged(state: FfmpegAudioPlayer.State) {
        if (isReleased) return
        when (state) {
            FfmpegAudioPlayer.State.PLAYING -> {
                consecutiveFailures = 0
                smForceTransition(PlayState.PLAYING, "recover_success")
                startProgressUpdate()
                val sessionId = ffmpegPlayer.audioSessionId
                Log.d("VirtualizerDebug", "Player PLAYING, audioSessionId=$sessionId")
                if (sessionId != 0) {
                    onAudioSessionChanged?.invoke(sessionId)
                    initEqualizerController(sessionId)
                }
            }
            FfmpegAudioPlayer.State.PAUSED -> {
                smForceTransition(PlayState.PAUSED, "recover_paused")
                stopProgressUpdate()
            }
            FfmpegAudioPlayer.State.PREPARING -> {
                smForceTransition(PlayState.PREPARING, "recover_preparing")
            }
            FfmpegAudioPlayer.State.STOPPED -> {
                smForceTransition(PlayState.STOPPED, "recover_stopped")
                stopProgressUpdate()
            }
            FfmpegAudioPlayer.State.ERROR -> {
                smForceTransition(PlayState.ERROR, "recover_error")
                stopProgressUpdate()
                val errorMsg = lastPlayerError
                consecutiveFailures++
                Log.w(
                    TAG,
                    "Playback entered ERROR, consecutiveFailures=$consecutiveFailures, usb=${_usbExclusiveActive.value}, msg=$errorMsg"
                )
                if (!shouldAutoAdvanceOnError(errorMsg)) {
                    Log.w(TAG, "Not auto-advancing after playback error")
                    return
                }
                if (consecutiveFailures > 5) {
                    Log.w(TAG, "Too many consecutive failures, stopping playback")
                    stop()
                    consecutiveFailures = 0
                    return
                }
                val q = _queue.value
                if (q.songs.size > 1) {
                    val nextIndex = (q.currentIndex + 1) % q.songs.size
                    play(q.songs[nextIndex], q.songs, nextIndex)
                }
            }
            FfmpegAudioPlayer.State.COMPLETED -> {
                smForceTransition(PlayState.STOPPED, "recover_final_stop")
                stopProgressUpdate()
                handlePlaybackComplete()
            }
            FfmpegAudioPlayer.State.IDLE -> {
                smForceTransition(PlayState.IDLE, "recover_idle")
            }
        }
    }

    private fun shouldAutoAdvanceOnError(message: String?): Boolean {
        // 最保守策略：USB 独占模式下，错误不自动下一首
        if (_usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode) {
            return false
        }
        val msg = message?.lowercase().orEmpty()
        // 输出设备类错误也不要自动切歌
        if (msg.contains("usb") ||
            msg.contains("claim") ||
            msg.contains("resource busy") ||
            msg.contains("audiotrack") ||
            msg.contains("device") ||
            msg.contains("voice_communication") ||
            msg.contains("sco") ||
            msg.contains("bluetooth")
        ) {
            return false
        }
        return true
    }

    private fun handlePlaybackComplete() {
        if (isReleased) return
        if (stopAfterCurrentSong) {
            stopAfterCurrentSong = false
            AppPreferences.Player.stopAfterCurrent = false
            AppPreferences.Player.sleepTimerMode = 0
            pause()
            return
        }
        if (songsUntilStop > 0) {
            songsUntilStop--
            if (songsUntilStop <= 0) {
                AppPreferences.Player.sleepTimerMode = 0
                pause()
                return
            }
        }
        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                _currentSong.value?.let { play(it) }
            }
            RepeatMode.ALL -> {
                next()
            }
            RepeatMode.OFF -> {
                val q = _queue.value
                if (q.currentIndex < q.songs.size - 1) {
                    next()
                }
            }
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerEndTime = SystemClock.elapsedRealtime() + minutes * 60_000L
        _sleepTimerRemaining.value = minutes * 60_000L
        AppPreferences.Player.sleepTimerMode = 1
        AppPreferences.Player.sleepTimerMinutes = minutes
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = sleepTimerEndTime - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    _sleepTimerRemaining.value = 0
                    pause()
                    cancelSleepTimer()
                    break
                }
                _sleepTimerRemaining.value = remaining
                delay(1000)
            }
        }
    }

    fun startSleepTimerSongs(count: Int) {
        cancelSleepTimer()
        songsUntilStop = count
        AppPreferences.Player.sleepTimerMode = 2
        stopAfterCurrentSong = false
    }

    fun enableStopAfterCurrent() {
        cancelSleepTimer()
        stopAfterCurrentSong = true
        AppPreferences.Player.sleepTimerMode = 3
        AppPreferences.Player.stopAfterCurrent = true
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndTime = 0L
        _sleepTimerRemaining.value = 0L
        stopAfterCurrentSong = false
        songsUntilStop = 0
        AppPreferences.Player.sleepTimerMode = 0
        AppPreferences.Player.stopAfterCurrent = false
    }

    fun isSleepTimerActive(): Boolean {
        return sleepTimerJob?.isActive == true || stopAfterCurrentSong || songsUntilStop > 0
    }

    fun getSleepTimerMode(): Int = AppPreferences.Player.sleepTimerMode

    /**
     * 开启 Shuffle
     * 1. 保存当前原始队列顺序
     * 2. 当前歌曲移到队首
     * 3. 其余歌曲用 BitSet+Random 无放回采样随机排列
     * 4. 初始化 shuffle 索引历史
     */
    private fun enableShuffle() {
        val q = _queue.value
        if (q.songs.size <= 1) return

        // 保存原始顺序（用于关闭 shuffle 时恢复）
        queueBeforeShuffle = q.songs.toList()
        AppPreferences.Player.originalQueueSongsJson = serializeSongList(queueBeforeShuffle)

        val currentSong = q.currentSong ?: return
        val currentId = currentSong.id
        val remaining = q.songs.filter { it.id != currentId }

        // BitSet + Random 无放回采样
        val shuffledRemaining = bitSetShuffle(remaining)

        val newSongs = mutableListOf(currentSong)
        newSongs.addAll(shuffledRemaining)

        _queue.value = PlayQueue(
            songs = newSongs,
            currentIndex = 0,
            repeatMode = _repeatMode.value,
            isShuffle = true,
            originalSongs = queueBeforeShuffle
        )

        // 初始化 shuffle 索引：当前歌曲已在首位，记录到历史
        shuffleIndices.clear()
        shuffleHistory.clear()
        shuffleHistory.addLast(0)

        saveState()
    }

    /**
     * 关闭 Shuffle — 恢复原始队列顺序
     */
    private fun disableShuffle() {
        val q = _queue.value
        val currentSong = q.currentSong

        // 优先从内存恢复，其次从持久化恢复
        val original = queueBeforeShuffle.ifEmpty {
            deserializeSongList(AppPreferences.Player.originalQueueSongsJson)
        }

        if (original.isNotEmpty() && currentSong != null) {
            // 在原始顺序中找到当前歌曲的位置
            val newIndex = original.indexOfFirst { it.id == currentId(currentSong) }
                .coerceAtLeast(0)

            _queue.value = PlayQueue(
                songs = original,
                currentIndex = newIndex,
                repeatMode = _repeatMode.value,
                isShuffle = false,
                originalSongs = emptyList()
            )
        }

        // 清理 shuffle 状态
        queueBeforeShuffle = emptyList()
        shuffleIndices.clear()
        shuffleHistory.clear()
        AppPreferences.Player.originalQueueSongsJson = ""

        saveState()
    }

    /**
     * BitSet+Random 无放回采样算法
     * 对应 ShuffledPlaybackQueueIndexGenerator.nextIndex()
     */
    private fun <T> bitSetShuffle(items: List<T>): List<T> {
        if (items.size <= 1) return items.toList()
        val result = mutableListOf<T>()
        val used = java.util.BitSet(items.size)
        var remaining = items.size
        while (remaining > 0) {
            var idx = shuffleRandom.nextInt(items.size)
            while (used.get(idx)) {
                idx = shuffleRandom.nextInt(items.size)
            }
            used.set(idx)
            result.add(items[idx])
            remaining--
        }
        return result
    }

    /**
     * 从随机索引中取下一首
     * 对应 ShuffledPlaybackQueueIndexGenerator.nextIndex()
     */
    private fun getNextShuffledIndex(): Int {
        val size = _queue.value.songs.size
        if (size <= 1) return if (size == 1) 0 else -1

        // 本循环所有歌曲都已播放，重新生成随机顺序
        if (shuffleIndices.isEmpty()) {
            val currentIdx = _queue.value.currentIndex
            val unplayed = (0 until size).filter { it != currentIdx }.toMutableList()

            // BitSet+Random 无放回采样
            val used = java.util.BitSet(unplayed.size)
            val shuffled = mutableListOf<Int>()
            var remaining = unplayed.size
            while (remaining > 0) {
                var r = shuffleRandom.nextInt(unplayed.size)
                while (used.get(r)) {
                    r = shuffleRandom.nextInt(unplayed.size)
                }
                used.set(r)
                shuffled.add(unplayed[r])
                remaining--
            }
            shuffleIndices.addAll(shuffled)
        }

        val nextIdx = shuffleIndices.removeFirst()
        shuffleHistory.addLast(nextIdx)

        // 限制历史长度，避免内存膨胀
        if (shuffleHistory.size > size * 2) {
            repeat(size / 2) { shuffleHistory.removeFirst() }
        }
        return nextIdx
    }

    /**
     * 从随机历史中取上一首索引
     */
    private fun getPreviousShuffledIndex(): Int {
        val size = _queue.value.songs.size
        if (size <= 1) return if (size == 1) 0 else -1

        val currentIdx = _queue.value.currentIndex
        // 当前歌曲放回待播队列头部（下次优先播放）
        if (currentIdx >= 0) shuffleIndices.add(0, currentIdx)

        if (shuffleHistory.size >= 2) {
            shuffleHistory.removeLast()
            return shuffleHistory.last()
        }
        // 历史为空，随机选一个
        return (0 until size).filter { it != currentIdx }.random()
    }

    private fun currentId(song: AudioFile): Long = song.id

    /** 根据歌曲 ReplayGain 标签和用户设置，计算并应用增益因子 */
    private fun applyReplayGain(song: AudioFile) {
        val prefs = AppPreferences.Player
        val normalizationOn = prefs.volumeNormalizationEnabled || prefs.replayGainEnabled
        if (normalizationOn) {
            val gainDB = when {
                prefs.replayGainEnabled -> when (prefs.replayGainMode) {
                    1 -> song.trackGain
                    2 -> song.albumGain
                    else -> 0f
                }
                song.trackGain != 0f -> song.trackGain
                else -> 0f
            }
            val peak = when {
                prefs.replayGainEnabled -> when (prefs.replayGainMode) {
                    1 -> song.trackPeak
                    2 -> song.albumPeak
                    else -> 1.0f
                }
                else -> song.trackPeak.takeIf { it > 0f } ?: 1.0f
            }
            var linearGain = safeReplayGainLinear(gainDB)
            if (peak > 0f && linearGain * peak > 1.0f) {
                linearGain = 1.0f / peak
            }
            replayGainVolumeModifier = linearGain
            Log.d(TAG, "ReplayGain: mode=${prefs.replayGainMode}, gainDB=$gainDB, peak=$peak, linear=$linearGain")
        } else {
            replayGainVolumeModifier = 1.0f
        }
        applyComposedVolume()
    }

    /** 安全的 dB → linear 转换，防止极端 ReplayGain 值导致静音 */
    private fun safeReplayGainLinear(db: Float): Float {
        if (db == 0f || db.isNaN() || db.isInfinite()) return 1f
        val clampedDb = db.coerceIn(-24f, 12f)
        return Math.pow(10.0, clampedDb / 20.0).toFloat().coerceIn(0.063f, 3.981f)
    }

    /** 合成用户音量和 ReplayGain 系数 */
    private fun currentComposedVolume(): Float {
        val baseVolume = AppPreferences.Player.volume
        return (baseVolume * replayGainVolumeModifier * duckVolumeFactor).coerceIn(0f, 1f)
    }

    /**
     * USB 独占的软件音量不能把 UI 百分比直接当作 PCM 振幅。
     * Android 的 STREAM_MUSIC 音量键是“档位/感知响度”，直接 linear 映射会导致
     * 1/15 档仍有约 -23.5 dB，听起来过大；这里使用三次 taper：
     * 1/15 -> 0.0003，2/15 -> 0.0024，50% -> 0.125，100% -> 1。
     */
    private fun usbSoftwareUiVolumeToPcmGain(uiVolume: Float): Float {
        val v = uiVolume.coerceIn(0f, 1f)
        if (v <= 0.0001f) return 0f
        if (v >= 0.9999f) return 1f
        return v.toDouble().pow(USB_SOFTWARE_VOLUME_TAPER.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun computeUsbVolumePlan(profile: com.rawsmusic.module.player.usb.UsbOutputProfile, reason: String): com.rawsmusic.module.player.usb.UsbVolumePlan {
        val userVolume = AppPreferences.Player.volume.coerceIn(0f, 1f)
        val replayGain = if (profile.bitPerfect) 1f else replayGainVolumeModifier.coerceIn(0f, 4f)
        val duck = if (profile.bitPerfect) 1f else duckVolumeFactor.coerceIn(0f, 1f)

        return when (profile.volumePath) {
            com.rawsmusic.module.player.usb.UsbVolumePath.HardwareUserVolume -> {
                // 用户音量走硬件 dB，不进 PCM。bit-perfect + HW volume still keeps
                // PCM unity; processed mode may keep replay/duck in PCM.
                com.rawsmusic.module.player.usb.UsbVolumePlan(
                    pcmGain = if (profile.bitPerfect) 1f else (replayGain * duck).coerceIn(0f, 1f),
                    hardwareDb = com.rawsmusic.module.player.usb.userVolumeToHardwareDb(userVolume),
                    useHardwareVolume = true,
                    fixedOutput = profile.bitPerfect,
                    reason = reason
                )
            }
            com.rawsmusic.module.player.usb.UsbVolumePath.Fixed -> {
                com.rawsmusic.module.player.usb.UsbVolumePlan(
                    pcmGain = 1f,
                    hardwareDb = 0,
                    useHardwareVolume = false,
                    fixedOutput = true,
                    reason = reason
                )
            }
            else -> {
                com.rawsmusic.module.player.usb.UsbVolumePlan(
                    pcmGain = (usbSoftwareUiVolumeToPcmGain(userVolume) * replayGain * duck).coerceIn(0f, 1f),
                    hardwareDb = 0,
                    useHardwareVolume = false,
                    fixedOutput = false,
                    reason = reason
                )
            }
        }
    }

    private fun applyUsbVolume(profile: com.rawsmusic.module.player.usb.UsbOutputProfile, reason: String) {
        val plan = computeUsbVolumePlan(profile, reason)

        val uiVolume = AppPreferences.Player.volume.coerceIn(0f, 1f)
        val hwDb = UsbHardwareVolumeModel.uiVolumeToHardwareDb(uiVolume)

        AppLogger.w(
            TAG,
            "APPLY_USB_VOLUME volume=$uiVolume hwDb=$hwDb planPcm=${plan.pcmGain} planHwDb=${plan.hardwareDb} useHw=${plan.useHardwareVolume} reason=$reason"
        )

        // 不要在 Kotlin 侧抬高或钳低用户 PCM gain。native 层的
        // startupVolumeGuardUntilMs 会在输出线程临时做最大值 cap，并且不会
        // 覆盖真实用户音量；这里直接下发真实目标，避免最低档被抬高。
        val guardedPcmGain = plan.pcmGain

        if (!isReleased) {
            // 只写一次 PCM gain 和一次硬件 dB
            sharedUsbAudioEngine.setPcmSoftwareGain(guardedPcmGain.coerceIn(0f, 1f))
            if (plan.useHardwareVolume) {
                if (isUsbTrackSwitchVolumeHeld()) {
                    AppLogger.w(TAG, "applyUsbVolume: hardware FU restore deferred during track switch reason=$reason holdReason=$usbTrackSwitchVolumeHoldReason")
                } else {
                    sharedUsbAudioEngine.setHardwareVolumeDb(plan.hardwareDb)
                }
            }
        }
    }

    private fun applyComposedVolume() {
        // 切歌/暂停进行中，禁止音量写入
        if (transportTransitioning) {
            AppLogger.w(TAG, "applyComposedVolume skipped during transport transition")
            return
        }

        // USB 独占模式走新的音量计划
        if (_usbExclusiveActive.value && currentUsbDevice != null) {
            val profile = buildUsbOutputProfile(exclusive = true)
            applyUsbVolume(profile, "composed")
            return
        }

        // 非 USB 独占：传统合成
        val userVolume = AppPreferences.Player.volume.coerceIn(0f, 1f)
        val rgLinear = replayGainVolumeModifier.coerceIn(0f, 4f)
        val duckLinear = duckVolumeFactor.coerceIn(0f, 1f)
        val composed = (userVolume * rgLinear * duckLinear).coerceIn(0f, 1f)

        if (!isReleased) {
            ffmpegPlayer.setVolume(composed)
        }
    }

    /**
     * USB 独占启动成功后调用。
     * 只设置保护窗口 + 应用合成音量，不做 ramp。
     * 保护期内合成音量被钳到 0.25，防止 ReplayGain 未稳定时满音量。
     */
    private fun onUsbExclusiveStreamingStarted() {
        usbStartupVolumeJob?.cancel()
        // 延长到 2 秒：防止后台冷启动时 ReplayGain / duck 状态晚到导致极小音量
        usbStartupVolumeGuardUntilMs = android.os.SystemClock.elapsedRealtime() + 2000L
        applyComposedVolume()
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressJob = scope.launch {
            var saveCounter = 0
            var logCounter = 0
            var lastLoggedPos = -1L
            var stableCount = 0
            while (isActive && !isReleased) {
                try {
                    val playerPos = ffmpegPlayer.positionMs
                    val isCue = cachedCueEndMs > 0

                    val displayPos = if (isCue && cachedCueOffsetMs > 0) {
                        (playerPos - cachedCueOffsetMs).coerceAtLeast(0L)
                    } else {
                        playerPos
                    }

                    if (seekJustPerformed) {
                        val targetSeek = _position.value
                        val diff = kotlin.math.abs(displayPos - targetSeek)
                        if (diff < 500) {
                            seekJustPerformed = false
                        } else {
                            stableCount++
                            if (stableCount > 20) {
                                seekJustPerformed = false
                                stableCount = 0
                            }
                        }
                    } else {
                        _position.value = displayPos
                    }

                    if (isCue) {
                        if (_duration.value != cachedSongDuration) {
                            _duration.value = cachedSongDuration
                        }
                    } else {
                        val ffDur = ffmpegPlayer.durationMs
                        if (ffDur > 0 && _duration.value != ffDur) {
                            _duration.value = ffDur
                        }
                    }

                    maybeRecordPlayCount(displayPos, _duration.value)

                    logCounter++
                    if (logCounter >= 4 || kotlin.math.abs(playerPos - lastLoggedPos) > 2000) {
                        logCounter = 0
                        if (playerPos != lastLoggedPos) {
                            Log.w(TAG, ">>> progressUpdate: ffmpegPos=$playerPos, displayPos=$displayPos, cueEnd=$cachedCueEndMs")
                            lastLoggedPos = playerPos
                        }
                    }
                    if (_usbExclusiveActive.value && _playState.value == PlayState.PLAYING && logCounter == 0) {
                        PlayerService.syncUsbMediaIdentityFromController(
                            song = _currentSong.value,
                            playing = true,
                            position = displayPos.coerceAtLeast(0L),
                            reason = "progress_update"
                        )
                    }

                    saveCounter++
                    if (saveCounter >= 25) {
                        saveCounter = 0
                        savePosition()
                    }
                    if (isCue && playerPos >= cachedCueEndMs) {
                        Log.d(TAG, "CUE track end reached: pos=$playerPos >= cueEndMs=$cachedCueEndMs, advancing to next")
                        next()
                        break
                    }
                } catch (e: Exception) {
                    break
                }
                delay(50)
            }
        }
    }

    private fun maybeRecordPlayCount(positionMs: Long, durationMs: Long) {
        if (!AppPreferences.Player.playCountEnabled) return
        val song = _currentSong.value ?: return
        if (durationMs <= 0L || positionMs <= 0L) return

        val key = buildPlaybackStatsKey(song)
        if (playCountRecordedKey == key) return

        currentTrackMaxPositionMs = maxOf(currentTrackMaxPositionMs, positionMs)
        val threshold = AppPreferences.Player.playCountThresholdPercent.coerceIn(1, 100)
        val percent = (currentTrackMaxPositionMs.toDouble() / durationMs.toDouble()) * 100.0

        if (percent >= threshold) {
            playCountRecordedKey = key
            scope.launch(Dispatchers.IO) {
                try {
                    PlaybackStatsStore.getInstance(context).recordPlay(song)
                    AppLogger.d(TAG, "play count recorded: ${song.title}, percent=${percent.toInt()} threshold=$threshold")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "record play count failed: ${e.message}")
                }
            }
        }
    }

    private fun buildPlaybackStatsKey(song: AudioFile): String {
        return "${song.id}|${song.path}|${song.cueOffsetMs}|${song.cueTrackIndex}"
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

    private var saveStateJob: Job? = null

    private fun saveState() {
        _currentSong.value?.let {
            AppPreferences.Player.lastSongId = it.id
            AppPreferences.Player.lastSongPath = it.path
            AppPreferences.Player.lastSongTitle = it.title
            AppPreferences.Player.lastSongArtist = it.artist
            AppPreferences.Player.lastSongAlbum = it.album
            AppPreferences.Player.lastSongAlbumArtPath = it.albumArtPath
            AppPreferences.Player.lastSongDuration = it.duration
            AppPreferences.Player.lastSongAlbumId = it.albumId
        }
        AppPreferences.Player.lastPlayStateOrdinal = _playState.value.ordinal
        AppPreferences.Player.lastUsbExclusiveActive =
            AppPreferences.Player.lastUsbExclusiveActive ||
                _usbExclusiveActive.value ||
                ffmpegPlayer.usbExclusiveMode
        savePosition()
        val q = _queue.value
        val songsSnapshot = q.songs.toList()
        val currentIndex = q.currentIndex
        saveStateJob?.cancel()
        saveStateJob = scope.launch(Dispatchers.IO) {
            try {
                AppPreferences.Player.currentQueueIndex = currentIndex
                val arr = org.json.JSONArray()
                for (s in songsSnapshot) {
                    val obj = org.json.JSONObject().apply {
                        put("id", s.id)
                        put("path", s.path)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album)
                        put("albumId", s.albumId)
                        put("duration", s.duration)
                        put("albumArtPath", s.albumArtPath ?: "")
                        put("cueOffsetMs", s.cueOffsetMs)
                        put("cueEndMs", s.cueEndMs)
                        put("cueTrackIndex", s.cueTrackIndex)
                    }
                    arr.put(obj)
                }
                AppPreferences.Player.playQueueSongsJson = arr.toString()
            } catch (_: Exception) {}
        }
    }

    private fun savePosition() {
        try {
            if (!AppPreferences.Player.trackProgressMemoryEnabled) {
                AppPreferences.Player.lastPosition = 0L
                return
            }
            val pos = ffmpegPlayer.positionMs
            AppPreferences.Player.lastPosition = pos
        } catch (_: Exception) {}
    }

    private fun serializeSongList(songs: List<AudioFile>): String {
        val arr = org.json.JSONArray()
        for (s in songs) {
            val obj = org.json.JSONObject().apply {
                put("id", s.id)
                put("path", s.path)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
                put("albumId", s.albumId)
                put("duration", s.duration)
                put("albumArtPath", s.albumArtPath ?: "")
                put("cueOffsetMs", s.cueOffsetMs)
                put("cueEndMs", s.cueEndMs)
                put("cueTrackIndex", s.cueTrackIndex)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeSongList(json: String): List<AudioFile> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val songs = mutableListOf<AudioFile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                songs.add(AudioFile(
                    id = obj.optLong("id", -1),
                    path = obj.optString("path", ""),
                    title = obj.optString("title", ""),
                    artist = obj.optString("artist", ""),
                    album = obj.optString("album", ""),
                    albumId = obj.optLong("albumId", -1),
                    duration = obj.optLong("duration", 0),
                    albumArtPath = obj.optString("albumArtPath", ""),
                    cueOffsetMs = obj.optLong("cueOffsetMs", 0),
                    cueEndMs = obj.optLong("cueEndMs", 0),
                    cueTrackIndex = obj.optInt("cueTrackIndex", 0)
                ))
            }
            songs
        } catch (_: Exception) { emptyList() }
    }

    private fun restoreState() {
        applyComposedVolume()
        // 恢复立体声扩展设置
        restoreStereoWidenSettings()
        // 恢复互馈设置
        restoreCrossfeedSettings()
    }

    /**
     * 恢复上次播放的歌曲（但不自动播放）
     */
    fun restoreLastSong(): AudioFile? {
        val restoreStartMs = SystemClock.elapsedRealtime()
        val lastPath = AppPreferences.Player.lastSongPath
        if (lastPath.isBlank()) {
            PowerTraceLogger.playerStartup(
                stage = "restore_last_song_empty",
                detail = "lastPath=blank",
                elapsedMs = SystemClock.elapsedRealtime() - restoreStartMs
            )
            return null
        }

        // Cold restore: do not synchronously hydrate the whole library here.
        // A large collection can make the main capsule/miniplayer appear late and burn IO/CPU on launch.
        // Use the already-published repository snapshot when it exists; otherwise restore from the
        // compact last-song preferences and let MusicRepository reconcile the real library later.
        val repoSongs = com.rawsmusic.module.data.repository.MusicRepository.songs.value
        val repoSongById = if (repoSongs.isNotEmpty()) repoSongs.associateBy { it.id } else emptyMap()
        val repoSongMap = if (repoSongs.isNotEmpty()) repoSongs.associateBy { it.path } else emptyMap()

        val lastId = AppPreferences.Player.lastSongId
        val repoSong = (if (lastId != -1L) repoSongById[lastId] else null) ?: repoSongMap[lastPath]
        val restoreSource = if (repoSong != null) "repository_state" else "preference_snapshot"

        val song = repoSong ?: AudioFile(
            id = lastId,
            path = lastPath,
            title = AppPreferences.Player.lastSongTitle,
            artist = AppPreferences.Player.lastSongArtist,
            album = AppPreferences.Player.lastSongAlbum,
            albumId = AppPreferences.Player.lastSongAlbumId,
            duration = AppPreferences.Player.lastSongDuration,
            albumArtPath = AppPreferences.Player.lastSongAlbumArtPath
        )

        _currentSong.value = song
        _duration.value = song.duration

        // 恢复上次播放位置
        val savedPosition = if (AppPreferences.Player.trackProgressMemoryEnabled) {
            AppPreferences.Player.lastPosition
        } else {
            0L
        }
        if (savedPosition > 0) {
            _position.value = savedPosition
            pendingSeekPosition = savedPosition
            pendingSeekPath = song.path
        }

        try {
            val queueJson = AppPreferences.Player.playQueueSongsJson
            if (queueJson.isNotBlank()) {
                val arr = org.json.JSONArray(queueJson)
                val savedSongs = mutableListOf<AudioFile>()
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        val path = obj.optString("path", "")
                        if (path.isBlank()) continue
                        val cueOff = obj.optLong("cueOffsetMs", 0)
                        val cueIdx = obj.optInt("cueTrackIndex", 0)
                        val savedId = obj.optLong("id", -1)
                        val repoQueueSong = (if (savedId != -1L) repoSongById[savedId] else null)
                            ?: repoSongMap[path]
                        if (repoQueueSong != null) {
                            savedSongs.add(repoQueueSong)
                        } else {
                            savedSongs.add(AudioFile(
                                id = savedId,
                                path = path,
                                title = obj.getString("title"),
                                artist = obj.getString("artist"),
                                album = obj.getString("album"),
                                albumId = obj.getLong("albumId"),
                                duration = obj.getLong("duration"),
                                albumArtPath = obj.optString("albumArtPath", ""),
                                cueOffsetMs = cueOff,
                                cueEndMs = obj.optLong("cueEndMs", 0),
                                cueTrackIndex = cueIdx
                            ))
                        }
                    } catch (_: Exception) { continue }
                }
                if (savedSongs.isNotEmpty()) {
                    val savedIndex = AppPreferences.Player.currentQueueIndex
                        .coerceIn(0, savedSongs.size - 1)
                    _queue.value = PlayQueue(songs = savedSongs, currentIndex = savedIndex)
                } else {
                    _queue.value = PlayQueue(songs = listOf(song), currentIndex = 0)
                }
            } else {
                _queue.value = PlayQueue(songs = listOf(song), currentIndex = 0)
            }
        } catch (_: Exception) {
            _queue.value = PlayQueue(songs = listOf(song), currentIndex = 0)
        }

        PowerTraceLogger.playerStartup(
            stage = "restore_last_song_done",
            detail = "source=$restoreSource repoSongs=${repoSongs.size} queue=${_queue.value.songs.size} pos=$savedPosition title=${song.title.take(48)}",
            elapsedMs = SystemClock.elapsedRealtime() - restoreStartMs
        )
        return song
    }

    fun release() {
        Log.w(TAG, "=== PlayerController.release() CALLED ===")
        if (isReleased) return
        isReleased = true
        PlayerRuntimeRegistry.detachController(this, "controller_release")
        saveState()
        stopProgressUpdate()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        scope.cancel()
        onAudioSessionChanged = null
        equalizerController?.release()
        equalizerController = null

        disableUsbExclusive()
        usbSystemAudioKeepAlive.stop("controller_release")
        try { usbExclusiveManager.unregister() } catch (_: Exception) {}

        try {
            ffmpegPlayer.release()
        } catch (_: Exception) {}
    }

    /** 获取当前音频会话ID（供均衡器等音频效果使用） */
    fun getAudioSessionId(): Int {
        return try {
            ffmpegPlayer.audioSessionId.takeIf { it != 0 } ?: android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        } catch (_: Exception) {
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        }
    }

    /**
     * 绑定均衡器控制器到播放器生命周期
     */
    fun setEqualizerController(reinitFn: ((newSessionId: Int) -> Unit)?) {
        onAudioSessionChanged = reinitFn
    }

    /**
     * 初始化/重新初始化均衡器控制器
     */
    private fun initEqualizerController(sessionId: Int) {
        equalizerController?.release()
        equalizerController = EqualizerController(sessionId).apply { init() }
        Log.d(TAG, "EqualizerController initialized for session $sessionId")
    }

    /** 设置立体声扩展因子 (0.0f ~ 1.0f)，实时生效 */
    fun setStereoWidenFactor(factor: Float) {
        val coerced = factor.coerceIn(0f, 1f)
        Log.w(TAG, "setStereoWidenFactor: input=$factor, coerced=$coerced, playerState=${ffmpegPlayer.state}")
        ffmpegPlayer.stereoWidenFactor = coerced
        AppPreferences.Equalizer.virtualizer = (coerced * 1000f).toInt().coerceIn(0, 1000)
    }

    // ========== 互馈 (Crossfeed) ==========

    /** 启用/禁用互馈 */
    fun setCrossfeedEnabled(enabled: Boolean) {
        AppPreferences.Equalizer.crossfeedEnabled = enabled
        val engine = ffmpegPlayer.dspEngine
        if (engine?.isInitialized() == true) {
            engine.setCrossfeedEnabled(enabled)
        }
        Log.d(TAG, "setCrossfeedEnabled: $enabled")
    }

    /**
     * 设置互馈参数
     * @param lowCutFreq 高通截止频率 (Hz)，50-1000
     * @param highCutFreq 低通截止频率 (Hz)，500-8000
     * @param attenuationDB 衰减量 (dB)，0.0-15.0
     */
    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) {
        val lowCut = lowCutFreq.coerceIn(50f, 1000f)
        val highCut = highCutFreq.coerceIn(500f, 8000f)
        val attenuation = attenuationDB.coerceIn(0f, 15f)
        AppPreferences.Equalizer.crossfeedLowCut = lowCut.toInt()
        AppPreferences.Equalizer.crossfeedHighCut = highCut.toInt()
        AppPreferences.Equalizer.crossfeedAttenuation = (attenuation * 10f).toInt()
        val engine = ffmpegPlayer.dspEngine
        if (engine?.isInitialized() == true) {
            engine.setCrossfeedParams(lowCut, highCut, attenuation)
        }
        Log.d(TAG, "setCrossfeedParams: lowCut=$lowCut, highCut=$highCut, atten=$attenuation")
    }

    /** 恢复互馈设置（从持久化存储） */
    fun restoreCrossfeedSettings() {
        val engine = ffmpegPlayer.dspEngine ?: return
        if (!engine.isInitialized()) return
        val enabled = AppPreferences.Equalizer.crossfeedEnabled
        val lowCut = AppPreferences.Equalizer.crossfeedLowCut.toFloat()
        val highCut = AppPreferences.Equalizer.crossfeedHighCut.toFloat()
        val atten = AppPreferences.Equalizer.crossfeedAttenuation / 10f
        engine.setCrossfeedParams(lowCut, highCut, atten)
        engine.setCrossfeedEnabled(enabled)
        Log.d(TAG, "restoreCrossfeedSettings: enabled=$enabled, lowCut=$lowCut, highCut=$highCut, atten=$atten")
    }

    private fun restoreStereoWidenSettings() {
        val savedVirtualizer = AppPreferences.Equalizer.virtualizer.coerceIn(0, 1000)
        ffmpegPlayer.stereoWidenFactor = savedVirtualizer / 1000f
    }

    /** 初始化 DSP 管线 */
    fun initDspPipeline() {
    }

    /** 清除所有音频转码缓存 */
    fun clearCache() {
        // 清除 ffmpeg_audio 目录
        val ffmpegDir = File(context.cacheDir, "ffmpeg_audio")
        if (ffmpegDir.exists()) {
            ffmpegDir.listFiles()?.forEach { it.delete() }
        }
        // 清除 resampled_pcm 目录
        val resampledDir = File(context.cacheDir, "resampled_pcm")
        if (resampledDir.exists()) {
            resampledDir.listFiles()?.forEach { it.delete() }
        }
        // 清除旧版遗留的 resampled_*.pcm 文件（根目录）
        context.cacheDir.listFiles()?.filter {
            it.isFile && it.name.startsWith("resampled_") && it.name.endsWith(".pcm")
        }?.forEach { it.delete() }
    }
}
