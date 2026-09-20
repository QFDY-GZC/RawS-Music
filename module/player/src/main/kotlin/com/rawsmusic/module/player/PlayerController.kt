package com.rawsmusic.module.player

import android.content.Context
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.media.AudioManager
import android.util.Log
import kotlin.math.pow
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.core.common.utils.PlayerSwitchTrace
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.core.common.model.ShuffleMode
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.ExperimentalGainController
import com.rawsmusic.module.player.dsp.LoudnessBalanceController
import com.rawsmusic.module.player.dsp.MonoBassController
import com.rawsmusic.module.player.dsp.MonoOutputController
import com.rawsmusic.module.player.dsp.DynamicEqController
import com.rawsmusic.module.player.dsp.MoogLadderController
import com.rawsmusic.module.player.dsp.CompressorController
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.TrebleBoostController
import com.rawsmusic.module.player.dsp.Surround360Controller
import com.rawsmusic.module.player.dsp.Panoramic360Controller
import com.rawsmusic.module.player.dsp.FftConvolverController
import com.rawsmusic.module.player.dsp.SpeakerOutputElasticityController
import com.rawsmusic.module.player.devicecontrol.DeviceControlRouteCoordinator
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteRequest
import com.rawsmusic.module.player.devicecontrol.DeviceControlWriteResult
import com.rawsmusic.module.player.devicecontrol.createDefaultDeviceControlRuntime
import com.rawsmusic.module.player.devicecontrol.usb.moondrop.MoondropUsbVendorAdapters
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbHardwareVolumeStore
import com.rawsmusic.module.player.usb.UsbHardwareVolumeMath
import com.rawsmusic.module.player.usb.UsbHardwareVolumeCoordinator
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbDsdTransport
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.R
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities
import com.rawsmusic.module.player.usb.UsbPcmFormatCapability
import com.rawsmusic.module.player.usb.UsbPcmFormatRequest
import com.rawsmusic.module.player.usb.UsbPcmFormatRequestPolicy
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import com.rawsmusic.module.player.usb.UsbPcmFormatScoring
import com.rawsmusic.module.player.usb.UsbVolumePath
import com.rawsmusic.module.player.usb.UsbVolumeModeBitPerfectPolicy
import com.rawsmusic.module.player.usb.UsbTransitionGainCoordinator
import com.rawsmusic.module.player.usb.UsbLearnedPolicyStore
import com.rawsmusic.module.player.usb.UsbSilentKind
import com.rawsmusic.module.player.usb.UsbSelfTest
import com.rawsmusic.module.player.usb.UsbStreamRecoveryPlanner
import com.rawsmusic.module.player.usb.UsbRecoveryAction
import com.rawsmusic.module.player.usb.UsbRecoveryPlan
import com.rawsmusic.module.player.usb.UsbStatsSnapshot
import com.rawsmusic.module.player.usb.UsbRuntimeStatsParser
import com.rawsmusic.module.player.usb.UsbDeviceStatusTextFormatter
import com.rawsmusic.module.player.usb.UsbPolicyRestartSourceInputs
import com.rawsmusic.module.player.usb.UsbPolicyRestartSourceResolver
import com.rawsmusic.module.player.usb.UsbExclusiveManager
import com.rawsmusic.module.player.usb.dsdMultiplierFromSourceRate
import com.rawsmusic.module.player.usb.dsdRateHzForMultiplier
import com.rawsmusic.module.player.usb.normalizeDsdSourceRateHz
import com.rawsmusic.module.player.usb.normalizeProbedDsdSourceRateHz
import com.rawsmusic.module.player.usb.buildUsbDsdModeConfig
import com.rawsmusic.module.player.usb.buildSupportedDsdSourceDirectModeConfig
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue
import com.rawsmusic.module.player.control.PlayerControl
import com.rawsmusic.module.player.control.PlayerControlFacade
import com.rawsmusic.module.player.control.PlayerQueueControlCoordinator
import com.rawsmusic.module.player.control.PlayerTransportControlCoordinator
import com.rawsmusic.module.player.control.PlaybackEventQueueTransportAdapter
import com.rawsmusic.module.player.control.PlayerSeekControlCoordinator
import com.rawsmusic.module.player.control.PlaybackEventQueueSeekAdapter
import com.rawsmusic.module.player.control.PlayerUiSelectionControlCoordinator
import com.rawsmusic.module.player.control.PlayerInterruptionControlCoordinator
import com.rawsmusic.module.player.control.PlayerBackendStateControlCoordinator
import com.rawsmusic.module.player.control.PlayerGaplessControlCoordinator
import com.rawsmusic.module.player.control.resolveGaplessNextIndex
import com.rawsmusic.module.player.control.PlayerPlayPauseSeedCoordinator
import com.rawsmusic.module.player.control.PlayerPlayRequestResolver
import com.rawsmusic.module.player.control.PlayerRestoreControlCoordinator
import com.rawsmusic.module.player.control.PlayerPlayStateControlCoordinator
import com.rawsmusic.module.player.control.PlayerVolumeControlCoordinator
import com.rawsmusic.module.player.control.UsbDeferredSeekCoordinator
import com.rawsmusic.module.player.control.UsbSeekRuntimePolicy
import com.rawsmusic.module.player.control.UsbTransientVolumeCoordinator
import com.rawsmusic.module.player.control.UsbHardRecoveryDeferralPolicy
import com.rawsmusic.module.player.control.UsbMediaIdentityCoordinator
import com.rawsmusic.module.player.control.UsbBackgroundPlaybackCoordinator
import com.rawsmusic.module.player.control.PlayerMetadataEnrichmentCoordinator
import com.rawsmusic.module.player.control.PlayerDuplicatePlayRequestGate
import com.rawsmusic.module.player.control.toBackendControlState
import com.rawsmusic.module.player.control.toInterruptionBackendState

class PlayerController private constructor(context: Context) {

    private val context = context.applicationContext
    private val previousUsbHardwareSessionUnclean =
        UsbHardwareVolumeStore.consumeUncleanSessionMarker(this.context)
    @Volatile
    private var usbHardwareRecoveryBlocked = UsbHardwareVolumeStore.isRecoveryBlocked()

    init {
        if (usbHardwareRecoveryBlocked) {
            // A system/kernel reset can leave the host controller or attached DAC in an unknown
            // state. Do not silently re-arm Feature Unit control on the next process boot.
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
            AppPreferences.Player.usbVolumeMode = 0
            AppPreferences.Player.usbExclusiveRequested = false
            AppPreferences.Player.lastUsbExclusiveActive = false
            AppPreferences.sync()
            AppLogger.e(
                TAG,
                "USB hardware-volume safety interlock active (previousUnclean=$previousUsbHardwareSessionUnclean); " +
                    "USB exclusive auto-restore and hardware volume disabled until explicit user enable",
            )
        }
    }

    /** Narrow UI/feature control surface; USB, DSP and diagnostics stay on PlayerController. */
    val controls: PlayerControl = PlayerControlFacade(this)
    private val hidRemoteController by lazy {
        PlayerHidRemoteController(
            isPlaying = { ffmpegPlayer.isPlayingNow },
            pause = ::pause,
            resume = ::resume,
            nextSong = ::getNextSong,
            previousSong = ::getPreviousSong,
            playNext = ::playNext,
            stop = ::stop,
            isUsbExclusiveActive = ::isUsbExclusiveActive,
            canControlUsbVolume = ::canControlUsbVolume,
            stepUsbVolume = ::stepUsbVolume,
            setUsbVolumeLinear = ::setUsbVolumeLinear,
            setVolume = ::setVolume,
        )
    }
    private val androidPlaybackServiceController by lazy {
        AndroidPlaybackServiceController(this.context)
    }
    private val usbMediaIdentityCoordinator by lazy {
        UsbMediaIdentityCoordinator(
            isExclusiveActive = { _usbExclusiveActive.value },
            syncControllerIdentity = { song, playing, position, reason ->
                PlayerService.syncUsbMediaIdentityFromController(song, playing, position, reason)
            },
            sendServiceIdentity = { reason, song, position, playing ->
                androidPlaybackServiceController.sendUsbMediaIdentity(reason, song, position, playing)
            },
            logInfo = { AppLogger.i(TAG, it) },
        )
    }
    private val usbBackgroundPlaybackCoordinator by lazy {
        UsbBackgroundPlaybackCoordinator(
            clockMs = SystemClock::elapsedRealtime,
            scope = scope,
            snapshotProvider = {
                UsbBackgroundPlaybackCoordinator.Snapshot(
                    released = isReleased,
                    exclusiveActive = _usbExclusiveActive.value,
                    engineExclusiveMode = ffmpegPlayer.usbExclusiveMode,
                    appInBackground = appInBackground,
                    controllerPlaying = _playState.value == PlayState.PLAYING,
                    controllerPreparing = _playState.value == PlayState.PREPARING,
                    backendPlaying = ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING,
                    backendPreparing = ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING,
                )
            },
            releaseIdleResources = { reason ->
                AppLogger.i(TAG, "USB exclusive idle in background; releasing DAC resources: reason=$reason")
                rememberUsbPausedResumePoint("idle_release:$reason")
                val rendererDrained = runCatching {
                    ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
                }.getOrDefault(false)
                runCatching { unregisterSystemVolumeObserver() }
                if (!rendererDrained) {
                    AppLogger.e(TAG, "USB idle release kept native session alive: playback Runnable did not exit")
                }
                runCatching {
                    clearUsbExclusiveState(
                        releaseManager = rendererDrained,
                        notifyNativeDetached = rendererDrained
                    )
                }
            },
        )
    }

    private var usbDetachRecoveryJob: Job? = null
    @Volatile
    private var appInBackground = false
    @Volatile
    private var appBackgroundEnteredAtElapsedMs = 0L
    private var usbBackgroundStallSamples = 0
    private var usbBackgroundMissingSessionSamples = 0
    private var usbBackgroundStallSessionId = 0L
    @Volatile
    private var deferredUsbActivationDevice: UsbDevice? = null
    @Volatile
    private var deferredUsbActivationReason: String = ""
    private fun shouldDeferUsbHardRecovery(reason: String): Boolean {
        val recovering = recoveringUsb.get()
        val decision = UsbHardRecoveryDeferralPolicy.evaluate(
            UsbHardRecoveryDeferralPolicy.Snapshot(
                exclusiveActive = _usbExclusiveActive.value,
                appInBackground = appInBackground,
                backgroundEnteredAtMs = appBackgroundEnteredAtElapsedMs,
                nowMs = SystemClock.elapsedRealtime(),
                playing = _playState.value == PlayState.PLAYING,
                transportTransitioning = transportTransitioning,
                usbSeeking = usbSeeking,
                recovering = recovering,
            )
        )
        if (decision.defer) {
            AppLogger.w(
                TAG,
                "Deferring destructive USB recovery: reason=$reason " +
                    "appInBackground=$appInBackground playingInBackground=${decision.playingInBackground} " +
                    "backgroundAgeMs=${decision.backgroundAgeMs} " +
                    "recentlyBackgrounded=${decision.recentlyBackgrounded} " +
                    "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking recovering=$recovering"
            )
        } else if (appInBackground) {
            AppLogger.w(
                TAG,
                "Allowing USB recovery in background: reason=$reason " +
                    "backgroundAgeMs=${decision.backgroundAgeMs} " +
                    "transportTransitioning=$transportTransitioning usbSeeking=$usbSeeking"
            )
        }
        return decision.defer
    }

    companion object {
        const val ACTION_SLEEP_TIMER_EXIT_APP = "com.rawsmusic.action.SLEEP_TIMER_EXIT_APP"
        private const val TAG = "PlayerController"
        private const val USB_BACKGROUND_REAL_PCM_LOW_MS = 120L
        @Volatile
        private var instance: PlayerController? = null

        fun getInstance(context: Context): PlayerController {
            instance?.takeIf { it.isOperational() }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.isOperational() } ?: PlayerController(context.applicationContext).also {
                    val stale = instance
                    instance = it
                    it.installMusicSourcePlaybackBackend()
                    PlayerRuntimeRegistry.attachController(it, "controller_singleton_create")
                    Log.i(
                        TAG,
                        "PlayerController singleton created: ${System.identityHashCode(it)} " +
                            "replaced=${stale?.let(System::identityHashCode) ?: -1}"
                    )
                }
            }
        }

        @JvmStatic
        fun getInstanceOrNull(): PlayerController? = instance?.takeIf { it.isOperational() }

        private fun clearReleasedInstance(controller: PlayerController) {
            synchronized(this) {
                if (instance === controller) {
                    instance = null
                }
            }
        }
    }

    // ======================== USB transient volume/startup control ========================
    @Volatile
    private var lastUsbRemoteVolumeDesired: Boolean? = null

    fun enterUsbCriticalStartup(reason: String, ms: Long = 2500L) =
        usbTransientVolumeCoordinator.enterCriticalStartup(reason, ms)

    fun isUsbCriticalStartup(): Boolean = usbTransientVolumeCoordinator.isCriticalStartup()

    private fun applyUsbNoDataSafetyVolume(reason: String) =
        usbTransientVolumeCoordinator.applyNoDataSafety(reason)

    private fun isUsbHardwareVolumeRouteActive(): Boolean {
        if (!_usbExclusiveActive.value) return false
        return resolveCurrentUsbOutputProfile()?.volumePath == UsbVolumePath.HardwareUserVolume
    }

    private fun ensureUsbMediaIdentity(
        reason: String,
        song: AudioFile? = _currentSong.value,
        forcePosition: Long = _position.value,
    ) = usbMediaIdentityCoordinator.ensure(reason, song, forcePosition, _playState.value == PlayState.PLAYING)


    /**
     * 音频会话变更回调 — 当播放器重建导致 audioSessionId 变化时触发
     * 外部（如 EqualizerViewModel）应监听此回调并重新初始化音效引擎
     */
    var onPcmWaveformFrame: ((buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, validBitsPerSample: Int, sampleEncoding: Int) -> Unit)? = null

    // FFmpeg + AudioTrack 播放器
    private var ffmpegPlayer = FfmpegAudioPlayer(context)
    val ffmpegPlayerRef: FfmpegAudioPlayer get() = ffmpegPlayer
    val autoTransitionPresentation: StateFlow<AutoTransitionPresentation>
        get() = ffmpegPlayer.autoTransitionPresentation
    private val audioEffectsSessionCoordinator by lazy {
        PlayerAudioEffectsSessionCoordinator(
            tag = TAG,
            player = { ffmpegPlayer },
        )
    }
    private val realtimeStemCoordinator by lazy {
        PlayerRealtimeStemCoordinator(
            tag = TAG,
            player = { ffmpegPlayer },
            usbExclusiveActive = { _usbExclusiveActive.value },
        )
    }
    private val androidSpatialPlaybackController =
        AndroidSpatialPlaybackController(context, ffmpegPlayer)

    init {
        ffmpegPlayer.onDspEngineReinit = {
            androidSpatialPlaybackController.restoreSettings()
            audioEffectsSessionCoordinator.restoreSettings()
            ensurePEQConnected()
            ensureGraphicEQConnected()
            ensureExperimentalGainConnected()
            ensureLoudnessBalanceConnected()
            ensureMonoBassConnected()
            ensureMonoOutputConnected()
            ensureDynamicEqConnected()
            ensureMoogLadderConnected()
            ensureCompressorConnected()
            ensureBassBoostConnected()
            ensureTrebleBoostConnected()
            ensureSurround360Connected()
            ensurePanoramic360Connected()
            ensureFftConvolverConnected()
            ensureSpeakerOutputElasticityConnected()
            restoreCrossfeedSettings()
        }
    }

    private val dspControllerRegistry by lazy {
        PlaybackDspControllerRegistry(
            context = context,
            scope = scope,
            engineProvider = { ffmpegPlayer.dspEngine }
        )
    }

    val peqController: ParametricEQController
        get() = dspControllerRegistry.peqController

    fun ensurePEQConnected() = dspControllerRegistry.ensurePeqConnected()

    val graphicEqController: GraphicEQController
        get() = dspControllerRegistry.graphicEqController

    fun ensureGraphicEQConnected() = dspControllerRegistry.ensureGraphicEqConnected()

    val experimentalGainController: ExperimentalGainController
        get() = dspControllerRegistry.experimentalGainController

    fun ensureExperimentalGainConnected() = dspControllerRegistry.ensureExperimentalGainConnected()

    val loudnessBalanceController: LoudnessBalanceController
        get() = dspControllerRegistry.loudnessBalanceController

    fun ensureLoudnessBalanceConnected() = dspControllerRegistry.ensureLoudnessBalanceConnected()

    val monoBassController: MonoBassController
        get() = dspControllerRegistry.monoBassController

    fun ensureMonoBassConnected() = dspControllerRegistry.ensureMonoBassConnected()

    val monoOutputController: MonoOutputController
        get() = dspControllerRegistry.monoOutputController

    fun ensureMonoOutputConnected() = dspControllerRegistry.ensureMonoOutputConnected()

    val dynamicEqController: DynamicEqController
        get() = dspControllerRegistry.dynamicEqController

    fun ensureDynamicEqConnected() = dspControllerRegistry.ensureDynamicEqConnected()

    val moogLadderController: MoogLadderController
        get() = dspControllerRegistry.moogLadderController

    fun ensureMoogLadderConnected() = dspControllerRegistry.ensureMoogLadderConnected()

    val fftConvolverController: FftConvolverController
        get() = dspControllerRegistry.fftConvolverController

    fun ensureFftConvolverConnected() = dspControllerRegistry.ensureFftConvolverConnected()

    val compressorController: CompressorController
        get() = dspControllerRegistry.compressorController

    fun ensureCompressorConnected() = dspControllerRegistry.ensureCompressorConnected()

    val bassBoostController: BassBoostController
        get() = dspControllerRegistry.bassBoostController

    fun ensureBassBoostConnected() = dspControllerRegistry.ensureBassBoostConnected()

    val trebleBoostController: TrebleBoostController
        get() = dspControllerRegistry.trebleBoostController

    fun ensureTrebleBoostConnected() = dspControllerRegistry.ensureTrebleBoostConnected()

    val speakerOutputElasticityController: SpeakerOutputElasticityController
        get() = dspControllerRegistry.speakerOutputElasticityController

    fun ensureSpeakerOutputElasticityConnected() =
        dspControllerRegistry.ensureSpeakerOutputElasticityConnected()

    val surround360Controller: Surround360Controller
        get() = dspControllerRegistry.surround360Controller

    fun ensureSurround360Connected() = dspControllerRegistry.ensureSurround360Connected()

    val panoramic360Controller: Panoramic360Controller
        get() = dspControllerRegistry.panoramic360Controller

    fun ensurePanoramic360Connected() = dspControllerRegistry.ensurePanoramic360Connected()

    val usbExclusiveManager = UsbExclusiveManager(context)
    private val sharedUsbAudioEngine = UsbAudioEngine
    private val usbTransientVolumeCoordinator by lazy {
        UsbTransientVolumeCoordinator(
            callbacks = UsbTransientVolumeCoordinator.Callbacks(
                elapsedRealtimeMs = SystemClock::elapsedRealtime,
                isExclusiveActive = { _usbExclusiveActive.value },
                isHardwareRouteActive = ::isUsbHardwareVolumeRouteActive,
                routeDescription = { resolveCurrentUsbOutputProfile()?.volumePath.toString() },
                setSoftwareGain = { gain ->
                    sharedUsbAudioEngine.nativeSetUsbSoftwareGain(gain)
                },
                logInfo = { AppLogger.i(TAG, it) },
            ),
        )
    }
    private val usbSystemAudioKeepAlive = AndroidUsbIdentityCarrier(context)
    @Volatile
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

    private val playStateControlCoordinator = PlayerPlayStateControlCoordinator(
        applyState = { state, reason, forced ->
            _playState.value = state
            val prefix = if (forced) "smForceTransition" else "smTransition"
            syncUsbSystemAudioKeepAlive("$prefix:$reason")
        },
    )

    /** Helper: legal transition + observable/system-audio identity sync. */
    private fun smTransition(target: PlayState, tag: String = ""): Boolean =
        playStateControlCoordinator.transition(target, tag)

    /** Helper: recovery transition + observable/system-audio identity sync. */
    private fun smForceTransition(target: PlayState, tag: String = "") =
        playStateControlCoordinator.forceTransition(target, tag)

    private fun syncUsbSystemAudioKeepAlive(reason: String) =
        usbBackgroundGuardCoordinator.sync(reason)

    private val _currentSong = MutableStateFlow<AudioFile?>(null)
    @Volatile private var autoTransitionLyrics: LyricData? = null
    val currentSong: StateFlow<AudioFile?> = _currentSong.asStateFlow()
    private val restoreSnapshotApplied = AtomicBoolean(false)
    private val restoreSnapshotLock = Any()
    /** True when currentSong is only the Reference-style queue selection, not a resumable decoder. */
    @Volatile private var queueSelectionOnly = false
    /**
     * A paused next/previous command changes the logical queue row without resuming the retiring
     * renderer. The next explicit Play must therefore open the selected row instead of resuming
     * the decoder that still belongs to the previous row.
     */
    @Volatile private var pausedSelectionPendingStart = false

    private val _playbackSourceOverride = MutableStateFlow<PlaybackSourceOverride?>(null)
    val playbackSourceOverride: StateFlow<PlaybackSourceOverride?> = _playbackSourceOverride.asStateFlow()
    private val playbackSourceOverrideGeneration = AtomicLong(0L)

    private fun currentRendererSongForOutputPolicy(): AudioFile? = _currentSong.value?.let { song ->
        PlaybackSourceOverridePolicy.rendererSong(song, _playbackSourceOverride.value)
    }

    private fun clearPlaybackSourceOverrideIfOwnerChanged(song: AudioFile, reason: String) {
        val active = _playbackSourceOverride.value ?: return
        if (!active.matches(song)) {
            _playbackSourceOverride.value = null
            AppLogger.i(
                TAG,
                "SOURCE_OVERRIDE cleared reason=$reason owner=${active.semanticPath} next=${song.path}",
            )
        }
    }

    private val usbFormatPolicyCoordinator by lazy {
        PlayerUsbFormatPolicyCoordinator(
            currentSong = ::currentRendererSongForOutputPolicy,
            currentCapabilities = { _usbCapabilities.value },
            setCapabilities = { _usbCapabilities.value = it },
            usbEngine = sharedUsbAudioEngine,
            logInfo = { message -> AppLogger.i(TAG, message) },
            logWarning = { message -> AppLogger.w(TAG, message) },
        )
    }

    private val _queue = MutableStateFlow(PlayQueue())
    val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    /**
     * Queue identity that must become authoritative only after the renderer confirms that the
     * first PCM frames of an inline manual handoff entered the output timeline.
     *
     * A manual request can be resolved against a repository/list queue that is newer than
     * [_queue]. Publishing that queue before TRACK_STARTED makes the UI lead the audio; not
     * retaining it at all makes TRACK_STARTED search a stale queue and leaves the UI on the old
     * song. Keep the exact request context here until the renderer owns the new track.
     *
     * Access is serialized by [transportMutex].
     */
    private data class PendingRendererUiCommit(
        val song: AudioFile,
        val queue: List<AudioFile>,
        val index: Int,
        /**
         * True when the target decoder was cold-reopened rather than committed by an already-live
         * two-slot handoff. In that case the following-track/gapless preparation must wait until
         * this renderer becomes authoritative; doing it before commit would inspect the old public
         * queue/current item.
         */
        val prepareFollowingAfterCommit: Boolean = false,
    )

    private var pendingRendererUiCommit: PendingRendererUiCommit? = null

    /**
     * UI-facing selection state is isolated from decoder commitment. The coordinator persists the
     * latest accepted identity immediately, then clears it only when the matching playback item is
     * committed or explicitly cancelled.
     */
    private val uiSelectionControlCoordinator = PlayerUiSelectionControlCoordinator(
        isReleased = { isReleased },
        currentSong = { _currentSong.value },
        setCurrentSong = { _currentSong.value = it },
        currentQueue = { _queue.value },
        updateQueue = { _queue.value = it },
        samePlaybackItem = { left, right -> samePlaybackItem(left, right) },
        persistSelection = { song -> statePersistence.saveSongSnapshot(song) },
        resetPersistedPosition = { AppPreferences.Player.lastPosition = 0L },
    )
    val requestedSongForUi: StateFlow<AudioFile?> =
        uiSelectionControlCoordinator.requestedSongForUi

    fun currentOrRequestedSongForUi(): AudioFile? =
        uiSelectionControlCoordinator.currentOrRequestedSong()

    fun hasRequestedSongForUi(): Boolean = uiSelectionControlCoordinator.hasRequestedSong()

    fun primeSongSelectionForUi(song: AudioFile) {
        // A user selection owns the queue generation even if it races the deferred cold-start
        // restore. The restore callback must not replace an explicit tap with an old snapshot.
        restoreSnapshotApplied.set(true)
        queueSelectionOnly = false
        uiSelectionControlCoordinator.primeSelection(song)
    }

    /** Whether the visible current item came from a cold queue restore and has not started audio. */
    fun isQueueSelectionOnly(): Boolean = queueSelectionOnly

    private fun clearRequestedSongIfCommitted(song: AudioFile) {
        uiSelectionControlCoordinator.clearRequestedSongIfMatching(song)
    }

    private fun clearRequestedSongIfMatching(song: AudioFile) {
        uiSelectionControlCoordinator.clearRequestedSongIfMatching(song)
    }

    private fun clearPendingUiSelection() {
        uiSelectionControlCoordinator.clearRequestedSong()
    }

    fun updateCurrentSongIfSamePath(song: AudioFile) {
        uiSelectionControlCoordinator.updateCurrentSongIfSameIdentity(song)
    }

    fun rebuildCurrentOpenSlAfterMetadataWrite(path: String): Boolean {
        val current = _currentSong.value ?: return false
        if (current.path != path || _usbExclusiveActive.value) return false
        if (AudioOutputManager.getCurrentOutputMode(context) != AudioOutputMode.OPENSL_ES) return false
        ffmpegPlayer.rebuildAfterSourceFileMutation()
        return true
    }

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    val outputLatencyMs: Int
        get() = ffmpegPlayer.latencyMs

    var isBluetoothOutput: Boolean
        get() = androidBluetoothOutputController.isBluetoothOutput
        private set(_) = Unit

    val hfpOnlyDeviceDetected: StateFlow<Boolean>
        get() = androidBluetoothOutputController.hfpOnlyDeviceDetected

    fun getBluetoothLatencyInfo(): String =
        androidBluetoothOutputController.getLatencyInfo()

    val repeatMode: StateFlow<RepeatMode>
        get() = playbackModeController.repeatMode

    val isShuffle: StateFlow<Boolean>
        get() = playbackModeController.isShuffle

    val playMode: StateFlow<PlayMode>
        get() = playbackModeController.playMode

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
    private val playbackSpeedRestartMutex = Mutex()
    private val playbackSpeedRequestToken = AtomicLong(0L)
    private var playbackSpeedRestartJob: Job? = null

    private val statePersistence = PlayerStatePersistence(context)
    private val playRequestResolver = PlayerPlayRequestResolver(
        logWarning = { message -> AppLogger.w(TAG, message) },
    )
    private val playbackStatePersistenceController = PlaybackStatePersistenceController(
        scope = scope,
        persistence = statePersistence,
        currentSong = { currentOrRequestedSongForUi() },
        shouldPersistPlayback = {
            currentOrRequestedSongForUi()?.encodingFormat != MUSIC_SOURCE_ONLINE_ENCODING_MARKER
        },
        playStateOrdinal = { _playState.value.ordinal },
        keepUsbExclusive = { _usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode },
        positionMs = {
            val requested = uiSelectionControlCoordinator.requestedSong()
            val committed = _currentSong.value
            if (requested != null && (committed == null || !samePlaybackItem(requested, committed))) {
                0L
            } else {
                ffmpegPlayer.positionMs
            }
        },
        queue = { _queue.value },
        priorityQueue = { queueControlCoordinator.priorityQueueSnapshot() },
        shouldPersistQueue = { restoreSnapshotApplied.get() },
    )
    private val shuffleQueueController = ShuffleQueueController(statePersistence) {
        PlaybackStatsStore.getInstance(context).stats.value.associate { it.songId to it.lastPlayedAt }
    }
    private val playbackModeController = PlaybackModeController(
        shuffleQueueController = shuffleQueueController,
        currentQueue = { _queue.value },
        updateQueue = { _queue.value = it },
        persistState = { saveState() },
    )
    private val playbackQueueCommitter by lazy {
        PlayerPlaybackQueueCommitter(
            currentQueue = { _queue.value },
            setQueue = { _queue.value = it },
            setCurrentSong = { song ->
                clearPlaybackSourceOverrideIfOwnerChanged(song, "queue_commit")
                _currentSong.value = song
            },
            sameItem = ::samePlaybackItem,
            sameQueue = ::samePlaybackQueue,
            clearHistory = queueControlCoordinator::clearHistoryForNewQueue,
            currentRepeatMode = { playbackModeController.currentRepeatMode },
            isShuffleEnabled = { playbackModeController.isShuffleEnabled },
            rebuildShuffle = playbackModeController::rebuildShuffleForCurrentQueue,
        )
    }
    private val playPauseSeedCoordinator = PlayerPlayPauseSeedCoordinator(
        callbacks = PlayerPlayPauseSeedCoordinator.Callbacks(
            currentSong = { _currentSong.value },
        restoreLastSong = ::restoreLastSong,
            currentQueue = { _queue.value },
            applyCurrentSong = { song ->
                clearPlaybackSourceOverrideIfOwnerChanged(song, "play_pause_seed")
                _currentSong.value = song
            },
            applyDurationMs = { durationMs -> _duration.value = durationMs },
            loadRepositorySongs = {
                com.rawsmusic.module.data.repository.MusicRepository.getAllSongs()
            },
            applyQueue = { queue ->
                _queue.value = queue.copy(
                    repeatMode = playbackModeController.currentRepeatMode,
                    isShuffle = playbackModeController.isShuffleEnabled,
                    entryIds = QueueEntryIdGenerator.normalize(queue.songs.size, queue.entryIds),
                )
            },
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            traceStartup = { stage, detail, elapsedMs ->
                PowerTraceLogger.playerStartup(stage, detail, elapsedMs)
            },
            logDebug = { message -> AppLogger.d(TAG, message) },
            logWarning = { message -> AppLogger.w(TAG, message) },
        ),
    )
    private val gaplessControlCoordinator = PlayerGaplessControlCoordinator(
        callbacks = PlayerGaplessControlCoordinator.Callbacks(
            gaplessEnabled = { AppPreferences.Player.gaplessPlaybackEnabled },
            automaticCrossfadeEnabled = { AppPreferences.Player.automaticCrossfadeEnabled },
            // Read the authoritative queue and priority projection here.  visibleQueue is a
            // stateIn(combine(...)) stream and may still expose the previous generation during a
            // mode/queue command; Reference computes its next cursor from the committed snapshot.
            currentQueue = {
                _queue.value.withPriorityQueue(queueControlCoordinator.priorityQueueSnapshot())
            },
            currentPlayMode = { playbackModeController.currentPlayMode },
            peekShuffleIndex = shuffleQueueController::peekNextIndex,
            applyPlan = { plan ->
                val current = _currentSong.value
                val queueSnapshot = _queue.value.withPriorityQueue(
                    queueControlCoordinator.priorityQueueSnapshot(),
                )
                val nextIndex = resolveGaplessNextIndex(
                    queue = queueSnapshot,
                    playMode = playbackModeController.currentPlayMode,
                    peekShuffleIndex = shuffleQueueController::peekNextIndex,
                )
                val next = queueSnapshot.songs.getOrNull(nextIndex)
                    ?.takeIf { plan.nextSongPath == null || it.path == plan.nextSongPath }
                val autoEnabled = plan.automaticCrossfadeEnabled
                val decision = if (autoEnabled) {
                    AutoTransitionPolicy.build(
                        current = current,
                        next = next,
                        lyrics = autoTransitionLyrics,
                    )
                } else {
                    AutoTransitionPolicy.Decision(
                        recipe = null,
                        forceGapless = false,
                        reason = "automatic_crossfade_disabled",
                    )
                }
                ffmpegPlayer.updateAutoTransitionRecipe(decision.recipe)
                ffmpegPlayer.updateNextSongPlan(
                    nextPath = plan.nextSongPath,
                    automaticCrossfadeEnabled = autoEnabled && !decision.forceGapless && decision.recipe != null,
                    reason = "queue_gapless_plan:${decision.reason}"
                )
                decision.recipe?.let { recipe ->
                    AppLogger.i(
                        TAG,
                        "AutoTransition recipe source=${recipe.source} confidence=${recipe.confidence} " +
                            "trigger=${recipe.triggerPositionMs} handover=${recipe.handoverPositionMs} " +
                            "handoverMs=${recipe.handoverDurationMs} target=${recipe.targetPath}"
                    )
                }
            },
            logInfo = { message -> Log.d(TAG, message) },
            logWarning = { message, error -> Log.w(TAG, message, error) },
        ),
    )
    private val restoreControlCoordinator = PlayerRestoreControlCoordinator(
        callbacks = PlayerRestoreControlCoordinator.Callbacks(
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            restoreSnapshot = playbackStatePersistenceController::restore,
            applyCurrentSong = { song ->
                clearPlaybackSourceOverrideIfOwnerChanged(song, "restore_commit")
                _currentSong.value = song
            },
            clearRequestedSong = uiSelectionControlCoordinator::clearRequestedSong,
            applyDurationMs = { durationMs -> _duration.value = durationMs },
            applyPositionMs = { positionMs -> _position.value = positionMs },
            armPendingSeek = { positionMs, path ->
                pendingSeekPosition = positionMs
                pendingSeekPath = path
            },
            applyQueue = { restoredQueue ->
                _queue.value = restoredQueue.copy(
                    repeatMode = playbackModeController.currentRepeatMode,
                    isShuffle = playbackModeController.isShuffleEnabled,
                )
            },
            applyPriorityQueue = ::restorePriorityQueue,
            onQueueSelectionOnly = { queueSelectionOnly = true },
            onPersistedNowPlaying = { queueSelectionOnly = false },
            logInfo = { message -> AppLogger.i(TAG, message) },
            traceStartup = { stage, detail, elapsedMs ->
                PowerTraceLogger.playerStartup(stage, detail, elapsedMs)
            },
        ),
    )
    private val queueControlCoordinator: PlayerQueueControlCoordinator = PlayerQueueControlCoordinator(
        mode = PlayerQueueControlCoordinator.ModeCallbacks(
            currentPlayMode = { playbackModeController.currentPlayMode },
            isShuffleEnabled = { playbackModeController.isShuffleEnabled },
            nextShuffleIndex = shuffleQueueController::nextIndex,
            previousShuffleIndex = shuffleQueueController::previousIndex,
            peekNextShuffleIndex = shuffleQueueController::peekNextIndex,
            peekPreviousShuffleIndex = shuffleQueueController::peekPreviousIndex,
            peekRelativeShuffleIndex = shuffleQueueController::peekRelativeIndex,
            toggleRepeatMode = playbackModeController::toggleRepeatMode,
            setRepeatMode = playbackModeController::setRepeatMode,
            toggleShuffle = playbackModeController::toggleShuffle,
            cyclePlayMode = playbackModeController::cyclePlayMode,
            setPlayMode = playbackModeController::setPlayMode,
            rebuildShuffleForCurrentQueue = playbackModeController::rebuildShuffleForCurrentQueue,
        ),
        callbacks = PlayerQueueControlCoordinator.Callbacks(
            isReleased = { isReleased },
            currentQueue = { _queue.value },
            updateQueue = { _queue.value = it },
            currentSong = { _currentSong.value },
            clearCurrentSong = { _currentSong.value = null },
            clearRequestedSong = ::clearPendingUiSelection,
            resetTimeline = {
                _position.value = 0L
                _duration.value = 0L
            },
            playerPositionMs = { ffmpegPlayer.positionMs },
            seekToStart = { seekTo(0L, userInitiated = false) },
            savePosition = { savePosition() },
            saveState = { saveState() },
            play = { song, songs, index -> play(song, songs, index) },
            manualSwitchFromStart = { song, songs, index, reason ->
                playManualSwitchFromStart(
                    song = song,
                    queue = songs,
                    index = index,
                    reason = reason,
                    primeUiSelection = false,
                    persistOutgoingPositionInWorker = true,
                )
            },
            manualArtworkGestureSwitchFromStart = { song, songs, index, reason ->
                playManualSwitchFromStart(
                    song = song,
                    queue = songs,
                    index = index,
                    reason = reason,
                    primeUiSelection = false,
                    persistOutgoingPositionInWorker = true,
                )
            },
            automaticSwitchFromStart = { song, songs, index ->
                transportControlCoordinator.automaticAdvance(song, songs, index)
            },
            stop = { stop() },
        ),
    )

    private fun restorePriorityQueue(songs: List<AudioFile>) {
        queueControlCoordinator.restorePriorityQueue(songs)
    }
    private val sleepTimerController = SleepTimerController(
        scope = scope,
        pausePlayback = { pause() },
        requestPlayerExit = ::requestSleepTimerPlayerExit,
    )
    private val playbackStatsTracker = PlaybackStatsTracker(context, scope)
    private val playbackProgressController = PlaybackProgressController(
        scope = scope,
        callbacks = PlaybackProgressController.Callbacks(
            isReleased = { isReleased },
            playerPositionMs = { ffmpegPlayer.positionMs },
            playerDurationMs = { ffmpegPlayer.durationMs },
            cueOffsetMs = { cachedCueOffsetMs },
            cueEndMs = { cachedCueEndMs },
            cueSongDurationMs = { cachedSongDuration },
            displayedPositionMs = { _position.value },
            setDisplayedPositionMs = { _position.value = it },
            displayedDurationMs = { _duration.value },
            setDisplayedDurationMs = { _duration.value = it },
            currentSong = { _currentSong.value },
            onProgress = playbackStatsTracker::onProgress,
            shouldSyncUsbMediaIdentity = {
                _usbExclusiveActive.value && _playState.value == PlayState.PLAYING
            },
            syncUsbMediaIdentity = { song, positionMs ->
                PlayerService.syncUsbMediaIdentityFromController(
                    song = song,
                    playing = true,
                    position = positionMs,
                    reason = "progress_update",
                )
            },
            savePosition = { playbackStatePersistenceController.savePosition() },
            onCueTrackEnd = { next() },
        ),
        logTag = TAG,
    )
    val sleepTimerRemaining: StateFlow<Long> = sleepTimerController.remainingMs
    val priorityQueue: StateFlow<List<AudioFile>> = queueControlCoordinator.priorityQueueState
    /**
     * One renderer-confirmed queue projection shared by the home carousel, mini player and queue
     * sheet. An in-flight selection is intentionally kept out of this stream: publishing it here
     * invalidates every queue consumer once for the request and again for renderer confirmation.
     * [requestedSongForUi] is a narrow hint for retained transition holders only.
     */
    private fun alignVisibleQueueToRendererSong(queue: PlayQueue, song: AudioFile?): PlayQueue {
        if (song == null || queue.songs.isEmpty()) return queue
        val exactIndex = queue.songs.indexOfFirst { samePlaybackItem(it, song) }
        return if (exactIndex >= 0 && exactIndex != queue.currentIndex) {
            queue.copy(currentIndex = exactIndex)
        } else {
            queue
        }
    }

    val visibleQueue: StateFlow<PlayQueue> by lazy {
        combine(queue, priorityQueue, currentSong) { base, priority, rendererSong ->
            alignVisibleQueueToRendererSong(
                base.withPriorityQueue(priority),
                rendererSong,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            alignVisibleQueueToRendererSong(
                queue.value.withPriorityQueue(priorityQueue.value),
                currentSong.value,
            ),
        )
    }

    /** Serialized playback event queue. */
    private val eventQueue = PlaybackEventQueue()

    init {
        eventQueue.start(scope)
        sleepTimerController.restorePersistedTimer()
    }

    @Volatile
    private var isReleased = false

    fun isOperational(): Boolean = !isReleased
    @Volatile
    private var lastPlayerError: String? = null
    private val transportMutex = Mutex()
    private val usbActivationMutex = Mutex()

    private val recoveringUsb = java.util.concurrent.atomic.AtomicBoolean(false)
    private val usbFatalStopInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val usbExclusiveFullRecoveryAttemptsMs = java.util.ArrayDeque<Long>()
    @Volatile
    private var stickyUsbHardwareVolumeValidated = false
    private val usbHardwareVolumeCoordinator: UsbHardwareVolumeCoordinator by lazy {
        UsbHardwareVolumeCoordinator(
            context = context,
            engine = sharedUsbAudioEngine,
            scope = scope,
            transportMutex = transportMutex,
            isReleased = { isReleased },
            isTransportTransitioning = { transportTransitioning },
            isRecovering = { recoveringUsb.get() },
            currentDevice = { currentUsbDevice },
            isHardwareRouteActive = ::isUsbHardwareVolumeRouteActive,
        )
    }
    private data class PendingUsbHardwareReleaseAfterStop(
        val snapshot: UsbHardwareVolumeCoordinator.LiveVolumeSnapshot,
    )

    @Volatile
    private var usbHardwareToSoftwareHandoffActive = false

    @Volatile
    private var pendingUsbHardwareReleaseAfterStop: PendingUsbHardwareReleaseAfterStop? = null
    private val musicSourcePlaybackBackend = PlayerControllerMusicSourcePlaybackBackend(this)

    private fun installMusicSourcePlaybackBackend() {
        MusicSourcePlaybackController.installBackend(musicSourcePlaybackBackend)
    }

    private val interruptionControlCoordinator = PlayerInterruptionControlCoordinator(
        callbacks = PlayerInterruptionControlCoordinator.Callbacks(
            isReleased = { isReleased },
            isUsbExclusiveActive = { _usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode },
            backendIsPlayingNow = { ffmpegPlayer.isPlayingNow },
            backendState = { ffmpegPlayer.state.toInterruptionBackendState() },
            controllerPlayState = { _playState.value },
            pauseBackend = ffmpegPlayer::pause,
            transitionToPaused = { reason ->
                smTransition(PlayState.PAUSED, reason)
                Unit
            },
            stopProgressUpdate = ::stopProgressUpdate,
            savePosition = ::savePosition,
            saveState = ::saveState,
            currentSong = { _currentSong.value },
            restoreLastSong = ::restoreLastSong,
            currentQueue = { _queue.value },
            samePlaybackItem = ::samePlaybackItem,
            resumeTransport = ::resume,
            playTransport = { song, songs, index -> play(song, songs, index) },
            logInfo = { message -> AppLogger.i(TAG, message) },
        ),
    )
    private val backendStateControlCoordinator = PlayerBackendStateControlCoordinator(
        callbacks = PlayerBackendStateControlCoordinator.Callbacks(
            isReleased = { isReleased },
            forcePlayState = { state, reason ->
                smForceTransition(state, reason)
                Unit
            },
            startProgressUpdate = ::startProgressUpdate,
            stopProgressUpdate = ::stopProgressUpdate,
            audioSessionId = { ffmpegPlayer.audioSessionId },
            onAudioSessionReady = { sessionId ->
                audioEffectsSessionCoordinator.onAudioSessionReady(sessionId)
            },
            lastPlayerError = { lastPlayerError },
            isUsbExclusiveActive = { _usbExclusiveActive.value },
            backendUsbExclusiveMode = { ffmpegPlayer.usbExclusiveMode },
            currentQueue = { visibleQueue.value },
            currentSong = { _currentSong.value },
            currentRepeatMode = { playbackModeController.currentRepeatMode },
            hasAutomaticContinuation = { queueControlCoordinator.hasAutomaticContinuation() },
            consumePlaybackCompletion = {
                musicSourcePlaybackBackend.consumePlaybackCompletion() ||
                    sleepTimerController.consumePlaybackCompletion()
            },
            onConsumedPlaybackCompletionStopped = {
                sleepTimerController.onPlaybackStoppedAfterTimerCompletion()
            },
            playTransport = { song, songs, index -> play(song, songs, index) },
            pauseTransport = { pause() },
            automaticReplayTransport = {
                val queue = visibleQueue.value
                val index = queue.currentIndex
                val song = _currentSong.value ?: queue.songs.getOrNull(index)
                song != null && index in queue.songs.indices &&
                    transportControlCoordinator.automaticAdvance(song, queue.songs, index)
            },
            automaticAdvanceTransport = { queueControlCoordinator.automaticAdvance() != null },
            stopTransport = { stop() },
            clearUnavailableSong = ::clearRequestedSongIfMatching,
            logDebug = { message -> Log.d("VirtualizerDebug", message) },
            logWarning = { message -> Log.w(TAG, message) },
        ),
    )

    private val androidAudioInterruptionController = AndroidAudioInterruptionController(
        context = context,
        scope = scope,
        callbacks = AndroidAudioInterruptionController.Callbacks(
            isReleased = { isReleased },
            isUsbExclusive = { _usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode },
            isPlaybackActive = interruptionControlCoordinator::isPlaybackActive,
            playbackStateSummary = { "${_playState.value}/${ffmpegPlayer.state}" },
            pauseForInterruption = { reason ->
                interruptionControlCoordinator.pauseForInterruption(reason)
            },
            pauseForNoisyRouteChange = ::pause,
            resumeAfterInterruption = { reason ->
                interruptionControlCoordinator.resumeOrStartRememberedSong(reason)
            },
            onDuckFactorChanged = ::applyComposedVolume,
            repairRouteAfterNoisy = {
                ffmpegPlayer.repairAndroidOutputRouteAfterDeviceChange(
                    "audio_becoming_noisy",
                    forceRebuild = true,
                )
            },
            shouldUseScoMode = { AudioOutputManager.shouldUseScoMode(context) },
            rebuildForScoConnected = { ffmpegPlayer.rebuildAudioTrackForSco() },
            rebuildForScoDisconnected = { ffmpegPlayer.rebuildAudioTrackForScoDisconnected() },
        ),
    )
    private val duckVolumeFactor: Float
        get() = androidAudioInterruptionController.duckVolumeFactor
    private val volumeControlCoordinator = PlayerVolumeControlCoordinator(
        callbacks = PlayerVolumeControlCoordinator.Callbacks(
            isReleased = { isReleased },
            transportTransitioning = { transportTransitioning },
            currentUsbProfile = {
                if (_usbExclusiveActive.value && currentUsbDevice != null) {
                    buildUsbOutputProfile(exclusive = true)
                } else {
                    null
                }
            },
            userVolume = {
                if (isUsbExclusiveSoftwareVolumeActive()) {
                    AppPreferences.Player.usbSoftwareVolume
                } else {
                    AppPreferences.Player.volume
                }
            },
            usbSoftwarePcmGain = { linear ->
                volumeRouteCoordinator.usbSoftwarePcmGainForLocalUi(linear)
            },
            duckFactor = { duckVolumeFactor },
            setAndroidSoftwareGain = ffmpegPlayer::setVolume,
            setUsbPcmGain = { gain -> sharedUsbAudioEngine.setPcmSoftwareGain(gain) },
            logDebug = { message -> Log.d(TAG, message) },
            logWarning = { message -> AppLogger.w(TAG, message) },
        ),
    )
    val scoConnected: StateFlow<Boolean>
        get() = androidAudioInterruptionController.scoConnected

    private val androidBluetoothOutputController = AndroidBluetoothOutputController(
        context = context,
        scope = scope,
        callbacks = AndroidBluetoothOutputController.Callbacks(
            currentTrackLatencyMs = { ffmpegPlayer.latencyMs },
            stopScoWithoutRouteRebuild = {
                androidAudioInterruptionController.stopBluetoothSco(rebuildRoute = false)
            },
            onBluetoothRouteDisconnected = {
                val state = ffmpegPlayer.state
                if (
                    state == FfmpegAudioPlayer.State.PLAYING ||
                    state == FfmpegAudioPlayer.State.PAUSED
                ) {
                    scope.launch(Dispatchers.Main) {
                        runCatching { ffmpegPlayer.rebuildAudioTrackForScoDisconnected() }
                            .onFailure { error ->
                                AppLogger.w(
                                    TAG,
                                    "Failed to rebuild media AudioTrack after Bluetooth disconnect",
                                    error,
                                )
                            }
                    }
                }
            },
        ),
    ).also { it.start() }

    private val deviceControlRuntime = createDefaultDeviceControlRuntime(
        context = context,
        scope = scope,
        usbEngine = sharedUsbAudioEngine,
        usbVendorAdapters = MoondropUsbVendorAdapters.production(),
    )
    private val deviceControlRouteCoordinator = DeviceControlRouteCoordinator(
        scope = scope,
        manager = deviceControlRuntime.manager,
        resolver = deviceControlRuntime.resolver,
        usbExclusiveActive = _usbExclusiveActive,
        bluetoothRoute = androidBluetoothOutputController.currentBluetoothRoute,
    ).also { it.start() }

    /** One dynamic current-device capability surface for settings/UI. */
    val hardwareDeviceControlSnapshot: StateFlow<DeviceControlSnapshot>
        get() = deviceControlRuntime.manager.snapshot

    /**
     * Settings/UI entry point. Reuses the service-owned live DeviceControlSession and snapshot
     * when the same physical route is still bound; unlike refreshHardwareDeviceControls(), this
     * must not re-read the device merely because a screen was reopened.
     */
    suspend fun ensureHardwareDeviceControlsBound() {
        deviceControlRouteCoordinator.ensureBound()
    }

    /** Explicit refresh path for the user, permission changes, or diagnostics. */
    suspend fun refreshHardwareDeviceControls() {
        deviceControlRouteCoordinator.refresh()
    }

    suspend fun writeHardwareDeviceControl(request: DeviceControlWriteRequest): DeviceControlWriteResult =
        deviceControlRuntime.manager.write(request)

    private val remoteCachedMediaEnricher = RemoteCachedMediaEnricher(
        cacheDir = context.cacheDir,
        tag = TAG,
    )
    private val metadataEnrichmentCoordinator = PlayerMetadataEnrichmentCoordinator(
        scope = scope,
        callbacks = PlayerMetadataEnrichmentCoordinator.Callbacks(
            isUsbCriticalStartup = ::isUsbCriticalStartup,
            currentSong = { _currentSong.value },
            setCurrentSong = { _currentSong.value = it },
            currentQueue = { _queue.value },
            setQueue = { _queue.value = it },
            enrichRemoteCachedSong = remoteCachedMediaEnricher::enrich,
            logInfo = { message -> AppLogger.i(TAG, message) },
        ),
    )
    private val duplicatePlayRequestGate = PlayerDuplicatePlayRequestGate()
    private val latestPlayRequestToken = AtomicLong(0L)
    private val transportControlCoordinator = PlayerTransportControlCoordinator(
        eventQueue = PlaybackEventQueueTransportAdapter(eventQueue),
        transportMutex = transportMutex,
        latestPlayRequestToken = latestPlayRequestToken,
        callbacks = PlayerTransportControlCoordinator.Callbacks(
            isReleased = { isReleased },
            clearAutomaticFocusResume = ::clearAutomaticFocusResume,
            resolveExplicitPlayQueue = ::resolveExplicitPlayQueue,
            onExplicitQueueSelection = ::applyExplicitShuffleSelectionPolicy,
            primeSongSelectionForUi = ::primeSongSelectionForUi,
            shouldRouteExplicitPlayThroughManualSwitch = ::shouldRouteExplicitPlayThroughManualSwitch,
            playManualSwitchFromStartLocked = ::playManualSwitchFromStartLocked,
            playInternal = ::playInternal,
            backendState = {
                when (ffmpegPlayer.state) {
                    FfmpegAudioPlayer.State.IDLE -> PlayerTransportControlCoordinator.BackendState.IDLE
                    FfmpegAudioPlayer.State.PREPARING -> PlayerTransportControlCoordinator.BackendState.PREPARING
                    FfmpegAudioPlayer.State.PLAYING -> PlayerTransportControlCoordinator.BackendState.PLAYING
                    FfmpegAudioPlayer.State.PAUSED -> PlayerTransportControlCoordinator.BackendState.PAUSED
                    FfmpegAudioPlayer.State.STOPPED -> PlayerTransportControlCoordinator.BackendState.STOPPED
                    FfmpegAudioPlayer.State.ERROR -> PlayerTransportControlCoordinator.BackendState.ERROR
                    FfmpegAudioPlayer.State.COMPLETED -> PlayerTransportControlCoordinator.BackendState.COMPLETED
                }
            },
            backendStateAgeMs = { ffmpegPlayer.stateAgeMs },
            backendStateSummary = {
                "state=${ffmpegPlayer.state} playState=${_playState.value} " +
                    "nativeState=${sharedUsbAudioEngine.getNativeStreamState()}"
            },
            resolvePlayPauseSeedSong = ::resolvePlayPauseSeedSong,
            hasPausedSelectionPendingStart = { pausedSelectionPendingStart },
            transitionPlayState = { target, reason ->
                smTransition(target, reason)
                Unit
            },
            forcePlayState = ::smForceTransition,
            isUsbExclusiveActive = { _usbExclusiveActive.value },
            controllerPlayState = { _playState.value },
            pauseUsbWarmInternal = ::pauseUsbWarmInternal,
            pauseSystemImmediateUi = ::pauseSystemImmediateUi,
            pauseSystemBackendInternal = ::pauseSystemBackendInternal,
            markAppForegroundForResume = { appInBackground = false },
            resumeInternal = ::resumeInternal,
            stopInternal = ::stopInternal,
            logWarn = { message -> AppLogger.w(TAG, message) },
        ),
    )
    private val resumeCoordinator by lazy {
        PlayerResumeCoordinator(
            scope = scope,
            tag = TAG,
            isReleased = { isReleased },
            isUsbExclusiveActive = { _usbExclusiveActive.value },
            nativeStreamState = { sharedUsbAudioEngine.getNativeStreamState() },
            nativeSessionBroken = { sharedUsbAudioEngine.isNativeSessionBroken() },
            resumeUsbWritesAfterPause = { reason ->
                sharedUsbAudioEngine.resumeWritesAfterPause(reason)
            },
            hasCurrentSong = { _currentSong.value != null },
            recoverUsbExclusiveAsync = ::recoverUsbExclusiveAsync,
            ffmpegResume = { ffmpegPlayer.resume() },
            isUsbHardwareVolumeRouteActive = ::isUsbHardwareVolumeRouteActive,
            transitionPlayState = { state, reason -> smTransition(state, reason) },
            startUsbKeepAlive = { reason -> usbSystemAudioKeepAlive.start(reason) },
            startProgressUpdate = ::startProgressUpdate,
            resumeUsbSessionEnvelope = { reason ->
                fadeUsbExclusiveSessionTo(
                    target = 1.0f,
                    fadeMs = TransitionPreferences.transportDurationOrZero(),
                    reason = reason,
                    waitForEnvelope = false,
                )
            },
            resetPreparedUsbSession = {
                sharedUsbAudioEngine.resetSessionForPlayback("user_resume_prepared")
            },
            isRenderSwitching = { _isRenderSwitching.value },
            clearRenderSwitching = { _isRenderSwitching.value = false },
            resumeSystemAudio = ::resumeSystemAudio,
        )
    }


    private val seekControlCoordinator: PlayerSeekControlCoordinator = PlayerSeekControlCoordinator(
        eventQueue = PlaybackEventQueueSeekAdapter(eventQueue),
        callbacks = PlayerSeekControlCoordinator.Callbacks(
            isReleased = { isReleased },
            currentSong = { _currentSong.value },
            armPreviousRestartBypass = { queueControlCoordinator.armPreviousRestartBypass() },
            isPaused = {
                _playState.value == PlayState.PAUSED ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED
            },
            setDisplayedPosition = { _position.value = it },
            markSeekPerformed = playbackProgressController::markSeekPerformed,
            isSameSongIdentity = ::isSameSongIdentity,
            executePausedSeekRequest = ::executePausedSeekRequest,
            executeSeekRequest = ::executeSeekRequest,
            logInfo = { message -> AppLogger.i(TAG, message) },
        ),
    )

    private val deferredUsbSeekCoordinator = UsbDeferredSeekCoordinator(
        scope = scope,
        callbacks = UsbDeferredSeekCoordinator.Callbacks(
            isReleased = { isReleased },
            currentSong = { _currentSong.value },
            isSameSong = ::isSameSongIdentity,
            isRuntimeReady = ::isUsbSeekRuntimeReady,
            executeSeek = { realSeekMs, displaySeekMs, keepPaused ->
                transportMutex.withLock {
                    seekUsbExclusiveInternal(realSeekMs, displaySeekMs, keepPaused)
                }
            },
            retainPendingSeek = { song, realSeekMs ->
                pendingSeekPosition = realSeekMs
                pendingSeekPath = song.path
            },
            logInfo = { message -> AppLogger.i(TAG, message) },
            logWarning = { message -> AppLogger.w(TAG, message) },
        ),
    )

    /** 切歌/暂停进行中，禁止 applyUsbVolume 写入 */
    @Volatile
    private var transportTransitioning = false

    private var cachedCueOffsetMs = 0L
    private var cachedCueEndMs = 0L
    private var cachedSongDuration = 0L
    /** 恢复播放位置：当 restoreLastSong() 恢复了位置后，在下次播放同一首歌时自动 seek */
    private var pendingSeekPosition: Long = -1L
    private var pendingSeekPath: String? = null
    private var pendingRestoreSeekJob: kotlinx.coroutines.Job? = null
    private var restoreSeekVerificationJob: kotlinx.coroutines.Job? = null

    // USB policy restart deferral: self-test/learned-policy should not hard-stop
    // playback while streaming.  Mark dirty and apply on next legitimate restart.
    @Volatile
    private var pendingUsbPolicyRestart = false
    @Volatile
    private var pendingUsbRecoveryPlan: UsbRecoveryPlan? = null
    @Volatile
    private var pcmToDsdSafetyBlocked = false

    private val usbDeviceStatusCoordinator by lazy {
        UsbDeviceStatusCoordinator(
            UsbDeviceStatusCoordinator.Callbacks(
                engine = sharedUsbAudioEngine,
                manager = usbExclusiveManager,
                player = ffmpegPlayer,
                currentSong = { _currentSong.value },
                sourceIsDsd = ::currentSongIsDsdSource,
                sourceDsdRate = ::currentSongDsdSourceRate,
                effectiveDsdMode = ::currentEffectiveUsbDsdMode,
                effectiveDsdRate = ::currentEffectiveUsbDsdRate,
                devicePolicyKey = { currentUsbDevice?.let(::usbLearnedPolicyKeyFor) },
                exclusiveActive = { _usbExclusiveActive.value },
                playbackModeName = ::getUsbPlaybackModeName,
                recoveryDiagnostics = {
                    pendingUsbRecoveryPlan?.let { plan ->
                        "pending action=${plan.action}, reason=${plan.reason}, disableFb=${plan.disableFeedback}, " +
                            "disableClock=${plan.disableClockSet}, disableFU=${plan.disableFeatureUnit}, " +
                            "force1ms=${plan.force1msPacket}, safeAlt=${plan.preferSafeAlt}, fullReopen=${plan.forceFullReopen}"
                    } ?: "none"
                },
            ),
        )
    }

    private val usbBackgroundGuardCoordinator by lazy {
        UsbBackgroundGuardCoordinator(
            UsbBackgroundGuardCoordinator.Callbacks(
                engine = sharedUsbAudioEngine,
                player = ffmpegPlayer,
                playState = { _playState.value },
                exclusiveActive = { _usbExclusiveActive.value },
                appInBackground = { appInBackground },
                isReleased = { isReleased },
            ),
        )
    }


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
        val safeDisplayPositionMs = displayPositionMs.coerceAtLeast(0L)
        val realPositionMs = if (song.cueOffsetMs > 0L) {
            song.cueOffsetMs + safeDisplayPositionMs
        } else {
            safeDisplayPositionMs
        }

        // A renderer restart has two owners which must carry the same timeline:
        // 1) FfmpegAudioPlayer needs the real decoder seek before the new renderer starts.
        // 2) PlayerController/Compose must keep displaying the pre-cutover position until
        //    renderer progress resumes.  The old implementation only armed (1), then
        //    playInternal() reset _position to 0 because this controller latch had already
        //    been cleared.  That produced audio at e.g. 2:00 with UI counting from 0:00.
        pendingSeekPosition = if (safeDisplayPositionMs > 0L) safeDisplayPositionMs else -1L
        pendingSeekPath = if (safeDisplayPositionMs > 0L) song.path else null
        ffmpegPlayer.queueStartSeekPosition(
            positionMs = if (realPositionMs > 0L) realPositionMs else -1L,
            reason = reason
        )
        AppLogger.i(
            TAG,
            "queueRendererRestartSeek: reason=$reason song=${song.title} display=$safeDisplayPositionMs " +
                "real=$realPositionMs cue=${song.cueOffsetMs} controllerLatch=${pendingSeekPosition}"
        )
    }

    private fun verifyRestoreStartSeek(song: AudioFile, displayPositionMs: Long) {
        restoreSeekVerificationJob?.cancel()
        restoreSeekVerificationJob = scope.launch {
            val realPositionMs = if (song.cueOffsetMs > 0L) {
                song.cueOffsetMs + displayPositionMs
            } else {
                displayPositionMs
            }
            repeat(12) { attempt ->
                delay(120L)
                val current = _currentSong.value
                if (!isSameSongIdentity(current, song)) return@launch
                val enginePosition = ffmpegPlayer.positionMs
                AppLogger.i(
                    TAG,
                    "RESTORE_TRACE verify attempt=${attempt + 1} engine=$enginePosition target=$realPositionMs state=${ffmpegPlayer.state}"
                )
                if (kotlin.math.abs(enginePosition - realPositionMs) <= 1_500L) {
                    AppLogger.i(TAG, "restore start seek verified: attempt=${attempt + 1} pos=$enginePosition")
                    return@launch
                }
                if (ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
                ) {
                    AppLogger.w(
                        TAG,
                        "restore start seek retry: attempt=${attempt + 1} engine=$enginePosition target=$realPositionMs"
                    )
                    ffmpegPlayer.seekTo(realPositionMs)
                    _position.value = displayPositionMs.coerceAtLeast(0L)
                    playbackProgressController.markSeekPerformed()
                    return@launch
                }
            }
        }
    }

    private fun persistSelectedSongForColdStart(song: AudioFile) {
        statePersistence.saveSongSnapshot(song)
        AppPreferences.Player.currentQueueIndex = _queue.value.currentIndex
    }

    private fun clearPlayRequestDedup(reason: String) {
        AppLogger.i(TAG, "clearPlayRequestDedup: reason=$reason")
        duplicatePlayRequestGate.clear()
    }



    private fun canUseUsbSoftNextFor(nextSong: AudioFile): Boolean {
        val cur = _currentSong.value ?: return false
        if (!_usbExclusiveActive.value || _playState.value != PlayState.PLAYING) return false
        if (!sharedUsbAudioEngine.isRunning()) return false
        val profile = resolveCurrentUsbOutputProfile()
        val sameDeviceRoute = profile?.volumePath != null
        // The library/source metadata is not the live renderer geometry.
        // A different source rate/bit depth can still be prepared into the exact active USB
        // PCM format. UsbSameProfileTrackSwitchCoordinator is the authoritative gate because
        // it compares the already-opened decoder's *actual* output format with the feeder.
        // Software-vs-hardware volume is deliberately not a stop/reopen condition.
        val ok = sameDeviceRoute
        AppLogger.i(
            TAG,
            "canUseUsbSoftNextFor: attempt=$ok sourceCur=${cur.sampleRate}/${cur.bitsPerSample}/${cur.channelCount} " +
                "sourceNext=${nextSong.sampleRate}/${nextSong.bitsPerSample}/${nextSong.channelCount} " +
                "route=${profile?.volumePath}; actual prepared-decoder format is authoritative"
        )
        return ok
    }

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
        val bitPerfectPolicy: String,
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
        val pcmInputDiagnostics: String = "",
        val featureUnitDiagnostics: String = "",
        val profileDiagnostics: String = "",
        val recoveryDiagnostics: String = "",
        val nativeStatsRaw: String = ""
    )

    // FFmpeg 播放器回调
    private val playerListener = object : FfmpegAudioPlayer.Listener {
        override fun onStateChanged(state: FfmpegAudioPlayer.State) {
            if (isReleased) return
            // Online portal must observe COMPLETED before the global local-queue repeat policy runs.
            musicSourcePlaybackBackend.onRendererStateChanged(state)
            scope.launch {
                val currentBackendState = ffmpegPlayer.state
                if (state != currentBackendState) {
                    // Renderer callbacks can originate on retiring decoder/feeder threads. By the
                    // time Main consumes PAUSED/STOPPED, a replacement track may already be
                    // PLAYING. Never let that stale terminal callback overwrite the live transport
                    // state shown by the playback bars or stop their progress clock.
                    AppLogger.w(
                        TAG,
                        "Ignoring stale backend state callback=$state current=$currentBackendState",
                    )
                    return@launch
                }
                backendStateControlCoordinator.onStateChanged(currentBackendState.toBackendControlState())
            }
        }

        override fun onPositionChanged(positionMs: Long, durationMs: Long, trackPath: String?) {
            if (cachedCueEndMs > 0) return
            val committedPath = _currentSong.value?.path
            if (trackPath != null && committedPath != null && trackPath != committedPath) {
                // A retiring decoder can publish one last clock tick after the handoff. Reference
                // never lets that old timeline overwrite the committed queue cursor.
                AppLogger.d(
                    TAG,
                    "Ignoring stale position callback path=$trackPath committed=$committedPath " +
                        "position=$positionMs duration=$durationMs",
                )
                return
            }
            // Renderer callbacks run at the PCM/block cadence. Feeding that cadence straight into
            // the public StateFlows wakes MainActivity/Compose independently of the UI clock and
            // makes playback compete with every scene transition. Keep renderer time private here;
            // PlaybackProgressController is the single fixed-rate publisher for _position/_duration.
            musicSourcePlaybackBackend.onPositionChanged(positionMs, durationMs)
        }

        override fun onError(message: String) {
            lastPlayerError = message
            musicSourcePlaybackBackend.onRendererError(message)
            scope.launch {
                Log.e(TAG, "FfmpegAudioPlayer error: $message")
            }
        }

        override fun onTrackStarted(newPath: String, positionMs: Long, durationMs: Long) {
            if (isReleased) return
            PlayerSwitchTrace.mark(
                "renderer_track_started_callback",
                "pathHash=${newPath.hashCode().toString(16)} pos=$positionMs duration=$durationMs thread=${Thread.currentThread().name}",
            )
            // TRACK_STARTED can arrive on the decoder/feeder boundary. Queue persistence, debug
            // trace reset, artwork identity and the follow-up gapless preparation are all safe
            // off-main; running them on the Main.immediate controller scope made the first visual
            // frame compete with file/native work.
            scope.launch(Dispatchers.Default) {
                PlayerSwitchTrace.mark(
                    "renderer_commit_worker_enter",
                    "thread=${Thread.currentThread().name}",
                )
                transportMutex.withLock {
                    val deferred = pendingRendererUiCommit
                    val latestRequested = uiSelectionControlCoordinator.requestedSong()

                    // TRACK_STARTED is the renderer-owned commit boundary. Never discard that
                    // boundary merely because a URI/path has a different textual representation.
                    // If a newer tap exists and this callback belongs to the retiring request, keep
                    // waiting for the newer renderer item without destroying its commit context.
                    if (latestRequested != null && !rendererSourceMatches(latestRequested.path, newPath)) {
                        PlayerSwitchTrace.mark(
                            "renderer_commit_ignored_retiring",
                            "new=${newPath.hashCode().toString(16)} latest=${latestRequested.path.hashCode().toString(16)}",
                        )
                        AppLogger.w(
                            TAG,
                            "TRACK_STARTED ignored retiring target path=$newPath " +
                                "pending=${deferred?.song?.path} latest=${latestRequested.path}",
                        )
                        return@withLock
                    }

                    val oldIndex = _queue.value.currentIndex
                    val oldSong = _currentSong.value
                    val songs: List<AudioFile>
                    val newIndex: Int
                    val song: AudioFile
                    val rendererDeferred = deferred?.takeIf {
                        rendererSourceMatches(it.song.path, newPath)
                    }
                    if (rendererDeferred != null) {
                        songs = rendererDeferred.queue
                        newIndex = rendererDeferred.index
                            .takeIf { it in songs.indices && samePlaybackItem(songs[it], rendererDeferred.song) }
                            ?: songs.indexOfFirst { samePlaybackItem(it, rendererDeferred.song) }
                        if (newIndex < 0) {
                            AppLogger.e(
                                TAG,
                                "TRACK_STARTED deferred queue lost renderer item path=$newPath " +
                                    "queueSize=${songs.size} requestedIndex=${rendererDeferred.index}",
                            )
                            return@withLock
                        }
                        song = songs[newIndex]
                    } else {
                        if (deferred != null) {
                            AppLogger.w(
                                TAG,
                                "TRACK_STARTED ignored while waiting for deferred target " +
                                    "path=$newPath expected=${deferred.song.path}",
                            )
                            return@withLock
                        }

                        songs = _queue.value.songs
                        val requestedIndex = latestRequested?.let { requested ->
                            songs.indexOfFirst { samePlaybackItem(it, requested) }
                        } ?: -1
                        newIndex = requestedIndex.takeIf { it >= 0 }
                            ?: songs.indexOfFirst { rendererSourceMatches(it.path, newPath) }
                        if (newIndex < 0) {
                            AppLogger.w(TAG, "TRACK_STARTED renderer item missing from queue path=$newPath")
                            return@withLock
                        }
                        song = songs[newIndex]
                    }

                    // One renderer-confirmed snapshot. StateFlow equality suppresses unchanged
                    // fields, while all consumers observe the same queue/song identity.
                    _queue.value = _queue.value.copy(songs = songs, currentIndex = newIndex)
                    pendingRendererUiCommit = null
                    clearPlaybackSourceOverrideIfOwnerChanged(song, "track_started")
                    _currentSong.value = song
                    _position.value = positionMs.coerceAtLeast(0L)
                    _duration.value = durationMs.takeIf { it > 0L } ?: song.duration
                    cachedCueOffsetMs = song.cueOffsetMs
                    cachedCueEndMs = song.cueEndMs
                    cachedSongDuration = song.duration
                    queueControlCoordinator.onManualTargetCommitted(song)
                    persistSelectedSongForColdStart(song)
                    clearRequestedSongIfCommitted(song)
                    AppPreferences.Player.lastPosition = _position.value
                    smForceTransition(PlayState.PLAYING, "native_track_started")
                    PlayerSwitchTrace.mark(
                        "renderer_ui_commit",
                        "oldIndex=$oldIndex newIndex=$newIndex songId=${song.id} title=${song.title} pos=${_position.value}",
                    )
                    if (oldSong == null || !samePlaybackItem(oldSong, song)) {
                        AppLogger.markPlaybackReportStart(
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            path = song.path,
                            cueOffsetMs = song.cueOffsetMs,
                        )
                        FFmpegBridge.resetDebugLog("native_track_started:${song.path}")
                        playbackStatsTracker.reset()
                        autoTransitionLyrics = null
                        ffmpegPlayer.updateAutoTransitionRecipe(null)
                    }

                    if (rendererDeferred != null) {
                        // Manual renderer-confirmed commits deliberately skipped presentation and
                        // queue-dependent startup work while the target decoder was opening. Run
                        // it only after the target owns the public current/queue snapshot.
                        metadataEnrichmentCoordinator.start(
                            song = song,
                            remoteUrl = song.path.startsWith("http://") || song.path.startsWith("https://"),
                        )
                        if (rendererDeferred.prepareFollowingAfterCommit) {
                            gaplessControlCoordinator.prepareNextSong()
                            precacheNextSong()
                        }
                        saveState()
                    }
                    AppLogger.i(
                        TAG,
                        "TRACK_STARTED committed UI index=$oldIndex->$newIndex " +
                            "position=$positionMs duration=${_duration.value} path=$newPath",
                    )
                    // Two native slots are still occupied until the old track retires.
                    // Preparing next-next here would replace the in-flight pending decoder.
                    // onGaplessSongChanged(HANDOFF_COMMITTED) owns the next prewarm.
                }
            }
        }

        override fun onGaplessSongChanged(
            newPath: String,
            positionMs: Long?,
            durationMs: Long?,
        ) {
            if (isReleased) return
            // The gapless handoff also performs queue/state persistence and prepares the next
            // decoder. Keep it on the transport worker so the acknowledged holder swap cannot
            // block Compose's next frame.
            scope.launch(Dispatchers.Default) {
                transportMutex.withLock {
                    val songs = _queue.value.songs
                    val curIdx = _queue.value.currentIndex
                    val requested = uiSelectionControlCoordinator.requestedSong()
                    val requestedIndex = requested
                        ?.takeIf { rendererSourceMatches(it.path, newPath) }
                        ?.let { target -> songs.indexOfFirst { samePlaybackItem(it, target) } }
                        ?: -1
                    val newIndex = requestedIndex.takeIf { it >= 0 }
                        ?: songs.indexOfFirst { rendererSourceMatches(it.path, newPath) }
                    if (newIndex >= 0) {
                        val nextSong = songs[newIndex]
                        if (newIndex != curIdx) {
                            Log.d(TAG, "Gapless: song changed, index $curIdx -> $newIndex")
                            _queue.value = _queue.value.copy(currentIndex = newIndex)
                            clearPlaybackSourceOverrideIfOwnerChanged(nextSong, "gapless_song_changed")
                            AppLogger.markPlaybackReportStart(
                                title = nextSong.title,
                                artist = nextSong.artist,
                                album = nextSong.album,
                                path = nextSong.path,
                                cueOffsetMs = nextSong.cueOffsetMs
                            )
                            FFmpegBridge.resetDebugLog("gapless_playback_report_start:${nextSong.path}")
                            playbackStatsTracker.reset()
                            autoTransitionLyrics = null
                            ffmpegPlayer.updateAutoTransitionRecipe(null)
                        } else {
                            // Manual same-profile USB switching commits queue/UI state before
                            // the feeder swaps decoders. The callback still has to prepare the
                            // following track after the consumed next request was cleared.
                            AppLogger.d(TAG, "Gapless: feeder confirmed already-selected index=$newIndex path=$newPath")
                        }
                        // Keep identity and timeline together even when TRACK_STARTED was
                        // already delivered earlier in the overlap. The explicit values are the
                        // renderer's target-track clock at the handoff boundary; the fallback is
                        // only for legacy callers that do not provide a boundary snapshot.
                        val alreadyPublishedCurrent = _currentSong.value
                            ?.let { samePlaybackItem(it, nextSong) } == true
                        if (!alreadyPublishedCurrent) {
                            _currentSong.value = nextSong
                        } else {
                            PlayerSwitchTrace.mark(
                                "gapless_current_song_duplicate_suppressed",
                                "songId=${nextSong.id} index=$newIndex",
                            )
                        }
                        queueControlCoordinator.onManualTargetCommitted(nextSong)
                        pendingRendererUiCommit
                            ?.takeIf { samePlaybackItem(it.song, nextSong) }
                            ?.let { pendingRendererUiCommit = null }
                        clearRequestedSongIfCommitted(nextSong)
                        persistSelectedSongForColdStart(nextSong)
                        if (positionMs != null) {
                            _position.value = positionMs.coerceAtLeast(0L)
                        }
                        val committedDuration = durationMs?.takeIf { it > 0L }
                            ?: ffmpegPlayer.durationMs.takeIf { it > 0L }
                            ?: nextSong.duration
                        if (committedDuration > 0L) {
                            _duration.value = committedDuration
                        }
                        gaplessControlCoordinator.prepareNextSong()
                    }
                }
            }
        }
    }

    init {
        Log.i(TAG, "PlayerController created: ${System.identityHashCode(this)}")
        ffmpegPlayer.listener = playerListener
        ffmpegPlayer.onPcmWaveformFrame = { buffer, read, channels, sampleRate, validBitsPerSample, sampleEncoding ->
            onPcmWaveformFrame?.invoke(
                buffer,
                read,
                channels,
                sampleRate,
                validBitsPerSample,
                sampleEncoding
            )
        }
        ffmpegPlayer.onAndroidUsbAudioRouteAdded = {
            AppLogger.i(TAG, "Android USB audio route callback: wait for USB attach/explicit user action before permission")
        }
        initDspPipeline()
        restoreState()
        scope.launch {
            kotlinx.coroutines.yield()
            if (!isReleased) {
                applyVolumeRoute("controller_init_deferred")
            }
        }
        androidAudioInterruptionController.start()
        usbExclusiveManager.onDeviceAttached = { device ->
            usbDetachToastShownForCurrentDetach.set(false)
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
        
        // Initialize HID key event listener for USB remote control support.
        hidRemoteController.start()
        
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
     * Get next song from queue
     */
    private fun getNextSong(): AudioFile? {
        return QueueNavigationPolicy.next(_queue.value)
    }
    
    /**
     * Get previous song from queue
     */
    private fun getPreviousSong(): AudioFile? {
        return QueueNavigationPolicy.previous(_queue.value)
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
                // Cold launch path: never open a permission dialog from startup. If Android
                // already retains permission and the user previously requested exclusive output,
                // re-arm only the renderer policy after the first UI frame. The real USB connection
                // and native handle remain lazy until playback prepares its first stream.
                usbExclusiveManager.rememberDeviceOnly(device, reason = "startup_deferred_scan")
                currentUsbDevice = device
                if (
                    AppPreferences.Player.usbExclusiveRequested &&
                    usbExclusiveManager.hasPermission(device) &&
                    !usbHardwareRecoveryBlocked &&
                    !UsbHardwareVolumeStore.isRecoveryBlocked()
                ) {
                    scheduleAuthorizedUsbColdStartRearm(
                        device = device,
                        reason = "startup_authorized_device",
                    )
                } else if (AppPreferences.Player.usbExclusiveRequested) {
                    AppLogger.i(
                        TAG,
                        "USB cold-start rearm deferred: permission=${usbExclusiveManager.hasPermission(device)} " +
                            "recoveryBlocked=$usbHardwareRecoveryBlocked device=${device.deviceName}",
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
            sharedUsbAudioEngine.resetUsbPolicyForNewDevice()
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
    ): Int = UsbPcmFormatScoring.score(
        format = format,
        targetRate = targetRate,
        targetBits = targetBits,
        targetSubslot = targetSubslot,
        targetChannels = targetChannels,
        pcmMode = pcmMode
    )

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
        val transportKey = currentUsbTransportKeyOrNull()
        val rejectedInCurrentSession = transportKey != null && transportKey == usbFeedbackRejectedTransportKey
        val pendingNoFeedback = pendingPlan?.disableFeedback == true ||
            (pendingPlan?.preferLastGoodProfile == true && learned?.lastGoodNoFeedback == true)
        val learnedNoFeedback = learned?.noFeedback == true ||
            (learned?.successCount ?: 0) > 0 && learned?.lastGoodNoFeedback == true
        val noFeedback = descriptorNoFeedback || rejectedInCurrentSession || pendingNoFeedback || learnedNoFeedback
        val reason = when {
            pendingNoFeedback -> "runtime recovery disabled an unstable feedback endpoint"
            rejectedInCurrentSession -> "feedback endpoint rejected for the current transport: $usbFeedbackRejectedReason"
            learnedNoFeedback -> "accepted last-good profile uses fixed no-feedback pacing"
            descriptorNoFeedback && modeledFormat?.feedbackEndpoint == 0 -> "descriptor has no feedback endpoint"
            descriptorNoFeedback -> "descriptor feedback is not explicit/eligible"
            else -> "descriptor explicit feedback eligible"
        }
        return UsbFeedbackModelDecision(
            noFeedback = noFeedback,
            reason = reason,
            format = modeledFormat
        )
    }

    private fun stopUsbExclusiveAfterFatalFailure(
        reason: String,
        releaseManager: Boolean = true,
        notifyNativeDetached: Boolean = true
    ) {
        if (!usbFatalStopInProgress.compareAndSet(false, true)) {
            AppLogger.w(TAG, "USB fatal stop already in progress; ignore duplicate reason=$reason")
            return
        }
        AppLogger.e(TAG, "USB exclusive fatal failure: reason=$reason; stop playback without Android fallback")

        pendingUsbRecoveryPlan = null
        pendingUsbPolicyRestart = false
        recoveringUsb.set(false)

        scope.launch(Dispatchers.IO) {
            try {
                // Fatal paths must obey the same real-Runnable barrier as ordinary
                // close/recovery. Calling stop() and immediately releasing from Main
                // reintroduced the exact writer-vs-nativeClose race this guard fixes.
                val rendererDrained = runCatching {
                    ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
                }.onFailure {
                    AppLogger.w(TAG, "USB fatal stop/drain failed: reason=$reason", it)
                }.getOrDefault(false)

                val canReleaseManager = releaseManager && rendererDrained
                val canNotifyDetached = notifyNativeDetached && rendererDrained
                if (releaseManager && !rendererDrained) {
                    AppLogger.e(
                        TAG,
                        "USB fatal path retained native session: playback Runnable did not exit reason=$reason"
                    )
                }

                withContext(Dispatchers.Main) {
                    runCatching {
                        clearUsbExclusiveState(
                            releaseManager = canReleaseManager,
                            notifyNativeDetached = canNotifyDetached
                        )
                    }
                    _isRenderSwitching.value = false
                    _usbExclusiveActive.value = false
                    ffmpegPlayer.usbExclusiveMode = false
                    sharedUsbAudioEngine.nativeSetUsbExclusiveActive(false)
                    smTransition(PlayState.PAUSED, "usb_exclusive_fatal_failure")
                    postUsbStatusNotice(
                        "USB 独占输出启动失败，已停止播放，请导出诊断日志",
                        AppNoticeIcon.ERROR,
                    )
                }
            } finally {
                usbFatalStopInProgress.set(false)
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
    // 1. 非独占 → 用户音量只由 Android STREAM_MUSIC 管理；播放器增益仅承载 ReplayGain/duck
    // 2. 独占 + 无硬件音量 → 系统媒体音量映射成 USB 软件增益
    // 3. 独占 + 硬件音量 → USB Feature Unit，PCM gain = 1.0

    private typealias VolumeRoute = PlayerUsbVolumeRouteCoordinator.VolumeRoute

    private val volumeRouteCoordinator by lazy {
        PlayerUsbVolumeRouteCoordinator(
            context = context,
            engine = sharedUsbAudioEngine,
            callbacks = PlayerUsbVolumeRouteCoordinator.Callbacks(
                isUsbExclusiveActive = { _usbExclusiveActive.value },
                buildUsbOutputProfile = ::buildUsbOutputProfile,
                isUsbSeeking = { usbSeeking },
                isRenderSwitching = { _isRenderSwitching.value },
                ffmpegState = { ffmpegPlayer.state },
                explicitSoftwareMute = { explicitUsbExclusiveSoftwareMuteThisProcess },
                setExplicitSoftwareMute = { explicitUsbExclusiveSoftwareMuteThisProcess = it },
                applyComposedVolume = ::applyComposedVolume,
                applyUsbVolume = ::applyUsbVolume,
                configureTransitionGainOwner = { _, reason ->
                    usbTransitionGainCoordinator.clearToUnity("volume_route:$reason")
                },
                syncUsbRemoteVolumeRoute = ::syncUsbRemoteVolumeRoute,
                setNativeDvc = { enabled, gain, headroomDb ->
                    ffmpegPlayer.setAndroidDvc(enabled, gain, headroomDb)
                },
                setHardwareVolumeStep = ::setUsbHardwareVolumeStep,
                setHardwareVolumeUi = ::setUsbHardwareVolumeUi,
                shouldUseUsbRemoteVolume = ::shouldUseUsbRemoteVolume,
            ),
        )
    }
    private val mediaVolumeCoordinator by lazy {
        PlayerMediaVolumeCoordinator(
            resolveRoute = ::resolveVolumeRoute,
            forceFixedVolume = ::forceUsbFixedVolume0Db,
            enqueueHardwareAdjustment = ::enqueueUsbHardwareVolumeAdjustment,
            currentHardwareStep = ::currentUsbHwStep,
            setHardwareStep = ::setUsbHardwareVolumeStep,
            isUsbExclusiveSoftwareVolumeActive = ::isUsbExclusiveSoftwareVolumeActive,
            applyUsbExclusiveSoftwareUserVolume = ::applyUsbExclusiveSoftwareUserVolume,
            dvcIsActive = { usbExclusive -> androidDvcController.isActive(usbExclusive) },
            dvcAdjust = { delta, reason -> androidDvcController.adjust(delta, reason) },
            dvcSetLogicalVolume = { volume, reason -> androidDvcController.setLogicalVolume(volume, reason) },
            dvcLogicalVolume = { androidDvcController.logicalVolume() },
            systemVolumeController = ::systemVolumeController,
            getSystemMusicVolumeLinear = ::getSystemMusicVolumeLinear,
            setSystemMusicVolumeLinear = { linear -> setSystemMusicVolumeLinear(linear) },
            applyVolumeRoute = ::applyVolumeRoute,
            setUserVolume = ::setUserVolume,
            usbVolumeDb = { usbHardwareVolumeCoordinator.getVolumeDb() },
        )
    }
    private val usbAutoRearmCoordinator by lazy {
        PlayerUsbAutoRearmCoordinator(
            tag = TAG,
            callbacks = PlayerUsbAutoRearmCoordinator.Callbacks(
                isExclusiveActive = { _usbExclusiveActive.value },
                isEngineExclusive = { ffmpegPlayer.usbExclusiveMode },
                isPcmToDsdSafetyBlocked = { pcmToDsdSafetyBlocked },
                isPcmToDsdRequested = ::isPcmToDsdRequestedForCurrentSong,
                isRenderSwitching = { _isRenderSwitching.value },
                isRecovering = { recoveringUsb.get() },
                isHardwareRecoveryBlocked = { usbHardwareRecoveryBlocked },
                setHardwareRecoveryBlocked = { usbHardwareRecoveryBlocked = it },
                currentDevice = { currentUsbDevice },
                findDevice = { usbExclusiveManager.findUsbAudioDevice() },
                prefetchedDeviceId = { usbAttachPermissionPrefetchDeviceId },
                requested = { AppPreferences.Player.usbExclusiveRequested },
                lastExclusiveActive = { AppPreferences.Player.lastUsbExclusiveActive },
                hasPermission = { device -> usbExclusiveManager.hasPermission(device) },
                setCurrentDevice = { currentUsbDevice = it },
                rememberDevice = { device, reason -> usbExclusiveManager.rememberDeviceOnly(device, reason) },
                prepareColdActivation = ::prepareUsbExclusiveColdActivation,
                activate = ::activateUsbEngineForPlayback,
                clearPrefetchedDevice = { usbAttachPermissionPrefetchDeviceId = -1 },
            ),
        )
    }
    private val usbPolicyRestartCoordinator by lazy {
        PlayerUsbPolicyRestartCoordinator(
            tag = TAG,
            currentDevice = { currentUsbDevice },
            refreshRuntimeSnapshot = { sharedUsbAudioEngine.refreshRuntimeSnapshotFromNative() },
            resolveRestartSource = ::resolveUsbPolicyRestartSource,
            stopPlayback = { timeoutMs -> ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs) },
            transitionStopped = { smTransition(PlayState.STOPPED, "internal_stop") },
            stopStreaming = { usbExclusiveManager.stopStreaming("policy_change") },
            setNativeExclusive = { enabled -> sharedUsbAudioEngine.nativeSetUsbExclusiveActive(enabled) },
            setNativePolicy = { exclusive, bitPerfect, hardwareVolume ->
                sharedUsbAudioEngine.setPolicy(exclusive, bitPerfect, hwVol = hardwareVolume)
            },
            setFfmpegBitPerfect = { enabled -> ffmpegPlayer.usbBitPerfectMode = enabled },
            releaseUsb = { usbExclusiveManager.release("policy_change") },
            prepareForPlayback = { sampleRate, bits, channels, sourcePath ->
                usbExclusiveManager.prepareForPlayback(
                    sampleRate, bits, channels, sourcePath,
                    effectiveBitPerfect = ffmpegPlayer.usbBitPerfectMode,
                )
            },
            initializeHardwareVolume = ::initializeUsbHardwareVolumeForHandle,
            applyVolumeRoute = { applyVolumeRoute("restart_usb_with_policy") },
        )
    }

    private val androidDvcController: AndroidDvcController
        get() = volumeRouteCoordinator.androidDvcController

    private fun resolveCurrentUsbOutputProfile(): UsbOutputProfile? =
        volumeRouteCoordinator.resolveCurrentUsbOutputProfile()

    private fun resolveVolumeRoute(): VolumeRoute = volumeRouteCoordinator.resolveVolumeRoute()

    private fun systemVolumeController(): AndroidSystemVolumeController =
        volumeRouteCoordinator.systemVolumeController()

    private fun getSystemMusicVolumeLinear(): Float =
        volumeRouteCoordinator.getSystemMusicVolumeLinear()

    private fun setSystemMusicVolumeLinear(
        linear: Float,
        flags: Int = AudioManager.FLAG_SHOW_UI,
    ) {
        volumeRouteCoordinator.setSystemMusicVolumeLinear(linear, flags)
    }

    private fun suppressSystemVolumeObserver(windowMs: Long, reason: String) {
        volumeRouteCoordinator.suppressSystemVolumeObserver(windowMs, reason)
    }

    private fun keepUsbExclusiveSoftwareVolumeIsolated(reason: String) {
        volumeRouteCoordinator.keepUsbExclusiveSoftwareVolumeIsolated(reason)
    }

    private fun isUsbExclusiveSoftwareVolumeActive(): Boolean =
        volumeRouteCoordinator.isUsbExclusiveSoftwareVolumeActive()

    private fun normalizeUsbExclusiveSoftwareEntryVolume(systemLinear: Float, reason: String): Float =
        volumeRouteCoordinator.normalizeUsbExclusiveSoftwareEntryVolume(systemLinear, reason)

    private fun applyUsbExclusiveSoftwareUserVolume(linear: Float, reason: String) {
        volumeRouteCoordinator.applyUsbExclusiveSoftwareUserVolume(linear, reason)
    }

    fun refreshUsbSoftwareVolumeCurve(reason: String = "settings") {
        volumeRouteCoordinator.refreshUsbSoftwareVolumeCurve(reason)
    }

    private fun forceUsbFixedVolume0Db(reason: String) {
        volumeRouteCoordinator.forceUsbFixedVolume0Db(reason)
    }

    // ======================== 硬件音量 step/dB 工具 ========================
    // UI keeps a normalized range; hardware key adjustments use the DAC's real raw resolution.

    private val USB_SESSION_DEFAULT_FADE_MS = 80
    @Volatile private var pendingManualTrackStartFadeMs: Int = 0

    private fun consumePendingManualTrackStartFadeMs(): Int {
        val value = pendingManualTrackStartFadeMs
        pendingManualTrackStartFadeMs = 0
        return value.coerceAtLeast(0)
    }

    private fun usbExclusiveStartupFadeInMs(): Int {
        if (!usbTransitionGainCoordinator.canUseSessionEnvelope()) {
            pendingManualTrackStartFadeMs = 0
            return 0
        }
        val manualFadeMs = consumePendingManualTrackStartFadeMs()
        if (manualFadeMs > 0) return manualFadeMs
        return TransitionPreferences.transportDurationOrZero().takeIf { it > 0 } ?: USB_SESSION_DEFAULT_FADE_MS
    }

    private fun usbExclusiveManualTrackFadeMs(): Int {
        val configured = ManualTrackTransitionPolicy.durations(
            mode = TransitionPreferences.manualTrackTransitionMode,
            shortFadeMs = TransitionPreferences.shortManualFadeMs,
            fullCrossfadeMs = TransitionPreferences.fullManualCrossfadeMs,
        ).usbSequentialMs
        return UsbTrackBoundaryPolicy.resolveFadeMs(configured)
    }

    private val usbTransitionGainCoordinator by lazy {
        UsbTransitionGainCoordinator(
            tag = TAG,
            currentHandle = { sharedUsbAudioEngine.currentHandle },
            isSessionEnvelopeAllowed = { handle ->
                sharedUsbAudioEngine.isSessionVolumeEnvelopeAllowed(handle)
            },
            applySessionTransition = { handle, target, durationMs, reason ->
                sharedUsbAudioEngine.applySessionVolumeTransition(handle, target, durationMs, reason)
            },
        )
    }

    private suspend fun fadeUsbExclusiveSessionTo(
        target: Float,
        fadeMs: Int,
        reason: String,
        waitForEnvelope: Boolean = true
    ): Boolean {
        val bounded = fadeMs.coerceIn(0, 1000)
        if (bounded <= 0) return false
        return usbTransitionGainCoordinator.fadeTo(
            target = target,
            fadeMs = bounded,
            reason = reason,
            waitForEnvelope = waitForEnvelope,
        )
    }

    /**
     * Manual USB track-boundary attenuation with one owner per volume route.
     *
     * Hardware-volume sessions keep PCM at unity and may use a conservative UAC2 master-FU ramp.
     * Software-volume sessions retain the existing native SessionPcm envelope unchanged.
     */
    private suspend fun fadeUsbExclusiveTrackBoundary(
        fadeMs: Int,
        reason: String,
    ): Boolean {
        val bounded = fadeMs.coerceIn(0, 1000)
        if (bounded <= 0) return false
        if (isUsbHardwareVolumeRouteActive()) {
            val applied = runCatching {
                usbHardwareVolumeCoordinator.fadeOutForTrackBoundary(bounded, reason)
            }.onFailure {
                AppLogger.w(TAG, "USB hardware boundary fade failed reason=$reason", it)
            }.getOrDefault(false)
            AppLogger.i(
                TAG,
                "USB hardware boundary fade applied=$applied fadeMs=$bounded reason=$reason",
            )
            return applied
        }
        return fadeUsbExclusiveSessionTo(
            target = 0.0f,
            fadeMs = bounded,
            reason = reason,
        )
    }

    private fun currentUsbHwStep(): Int =
        usbHardwareVolumeCoordinator.currentStep()

    private fun enqueueUsbHardwareVolumeAdjustment(direction: Int, reason: String): Int {
        return usbHardwareVolumeCoordinator.enqueueAdjustment(direction, reason)
    }

    /**
     * One-time Feature Unit initialization for one physical DAC attachment session.
     * Native validation is read-only and runs before ISO starts. Restore the exact raw value saved
     * for this DAC, or use a conservative -30 dB default for a new device. Native-handle rebuilds
     * for track format changes/recovery must not emit another Feature Unit write.
     */
    private fun initializeUsbHardwareVolumeForHandle(device: UsbDevice, reason: String): Boolean {
        return usbHardwareVolumeCoordinator.initializeForHandle(device, reason)
    }

    /** 硬件音量写入：只写 dB，不写 linear */
    private fun setUsbHardwareVolumeStep(step: Int, reason: String): Int {
        return usbHardwareVolumeCoordinator.setStep(step, reason)
    }

    private fun setUsbHardwareVolumeUi(volume: Float, reason: String): Int {
        return usbHardwareVolumeCoordinator.setUiVolume(volume, reason)
    }

    private fun applyVolumeRoute(reason: String) {
        if (usbHardwareToSoftwareHandoffActive &&
            !reason.startsWith("hw_to_sw_commit") &&
            !reason.startsWith("hw_to_sw_rollback")
        ) {
            AppLogger.i(TAG, "applyVolumeRoute deferred during HW->SW handoff reason=$reason")
            return
        }
        volumeRouteCoordinator.applyVolumeRoute(reason)
    }

    /** 用户调节音量统一入口（UI 滑条 / +/- 按钮 / 后台音量键） */
    fun setUserVolume(linear: Float) {
        volumeRouteCoordinator.setUserVolume(linear)
    }

    // ======================== 系统音量观察器 ========================

    private fun unregisterSystemVolumeObserver() {
        volumeRouteCoordinator.unregisterSystemVolumeObserver()
    }

    private fun syncSystemVolumeObserverForRoute(reason: String) {
        volumeRouteCoordinator.syncSystemVolumeObserverForRoute(reason)
    }

    /** 真正切换到 USB 独占播放：设 exclusive policy → 释放 AudioTrack → 激活 USB 渲染器。 */
    private fun activateUsbEngineForPlayback(device: UsbDevice): Boolean {
        if (pcmToDsdSafetyBlocked && isPcmToDsdRequestedForCurrentSong()) {
            AppLogger.e(TAG, "USB activation blocked by PCM_TO_DSD safety fuse; disable PCM→DSD before retry")
            return false
        }
        prepareUsbDevice(device)

        val exclusive = true
        val bitPerfectPolicy = AppPreferences.Player.usbBitPerfectMode
        // Before the per-track resolver runs, only STRICT may pre-arm native strict mode.
        // WHEN_POSSIBLE starts permissive and is promoted only after an exact track/device
        // geometry has been proven.
        val bitPerfect = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
            bitPerfectPolicy,
            AppPreferences.Player.usbVolumeMode,
        )
        val hwVol = AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled

        ffmpegPlayer.usbBitPerfectPolicyMode = bitPerfectPolicy
        ffmpegPlayer.usbBitPerfectMode = bitPerfect
        sharedUsbAudioEngine.nativeSetUsbExclusiveActive(true)
        sharedUsbAudioEngine.setPolicy(exclusive, bitPerfect, hwVol)

        val profile = buildUsbOutputProfile(exclusive = true)
        applyUsbOutputProfile(profile)

        AppLogger.i(TAG, "activateUsbEngineForPlayback: exclusive=true bitPerfectPolicy=$bitPerfectPolicy initialStrict=$bitPerfect hwVol=$hwVol")

        // Keep AUDIOFOCUS_GAIN while the native USB path owns the DAC.
        // Rebuild the request after switching the output policy so the USB path
        // uses PAUSES_ON_DUCKABLE_LOSS and does not accept delayed focus.
        abandonAudioFocus()
        val usbFocusGranted = requestAudioFocus()
        AppLogger.i(TAG, "AudioFocus: USB activation granted=$usbFocusGranted")

        try {
            ffmpegPlayer.releaseAudioTrackForUsb()
        } catch (_: Exception) {}

        ffmpegPlayer.usbPrepareForPlayback = { sr, bits, ch, srcFilePath, effectiveBitPerfect ->
            AppLogger.i(TAG, "usbPrepareForPlayback callback: sr=$sr bits=$bits ch=$ch file=$srcFilePath effectiveBitPerfect=$effectiveBitPerfect policy=${AppPreferences.Player.usbBitPerfectMode}")
            val recoveryProfile = buildUsbOutputProfile(exclusive = true)
            applyUsbOutputProfile(recoveryProfile)
            AppLogger.i(TAG, "usbPrepareForPlayback applied recovery profile: $recoveryProfile")
            currentUsbDevice?.let { device ->
                usbExclusiveManager.rememberDeviceOnly(device, reason = "usb_prepare_callback")
            }
            val ok = usbExclusiveManager.prepareForPlayback(sr, bits, ch, srcFilePath, effectiveBitPerfect = effectiveBitPerfect)
            // prepareForPlayback performs the native Feature Unit probe. The profile built above
            // can therefore be stale on the first authorization and still say Software even when
            // native validation has just enabled the hardware path. Re-read the profile after the
            // probe so the persisted/safe raw value is always restored before ISO starts.
            val preparedProfile = if (ok) buildUsbOutputProfile(exclusive = true) else recoveryProfile
            AppLogger.i(
                TAG,
                "usbPrepareForPlayback prepared: ok=$ok " +
                    "beforePath=${recoveryProfile.volumePath} afterPath=${preparedProfile.volumePath} " +
                    "nativeValidated=${sharedUsbAudioEngine.isHardwareVolumeValidated()}",
            )
            val volumeReady = if (ok && preparedProfile.volumePath == UsbVolumePath.HardwareUserVolume) {
                initializeUsbHardwareVolumeForHandle(device, "usb_prepare_callback")
            } else {
                true
            }
            if (!ok || !volumeReady) {
                AppLogger.e(
                    TAG,
                    "USB prepare or hardware-volume initialization failed; stop exclusive playback without Android fallback"
                )
                stopUsbExclusiveAfterFatalFailure(
                    reason = if (!ok) "usb_prepare_failed" else "usb_hardware_volume_init_failed",
                    notifyNativeDetached = false
                )
            }
            ok && volumeReady
        }
        ffmpegPlayer.usbStartStreaming = { reason ->
            AppLogger.i(TAG, "usbStartStreaming callback: reason=$reason")
            usbExclusiveManager.startStreaming()
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
        ffmpegPlayer.onUsbPlaybackStarted = {
            AppLogger.i(TAG, "onUsbPlaybackStarted")
            usbDetachToastShownForCurrentDetach.set(false)
            if (isUsbHardwareVolumeRouteActive()) {
                UsbHardwareVolumeStore.markSessionActive(context, currentUsbDevice)
            }
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
            // Carrier lifecycle: create the system AudioTrack only after native USB start succeeds.
            usbSystemAudioKeepAlive.start("native_usb_stream_started")
            syncUsbRemoteVolumeRoute("usb_playback_started", force = true)
        }
        ffmpegPlayer.onUsbStreamHealthFailure = { kind, detail ->
            handleUsbStreamHealthFailure(kind, detail)
        }
        ffmpegPlayer.onUsbPlaybackDataFlowing = {
            AppLogger.i(TAG, "onUsbPlaybackDataFlowing: steady-state USB data accepted")
            usbExclusiveManager.setStreamingState(true)
            val profile = buildUsbOutputProfile(exclusive = true)
            // PlayerVolumeControlCoordinator applies only PCM-side modifiers here. A temporary
            // hardware boundary attenuation, when supported, is restored by its dedicated FU
            // owner and never enters the software/session gain chain.
            applyUsbVolume(profile, "usb_first_audio_flowing")
            if (isUsbHardwareVolumeRouteActive()) {
                usbHardwareVolumeCoordinator.restoreAfterTrackBoundary("usb_first_audio_flowing")
            }
            // Producer acceptance is not proof that the DAC consumed the stream. Persist the
            // profile only after completed ISO throughput and playback motion stay healthy.
            scheduleUsbSelfTest("usb_first_audio_flowing")
            val fadeMs = usbExclusiveStartupFadeInMs()
            usbTransitionGainCoordinator.onFirstData(
                fadeMs = fadeMs,
                reason = "usb_first_audio_flowing",
            )
        }
        ffmpegPlayer.onUsbPlaybackStopped = {
            usbSystemAudioKeepAlive.stop("native_usb_stream_stopped")
            usbExclusiveManager.setStreamingState(false)
        }
        ffmpegPlayer.usbExclusiveMode = true
        ffmpegPlayer.usbCapabilitiesProvider = { _usbCapabilities.value ?: sharedUsbAudioEngine.getDeviceCapabilities() }
        ffmpegPlayer.usbBitPerfectPolicyMode = AppPreferences.Player.usbBitPerfectMode
        ffmpegPlayer.usbBitPerfectMode = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
            AppPreferences.Player.usbBitPerfectMode,
            AppPreferences.Player.usbVolumeMode,
        )
        ffmpegPlayer.onBeforeUsbNativeStart = {
            val profile = buildUsbOutputProfile(exclusive = true)
            AppLogger.i(TAG, "onBeforeUsbNativeStart: selecting USB transition gain owner bitPerfect=${profile.bitPerfect} dsd=${profile.dsdSourceDirect || profile.dsdConversionEnabled || profile.dsdDoPEnabled}")
            applyVolumeRoute("before_native_start")
            // Configure exactly one transition-gain owner before ISO starts. Mutable PCM uses
            // the native session envelope; bit-perfect/hardware/fixed PCM stays at unity; raw
            // DSD/DoP/PCM-to-DSD uses transport-correct silence rather than sample multiplication.
            usbTransitionGainCoordinator.armBeforeNativeStart(
                reason = "before_native_start",
            )
        }
        _usbExclusiveActive.value = true
        androidSpatialPlaybackController.refreshRoutingState()
        AppPreferences.Player.lastUsbExclusiveActive = true
        syncUsbSystemAudioKeepAlive("usb_exclusive_enabled")

        // 切换 MediaSession 到 remote volume 控制（后台音量键可用）
        androidPlaybackServiceController.setUsbRemoteVolumeActive(
            active = true,
            reason = "usb_exclusive_enabled",
        )

        val shouldEnsureUsbForeground =
            _playState.value == PlayState.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        if (shouldEnsureUsbForeground) {
            androidPlaybackServiceController.ensureUsbPlaybackForeground(
                reason = "usb_exclusive_enabled",
            )
        } else {
            AppLogger.i(TAG, "USB exclusive armed while not playing; skip foreground playback assertion")
        }

        AppLogger.i(TAG, "USB exclusive playback engine activated: ${device.productName}")

        // 激活独占后仅压低软件 PCM 无数据窗口；硬件 Feature Unit 保持设备初始化值。
        applyUsbNoDataSafetyVolume("usb_exclusive_activated")
        return _usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode
    }

    @Volatile
    private var pendingEnableUsbExclusiveAfterPermission = false
    private var usbAttachPermissionPrefetchDeviceId = -1
    private var usbAttachPermissionPromptJob: Job? = null
    private var usbColdStartRearmJob: Job? = null
    private var usbPermissionActivationJob: Job? = null
    private var usbRenderSwitchReleaseJob: Job? = null
    private val usbDetachToastShownForCurrentDetach = AtomicBoolean(false)

    private fun postUsbStatusNotice(
        message: String,
        icon: AppNoticeIcon = AppNoticeIcon.USB,
    ) {
        // Do not publish directly from the service/application Context. The Android USB
        // permission sheet can temporarily stop the foreground Activity. The sticky bus keeps
        // the latest message until a resumed RawSMusic Activity forwards it into AppNoticeHost
        // and acknowledges it.
        AppLogger.i(TAG, "USB status notice queued: message=$message")
        UsbStatusNoticeBus.post(message, icon)
    }

    private fun scheduleUsbExclusiveActivation(
        device: UsbDevice,
        reason: String,
        showSuccessToast: Boolean,
        waitForPermissionDialogToClose: Boolean,
    ) {
        usbPermissionActivationJob?.cancel()
        // The success notice is emitted after the serialized activation task confirms that
        // the USB renderer is armed. Posting through UsbStatusNoticeBus is safe even while the
        // system permission sheet still covers MainActivity: the notice remains pending until
        // the Activity resumes. Do not defer this to onUsbPlaybackStarted, because an already
        // playing song may complete its USB cutover without emitting a second start callback.
        usbPermissionActivationJob = scope.launch {
            if (waitForPermissionDialogToClose) {
                // The system USB permission sheet can briefly move ProcessLifecycleOwner to STOPPED.
                // Do not convert that transient lifecycle state into a lost permission result.
                val foregroundDeadline = SystemClock.elapsedRealtime() + 1_800L
                while (
                    !isReleased &&
                    appInBackground &&
                    SystemClock.elapsedRealtime() < foregroundDeadline
                ) {
                    delay(50L)
                }
            }

            val activated = usbActivationMutex.withLock {
                if (isReleased || !AppPreferences.Player.usbExclusiveRequested) {
                    AppLogger.w(
                        TAG,
                        "USB_PERMISSION_ACTIVATION cancelled: reason=$reason released=$isReleased " +
                            "requested=${AppPreferences.Player.usbExclusiveRequested}",
                    )
                    return@withLock false
                }
                if (!usbExclusiveManager.hasPermission(device)) {
                    AppLogger.e(
                        TAG,
                        "USB_PERMISSION_ACTIVATION lost permission before activation: " +
                            "reason=$reason device=${device.deviceName}",
                    )
                    return@withLock false
                }

                // Open the authorized Android USB connection before transport initialization. Do
                // the same before stopping AudioTrack: if the framework cannot open this grant yet,
                // bounded retries happen off Main and normal Android playback remains intact.
                val authorizationReady = withContext(Dispatchers.IO) {
                    usbExclusiveManager.primeAuthorizedDevice(
                        device = device,
                        reason = "activation:$reason",
                    )
                }
                if (!authorizationReady) {
                    AppLogger.e(
                        TAG,
                        "USB_PERMISSION_ACTIVATION authorization lease failed before renderer cutover " +
                            "reason=$reason device=${device.deviceName}",
                    )
                    return@withLock false
                }

                currentUsbDevice = device
                usbExclusiveManager.rememberDeviceOnly(
                    device,
                    reason = "permission_activation:$reason",
                )

                var waitedMs = 0L
                while (
                    !isReleased &&
                    _isRenderSwitching.value &&
                    waitedMs < 4_000L
                ) {
                    delay(50L)
                    waitedMs += 50L
                }
                if (_isRenderSwitching.value && !recoveringUsb.get()) {
                    // Some old disable/cutover paths left this latch true permanently. A stale
                    // latch must not discard a newly granted Android USB permission forever.
                    AppLogger.e(
                        TAG,
                        "USB_PERMISSION_ACTIVATION clearing stale render-switch latch after " +
                            "${waitedMs}ms reason=$reason",
                    )
                    _isRenderSwitching.value = false
                }

                if (_usbExclusiveActive.value && ffmpegPlayer.usbExclusiveMode) {
                    AppLogger.i(TAG, "USB_PERMISSION_ACTIVATION already armed: reason=$reason")
                    true
                } else {
                    withContext(Dispatchers.IO) {
                        activateUsbExclusiveAfterPermission(
                            device = device,
                            reason = reason,
                        )
                    }
                }
            }

            if (activated) {
                AppLogger.i(
                    TAG,
                    "USB_PERMISSION_ACTIVATION success: reason=$reason " +
                        "active=${_usbExclusiveActive.value} playerUsb=${ffmpegPlayer.usbExclusiveMode}",
                )
                if (
                    showSuccessToast &&
                    _usbExclusiveActive.value &&
                    ffmpegPlayer.usbExclusiveMode
                ) {
                    AppLogger.i(
                        TAG,
                        "USB DAC activation completed; queue success notice: reason=$reason",
                    )
                    postUsbStatusNotice("USB 独占已启用")
                }
            } else if (!isReleased) {
                ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(
                    false,
                    "usb_permission_activation_failed:$reason",
                )
                AppLogger.e(
                    TAG,
                    "USB_PERMISSION_ACTIVATION failed: reason=$reason " +
                        "active=${_usbExclusiveActive.value} playerUsb=${ffmpegPlayer.usbExclusiveMode} " +
                        "switching=${_isRenderSwitching.value}",
                )
                postUsbStatusNotice(
                    "USB 独占启动失败，请查看 USB DAC 日志",
                    AppNoticeIcon.ERROR,
                )
            }
        }
    }

    /**
     * A granted USB permission survives an app-process restart. Re-arm through the same serialized
     * activation lane used by a fresh permission grant, but do not show a user-action toast.
     */
    private fun scheduleAuthorizedUsbColdStartRearm(device: UsbDevice, reason: String) {
        usbColdStartRearmJob?.cancel()
        usbColdStartRearmJob = scope.launch {
            delay(260L)
            if (
                isReleased ||
                !AppPreferences.Player.usbExclusiveRequested ||
                usbHardwareRecoveryBlocked ||
                UsbHardwareVolumeStore.isRecoveryBlocked() ||
                !usbExclusiveManager.hasPermission(device) ||
                !usbExclusiveManager.isDeviceConnected()
            ) {
                AppLogger.i(
                    TAG,
                    "USB cold-start rearm cancelled: reason=$reason released=$isReleased " +
                        "background=$appInBackground requested=${AppPreferences.Player.usbExclusiveRequested} " +
                        "blocked=$usbHardwareRecoveryBlocked permission=${usbExclusiveManager.hasPermission(device)} " +
                        "connected=${usbExclusiveManager.isDeviceConnected()}",
                )
                return@launch
            }
            scheduleUsbExclusiveActivation(
                device = device,
                reason = "cold_start_authorized_rearm:$reason",
                showSuccessToast = false,
                waitForPermissionDialogToClose = true,
            )
        }
    }

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
                    "requested=${AppPreferences.Player.usbExclusiveRequested} " +
                    "hasPerm=${target?.let { usbExclusiveManager.hasPermission(it) }}"
            )

            if (isReleased || target == null) {
                return@launch
            }

            val exclusiveRequested =
                AppPreferences.Player.usbExclusiveRequested || pendingEnableUsbExclusiveAfterPermission
            if (!exclusiveRequested) {
                // Merely plugging in a DAC must not open Android's USB permission sheet. The
                // permission boundary belongs to the explicit USB-exclusive route selection.
                // Remember the device so Settings can show it, then stay on Android output.
                usbExclusiveManager.rememberDeviceOnly(target, reason = "shared_route_attach:$reason")
                usbAttachPermissionPrefetchDeviceId = -1
                AppLogger.i(
                    TAG,
                    "USB attach permission skipped: shared Android route selected device=${target.deviceName}"
                )
                return@launch
            }

            val hasPermission = usbExclusiveManager.hasPermission(target)
            if (hasPermission) {
                // A remembered Android grant is already authorization. Re-arm the serialized
                // renderer lane without requiring another visit to Settings. This remains safe
                // in background because no permission sheet is opened here.
                if (
                    AppPreferences.Player.usbExclusiveRequested &&
                    !_usbExclusiveActive.value &&
                    !usbExclusiveManager.hasOpenConnection()
                ) {
                    currentUsbDevice = target
                    scheduleAuthorizedUsbColdStartRearm(
                        device = target,
                        reason = "authorized_attach:$reason",
                    )
                } else {
                    AppLogger.i(
                        TAG,
                        "USB attach already authorized; no rearm needed: active=${_usbExclusiveActive.value} " +
                            "open=${usbExclusiveManager.hasOpenConnection()} " +
                            "requested=${AppPreferences.Player.usbExclusiveRequested}",
                    )
                }
                return@launch
            }

            // A physical attach alone never enters native USB. The permission sheet is the
            // explicit authorization boundary; only a foreground Activity may show it.
            if (!shouldPrompt) return@launch

            usbAttachPermissionPrefetchDeviceId = -1
            AppLogger.i(TAG, "USB exclusive permission request: dialog for ${target.deviceName}")
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
        scheduleUsbExclusiveActivation(
            device = resolved,
            reason = "deferred_foreground:$originalReason:$reason",
            showSuccessToast = true,
            waitForPermissionDialogToClose = false,
        )
    }

    /** USB 独占入口：有权限直接切，没权限先请求，等回调续接。 */
    fun enableUsbExclusive() {
        AppLogger.i(TAG, "enableUsbExclusive ENTER")
        if (usbHardwareRecoveryBlocked) {
            UsbHardwareVolumeStore.clearRecoveryBlock("explicit_enable_usb_exclusive")
            usbHardwareRecoveryBlocked = false
        }
        // Persist the user's choice before permission or native initialization. A failed or
        // interrupted activation must not silently turn the next playback into Android output.
        AppPreferences.Player.usbExclusiveRequested = true
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
            postUsbStatusNotice(
                "未检测到支持 USB 独占的 DAC，请先连接设备",
                AppNoticeIcon.ERROR,
            )
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
        scheduleUsbExclusiveActivation(
            device = device,
            reason = "explicit_enable_already_authorized",
            showSuccessToast = true,
            waitForPermissionDialogToClose = false,
        )
    }

    /** USB 权限回调：授权成功后续接 enableUsbExclusive。 */
    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        AppLogger.i(TAG, "USB permission result: granted=$granted pendingEnable=$pendingEnableUsbExclusiveAfterPermission device=${device.deviceName}")

        if (!granted) {
            usbPermissionActivationJob?.cancel()
            usbPermissionActivationJob = null
            pendingEnableUsbExclusiveAfterPermission = false
            clearDeferredUsbExclusiveActivation("usb_permission_denied")
            ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "usb_permission_denied")
            AppLogger.w(TAG, "USB permission denied")
            postUsbStatusNotice(
                "USB DAC 权限未授予，无法进入独占输出",
                AppNoticeIcon.ERROR,
            )
            return
        }

        // A permission result can arrive after the user has already switched back to an Android
        // output route. Do not let that stale callback silently re-enable USB exclusive.
        val shouldActivateExclusive =
            pendingEnableUsbExclusiveAfterPermission || AppPreferences.Player.usbExclusiveRequested
        pendingEnableUsbExclusiveAfterPermission = false
        if (!shouldActivateExclusive) {
            usbExclusiveManager.rememberDeviceOnly(device, reason = "permission_granted_shared_route")
            ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "usb_permission_shared_route")
            AppLogger.i(TAG, "USB permission granted but exclusive is no longer requested; keep Android output")
            return
        }
        AppPreferences.Player.usbExclusiveRequested = true
        // Authorization is only a route boundary. Do not overwrite a value already saved for
        // this DAC here: initializeUsbHardwareVolumeForHandle() will restore the exact raw value,
        // and will choose the conservative -30dB value only when no compatible record exists.
        usbHardwareVolumeCoordinator.prepareForAuthorization()
        AppLogger.i(TAG, "Fresh USB permission grant armed hardware-volume restore; " +
            "stored DAC value is preserved and first-use devices still use -30dB")
        if (shouldActivateExclusive) {
            clearDeferredUsbExclusiveActivation("permission_granted_scheduled")
            currentUsbDevice = device
            usbExclusiveManager.rememberDeviceOnly(device, reason = "permission_granted")
            scheduleUsbExclusiveActivation(
                device = device,
                reason = "permission_granted_requested",
                // A fresh grant no longer emits a premature "initialized" toast. Report success
                // only after the serialized renderer cutover has actually been armed.
                showSuccessToast = true,
                waitForPermissionDialogToClose = true,
            )
        } else {
            ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(
                false,
                "usb_permission_granted_without_exclusive_request"
            )
            AppLogger.i(TAG, "USB permission granted for prefetch only; exclusive was not requested")
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
        // Permission/open ownership keeps the Android-authorized
        // UsbDeviceConnection across the cutover and reset only native stream state. Closing the
        // Java connection here would throw away the fresh permission-result FD immediately before
        // the first renderer prepare and recreate the OEM openDevice race.
        usbExclusiveManager.resetPlaybackPipelinePreservingAuthorization("cold_activation:$reason")
        sharedUsbAudioEngine.clearState()
    }

    /** 真正的渲染器切换逻辑，权限确认后调用。 */
    private suspend fun activateUsbExclusiveAfterPermission(device: UsbDevice, reason: String = ""): Boolean {
        AppLogger.i(TAG, "activateUsbExclusiveAfterPermission ENTER: reason=$reason device=${device.productName}")
        return enableUsbExclusiveAfterPermission(device)
    }

    private suspend fun enableUsbExclusiveAfterPermission(device: UsbDevice): Boolean {
        AppLogger.i(TAG, "enableUsbExclusiveAfterPermission ENTER")

        if (pcmToDsdSafetyBlocked && isPcmToDsdRequestedForCurrentSong()) {
            AppLogger.e(TAG, "enableUsbExclusiveAfterPermission blocked by PCM_TO_DSD safety fuse")
            return false
        }

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
                val rendererDrained = runCatching {
                    ffmpegPlayer.stopForUsbExclusiveCutover()
                }.onFailure {
                    AppLogger.w(TAG, "USB cutover: stop+drain old renderer failed", it)
                }.getOrDefault(false)
                if (!rendererDrained) {
                    AppLogger.e(TAG, "USB cutover aborted: old playback Runnable did not exit")
                    stopUsbExclusiveAfterFatalFailure(
                        reason = "usb_cutover_worker_not_drained",
                        notifyNativeDetached = false
                    )
                    return false
                }
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
            if (!activateUsbEngineForPlayback(device)) {
                AppLogger.e(TAG, "USB exclusive cutover failed: renderer was not armed")
                return false
            }

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

    fun disableUsbExclusive(preserveUserIntent: Boolean = false) {
        usbColdStartRearmJob?.cancel()
        usbColdStartRearmJob = null
        usbPermissionActivationJob?.cancel()
        usbPermissionActivationJob = null
        usbRenderSwitchReleaseJob?.cancel()
        usbRenderSwitchReleaseJob = null
        AppLogger.i(TAG, "Disabling USB exclusive mode preserveUserIntent=$preserveUserIntent")
        if (!preserveUserIntent) {
            AppPreferences.Player.usbExclusiveRequested = false
        }
        usbSystemAudioKeepAlive.stop("disableUsbExclusive")

        val wasUsingUsb = ffmpegPlayer.usbExclusiveMode || _usbExclusiveActive.value
        val rendererDrained = if (wasUsingUsb) {
            runCatching {
                ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
            }.onFailure {
                AppLogger.w(TAG, "disableUsbExclusive: renderer drain failed", it)
            }.getOrDefault(false)
        } else {
            true
        }
        if (!rendererDrained) {
            AppLogger.e(
                TAG,
                "disableUsbExclusive: native session retained because playback Runnable did not exit"
            )
        }

        // 切回本地音量控制
        androidPlaybackServiceController.setUsbRemoteVolumeActive(
            active = false,
            reason = "disable_usb_exclusive",
        )

        clearUsbExclusiveState(
            releaseManager = rendererDrained,
            notifyNativeDetached = rendererDrained,
            clearLastExclusivePreference = !preserveUserIntent
        )

        // 渲染切换延迟保护，确保USB资源完全释放后再允许AudioTrack播放
        if (wasUsingUsb) {
            AppLogger.i(TAG, "USB exclusive mode disabled, applying render switch delay protection")
            // 标记渲染切换中，防止立即播放导致资源冲突。必须定时释放，
            // 否则下一次授权/启用会被永久判定为 render switching。
            _isRenderSwitching.value = true
            usbRenderSwitchReleaseJob = scope.launch {
                delay(700L)
                if (!isReleased) {
                    _isRenderSwitching.value = false
                    AppLogger.i(TAG, "USB render-switch delay released after disable")
                }
            }
        } else {
            _isRenderSwitching.value = false
        }

        // Explicit output-route disable clears the hardware-volume preference. Lifecycle
        // teardown keeps the user's route intent so an already-authorized DAC can be re-armed
        // after a normal task/process cold start.
        if (!preserveUserIntent) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
        }
        unregisterSystemVolumeObserver()

        // 音量路由切回系统音量
        sharedUsbAudioEngine.setPolicy(exclusive = false, bitPerfect = false, hwVol = false)
        sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
        applyVolumeRoute("usb_exclusive_disabled")

        AppLogger.i(TAG, "USB exclusive mode disabled")
    }

    private fun handleUsbDeviceDetached(detachedDevice: UsbDevice?) {
        AppLogger.i(TAG, "Handling USB device detached: ${detachedDevice?.deviceName}")
        // Notify immediately from the physical detach event. Recovery may continue in the
        // background, but the user should not wait for its timeout before seeing the unplug hint.
        notifyUsbDetachConfirmed("usb_detached_broadcast")
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
        // BroadcastReceiver callbacks run on the main thread. Do not block it
        // for several seconds waiting on a native writer. Stop requests are
        // issued immediately; UsbExclusiveManager then invalidates/closes the
        // opaque native token under the exclusive lifecycle lock. Any late
        // feeder call receives a stale-token error instead of touching freed USB
        // state. Auto-rearm below waits for the real Runnable exit off-main.
        runCatching { ffmpegPlayer.stop() }
            .onFailure { AppLogger.w(TAG, "USB detach: immediate stop request failed", it) }
        unregisterSystemVolumeObserver()
        clearUsbExclusiveState(
            releaseManager = false,
            notifyNativeDetached = false,
            clearLastExclusivePreference = false,
            hardwareSessionEndedCleanly = true,
        )
        if (wasActive && songBeforeDetach != null && AppPreferences.Player.usbExclusiveRequested) {
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
        androidPlaybackServiceController.releaseUsbServiceWakeLock("usb_detached")
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

            val rendererDrained = withContext(Dispatchers.IO) {
                runCatching {
                    ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
                }.getOrDefault(false)
            }
            if (!rendererDrained) {
                AppLogger.e(TAG, "USB detached recovery cancelled: old playback Runnable did not exit")
                notifyUsbDetachConfirmed("usb_detach_worker_not_drained")
                return@launch
            }

            // Give the kernel/USB framework a quiet interval after physical
            // detach. Reclaiming within ~180 ms can overlap OEM host-controller
            // teardown and re-enumeration.
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var recoveredDevice: UsbDevice? = null
            while (!isReleased && SystemClock.elapsedRealtime() < deadline) {
                delay(500)
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
                check(activateUsbEngineForPlayback(device)) {
                    "USB renderer was not armed during detach recovery"
                }
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

                postUsbStatusNotice("USB DAC 已恢复")
            }.onFailure { error ->
                AppLogger.w(TAG, "USB detached recovery failed while rearming", error)
                notifyUsbDetachConfirmed("usb_detach_rearm_failed")
            }
        }
    }

    private fun notifyUsbDetachConfirmed(reason: String) {
        if (!usbDetachToastShownForCurrentDetach.compareAndSet(false, true)) {
            AppLogger.i(TAG, "USB detach toast suppressed as duplicate: reason=$reason")
            return
        }
        AppLogger.w(TAG, "USB detach confirmed: reason=$reason")
        postUsbStatusNotice("DAC已拔出！")
    }

    private fun clearUsbExclusiveState(
        releaseManager: Boolean,
        notifyNativeDetached: Boolean,
        clearLastExclusivePreference: Boolean = false,
        hardwareSessionEndedCleanly: Boolean = releaseManager || notifyNativeDetached,
    ) {
        usbHardwareVolumeCoordinator.resetInitialization()
        ffmpegPlayer.usbExclusiveMode = false
        ffmpegPlayer.setSuppressAndroidExternalRouteForUsbCutover(false, "clear_usb_exclusive_state")
        ffmpegPlayer.usbActualOutputSampleRate = 0
        ffmpegPlayer.usbPrepareForPlayback = null
        ffmpegPlayer.usbStartStreaming = null
        ffmpegPlayer.onUsbTransportLost = null
        ffmpegPlayer.onUsbPlaybackStarted = null
        ffmpegPlayer.onUsbPlaybackDataFlowing = null
        ffmpegPlayer.onUsbStreamHealthFailure = null
        ffmpegPlayer.onUsbPlaybackStopped = null
        ffmpegPlayer.shouldDeferUsbHardRecovery = null
        ffmpegPlayer.onBeforeUsbNativeStart = null
        usbSystemAudioKeepAlive.stop("clear_usb_exclusive_state")
        _usbOutputSampleRate.value = 0
        runCatching { unregisterSystemVolumeObserver() }

        var nativeTeardownConfirmed = !releaseManager && !notifyNativeDetached
        if (notifyNativeDetached) {
            // Detach poisoning is serialized with init/start/stop/close by the
            // same Transport owner. Controller callbacks never enter native USB
            // lifecycle functions directly.
            usbExclusiveManager.notifyNativeDetached("clear_usb_exclusive_state")
            nativeTeardownConfirmed = true
        }

        currentUsbDevice = null
        usbFeedbackRejectedTransportKey = null
        usbFeedbackRejectedReason = ""
        clearDeferredUsbExclusiveActivation("clear_usb_exclusive_state")
        if (releaseManager) {
            usbExclusiveManager.release("clear_usb_exclusive_state")
            nativeTeardownConfirmed = true
        }
        if (hardwareSessionEndedCleanly && nativeTeardownConfirmed) {
            // Clear the reboot fuse only after release/detach has actually returned.
            // If the kernel resets during teardown, this line is never reached and
            // the next process boot refuses USB-exclusive auto restore.
            UsbHardwareVolumeStore.markSessionClean("clear_usb_exclusive_state_teardown_complete")
        } else {
            AppLogger.w(
                TAG,
                "USB hardware-session marker kept active because native teardown was not confirmed",
            )
        }
        _usbExclusiveActive.value = false
        androidSpatialPlaybackController.refreshRoutingState()
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

        abandonAudioFocus()

        androidPlaybackServiceController.releaseUsbPlaybackWakeLock("clear_usb_exclusive_state")
        PlayerService.clearUsbMediaIdentityFromController("clear_usb_exclusive_state", true)
        androidPlaybackServiceController.sendUsbMediaIdentity(
            reason = "clear_usb_exclusive_state",
            song = _currentSong.value,
            positionMs = _position.value,
            playing = false,
        )

        // 释放 USB 专用 WakeLock
        androidPlaybackServiceController.releaseUsbServiceWakeLock("clear_usb_exclusive_state")
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

    /**
     * USB PCM bit-perfect policy.
     * OFF always uses the processed path; WHEN_POSSIBLE promotes only tracks with
     * an exact device geometry; STRICT refuses any track that would need format mutation.
     */
    fun setUsbBitPerfectMode(mode: UsbBitPerfectMode): Int {
        if (mode.requestsBitPerfect && !_usbExclusiveActive.value) return -1

        val oldMode = AppPreferences.Player.usbBitPerfectMode
        if (oldMode == mode) return 0

        AppPreferences.Player.usbBitPerfectMode = mode
        ffmpegPlayer.usbBitPerfectPolicyMode = mode
        // Provisional state only. The per-track resolver will promote WHEN_POSSIBLE
        // after proving an exact source/device geometry.
        ffmpegPlayer.usbBitPerfectMode = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
            mode,
            AppPreferences.Player.usbVolumeMode,
        )

        val exclusive = _usbExclusiveActive.value
        val hwVol = AppPreferences.Player.usbVolumeMode == 1 &&
            AppPreferences.Player.hardwareFeatureUnitEnabled && exclusive

        AppLogger.i(TAG, "USB bit-perfect policy changed: old=$oldMode new=$mode exclusive=$exclusive hwVol=$hwVol")
        if (!exclusive) return 0

        // Re-resolve the current track from source geometry. Do not ask the old native
        // session to reinterpret WHEN_POSSIBLE as strict merely because the user intent
        // is enabled. The normal settings transaction closes the old session first, then
        // playbackTargetResolver computes the new effective state for this track.
        return applyUsbOutputSettingsChanged(
            userInitiated = true,
            forceBitPerfectPolicyRestart = true,
        )
    }

    /** Legacy API: `true` now means WHEN_POSSIBLE, not STRICT. */
    fun setUsbBitPerfectEnabled(enabled: Boolean): Int =
        setUsbBitPerfectMode(if (enabled) UsbBitPerfectMode.WHEN_POSSIBLE else UsbBitPerfectMode.OFF)

    private fun preArmSoftwareVolumeFromHardware(
        snapshot: UsbHardwareVolumeCoordinator.LiveVolumeSnapshot,
        strictLiveHandle: Boolean,
        reason: String,
    ): UsbOutputProfile {
        volumeRouteCoordinator.prepareHardwareToSoftwareEntry(snapshot.db, reason)
        val softwareProfile = buildUsbOutputProfile(exclusive = true)
        if (!strictLiveHandle && snapshot.handle == sharedUsbAudioEngine.currentHandle) {
            // ExclusiveProcessedHwVol explicitly allows processed PCM gain. Apply the complete
            // software plan (user curve + ReplayGain/duck) while the Feature Unit still owns the
            // physical attenuation. This is make-before-break: any overlap can only be quieter.
            applyUsbVolume(softwareProfile, "hw_to_sw_prearm:$reason")
        }
        return softwareProfile
    }

    private fun rollbackHardwareToSoftwareHandoff(
        wasEffectiveBitPerfect: Boolean,
        reason: String,
    ) {
        pendingUsbHardwareReleaseAfterStop = null
        AppPreferences.Player.hardwareFeatureUnitEnabled = true
        AppPreferences.Player.usbVolumeMode = 1
        ffmpegPlayer.usbBitPerfectMode = wasEffectiveBitPerfect
        sharedUsbAudioEngine.setPolicy(
            exclusive = _usbExclusiveActive.value,
            bitPerfect = wasEffectiveBitPerfect && _usbExclusiveActive.value,
            hwVol = _usbExclusiveActive.value,
        )
        usbHardwareToSoftwareHandoffActive = false
        applyVolumeRoute("hw_to_sw_rollback:$reason")
        syncUsbRemoteVolumeRoute("hw_to_sw_rollback:$reason", force = true)
        AppLogger.w(TAG, "USB HW->SW handoff rolled back safely reason=$reason")
    }

    private fun completeLiveHardwareToSoftwareHandoff(
        snapshot: UsbHardwareVolumeCoordinator.LiveVolumeSnapshot,
        reason: String,
    ): Boolean {
        val liveHandle = sharedUsbAudioEngine.currentHandle
        if (liveHandle == 0L || liveHandle != snapshot.handle) {
            AppLogger.w(
                TAG,
                "USB HW->SW live handoff lost handle before fence: old=0x${snapshot.handle.toString(16)} " +
                    "live=0x${liveHandle.toString(16)} reason=$reason",
            )
            return false
        }

        val pipelineFenceMs = if (sharedUsbAudioEngine.isRunning()) {
            runCatching { sharedUsbAudioEngine.nativeGetIsoPipelineDepthMs(snapshot.handle) }
                .getOrDefault(0)
                .coerceIn(0, 120)
        } else {
            0
        }
        if (pipelineFenceMs > 0) {
            AppLogger.i(
                TAG,
                "USB HW->SW waiting for old unity ISO packets to retire: fenceMs=$pipelineFenceMs " +
                    "handle=0x${snapshot.handle.toString(16)} reason=$reason",
            )
            // This setting transaction is rare and bounded. The wait is derived from the live USB
            // transfer geometry, not a guessed fade duration, and prevents an already-submitted
            // unity packet from becoming audible after the Feature Unit is released.
            SystemClock.sleep(pipelineFenceMs.toLong())
        }

        if (sharedUsbAudioEngine.currentHandle != snapshot.handle) return false
        if (!usbHardwareVolumeCoordinator.releaseToUnityVerified(snapshot, reason)) return false

        sharedUsbAudioEngine.setPolicy(
            exclusive = true,
            bitPerfect = false,
            hwVol = false,
        )
        usbHardwareVolumeCoordinator.resetInitialization()
        stickyUsbHardwareVolumeValidated = false
        usbHardwareToSoftwareHandoffActive = false
        applyVolumeRoute("hw_to_sw_commit_live:$reason")
        UsbHardwareVolumeStore.markSessionClean("hw_to_sw_commit_live")
        syncUsbRemoteVolumeRoute("hw_to_sw_commit_live:$reason", force = true)
        AppLogger.i(
            TAG,
            "USB HW->SW make-before-break committed without stream restart: " +
                "oldHwDb=${snapshot.db} fenceMs=$pipelineFenceMs reason=$reason",
        )
        return true
    }

    private fun releasePendingHardwareVolumeAfterTransportStop(reason: String): Boolean {
        val pending = pendingUsbHardwareReleaseAfterStop ?: return true
        val snapshot = pending.snapshot
        val stoppedHandle = sharedUsbAudioEngine.currentHandle
        if (stoppedHandle == 0L || !sharedUsbAudioEngine.nativeIsStreamFullyStopped(stoppedHandle)) {
            AppLogger.e(
                TAG,
                "USB HW->SW safety gate refused FU release: native stream not fully stopped " +
                    "handle=0x${stoppedHandle.toString(16)} reason=$reason; keep hardware attenuation",
            )
            return false
        }
        if (stoppedHandle != snapshot.handle) {
            AppLogger.e(
                TAG,
                "USB HW->SW safety gate refused FU release: handle changed " +
                    "old=0x${snapshot.handle.toString(16)} live=0x${stoppedHandle.toString(16)} " +
                    "reason=$reason; keep hardware attenuation",
            )
            return false
        }
        val released = stoppedHandle == snapshot.handle &&
            usbHardwareVolumeCoordinator.releaseToUnityVerified(snapshot, "after_stop:$reason")
        if (released) {
            pendingUsbHardwareReleaseAfterStop = null
            usbHardwareVolumeCoordinator.resetInitialization()
            stickyUsbHardwareVolumeValidated = false
        } else {
            AppLogger.w(
                TAG,
                "USB HW->SW stopped-transport FU release failed old=0x${snapshot.handle.toString(16)} " +
                    "live=0x${stoppedHandle.toString(16)} reason=$reason; keep hardware attenuation",
            )
        }
        if (released) usbHardwareToSoftwareHandoffActive = false
        return released
    }

    /** 设置 USB DAC 音量模式：0=软件音量, 1=硬件音量, 2=数字固定 0dB。 */
    fun setUsbVolumeMode(mode: Int): Int {
        val normalized = mode.coerceIn(0, 2)
        if (normalized == 1) {
            return setUsbHardwareFeatureUnitEnabled(true)
        }
        if (AppPreferences.Player.hardwareFeatureUnitEnabled) {
            val disableResult = setUsbHardwareFeatureUnitEnabled(false)
            if (disableResult != 0) return disableResult
            if (normalized == 0) return 0
        }

        val exclusive = _usbExclusiveActive.value
        val wasEffectiveBitPerfect = exclusive && ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack()
        AppPreferences.Player.usbVolumeMode = normalized
        AppPreferences.Player.hardwareFeatureUnitEnabled = false
        if (normalized == 0 && wasEffectiveBitPerfect) {
            // Software user volume owns the PCM data plane, so an already-running strict/fixed
            // decoder contract must be dropped before the settings transaction is evaluated.
            ffmpegPlayer.usbBitPerfectMode = false
        }
        if (normalized == 2) {
            AppPreferences.Player.volume = 1.0f
            forceUsbFixedVolume0Db("setUsbVolumeMode")
        }
        AppLogger.i(TAG, "setUsbVolumeMode: mode=$normalized exclusive=$exclusive")

        if (!exclusive) {
            sharedUsbAudioEngine.setPolicy(false, false, false)
            return 0
        }

        val profile = buildUsbOutputProfile(exclusive = true)
        sharedUsbAudioEngine.setPolicy(
            exclusive = true,
            bitPerfect = profile.bitPerfect || profile.fixedDigitalVolume || profile.dsdSourceDirect,
            hwVol = profile.hardwareVolumeRequested
        )
        applyVolumeRoute("setUsbVolumeMode:$normalized")
        return applyUsbOutputSettingsChanged(
            userInitiated = true,
            forceBitPerfectPolicyRestart = normalized == 0 && wasEffectiveBitPerfect,
        )
    }

    /** 设置硬件 Feature Unit 音量控制。非完美比特和完美比特都可用。
     *  注意：当前 handle 如果还在软件音量模式，不能在旧 handle 上预验证；必须先把
     *  hwVolRequested 写入 policy，再完整重建 native handle，让 nativeInit 解析 AC
     *  topology 后验证 Feature Unit。 */
    fun setUsbHardwareFeatureUnitEnabled(enabled: Boolean): Int {
        val exclusive = _usbExclusiveActive.value

        AppLogger.i(TAG, "setUsbHardwareFeatureUnitEnabled: enabled=$enabled exclusive=$exclusive")

        if (transportTransitioning || recoveringUsb.get()) {
            AppLogger.w(
                TAG,
                "Hardware-volume route change rejected during USB transport transition: " +
                    "enabled=$enabled recovering=${recoveringUsb.get()}",
            )
            postUsbStatusNotice(
                context.getString(R.string.usb_volume_transition),
                AppNoticeIcon.ERROR,
            )
            return -4
        }

        if (enabled && !exclusive) {
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
            stickyUsbHardwareVolumeValidated = false
            AppLogger.w(TAG, "Hardware volume can only be enabled in USB exclusive mode")
            postUsbStatusNotice(
                context.getString(R.string.usb_volume_exclusive_only),
                AppNoticeIcon.ERROR,
            )
            applyVolumeRoute("hardware_volume_rejected_non_exclusive")
            return -1
        }

        val wasEffectiveBitPerfect = ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack()
        val liveHandle = sharedUsbAudioEngine.currentHandle

        // Route switch: turning hardware volume OFF should not force
        // a USB profile rebuild.  The stream profile, clock, alt setting and ISO
        // runtime are still valid; only the volume controller path changes.
        // Keep playback running and move gain control back to the software path.
        if (!enabled) {
            if (!exclusive || liveHandle == 0L) {
                AppPreferences.Player.hardwareFeatureUnitEnabled = false
                AppPreferences.Player.usbVolumeMode = 0
                usbHardwareVolumeCoordinator.resetInitialization()
                stickyUsbHardwareVolumeValidated = false
                sharedUsbAudioEngine.setPolicy(
                    exclusive = exclusive,
                    bitPerfect = false,
                    hwVol = false,
                )
                applyVolumeRoute("hw_to_sw_commit_no_live_handle")
                syncUsbRemoteVolumeRoute("hw_to_sw_commit_no_live_handle", force = true)
                return 0
            }

            val snapshot = usbHardwareVolumeCoordinator.captureLiveVolume(
                "hardware_volume_disable_handoff",
            )
            if (snapshot == null) {
                AppLogger.e(
                    TAG,
                    "USB HW->SW refused: live Feature Unit value could not be captured; " +
                        "keeping hardware ownership to avoid a full-scale burst",
                )
                return -5
            }

            usbHardwareToSoftwareHandoffActive = true
            AppPreferences.Player.hardwareFeatureUnitEnabled = false
            AppPreferences.Player.usbVolumeMode = 0

            // Explicit software volume mutates PCM. If this track was effectively bit-perfect,
            // the old strict decoder/native contract cannot remain active: downgrade the effective
            // state and rebuild once so software gain is real rather than silently pinned to unity.
            val softwareNeedsProcessedRestart = exclusive && wasEffectiveBitPerfect
            preArmSoftwareVolumeFromHardware(
                snapshot = snapshot,
                strictLiveHandle = softwareNeedsProcessedRestart,
                reason = "hardware_volume_disable",
            )

            if (softwareNeedsProcessedRestart) {
                ffmpegPlayer.usbBitPerfectMode = false
                pendingUsbHardwareReleaseAfterStop = PendingUsbHardwareReleaseAfterStop(snapshot)
                AppLogger.i(
                    TAG,
                    "USB HW->SW strict handoff armed: keep FU at ${snapshot.db}dB until old ISO stops",
                )
                val result = applyUsbOutputSettingsChanged(
                    userInitiated = true,
                    forceBitPerfectPolicyRestart = true,
                )
                if (pendingUsbHardwareReleaseAfterStop != null) {
                    // The settings transaction returned before reaching its stopped-transport
                    // release point (for example no active song). Keep the old hardware route.
                    rollbackHardwareToSoftwareHandoff(
                        wasEffectiveBitPerfect = wasEffectiveBitPerfect,
                        reason = "strict_restart_did_not_reach_release",
                    )
                    return -5
                }
                UsbHardwareVolumeStore.markSessionClean("hw_to_sw_strict_restart")
                syncUsbRemoteVolumeRoute("hw_to_sw_strict_restart", force = true)
                postUsbStatusNotice(context.getString(R.string.usb_volume_disabled))
                AppLogger.i(TAG, "Hardware->software strict handoff completed through stopped transport")
                return result
            }

            val committed = completeLiveHardwareToSoftwareHandoff(
                snapshot = snapshot,
                reason = "hardware_volume_disable",
            )
            if (!committed) {
                rollbackHardwareToSoftwareHandoff(
                    wasEffectiveBitPerfect = wasEffectiveBitPerfect,
                    reason = "live_handoff_failed",
                )
                return -5
            }
            postUsbStatusNotice(context.getString(R.string.usb_volume_disabled))
            return 0
        }

        AppPreferences.Player.hardwareFeatureUnitEnabled = true
        AppPreferences.Player.usbVolumeMode = 1
        pendingUsbHardwareReleaseAfterStop = null
        usbHardwareToSoftwareHandoffActive = false
        val hardwareRouteRequestToken = latestPlayRequestToken.get()
        val hwRequested = exclusive

        ffmpegPlayer.usbBitPerfectPolicyMode = AppPreferences.Player.usbBitPerfectMode
        val preArmStrict = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
            AppPreferences.Player.usbBitPerfectMode,
            AppPreferences.Player.usbVolumeMode,
        )

        sharedUsbAudioEngine.setPolicy(
            exclusive = exclusive,
            bitPerfect = preArmStrict && exclusive,
            hwVol = hwRequested
        )

        if (hwRequested) {
            // Enabling hardware volume on a live ISO stream must not issue Feature Unit SET_CUR.
            // Rebuild the handle first; restartUsbWithPolicy performs the one device-scoped
            // initialization before ISO is allowed to start.
            AppLogger.i(
                TAG,
                "Hardware volume requested; defer Feature Unit initialization until pre-ISO restart",
            )
        }

        if (exclusive && liveHandle != 0L) {
            scope.launch {
                val applyResult = transportMutex.withLock {
                    transportTransitioning = true
                    try {
                        // Hardware enable is an output-policy transaction, not a device-only
                        // prepare. Reuse the same stop -> close -> stage policy -> restart-current
                        // lane as every other USB output setting so decoder/feeder ownership and
                        // explicit play requests stay serialized on one transport timeline.
                        applyUsbOutputSettingsChanged(
                            userInitiated = true,
                            forceBitPerfectPolicyRestart = true,
                            expectedRestartPlayToken = hardwareRouteRequestToken,
                        )
                    } finally {
                        transportTransitioning = false
                    }
                }

                // The restart-current continuation is queued on the same PLAY lane and can only
                // run after the mutex above is released. Wait for that renderer to prepare its new
                // handle before deciding whether Feature Unit ownership really came up.
                var waitedMs = 0L
                var handleAfterRestart = sharedUsbAudioEngine.currentHandle
                while (
                    AppPreferences.Player.hardwareFeatureUnitEnabled &&
                    AppPreferences.Player.usbVolumeMode == 1 &&
                    waitedMs < 3_000L &&
                    (handleAfterRestart == 0L || !sharedUsbAudioEngine.nativeCanControlVolume(handleAfterRestart)) &&
                    (ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING ||
                        _playState.value == PlayState.PREPARING)
                ) {
                    delay(50L)
                    waitedMs += 50L
                    handleAfterRestart = sharedUsbAudioEngine.currentHandle
                }

                val canHw = AppPreferences.Player.hardwareFeatureUnitEnabled &&
                    AppPreferences.Player.usbVolumeMode == 1 &&
                    handleAfterRestart != 0L &&
                    sharedUsbAudioEngine.nativeCanControlVolume(handleAfterRestart)
                AppLogger.i(
                    TAG,
                    "Hardware volume settings transaction complete: result=$applyResult canHw=$canHw " +
                        "waitedMs=$waitedMs handle=0x${handleAfterRestart.toString(16)} " +
                        "policy=${sharedUsbAudioEngine.getHardwareVolumePolicyString()}",
                )
                if (canHw) {
                    sharedUsbAudioEngine.nativeSetUsbSoftwareGain(1.0f)
                    currentUsbDevice?.let { UsbHardwareVolumeStore.markSessionActive(context, it) }
                    applyVolumeRoute("hardware_volume_after_settings_restart")
                    syncUsbRemoteVolumeRoute("hardware_volume_after_settings_restart", force = true)
                    postUsbStatusNotice("硬件音量已启用")
                } else if (
                    ffmpegPlayer.state != FfmpegAudioPlayer.State.PREPARING &&
                    _playState.value != PlayState.PREPARING
                ) {
                    AppLogger.e(
                        TAG,
                        "Hardware-volume restart finished without a controllable Feature Unit; " +
                            "falling back to software route",
                    )
                    AppPreferences.Player.hardwareFeatureUnitEnabled = false
                    AppPreferences.Player.usbVolumeMode = 0
                    ffmpegPlayer.usbBitPerfectMode = false
                    sharedUsbAudioEngine.setPolicy(exclusive = true, bitPerfect = false, hwVol = false)
                    applyVolumeRoute("hardware_volume_restart_failed")
                    syncUsbRemoteVolumeRoute("hardware_volume_restart_failed", force = true)
                }
            }
        } else {
            applyVolumeRoute("hardware_volume_changed_non_exclusive")
            androidPlaybackServiceController.setUsbRemoteVolumeActive(
                active = false,
                reason = "hardware_volume_changed_non_exclusive",
            )
            postUsbStatusNotice(
                if (enabled) "硬件音量将在下次 USB 初始化后验证" else "硬件音量已关闭"
            )
        }
        return 0
    }

    /** 后台冷启动保护：在 USB nativeStart 之前确保前台服务 + WakeLock 已就位。 */
    private fun ensureUsbForegroundImmediate(reason: String) {
        AppLogger.i(TAG, "ensureUsbForegroundImmediate: $reason")
        androidPlaybackServiceController.acquireUsbPlaybackWakeLock(reason)
        androidPlaybackServiceController.ensureUsbPlaybackForeground(reason)
    }

    private suspend fun ensureUsbForegroundBeforeStart(reason: String) {
        withContext(Dispatchers.Main.immediate) {
            AppLogger.i(TAG, "ensureUsbForegroundBeforeStart: $reason")
            androidPlaybackServiceController.acquireUsbPlaybackWakeLock(reason)
            androidPlaybackServiceController.ensureUsbPlaybackForeground(reason)
        }
        // 给 Service 时间进入 foreground
        kotlinx.coroutines.delay(150)
    }


    private fun resolveUsbPcmFormatRequest(): UsbPcmFormatRequest =
        usbFormatPolicyCoordinator.resolvePcmFormatRequest()

    private fun currentSongIsDsdSource(): Boolean =
        usbFormatPolicyCoordinator.currentSongIsDsdSource()

    private fun currentSongDsdSourceRate(): Int =
        usbFormatPolicyCoordinator.currentSongDsdSourceRate()

    private fun currentPcmSourceRateForDsd(): Int =
        usbFormatPolicyCoordinator.currentPcmSourceRateForDsd()

    private fun resolveUsbPolicyRestartSource(): com.rawsmusic.module.player.usb.UsbPolicyRestartSource =
        usbFormatPolicyCoordinator.resolvePolicyRestartSource()

    private fun currentEffectiveUsbDsdMode(): com.rawsmusic.module.player.usb.UsbDsdModeConfig? =
        usbFormatPolicyCoordinator.currentEffectiveDsdMode()

    private fun currentEffectiveUsbDsdRate(): Int =
        usbFormatPolicyCoordinator.currentEffectiveDsdRate()

    fun refreshUsbCapabilities(reason: String) =
        usbFormatPolicyCoordinator.refreshCapabilities(reason)

    private fun reconcileUsbOutputSettingsWithCapabilities(
        caps: com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities?
    ) {
        usbFormatPolicyCoordinator.reconcileOutputSettingsForOwner(caps)
    }

    private fun usbLearnedPolicyKeyFor(device: android.hardware.usb.UsbDevice): String =
        usbFormatPolicyCoordinator.learnedPolicyKey(device)

    private val usbOutputProfileBuilder by lazy {
        PlayerUsbOutputProfileBuilder(
            tag = TAG,
            callbacks = PlayerUsbOutputProfileBuilder.Callbacks(
                currentDsdMode = ::currentEffectiveUsbDsdMode,
                currentDsdRate = ::currentEffectiveUsbDsdRate,
                resolvePcmFormatRequest = ::resolveUsbPcmFormatRequest,
                currentDevice = { currentUsbDevice },
                pendingRecoveryPlan = { pendingUsbRecoveryPlan },
                capabilities = { _usbCapabilities.value },
                engineCapabilities = { sharedUsbAudioEngine.getDeviceCapabilities() },
                isHardwareVolumeValidated = { sharedUsbAudioEngine.isHardwareVolumeValidated() },
                isEffectiveBitPerfect = { ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack() },
                decideFeedbackModel = ::decideUsbFeedbackModel,
                currentSongIsDsdSource = ::currentSongIsDsdSource,
                markHardwareVolumeValidated = { stickyUsbHardwareVolumeValidated = true },
                stickyHardwareVolumeValidated = { stickyUsbHardwareVolumeValidated },
            ),
        )
    }

    private fun buildUsbOutputProfile(exclusive: Boolean): UsbOutputProfile =
        usbOutputProfileBuilder.build(exclusive)

    private val usbOutputProfileApplier by lazy {
        PlayerUsbOutputProfileApplier(
            tag = TAG,
            callbacks = PlayerUsbOutputProfileApplier.Callbacks(
                setFfmpegBitPerfect = { ffmpegPlayer.usbBitPerfectMode = it },
                setNativePolicy = { exclusive, bitPerfect, hardwareVolume ->
                    sharedUsbAudioEngine.setPolicy(exclusive, bitPerfect, hwVol = hardwareVolume)
                },
                stageNativeSessionPolicy = { profile, dsdRate, dsdType, dither ->
                    sharedUsbAudioEngine.stageOutputProfile(profile, dsdRate, dsdType, dither)
                },
                currentDsdRate = ::currentEffectiveUsbDsdRate,
                currentSongIsDsdSource = ::currentSongIsDsdSource,
            ),
        )
    }

    private fun applyUsbOutputProfile(profile: UsbOutputProfile) =
        usbOutputProfileApplier.apply(profile)

    private fun isPcmToDsdRequestedForCurrentSong(): Boolean =
        AppPreferences.Player.dsdConversionEnabled && !currentSongIsDsdSource()

    private fun tripPcmToDsdSafetyFuse(reason: String) {
        if (pcmToDsdSafetyBlocked) return
        pcmToDsdSafetyBlocked = true
        pendingUsbRecoveryPlan = null
        pendingUsbPolicyRestart = false
        AppLogger.e(
            TAG,
            "PCM_TO_DSD safety fuse tripped: reason=$reason; stop USB without AAudio fallback or auto-reopen",
        )
        stopUsbExclusiveAfterFatalFailure(
            reason = "pcm_to_dsd_safety:$reason",
            notifyNativeDetached = false,
        )
    }

    private fun handleUsbStreamHealthFailure(kind: UsbSilentKind, detail: String): Boolean {
        if (isPcmToDsdRequestedForCurrentSong()) {
            tripPcmToDsdSafetyFuse("health_${kind.name}:$detail")
            return true
        }
        val stats = runCatching {
            val h = sharedUsbAudioEngine.currentHandle
            if (h != 0L) UsbRuntimeStatsParser.parseStats(sharedUsbAudioEngine.nativeGetStatsString(h)) else null
        }.getOrNull()
        val runtimeLivenessFailure =
            kind == UsbSilentKind.TransportError && detail.startsWith("runtime_liveness_stall")
        if (!runtimeLivenessFailure) {
            invalidateFailedUsbLastGoodProfileIfNeeded(kind, stats)
        } else {
            AppLogger.w(
                TAG,
                "USB runtime liveness failure: preserving last-good profile; " +
                    "transport ownership died without descriptor/profile evidence",
            )
        }
        val profile = buildUsbOutputProfile(exclusive = true)
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
                    AppLogger.w(
                        TAG,
                        "USB full reopen postponed while playback is background-protected: " +
                            "action=${plan.action} message=${plan.message}"
                    )
                } else {
                    recoverUsbExclusiveAsync()
                }
                // Controller owns both the immediate and deferred full-reopen.
                // The feeder must stop without touching the USB lifecycle.
                return true
            }
            AppLogger.w(
                TAG,
                "USB profile recovery left to the feeder's serialized reprepare path: " +
                    "action=${plan.action} message=${plan.message}"
            )
        }
        return false
    }

    private fun invalidateFailedUsbLastGoodProfileIfNeeded(
        kind: UsbSilentKind,
        stats: UsbStatsSnapshot?,
    ) {
        if (kind != UsbSilentKind.TransportError && kind != UsbSilentKind.UsbNotOutputting) return
        stats ?: return
        val expected = stats.expectedBytesPerSec
        if (expected <= 0L || stats.usbOutBytesPerSec >= expected * 35L / 100L) return
        val transportWasFed = stats.scheduledUsbBytesPerSec > 0L ||
            stats.bufferUsedBytes >= expected * 100L / 1000L
        if (!transportWasFed) return

        val device = currentUsbDevice ?: return
        val runtime = runCatching { sharedUsbAudioEngine.getRuntimeFormat() }.getOrNull() ?: return
        if (!runtime.isValid) return
        val deviceKey = usbLearnedPolicyKeyFor(device)
        if (UsbLearnedPolicyStore.invalidateLastGoodIfMatches(
                deviceKey = deviceKey,
                alt = runtime.alt,
                sampleRate = runtime.sampleRate,
                bitDepth = runtime.validBits,
                subslot = runtime.subslotBytes,
                feedbackEndpoint = runtime.feedbackEndpoint,
            )
        ) {
            AppLogger.w(
                TAG,
                "USB invalidated non-draining last-good profile: kind=$kind " +
                    "iface=${runtime.iface} alt=${runtime.alt} sr=${runtime.sampleRate} " +
                    "bits=${runtime.validBits} subslot=${runtime.subslotBytes} " +
                    "fb=0x${runtime.feedbackEndpoint.toString(16)} " +
                    "completed=${stats.usbOutBytesPerSec}/$expected scheduled=${stats.scheduledUsbBytesPerSec}",
            )
        }
    }


    private fun recordUsbLastGoodProfile(reason: String) {
        val device = currentUsbDevice ?: return
        val runtime = runCatching { sharedUsbAudioEngine.getRuntimeFormat() }.getOrNull() ?: return
        if (!runtime.isValid) return
        val profile = buildUsbOutputProfile(exclusive = true)
        val deviceKey = usbLearnedPolicyKeyFor(device)
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
        if (pendingUsbRecoveryPlan != null) {
            AppLogger.i(TAG, "USB runtime acceptance clears deferred recovery: oldPlan=$pendingUsbRecoveryPlan")
            val liveHandle = sharedUsbAudioEngine.currentHandle
            if (liveHandle != 0L) {
                runCatching { sharedUsbAudioEngine.nativeInvalidateUsbRecoveryToken(liveHandle) }
            }
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
                val stats = UsbRuntimeStatsParser.parseStats(statsStr) ?: return@launch
                val audibleState = runCatching { sharedUsbAudioEngine.nativeGetAudibleStateString(handle) }.getOrDefault("")
                val audibleAccepted = UsbRuntimeStatsParser.isAudibleAccepted(audibleState)
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
                            if (UsbRuntimeStatsParser.isAudibleAccepted(repairedState)) {
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
                if (plan.action == UsbRecoveryAction.RetryWithoutFeedback) {
                    AppLogger.w(TAG, "USB feedback recovery: rebuilding stream immediately with fixed pacing")
                    scope.launch(Dispatchers.Main.immediate) {
                        var waitedMs = 0L
                        while (_isRenderSwitching.value && waitedMs < 3_000L) {
                            delay(100L)
                            waitedMs += 100L
                        }
                        val pending = pendingUsbRecoveryPlan
                        if (pending?.action != UsbRecoveryAction.RetryWithoutFeedback ||
                            pending.nativeRecoveryToken != plan.nativeRecoveryToken ||
                            !UsbStreamRecoveryPlanner.isNativeRecoveryTokenCurrent(plan)
                        ) {
                            AppLogger.i(
                                TAG,
                                "USB feedback recovery skipped: stale native recovery token " +
                                    "planned=${plan.nativeRecoveryToken} current=${pending?.nativeRecoveryToken}",
                            )
                            return@launch
                        }
                        applyUsbOutputSettingsChanged(userInitiated = true)
                    }
                }
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
            AppPreferences.Player.usbBitPerfectMode.id,
            ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack(),
            AppPreferences.Player.hardwareFeatureUnitEnabled,
            AppPreferences.Player.usbPcmOutputMode,
            currentSongIsDsdSource(),
            currentEffectiveUsbDsdMode()?.transport?.prefValue ?: -1,
            currentEffectiveUsbDsdRate()
        ).joinToString("/")
    }

    fun applyUsbOutputSettingsChanged(
        userInitiated: Boolean = false,
        forceBitPerfectPolicyRestart: Boolean = false,
        expectedRestartPlayToken: Long = latestPlayRequestToken.get(),
    ): Int {
        if (!AppPreferences.Player.dsdConversionEnabled) {
            pcmToDsdSafetyBlocked = false
        }
        if (!_usbExclusiveActive.value) {
            val staleUsbOwnership =
                ffmpegPlayer.usbExclusiveMode ||
                    sharedUsbAudioEngine.currentHandle != 0L ||
                    usbExclusiveManager.hasOpenConnection()
            if (staleUsbOwnership) {
                AppLogger.e(
                    TAG,
                    "USB settings changed while Android/AAudio route owns playback but stale USB state remains; " +
                        "stop and close USB before applying PCM_TO_DSD preference",
                )
                stopUsbExclusiveAfterFatalFailure(
                    reason = "pcm_to_dsd_route_split_brain",
                    notifyNativeDetached = false,
                )
            } else {
                AppLogger.i(TAG, "USB settings staged for next clean USB init; no active USB handle")
            }
            return 0
        }
        reconcileUsbOutputSettingsWithCapabilities(_usbCapabilities.value)
        val recoveryPlan = pendingUsbRecoveryPlan?.takeIf { it.requiresProfileRestart }

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

        val effectiveDsdMode = currentEffectiveUsbDsdMode()
        val dsdActive = effectiveDsdMode != null
        val bitPerfectPolicy = AppPreferences.Player.usbBitPerfectMode
        ffmpegPlayer.usbBitPerfectPolicyMode = bitPerfectPolicy
        val effectiveBitPerfect = ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack()
        val hwVol = AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled
        val fixedDigital = AppPreferences.Player.usbVolumeMode == 2
        // Do not change native policy/DSD globals yet. The old USB writer and handle still own
        // the previous altsetting. Apply the new configuration only after drain + full close.

        AppLogger.w(
            TAG,
            "USB_RESAMPLE_APPLY prefRate=${AppPreferences.Player.usbTargetSampleRate} " +
                "bitPerfectPolicy=$bitPerfectPolicy effectiveBitPerfect=$effectiveBitPerfect dsd=$dsdActive"
        )

        if (effectiveBitPerfect && !dsdActive && recoveryPlan == null && !forceBitPerfectPolicyRestart) {
            AppLogger.i(TAG, "applyUsbOutputSettingsChanged: current track is effectively bit-perfect; resample settings staged for the next non-bit-perfect track")
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
        val settingsRestartPlayToken = expectedRestartPlayToken

        Log.i(
            TAG,
            "applyUsbOutputSettingsChanged: wasPlaying=$wasPlaying pos=${position}ms " +
                "usbTargetSr=${AppPreferences.Player.usbTargetSampleRate} usbTargetBits=${AppPreferences.Player.usbTargetBitDepth}"
        )

        _isRenderSwitching.value = true
        try {
            val rendererDrained = runCatching {
                ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
            }.onFailure {
                AppLogger.w(TAG, "applyUsbOutputSettingsChanged: ffmpeg drain failed", it)
            }.getOrDefault(false)
            if (!rendererDrained) {
                AppLogger.e(TAG, "applyUsbOutputSettingsChanged aborted: old playback Runnable did not exit")
                stopUsbExclusiveAfterFatalFailure(
                    reason = "usb_settings_worker_not_drained",
                    releaseManager = false,
                    notifyNativeDetached = false
                )
                return 0
            }
            try {
                usbExclusiveManager.stopStreaming("usb_output_settings_changed")
            } catch (_: Throwable) {
            }
            // Strict hardware-volume -> software-volume cannot pre-arm mutable PCM on the live
            // bit-perfect handle. Keep the physical FU attenuation until ISO is fully stopped,
            // then release it while no audio packets can expose a full-scale window.
            if (!releasePendingHardwareVolumeAfterTransportStop("usb_output_settings_changed")) {
                AppLogger.e(
                    TAG,
                    "USB settings transaction aborted: HW->SW Feature Unit release safety gate failed",
                )
                return 0
            }
            try {
                usbExclusiveManager.resetPlaybackPipeline("usb_output_settings_changed")
            } catch (t: Throwable) {
                AppLogger.e(TAG, "applyUsbOutputSettingsChanged: USB pipeline close failed", t)
                stopUsbExclusiveAfterFatalFailure(
                    reason = "usb_settings_close_failed",
                    releaseManager = false,
                    notifyNativeDetached = false,
                )
                return 0
            }

            if (sharedUsbAudioEngine.currentHandle != 0L || usbExclusiveManager.hasOpenConnection()) {
                AppLogger.e(TAG, "USB settings transaction refused: old handle/connection still live after reset")
                stopUsbExclusiveAfterFatalFailure(
                    reason = "usb_settings_old_session_still_live",
                    releaseManager = false,
                    notifyNativeDetached = false,
                )
                return 0
            }

            // The old session is now fully closed. It is finally safe to change process-global
            // PCM/DoP/Native-DSD state for the next nativeInitUsbDevice().
            sharedUsbAudioEngine.nativeSetUsbExclusiveActive(true)
            sharedUsbAudioEngine.setPolicy(
                exclusive = true,
                bitPerfect = UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(
                    bitPerfectPolicy,
                    AppPreferences.Player.usbVolumeMode,
                ) || fixedDigital,
                hwVol = hwVol,
            )
            sharedUsbAudioEngine.setDsdConversion(
                enabled = dsdActive,
                rate = currentEffectiveUsbDsdRate(),
                type = AppPreferences.Player.dsdConversionType,
                dither = AppPreferences.Player.dsdDitherEnabled,
                dop = effectiveDsdMode?.transport == UsbDsdTransport.DOP,
            )
            AppLogger.i(TAG, "USB settings transaction committed after old session close: dsd=$dsdActive")

            queueRendererRestartSeek(
                song = song,
                displayPositionMs = position,
                reason = "usb_output_settings_changed"
            )
            smForceTransition(PlayState.PREPARING, "applyUsbSettings")
            val restartQueue = if (queueSongs.isNotEmpty() && queueIndex in queueSongs.indices) {
                queueSongs
            } else {
                listOf(song)
            }
            val restartIndex = if (queueSongs.isNotEmpty() && queueIndex in queueSongs.indices) {
                queueIndex
            } else {
                0
            }
            val restartQueued = transportControlCoordinator.restartAfterSettingsIfUncontested(
                song = song,
                queue = restartQueue,
                index = restartIndex,
                expectedPlayRequestToken = settingsRestartPlayToken,
            )
            if (!restartQueued) {
                AppLogger.i(
                    TAG,
                    "USB settings restart skipped: explicit play superseded old song=${song.title} " +
                        "expectedToken=$settingsRestartPlayToken latest=${latestPlayRequestToken.get()}",
                )
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

    fun applyAudioOutputSettingsChanged(allowUsbExclusive: Boolean = false): Int {
        if (!allowUsbExclusive && (_usbExclusiveActive.value || ffmpegPlayer.usbExclusiveMode)) {
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
                "targetBits=${AppPreferences.Player.targetBitDepth} speed=${AppPreferences.Player.playbackSpeed} " +
                "allowUsbExclusive=$allowUsbExclusive"
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

    /**
     * Replaces only the physical decoder source for the currently committed semantic song.
     *
     * Queue/currentSong identity never changes. The replacement is serialized with all other
     * transport mutations, seeks the new decoder to the same playback timeline and restores the
     * previous paused/playing state. Passing a blank/null [playbackPath] returns to the original
     * song source. This primitive is intentionally AI-agnostic so prepared local media variants
     * can use it without making module:player depend on the app AI layer.
     */
    suspend fun switchCurrentPlaybackSource(
        expectedSong: AudioFile,
        playbackPath: String?,
        sourceTag: String = "media_variant",
        sampleRate: Int = 0,
        channels: Int = 0,
        bitsPerSample: Int = 0,
        format: String = "",
    ): PlaybackSourceSwitchResult {
        if (isReleased) return PlaybackSourceSwitchResult.PLAYER_RELEASED
        return transportMutex.withLock {
            if (isReleased) return@withLock PlaybackSourceSwitchResult.PLAYER_RELEASED
            val song = _currentSong.value ?: return@withLock PlaybackSourceSwitchResult.NO_CURRENT_SONG
            if (!samePlaybackItem(song, expectedSong)) {
                AppLogger.w(
                    TAG,
                    "SOURCE_OVERRIDE rejected stale expected=${expectedSong.path} current=${song.path} tag=$sourceTag",
                )
                return@withLock PlaybackSourceSwitchResult.STALE_SONG
            }

            val desired = PlaybackSourceOverridePolicy.normalizeForSong(
                song = song,
                playbackPath = playbackPath,
                sourceTag = sourceTag,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                format = format,
                generation = playbackSourceOverrideGeneration.incrementAndGet(),
            )
            if (desired != null && !desired.sourceExists()) {
                AppLogger.w(TAG, "SOURCE_OVERRIDE unavailable path=${desired.playbackPath} tag=$sourceTag")
                return@withLock PlaybackSourceSwitchResult.SOURCE_UNAVAILABLE
            }
            val rawCurrentOverride = _playbackSourceOverride.value
            val currentOverride = rawCurrentOverride?.takeIf { it.matches(song) && it.sourceExists() }
            if (rawCurrentOverride != null && currentOverride == null) {
                _playbackSourceOverride.value = null
            }
            if (PlaybackSourceOverridePolicy.sameTarget(currentOverride, desired)) {
                return@withLock PlaybackSourceSwitchResult.ALREADY_ACTIVE
            }

            val backendState = ffmpegPlayer.state
            val wasPlaying = _playState.value == PlayState.PLAYING ||
                backendState == FfmpegAudioPlayer.State.PLAYING ||
                backendState == FfmpegAudioPlayer.State.PREPARING
            val wasPaused = _playState.value == PlayState.PAUSED ||
                backendState == FfmpegAudioPlayer.State.PAUSED
            val wasActive = wasPlaying || wasPaused
            val timelinePositionMs = _position.value.coerceAtLeast(0L)
            val queueSnapshot = _queue.value

            AppLogger.i(
                TAG,
                "SOURCE_OVERRIDE switch from=${currentOverride?.playbackPath ?: song.path} " +
                    "to=${desired?.playbackPath ?: song.path} pos=$timelinePositionMs state=$backendState " +
                    "usbExclusive=${_usbExclusiveActive.value} tag=$sourceTag",
            )

            if (wasPlaying) {
                val fadeMs = TransitionPreferences.transportDurationOrZero().coerceIn(0, 160)
                if (fadeMs > 0) {
                    val outgoingMs = ((fadeMs + 1) / 2).coerceAtLeast(1)
                    val faded = withContext(Dispatchers.IO) {
                        ffmpegPlayer.fadeOutForTransitionBlocking(
                            durationMs = outgoingMs,
                            reason = "source_override_out:$sourceTag",
                        )
                    }
                    if (faded) {
                        ffmpegPlayer.armNextStartFadeIn(
                            (fadeMs - outgoingMs).coerceAtLeast(1),
                            "source_override_in:$sourceTag",
                        )
                    }
                }
            }

            runCatching { ffmpegPlayer.stop() }
                .onFailure { error -> AppLogger.w(TAG, "SOURCE_OVERRIDE stop failed", error) }
            _playbackSourceOverride.value = desired
            duplicatePlayRequestGate.clear()
            queueRendererRestartSeek(
                song = song,
                displayPositionMs = timelinePositionMs,
                reason = "source_override:$sourceTag",
            )

            if (wasActive) {
                smForceTransition(PlayState.PREPARING, "sourceOverride")
                if (queueSnapshot.songs.isNotEmpty() && queueSnapshot.currentIndex in queueSnapshot.songs.indices) {
                    playInternal(song, queueSnapshot.songs, queueSnapshot.currentIndex)
                } else {
                    playInternal(song)
                }
                if (wasPaused) {
                    scope.launch {
                        delay(500L)
                        val committed = _currentSong.value
                        if (committed != null && samePlaybackItem(committed, song) &&
                            (_playState.value == PlayState.PLAYING ||
                                ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                                ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING)
                        ) {
                            pause()
                        }
                    }
                }
            }
            PlaybackSourceSwitchResult.APPLIED
        }
    }

    /**
     * Changes the real PCM decoder speed and restarts the active stream at the
     * current position. Strict bit-perfect and native DSD remain at 1.00x.
     */
    fun setPlaybackSpeed(speed: Float): Int {
        val normalized = if (speed.isFinite()) speed.coerceIn(0.25f, 3f) else 1f
        val configuredSpeedBeforeRequest = AppPreferences.Player.playbackSpeed
        AppPreferences.Player.playbackSpeed = normalized
        val song = _currentSong.value
        if (song == null) {
            AppLogger.i(TAG, "Playback speed saved without active song: speed=$normalized")
            return 0
        }
        if (ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack()) {
            AppLogger.i(TAG, "Playback speed saved but strict bit-perfect keeps effective speed=1.00")
            return 0
        }
        val requestToken = playbackSpeedRequestToken.incrementAndGet()
        playbackSpeedRestartJob?.cancel()
        playbackSpeedRestartJob = scope.launch(Dispatchers.Default) {
            playbackSpeedRestartMutex.withLock {
                if (requestToken != playbackSpeedRequestToken.get() || isReleased) return@withLock
                restartActivePlaybackForSpeed(
                    requestedSpeed = normalized,
                    configuredSpeedBeforeRequest = configuredSpeedBeforeRequest,
                    requestToken = requestToken,
                )
            }
        }
        // The preference is committed synchronously; the decoder replacement is serialized on
        // the transport lane so an intentional same-song restart cannot be discarded as a
        // duplicate play request.
        return 0
    }

    private suspend fun restartActivePlaybackForSpeed(
        requestedSpeed: Float,
        configuredSpeedBeforeRequest: Float,
        requestToken: Long,
    ) {
        transportMutex.withLock {
            if (requestToken != playbackSpeedRequestToken.get() || isReleased) return@withLock

            val song = _currentSong.value ?: return@withLock
            val oldTimelinePosition = _position.value
            // FFmpegBridge exposes a speed-adjusted playback timeline: decoder duration is
            // sourceDuration / speed and seek maps playbackMs * speed back to the source.
            // Convert through source time when replacing the decoder so a 1x -> 2x change at
            // 01:00 does not jump to source 02:00.
            val previousEffectiveSpeed = ffmpegPlayer.currentDecoderPlaybackSpeedOrNull()
                ?: configuredSpeedBeforeRequest
            val safePreviousSpeed = if (previousEffectiveSpeed.isFinite()) {
                previousEffectiveSpeed.coerceIn(0.25f, 3f)
            } else {
                1f
            }
            val safeRequestedSpeed = if (requestedSpeed.isFinite()) requestedSpeed.coerceIn(0.25f, 3f) else 1f
            val sourcePositionMs = (oldTimelinePosition.toDouble() * safePreviousSpeed.toDouble())
                .toLong()
                .coerceAtLeast(0L)
            val restartTimelinePosition = (sourcePositionMs.toDouble() / safeRequestedSpeed.toDouble())
                .toLong()
                .coerceAtLeast(0L)
            val backendState = ffmpegPlayer.state
            val wasPlaying = _playState.value == PlayState.PLAYING ||
                backendState == FfmpegAudioPlayer.State.PLAYING ||
                backendState == FfmpegAudioPlayer.State.PREPARING
            val wasActive = wasPlaying ||
                _playState.value == PlayState.PAUSED ||
                backendState == FfmpegAudioPlayer.State.PAUSED
            if (!wasActive) {
                AppLogger.i(
                    TAG,
                    "PLAYBACK_SPEED_RESTART skipped inactive speed=$requestedSpeed state=$backendState",
                )
                return@withLock
            }

            val queue = _queue.value
            val queueSongs = queue.songs
            val queueIndex = queue.currentIndex
            AppLogger.i(
                TAG,
                "PLAYBACK_SPEED_RESTART start requested=$requestedSpeed previous=$safePreviousSpeed " +
                    "oldTimelineMs=$oldTimelinePosition sourceMs=$sourcePositionMs newTimelineMs=$restartTimelinePosition " +
                    "state=$backendState playState=${_playState.value} queueIndex=$queueIndex " +
                    "usbExclusive=${_usbExclusiveActive.value} strict=${ffmpegPlayer.isUsbBitPerfectEffectiveForCurrentTrack()}",
            )

            runCatching { ffmpegPlayer.stop() }
                .onFailure { error ->
                    AppLogger.w(TAG, "PLAYBACK_SPEED_RESTART stop failed", error)
                }

            // stop() retires the decoder synchronously, but the normal play request gate still
            // remembers the previous same-song request. This is an intentional restart, not a
            // duplicate tap, so it must always reach playInternal and open a new decoder.
            duplicatePlayRequestGate.clear()
            queueRendererRestartSeek(
                song = song,
                displayPositionMs = restartTimelinePosition,
                reason = "playback_speed_changed",
            )
            smForceTransition(PlayState.PREPARING, "playbackSpeedChanged")

            if (queueSongs.isNotEmpty() && queueIndex in queueSongs.indices) {
                playInternal(song, queueSongs, queueIndex)
            } else {
                playInternal(song)
            }
            AppLogger.i(
                TAG,
                "PLAYBACK_SPEED_RESTART queued requested=$requestedSpeed " +
                    "preference=${AppPreferences.Player.playbackSpeed}",
            )

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
        }
    }

    fun setInternalDoublePrecisionProcessing(enabled: Boolean) {
        AppPreferences.Player.internalDoublePrecisionProcessingEnabled = enabled
        ffmpegPlayer.internalDoublePrecisionProcessing = enabled
        AppLogger.i(TAG, "Internal DSP precision changed: double=$enabled")
    }

    /** Apply PCM output dither to the active player's integer conversion path. */
    fun setPcmDitherMode(mode: Int) {
        val normalized = PcmDitherMode.fromId(mode).id
        AppPreferences.Player.pcmDitherMode = normalized
        ffmpegPlayer.pcmDitherMode = normalized
        AppLogger.i(TAG, "PCM output dither changed: mode=$normalized")
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
        return usbHardwareVolumeCoordinator.canControl()
    }

    fun shouldUseUsbRemoteVolume(): Boolean {
        if (!_usbExclusiveActive.value) return false
        return when (resolveCurrentUsbOutputProfile()?.volumePath) {
            UsbVolumePath.HardwareUserVolume -> canControlUsbVolume()
            // Software gain is app-owned, but presenting it as a remote VolumeProvider makes
            // SystemUI render a cast/remote-style slider and introduces a second async UI owner.
            // Keep the MediaSession local and mirror STREAM_MUSIC into the native USB gain instead.
            UsbVolumePath.Software -> false
            else -> false
        }
    }

    fun setAndroidDvcEnabled(enabled: Boolean) {
        androidDvcController.setEnabled(
            enabled = enabled,
            usbExclusive = _usbExclusiveActive.value,
            reason = "settings",
        )
        applyComposedVolume()
        syncUsbRemoteVolumeRoute("dvc_settings_changed", force = true)
        syncSystemVolumeObserverForRoute("dvc_settings_changed")
    }

    fun setAndroidNoDvcHeadroomDb(gainDb: Float) {
        AppPreferences.Player.androidNoDvcHeadroomDb = gainDb.coerceIn(-24f, 0f)
        androidDvcController.refreshHeadroom(
            usbExclusive = _usbExclusiveActive.value,
            reason = "no_dvc_headroom_changed",
        )
    }

    private fun readDisplayedUsbHardwareVolumeStep(reason: String): Int? {
        return usbHardwareVolumeCoordinator.readDisplayedStep(reason)
    }

    private fun syncUsbRemoteVolumeRoute(reason: String, force: Boolean = false) {
        val desired = shouldUseUsbRemoteVolume()
        if (!force && lastUsbRemoteVolumeDesired == desired) return
        lastUsbRemoteVolumeDesired = desired

        AppLogger.i(TAG, "syncUsbRemoteVolumeRoute: desired=$desired reason=$reason")
        androidPlaybackServiceController.setUsbRemoteVolumeActive(
            active = desired,
            reason = reason,
        )
    }

    fun seedUsbHardwareVolumeStepFromUiVolume(): Int {
        return usbHardwareVolumeCoordinator.seedStepFromUiVolume()
    }

    /**
     * UI +/- 按钮统一入口。
     * 不直接写硬件 dB，只调用 setUserVolumeUnified → applyComposedVolume → applyUsbVolume。
     */
    fun adjustVolumeFromUiButton(deltaStep: Int) {
        mediaVolumeCoordinator.adjustFromUiButton(deltaStep)
    }

    /** 步进 USB 音量（实体键映射）。走统一入口。 */
    fun stepUsbVolume(delta: Float) = mediaVolumeCoordinator.step(delta)

    /** 获取当前 USB 硬件音量分贝值（从 DAC raw 读取，仅用于诊断，不回写 UI） */
    fun getUsbVolumeDb(): Float = mediaVolumeCoordinator.volumeDb()

    /** 直接设置 USB 音量（0..1 线性值）— 走统一入口 */
    fun setUsbVolumeLinear(linear: Float) = mediaVolumeCoordinator.setLinear(linear)

    // ========== MediaSession 后台硬件音量接口 ==========

    fun setUsbVolumeStepFromMediaSession(step: Int, reason: String) {
        mediaVolumeCoordinator.setMediaSessionStep(step, reason)
    }

    fun adjustUsbVolumeStepFromMediaSession(direction: Int, reason: String) {
        mediaVolumeCoordinator.adjustMediaSession(direction, reason)
    }

    fun getUsbVolumeStepForMediaSession(): Int = mediaVolumeCoordinator.mediaSessionStep()

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

    fun getUsbDeviceStatus(): UsbDeviceStatus =
        usbDeviceStatusCoordinator.build()

    fun restartUsbWithPolicy(
        exclusive: Boolean,
        bitPerfect: Boolean,
        hardwareVolumeRequested: Boolean
    ): Boolean = usbPolicyRestartCoordinator.restart(exclusive, bitPerfect, hardwareVolumeRequested)

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
        transportTransitioning = true
        scope.launch(Dispatchers.IO) {
            try {
                AppLogger.e(TAG, "USB transport lost, full recovery required")
                val rendererDrained = runCatching {
                    ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
                }.getOrDefault(false)
                if (!rendererDrained) {
                    AppLogger.e(TAG, "USB recovery aborted: playback worker did not exit")
                    withContext(Dispatchers.Main) {
                        stopUsbExclusiveAfterFatalFailure(
                            reason = "usb_recovery_worker_not_drained",
                            notifyNativeDetached = false
                        )
                    }
                    return@launch
                }

                // reference USB implementation separates the physical USB-device lifetime from the playback-stream
                // lifetime. A stalled native stream/ISO owner first rebuilds the current stream on
                // the same device handle; only an actually poisoned/lost physical session is
                // released and re-opened. Raw previously jumped directly to release(), which can
                // wedge close/re-wrap-sensitive DAC/Android-host combinations even though the
                // original device authorization and handle were still valid.
                val streamRecoveredInPlace = runCatching {
                    usbExclusiveManager.recoverCurrentPcmStreamInPlace(
                        "runtime_liveness_full_recovery",
                    )
                }.onFailure {
                    AppLogger.w(TAG, "USB in-place stream recovery threw; falling back to physical reopen", it)
                }.getOrDefault(false)

                if (streamRecoveredInPlace) {
                    AppLogger.i(
                        TAG,
                        "USB recovery retained physical device session; restarting current track",
                    )
                    if (wasPlaying && currentSong != null) {
                        try {
                            play(currentSong)
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed to resume playback after in-place USB stream recovery", e)
                        }
                    }
                    return@launch
                }

                AppLogger.w(
                    TAG,
                    "USB in-place stream recovery unavailable/failed; escalating to physical device reopen",
                )
                // UsbExclusiveManager is the only Kotlin lifecycle owner. Do not
                // call UsbAudioEngine.release() independently and then close the
                // same connection again through the manager.
                try {
                    usbExclusiveManager.release("runtime_recovery_physical_reopen")
                } catch (_: Throwable) {
                }

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
                    if (!activateUsbEngineForPlayback(device)) {
                        error("USB renderer was not armed during recovery")
                    }
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
                transportTransitioning = false
                recoveringUsb.set(false)
            }
        }
    }

    // ==================== Android system-audio interruption coordinator ====================

    private fun requestAudioFocus(): Boolean {
        if (
            !_usbExclusiveActive.value &&
            !androidAudioInterruptionController.hasAudioFocusSession()
        ) {
            volumeRouteCoordinator.prepareAndroidVolumeForPlaybackTakeover("audio_focus_acquire")
        }
        return androidAudioInterruptionController.requestAudioFocus()
    }

    private fun abandonAudioFocus() {
        androidAudioInterruptionController.abandonAudioFocus()
    }

    private fun clearAutomaticFocusResume(reason: String) {
        androidAudioInterruptionController.clearAutomaticFocusResume(reason)
    }

    /** Delegates Android-output spatialization policy to its extracted coordinator. */
    fun setAndroidSpatialAudioEnabled(enabled: Boolean): Boolean =
        androidSpatialPlaybackController.setPlatformSpatialEnabled(enabled)

    fun setAndroidBinauralSpatialEnabled(enabled: Boolean): Boolean =
        androidSpatialPlaybackController.setBinauralEnabled(enabled)

    fun setAndroidBinauralSpatialParameters(intensity: Float, room: Float) {
        androidSpatialPlaybackController.setBasicParameters(intensity, room)
    }

    fun setAndroidBinauralAdvancedParameters(
        brirEnabled: Boolean,
        separation: Float,
        headSizeCentimeters: Float,
        pinnaDetail: Float,
    ) {
        androidSpatialPlaybackController.setAdvancedParameters(
            brirEnabled = brirEnabled,
            separation = separation,
            headSizeCentimeters = headSizeCentimeters,
            pinnaDetail = pinnaDetail,
        )
    }

    fun setAndroidBinauralHeadTrackingEnabled(enabled: Boolean): Boolean =
        androidSpatialPlaybackController.setHeadTrackingEnabled(enabled)

    /**
     * Applies transient stem emphasis directly to the current playback PCM stream.
     * No separated audio or processing result is persisted.
     */
    fun setRealtimeStemProcessing(enabled: Boolean, mode: Int, strength: Float) {
        realtimeStemCoordinator.setProcessing(enabled, mode, strength)
    }

    fun setRealtimeStemEnabled(enabled: Boolean) {
        realtimeStemCoordinator.setEnabled(enabled)
    }

    fun setRealtimeStemMode(mode: Int) {
        realtimeStemCoordinator.setMode(mode)
    }

    fun setRealtimeStemStrength(strength: Float) {
        realtimeStemCoordinator.setStrength(strength)
    }

    fun recenterAndroidBinauralHeadTracking() {
        androidSpatialPlaybackController.recenterHeadTracking()
    }

    fun androidBinauralHeadTrackingCapability(): AndroidSpatialHeadTracker.Capability =
        androidSpatialPlaybackController.headTrackingCapability()

    fun refreshAudioFocusSettings() {
        androidAudioInterruptionController.refreshSettings()
    }

    internal fun ensureAudioFocusForService(reason: String): Boolean {
        val granted = androidAudioInterruptionController.requestAudioFocus()
        AppLogger.d(TAG, "AudioFocus: service request reason=$reason granted=$granted")
        return granted
    }

    internal fun releaseAudioFocusForService(reason: String) {
        AppLogger.d(TAG, "AudioFocus: service abandon reason=$reason")
        androidAudioInterruptionController.abandonAudioFocus()
    }

    private fun registerNoisyReceiver() {
        androidAudioInterruptionController.registerNoisyReceiver()
    }

    private fun unregisterNoisyReceiver() {
        androidAudioInterruptionController.unregisterNoisyReceiver()
    }

    fun startBluetoothSco(): Boolean =
        androidAudioInterruptionController.startBluetoothSco()

    fun stopBluetoothSco() {
        androidAudioInterruptionController.stopBluetoothSco()
    }

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) =
        transportControlCoordinator.play(song, queue, index)

    /**
     * Refresh the semantic auto-transition recipe when lyrics arrive asynchronously.
     * Lyrics are tied to the current renderer identity, so a new/gapless committed track clears
     * this field until its own LyricData is published by MainActivity.
     */
    fun onLyricsUpdated(song: AudioFile?, lyricData: LyricData?) {
        val current = _currentSong.value ?: return
        if (song != null && !samePlaybackItem(current, song)) {
            AppLogger.d(TAG, "AutoTransition: ignore stale lyrics song=${song.path} current=${current.path}")
            return
        }
        autoTransitionLyrics = lyricData
        if (_queue.value.songs.isNotEmpty()) {
            gaplessControlCoordinator.prepareNextSong()
        }
    }

    private fun samePlaybackItem(a: AudioFile, b: AudioFile): Boolean =
        playRequestResolver.sameItem(a, b)

    private fun rendererSourceMatches(requestedPath: String, rendererPath: String): Boolean =
        normalizeRendererSource(requestedPath) == normalizeRendererSource(rendererPath)

    private fun normalizeRendererSource(path: String): String {
        val trimmed = path.trim()
        val decodedFilePath = if (trimmed.startsWith("file://", ignoreCase = true)) {
            runCatching { android.net.Uri.parse(trimmed).path }.getOrNull()
        } else {
            null
        }
        return (decodedFilePath ?: trimmed)
            .replace('\\', '/')
            .trimEnd('/')
    }

    private fun samePlaybackQueue(left: List<AudioFile>, right: List<AudioFile>): Boolean =
        playRequestResolver.sameQueue(left, right)

    private fun resolveExplicitPlayQueue(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
    ): Pair<List<AudioFile>, Int> {
        val resolved = playRequestResolver.resolve(
            song = song,
            requestedQueue = queue,
            requestedIndex = index,
            currentQueue = _queue.value.songs,
        )
        return resolved.queue to resolved.index
    }

    private fun applyExplicitShuffleSelectionPolicy(queue: List<AudioFile>, index: Int) {
        if (!playbackModeController.isShuffleEnabled ||
            !samePlaybackQueue(_queue.value.songs, queue)
        ) return
        val forceContinuousShuffle = shuffleQueueController.onExplicitSelection(_queue.value, index)
        if (forceContinuousShuffle && playbackModeController.currentPlayMode == PlayMode.SHUFFLE_ONCE) {
            playbackModeController.setPlayMode(PlayMode.SHUFFLE_ALL)
        }
    }

    private fun shouldRouteExplicitPlayThroughManualSwitch(song: AudioFile): Boolean =
        playRequestResolver.shouldUseManualSwitch(
            currentSong = _currentSong.value,
            requestedSong = song,
            controllerPlaying = _playState.value == PlayState.PLAYING,
            enginePlaying = ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING,
            usbExclusiveActive = _usbExclusiveActive.value,
            configuredManualFadeMs = configuredManualTransitionMs(),
        )

    private fun tryAutoRearmUsbExclusiveForPlayback(song: AudioFile): Boolean {
        return usbAutoRearmCoordinator.tryRearm(song)
    }

    /**
     * Debug-only v2 playback requires exclusive ownership before creating a v2
     * session. PlayerController.stop() is queued and ffmpegPlayer.stop() leaves
     * USB lifecycle ownership to PlayerController, so the manual smoke/gray path
     * needs a synchronous preflight that drains decoder writes and closes the
     * legacy native handle before UsbExclusiveManager checks for legacy-usb-active.
     */
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
    private fun playInternal(
        song: AudioFile,
        queue: List<AudioFile> = emptyList(),
        index: Int = 0,
        deferPublicCommitToRenderer: Boolean = false,
    ) {
        pausedSelectionPendingStart = false
        if (!deferPublicCommitToRenderer) pendingRendererUiCommit = null
        Log.d(TAG, "play() called: title=${song.title}, path=${song.path}, isReleased=$isReleased")
        if (isReleased) {
            Log.w(TAG, "play() skip: isReleased")
            return
        }
        _playbackSourceOverride.value?.let { activeOverride ->
            if (!activeOverride.matches(song)) {
                AppLogger.i(
                    TAG,
                    "SOURCE_OVERRIDE cleared on semantic track change owner=${activeOverride.semanticPath} next=${song.path}",
                )
                _playbackSourceOverride.value = null
            } else if (!activeOverride.sourceExists()) {
                AppLogger.w(
                    TAG,
                    "SOURCE_OVERRIDE source disappeared; fallback to original path=${activeOverride.playbackPath}",
                )
                _playbackSourceOverride.value = null
            }
        }
        val rendererSong = PlaybackSourceOverridePolicy.rendererSong(song, _playbackSourceOverride.value)
        val playbackPath = rendererSong.path
        if (_usbExclusiveActive.value) {
            invalidateUsbSelfTest("play_request:${song.path}", clearSessionKey = true)
        }
        // Fresh playback already rebuilds the stream, so retain the recovery
        // profile until the runtime acceptance gate verifies and persists it.
        if (pendingUsbPolicyRestart) {
            AppLogger.i(TAG, "playInternal: applying pending USB profile on fresh stream plan=$pendingUsbRecoveryPlan")
            pendingUsbPolicyRestart = false
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
                    playInternal(
                        song = song,
                        queue = queue,
                        index = index,
                        deferPublicCommitToRenderer = deferPublicCommitToRenderer,
                    )
                }
            }
            return
        }
        // 播放请求风暴防护：列表点击、场景切换和恢复回调可能在 1 秒内重复发同一首。
        val ffState = ffmpegPlayer.state
        val duplicateBusy = ffState == FfmpegAudioPlayer.State.PREPARING ||
            ffState == FfmpegAudioPlayer.State.PLAYING ||
            ffState == FfmpegAudioPlayer.State.PAUSED
        if (
            duplicatePlayRequestGate.shouldIgnore(
                song = song,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                backendPreparingPlayingOrPaused = duplicateBusy,
            )
        ) {
            Log.w(TAG, "Duplicate play request ignored: state=$ffState path=${song.path}")
            _currentSong.value?.let { current ->
                if (samePlaybackItem(current, song)) clearRequestedSongIfCommitted(song)
            }
            return
        }

        lastPlayerError = null
        precacheJob?.cancel()

        _currentSong.value?.let { current ->
            // Queue navigation/history policy lives outside the renderer controller.
            queueControlCoordinator.recordCurrentSongBeforePlay(current, song)
        }

        androidPlaybackServiceController.ensurePlaybackWakeLock("play_request")

        val playbackIsRemoteUrl = playbackPath.startsWith("http://") || playbackPath.startsWith("https://")
        val semanticIsRemoteUrl = song.path.startsWith("http://") || song.path.startsWith("https://")
        if (playbackPath.isBlank() || (!playbackIsRemoteUrl && !java.io.File(playbackPath).exists())) {
            Log.w(TAG, "play() skip: renderer source not found: $playbackPath semantic=${song.path}")
            if (deferPublicCommitToRenderer) {
                pendingRendererUiCommit
                    ?.takeIf { samePlaybackItem(it.song, song) }
                    ?.let { pendingRendererUiCommit = null }
            }
            val unavailableResult = backendStateControlCoordinator.handleUnavailableSource(
                song = song,
                queue = queue,
                index = index,
            )
            if (
                unavailableResult ==
                PlayerBackendStateControlCoordinator.UnavailableSourceResult.STOPPED_FAILURE_FUSE
            ) {
                try {
                    android.widget.Toast.makeText(
                        context,
                        "连续播放失败，已停止",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } catch (_: Exception) {
                }
            }
            return
        }

        try {
            if (!deferPublicCommitToRenderer) {
                playbackQueueCommitter.commit(song, queue, index)
            } else if (pendingRendererUiCommit?.song?.let { samePlaybackItem(it, song) } != true) {
                pendingRendererUiCommit = PendingRendererUiCommit(
                    song = song,
                    queue = queue.toList(),
                    index = index,
                    prepareFollowingAfterCommit = true,
                )
            }
            autoTransitionLyrics = null
            ffmpegPlayer.updateAutoTransitionRecipe(null)
            if (!deferPublicCommitToRenderer) {
                // Explicit/cold starts have no retiring renderer to remain authoritative. Manual
                // live switches persist/report only after TRACK_STARTED owns the new timeline.
                persistSelectedSongForColdStart(song)
                clearRequestedSongIfCommitted(song)
                AppLogger.markPlaybackReportStart(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    path = song.path,
                    cueOffsetMs = song.cueOffsetMs
                )
                FFmpegBridge.resetDebugLog("playback_report_start:${song.path}")
            }
            // Route/cold renderer restarts preserve the visible position while the new
            // decoder is being opened. Manual/new-song requests clear the latch before
            // entering here and therefore still start from 0 as before.
            val rendererRestartDisplayPosition = RendererRestartTimelinePolicy.initialDisplayPositionMs(
                pendingPositionMs = pendingSeekPosition,
                pendingPath = pendingSeekPath,
                songPath = song.path,
            )
            if (!deferPublicCommitToRenderer) {
                _position.value = rendererRestartDisplayPosition
                playbackStatsTracker.reset()
                cachedCueOffsetMs = song.cueOffsetMs
                cachedCueEndMs = song.cueEndMs
                cachedSongDuration = song.duration
                if (song.duration > 0) _duration.value = song.duration
            }
            applyReplayGain(song)
            // Shared Android USB Audio must not touch the exclusive activation path at all.
            // Besides preserving user intent, this keeps rapid shared-route track changes away
            // from USB permission/device probing and exclusive renderer state.
            if (AppPreferences.Player.usbExclusiveRequested) {
                tryAutoRearmUsbExclusiveForPlayback(rendererSong)
            }

            if (!deferPublicCommitToRenderer) {
                metadataEnrichmentCoordinator.start(
                    song = song,
                    remoteUrl = semanticIsRemoteUrl,
                )
            }

            Log.d(TAG, "Starting FFmpeg playback: $playbackPath semantic=${song.path}")
            if (!requestAudioFocus()) {
                Log.w(TAG, "AudioFocus denied, playback may be interrupted")
            }
            registerNoisyReceiver()

            // 蓝牙 SCO 模式：启动 SCO 连接（异步，不阻塞主线程）
            // AudioTrack 会立即使用 SCO AudioAttributes 创建
            // SCO 连接成功后 AndroidAudioInterruptionController 会重建 AudioTrack 确保路由正确
            if (AudioOutputManager.shouldUseScoMode(context)) {
                Log.i(TAG, "SCO mode enabled, starting Bluetooth SCO...")
                if (startBluetoothSco()) {
                    Log.i(TAG, "SCO start requested, AudioTrack will be rebuilt on SCO connected")
                } else {
                    Log.w(TAG, "Failed to start SCO, proceeding with normal playback")
                }
            }

            // Publish the USB media identity and foreground/wake-lock contract before
            // native start. The AudioTrack carrier itself is deliberately not created
            // here; it starts only from ffmpegPlayer.onUsbPlaybackStarted.
            if (_usbExclusiveActive.value) {
                enterUsbCriticalStartup("play_usb_exclusive")
                ensureUsbMediaIdentity("play_usb_exclusive_before_native", song, _position.value)
                ensureUsbForegroundImmediate("play_usb_exclusive_before_native")
                sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(
                    true,
                    "pre_native_media_identity"
                )
            }

            if (!_usbExclusiveActive.value) {
                ffmpegPlayer.armDefaultStartFadeIn(
                    durationMs = TransitionPreferences.transportDurationOrZero(),
                    reason = "play_internal_start"
                )
            }
            // Cold restore must reach the decoder before it starts. Delaying this seek lets
            // the UI briefly show the remembered position while audio actually begins at zero.
            if (pendingSeekPosition > 0L && pendingSeekPath == song.path) {
                val restoredPosition = pendingSeekPosition
                queueRendererRestartSeek(song, restoredPosition, "cold_restore_before_play")
                if (!deferPublicCommitToRenderer) _position.value = restoredPosition
                verifyRestoreStartSeek(song, restoredPosition)
            }
            ffmpegPlayer.play(playbackPath)

            // No PlayState polling or delayed carrier retry here. The confirmed native
            // stream-start callback owns creation, matching the carrier's actual lifecycle.

            // A renderer-deferred manual switch still exposes the old queue/current item here.
            // Preparing from that public snapshot would arm the wrong following track. The
            // TRACK_STARTED commit performs this step for cold-reopen manual switches.
            if (!deferPublicCommitToRenderer) {
                gaplessControlCoordinator.prepareNextSong()
            }

            scope.launch {
                delay(1000)
                androidBluetoothOutputController.refreshNow()
            }

            if (song.cueOffsetMs > 0) {
                val cueOffset = song.cueOffsetMs
                Log.d(TAG, "CUE track: seeking to ${cueOffset}ms for ${song.title}")
                pendingRestoreSeekJob?.cancel()
                pendingRestoreSeekJob = scope.launch {
                    delay(300)
                    val cur = _currentSong.value
                    val committedMatches = cur?.path == song.path && cur.cueOffsetMs == song.cueOffsetMs
                    val deferredMatches = pendingRendererUiCommit?.song?.let { pending ->
                        pending.path == song.path && pending.cueOffsetMs == song.cueOffsetMs
                    } == true
                    if (!committedMatches && !deferredMatches) {
                        AppLogger.w(TAG, "cue seek ignored: song changed during delay")
                        return@launch
                    }
                    if (ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
                        ffmpegPlayer.seekTo(cueOffset)
                        // Keep the retiring song's public timeline intact until renderer commit.
                        if (_currentSong.value?.let { samePlaybackItem(it, song) } == true) {
                            _position.value = 0L
                        }
                        playbackProgressController.markSeekPerformed()
                    }
                }
            } else if (pendingSeekPosition > 0) {
                pendingSeekPosition = -1L
                pendingSeekPath = null
            }

            if (!deferPublicCommitToRenderer) {
                precacheNextSong()
                saveState()
            }
        } catch (e: Exception) {
            if (deferPublicCommitToRenderer) {
                pendingRendererUiCommit
                    ?.takeIf { samePlaybackItem(it.song, song) }
                    ?.let { pendingRendererUiCommit = null }
            }
            Log.e(TAG, "play() failed", e)
            if (queue.size > 1) {
                val nextIndex = (index + 1) % queue.size
                play(queue[nextIndex], queue, nextIndex)
            }
        }
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0) =
        transportControlCoordinator.playQueue(songs, startIndex)

    /** Creates one Reference-style shuffle session, including category grouping semantics. */
    fun planShuffleSession(
        songs: List<AudioFile>,
        categoryGroups: List<List<AudioFile>>? = null,
    ): List<AudioFile> = shuffleQueueController.planSession(songs, categoryGroups)

    /** Plays an already planned order without applying a second flat randomization pass. */
    fun playPlannedShuffleQueue(songs: List<AudioFile>) {
        if (songs.isEmpty()) return
        shuffleQueueController.armPlannedTraversal(songs)
        setPlayMode(PlayMode.SHUFFLE_ONCE)
        playQueue(songs, 0)
    }

    private var precacheJob: Job? = null

    fun precacheNextSong() {
        precacheJob?.cancel()
        if (isReleased) return
        if (!com.rawsmusic.module.data.prefs.AppPreferences.Player.gaplessPlaybackEnabled) return
        val q = _queue.value
        if (q.songs.isEmpty()) return
        val nextIdx = if (playbackModeController.isShuffleEnabled) {
            shuffleQueueController.peekNextIndex(q)
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
    private fun resolvePlayPauseSeedSong(): AudioFile? =
        playPauseSeedCoordinator.resolve()

    fun playPause() = transportControlCoordinator.playPause()

    fun pause() = transportControlCoordinator.pause()

    private suspend fun pauseUsbWarmInternal() {
        transportTransitioning = true
        AppLogger.w(TAG, "USB_WARM_PAUSE_START")
        try {
            val pausedPosition = _position.value.coerceAtLeast(0L)
            val pausedSong = _currentSong.value
            // DSD stop/pause destroys the active audio transport.
            // Never let a DSD or PCM→DSD handle enter the ordinary PCM warm-standby path:
            // the RAW/DoP altsetting, packer phase and per-session modulator state must be
            // released together with the Java UsbDeviceConnection.
            if (usbExclusiveManager.isDsdSessionActive()) {
                ffmpegPlayer.pauseDecoderOnly("manual_pause_dsd_destroy")
                usbSystemAudioKeepAlive.stop("manual_pause_dsd_destroy")
                usbExclusiveManager.pauseStreaming("manual_pause_dsd_destroy")
                smTransition(PlayState.PAUSED, "pause_dsd_destroy")
                PlayerService.syncUsbMediaIdentityFromController(
                    song = _currentSong.value,
                    playing = false,
                    position = _position.value.coerceAtLeast(0L),
                    reason = "manual_pause_dsd_destroy",
                )
                androidPlaybackServiceController.sendUsbMediaIdentity(
                    reason = "manual_pause_dsd_destroy",
                    song = _currentSong.value,
                    positionMs = _position.value,
                    playing = false,
                )
                stopProgressUpdate()
                savePosition()
                saveState()
                AppLogger.i(TAG, "USB DSD pause: transport and device connection fully destroyed")
                return
            }

            if (isUsbHardwareVolumeRouteActive()) {
                AppLogger.i(TAG, "USB warm pause keeps Feature Unit at the user value")
            } else {
                fadeUsbExclusiveSessionTo(
                    target = 0.0f,
                    fadeMs = TransitionPreferences.transportDurationOrZero(),
                    reason = "manual_pause_warm_down",
                )
                applyUsbNoDataSafetyVolume("before_usb_pause")
            }
            // Ordinary pause keeps the USB engine warm. The optional bandwidth-release policy may
            // move to Alt 0, but the decoder and media identity remain one serialized operation.
            ffmpegPlayer.pauseDecoderOnly("manual_pause_warm")
            val silenced = sharedUsbAudioEngine.pauseToSilence("manual_pause_warm")
            AppLogger.i(
                TAG,
                "USB_PAUSE_TRACE immediate_silence=$silenced path=${pausedSong?.path} position=$pausedPosition"
            )
            // The carrier releases its mute-data AudioTrack on pause, even while native USB remains warm.
            usbSystemAudioKeepAlive.stop("manual_pause_warm")
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
                reason = "manual_pause_warm",
            )
            androidPlaybackServiceController.sendUsbMediaIdentity(
                reason = "manual_pause_warm",
                song = _currentSong.value,
                positionMs = _position.value,
                playing = false,
            )
            stopProgressUpdate()
            savePosition()
            saveState()
        } finally {
            transportTransitioning = false
        }
    }

    private fun rememberUsbPausedResumePoint(reason: String) {
        val song = _currentSong.value ?: return
        val displayPosition = _position.value.coerceAtLeast(0L)
        if (displayPosition <= 0L) return
        pendingSeekPosition = displayPosition
        pendingSeekPath = song.path
        playbackStatePersistenceController.savePosition()
        AppLogger.i(
            TAG,
            "USB_PAUSE_TRACE checkpoint reason=$reason path=${song.path} position=$displayPosition"
        )
    }

    private fun pauseSystemImmediateUi() {
        // UI and MediaSession react immediately; the backend may finish its short fade later.
        smTransition(PlayState.PAUSED, "pause_immediate_ui")
        stopProgressUpdate()
        PlayerService.syncUsbMediaIdentityFromController(
            song = _currentSong.value,
            playing = false,
            position = _position.value.coerceAtLeast(0L),
            reason = "manual_pause_immediate_ui",
        )
    }

    private suspend fun pauseSystemBackendInternal() {
        withContext(Dispatchers.IO) {
            ffmpegPlayer.pauseWithFadeBlocking(
                durationMs = TransitionPreferences.transportDurationOrZero(),
                reason = "manual_pause",
            )
        }
        savePosition()
        saveState()
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
            usbBackgroundPlaybackCoordinator.scheduleIdleRelease("app_background_idle")
        }

        if (
            _playState.value == PlayState.PLAYING ||
            _playState.value == PlayState.PREPARING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        ) {
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
        // ProcessLifecycleOwner reports background after the last Activity pause. Arm protection
        // here so OEM schedulers cannot freeze the decoder/libusb threads in that gap.
        if (!appInBackground) {
            appInBackground = true
            appBackgroundEnteredAtElapsedMs = SystemClock.elapsedRealtime()
        }
        reinforceUsbBackgroundPlayback("activity_paused_usb_playing")
        ensureUsbForegroundImmediate("activity_paused_usb_playing")
        AppLogger.i(TAG, "USB playing while activity paused: foreground protection asserted early")
    }

    fun shouldSustainUsbBackgroundPlayback(): Boolean {
        // Runtime playback state is authoritative. A persisted "last state was PLAYING" value is
        // only a cold-start hint and must never keep MediaSession/FGS advertising active audio after
        // the decoder/controller is actually paused or stopped. The coordinator already covers the
        // short USB route/session gaps by observing both controller and backend PREPARING/PLAYING.
        return usbBackgroundPlaybackCoordinator.shouldSustain()
    }

    fun reinforceUsbBackgroundPlayback(reason: String) {
        if (!shouldSustainUsbBackgroundPlayback()) return
        sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(true, "reinforceUsbBackgroundPlayback:$reason")
        syncUsbSystemAudioKeepAlive(reason)
        ensureUsbMediaIdentity(reason, _currentSong.value, _position.value)
        androidPlaybackServiceController.acquireUsbPlaybackWakeLock(reason)

        if (usbBackgroundPlaybackCoordinator.shouldLogReinforce()) {
            AppLogger.i(
                TAG,
                "reinforceUsbBackgroundPlayback: reason=$reason playState=${_playState.value} " +
                    "ffState=${ffmpegPlayer.state} pos=${_position.value}"
            )
        }
    }

    fun verifyUsbBackgroundPlaybackHealth(reason: String) {
        // Runtime watchdog intentionally disabled. Keep only route/process ownership; never
        // sample throughput, reopen USB, change policy, or trip PCM->DSD from a periodic tick.
        if (shouldSustainUsbBackgroundPlayback()) {
            reinforceUsbBackgroundPlayback("health_tick_keepalive:$reason")
        }
    }

    fun resume() = transportControlCoordinator.resume()

    private suspend fun resumeInternal() {
        resumeCoordinator.resume()
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

    fun stop() = transportControlCoordinator.stop()

    private suspend fun stopInternal() {
        clearPendingUiSelection()
        AppLogger.i(TAG, "stop() called usbExclusive=${_usbExclusiveActive.value}")
        savePosition()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        val transportFadeMs = TransitionPreferences.transportDurationOrZero()
        if (_usbExclusiveActive.value) {
            // USB exclusive has one transport-fade owner: the native session envelope. Strict
            // bit-perfect, hardware/fixed and DSD routes reject SessionPcm ownership, so STOP
            // never multiplies those payloads just to manufacture a fade.
            if (_playState.value == PlayState.PLAYING && transportFadeMs > 0) {
                fadeUsbExclusiveSessionTo(
                    target = 0.0f,
                    fadeMs = transportFadeMs,
                    reason = "player_stop",
                )
            }
            usbSystemAudioKeepAlive.stop("player_stop")
            val rendererDrained = ffmpegPlayer.stopForUsbExclusiveCutover(timeoutMs = 5_000L)
            if (rendererDrained) {
                usbExclusiveManager.stopStreaming("player_stop")
            } else {
                AppLogger.e(TAG, "player_stop kept USB session alive: playback Runnable did not exit")
            }
        } else {
            if (_playState.value == PlayState.PLAYING && transportFadeMs > 0) {
                ffmpegPlayer.fadeOutForTransitionBlocking(
                    durationMs = transportFadeMs,
                    reason = "player_stop",
                )
            }
            ffmpegPlayer.stop()
        }
        smTransition(PlayState.STOPPED, "stop")
        stopProgressUpdate()
        backendStateControlCoordinator.resetFailures()
        saveState()
    }

    /**
     * 应用从后台恢复时调用
     * 仅处理 WakeLock 持有，AudioTrack 检查已移入 resume() 流程
     */
    fun onAppForegroundResumed() {
        if (isReleased) return
        val returningFromBackground = appInBackground
        Log.i(TAG, "App resumed from background, ensuring WakeLock")
        appInBackground = false
        usbBackgroundPlaybackCoordinator.cancelIdleRelease()
        sharedUsbAudioEngine.setBackgroundPlaybackActiveSafely(false, "app_foreground_resumed")
        tryActivateDeferredUsbExclusiveOnForeground("app_foreground_resumed")

        // 1. 确保 WakeLock 持有
        androidPlaybackServiceController.ensurePlaybackWakeLock("app_foreground_resumed")

        // 2. USB 独占模式：确保 USB WakeLock 持有
        if (_usbExclusiveActive.value) {
            androidPlaybackServiceController.ensureUsbPlaybackForeground(
                reason = "app_foreground_resumed",
            )
            recoverDeferredUsbOnForegroundIfNeeded()
        } else if (AppPreferences.Player.usbExclusiveRequested) {
            val song = _currentSong.value
            if (song != null) {
                AppLogger.i(TAG, "App foreground rearm: USB exclusive requested, current inactive; attempting rearm")
                tryAutoRearmUsbExclusiveForPlayback(song)
                if (_usbExclusiveActive.value) {
                    ensureUsbMediaIdentity("foreground_rearm_after_cold_resume", song, _position.value)
                    applyVolumeRoute("foreground_rearm_after_cold_resume")
                }
            } else {
                AppLogger.i(TAG, "App foreground rearm deferred: last USB exclusive active but no current song yet")
            }
        }
        androidAudioInterruptionController.maybeResumeOnAppForeground(returningFromBackground)
    }

    private fun recoverDeferredUsbOnForegroundIfNeeded() {
        val nativeState = sharedUsbAudioEngine.getNativeStreamState()
        val playing = _playState.value == PlayState.PLAYING || ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING
        val nativeBroken =
            nativeState == UsbAudioEngine.NativeStreamState.BROKEN ||
                sharedUsbAudioEngine.isNativeSessionBroken()
        // Native transport ownership is authoritative. If the event/transfer owner died while the
        // Activity was backgrounded, do not require an earlier Kotlin policy flag before reopening.
        // This mirrors the reference USB runtime wait/completion lifecycle: a dead stream owner is torn down and
        // reopened even when its last throughput sample looked healthy.
        if (playing && nativeBroken) {
            AppLogger.w(
                TAG,
                "Foreground found broken USB transport; reopening immediately nativeState=$nativeState",
            )
            pendingUsbPolicyRestart = true
            scope.launch { recoverUsbExclusiveAsync() }
            return
        }
        if (!pendingUsbPolicyRestart) return
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

    private var usbSeekExecutionJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var usbSeeking = false

    fun isUsbSeekingNow(): Boolean = usbSeeking

    private fun isSameSongIdentity(a: AudioFile?, b: AudioFile?): Boolean {
        if (a == null || b == null) return false
        return a.path == b.path &&
            a.cueOffsetMs == b.cueOffsetMs &&
            a.cueTrackIndex == b.cueTrackIndex
    }

    private fun isUsbSeekRuntimeReady(): Boolean = UsbSeekRuntimePolicy.isReady(
        UsbSeekRuntimePolicy.Snapshot(
            usbExclusiveActive = _usbExclusiveActive.value,
            publicPreparing = _playState.value == PlayState.PREPARING,
            decoderPreparing = ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING,
            transportTransitioning = transportTransitioning,
            seekAlreadyRunning = usbSeeking,
            handle = sharedUsbAudioEngine.currentHandle,
            engineInitialized = sharedUsbAudioEngine.isInitialized(),
        )
    )

    private fun deferUsbSeekUntilReady(
        song: AudioFile,
        realSeekMs: Long,
        displaySeekMs: Long,
        keepPaused: Boolean,
        reason: String
    ) {
        deferredUsbSeekCoordinator.defer(
            song = song,
            realSeekMs = realSeekMs,
            displaySeekMs = displaySeekMs,
            keepPaused = keepPaused,
            reason = reason,
        )
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

    fun seekTo(positionMs: Long, userInitiated: Boolean = true) =
        seekControlCoordinator.seekTo(positionMs, userInitiated)

    private suspend fun executePausedSeekRequest(
        song: AudioFile,
        realSeekMs: Long,
        displaySeekMs: Long,
    ) {
        if (isReleased || !isSameSongIdentity(_currentSong.value, song)) return
        if (_usbExclusiveActive.value && isUsbSeekRuntimeReady()) {
            deferredUsbSeekCoordinator.cancel()
            usbSeekExecutionJob?.cancel()
            transportMutex.withLock {
                seekUsbExclusiveInternal(realSeekMs, displaySeekMs, keepPaused = true)
            }
            return
        }
        executeSeekRequest(
            song = song,
            realSeekMs = realSeekMs,
            displaySeekMs = displaySeekMs,
            keepPaused = true,
        )
    }

    private fun executeSeekRequest(
        song: AudioFile,
        realSeekMs: Long,
        displaySeekMs: Long,
        keepPaused: Boolean,
    ) {
        if (isReleased || !isSameSongIdentity(_currentSong.value, song)) return

        AppLogger.i(
            TAG,
            "seekTo execute: display=$displaySeekMs real=$realSeekMs " +
                "usbExclusive=${_usbExclusiveActive.value} keepPaused=$keepPaused"
        )

        if (_usbExclusiveActive.value) {
            if (!isUsbSeekRuntimeReady()) {
                AppLogger.w(
                    TAG,
                    "seekTo deferred: display=$displaySeekMs real=$realSeekMs handle=0x${
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
            deferredUsbSeekCoordinator.cancel()
            usbSeekExecutionJob?.cancel()
            usbSeekExecutionJob = scope.launch {
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
                if (!isReleased && isSameSongIdentity(_currentSong.value, song) &&
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
                // Warm seek is software/session-envelope only. Feature Unit stays at
                // the device-scoped user value and is never restored from a callback.
            }

            // 2. seek decoder。Controller 已完成 nativePrepareForSeek，避免内部重复 flush。
            ffmpegPlayer.seekTo(realSeekMs, usbPrepareAlreadyDone = usbPrepared, keepPaused = keepPaused)

            _position.value = displaySeekMs
            playbackProgressController.markSeekPerformed()

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
    private fun configuredManualTransitionMs(): Int = ManualTrackTransitionPolicy.durations(
        mode = TransitionPreferences.manualTrackTransitionMode,
        shortFadeMs = TransitionPreferences.shortManualFadeMs,
        fullCrossfadeMs = TransitionPreferences.fullManualCrossfadeMs,
    ).overlapMs

    private suspend fun playManualSwitchFromStartLocked(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        reason: String,
        isStillLatestRequest: () -> Boolean = { true },
    ) {
        PlayerSwitchTrace.mark(
            "transport_locked_enter",
            "reason=$reason targetId=${song.id} index=$index thread=${Thread.currentThread().name}",
        )
        val oldPos = _position.value
        val preservePausedState =
            _playState.value == PlayState.PAUSED ||
                ffmpegPlayer.state == FfmpegAudioPlayer.State.PAUSED
        val rendererOwnedCurrentAtRequest =
            !preservePausedState &&
                !_usbExclusiveActive.value &&
                _currentSong.value != null &&
                (_playState.value == PlayState.PLAYING ||
                    _playState.value == PlayState.PREPARING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                    ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING)
        queueControlCoordinator.clearPreviousRestartBypass()
        AppLogger.w(
            TAG,
            "MANUAL_SWITCH_START oldPos=$oldPos newSong=${song.title} start=0 reason=$reason " +
                "thread=${Thread.currentThread().name}"
        )

        var playInternalAfterSwitch = true
        var deferUiCommitToRenderer = rendererOwnedCurrentAtRequest
        transportTransitioning = true
        try {
            cancelPendingRestoreSeek(reason)
            // 清除 pending seek，确保 playInternal 不会恢复旧位置
            pendingSeekPosition = -1L
            pendingSeekPath = null

            val manualDurations = ManualTrackTransitionPolicy.durations(
                mode = TransitionPreferences.manualTrackTransitionMode,
                shortFadeMs = TransitionPreferences.shortManualFadeMs,
                fullCrossfadeMs = TransitionPreferences.fullManualCrossfadeMs,
            )
            val manualTransitionMs = manualDurations.overlapMs
            val manualSequentialFallbackMs = manualDurations.sequentialFallbackMs
            PlayerSwitchTrace.mark(
                "transport_transition_policy",
                "reason=$reason preservePaused=$preservePausedState usb=${_usbExclusiveActive.value} overlapMs=$manualTransitionMs fallbackMs=$manualSequentialFallbackMs rendererOwned=$rendererOwnedCurrentAtRequest",
            )
            if (!preservePausedState && _usbExclusiveActive.value && _playState.value == PlayState.PLAYING) {
                val softNext = canUseUsbSoftNextFor(song)
                val preSwitchFadeMs = if (softNext) {
                    usbExclusiveManualTrackFadeMs().also { fadeMs ->
                        fadeUsbExclusiveTrackBoundary(
                            fadeMs = fadeMs,
                            reason = "same_profile_before_feeder:$reason",
                        )
                    }
                } else {
                    0
                }
                // The feeder can commit and accept the first new-track PCM before this
                // coroutine resumes from requestUsbSameProfileTrackSwitch(). Arm the matching
                // incoming envelope now, not after acknowledgement, so the first real PCM after
                // the native silence barrier can never escape at full scale.
                if (softNext && !isUsbHardwareVolumeRouteActive()) {
                    pendingManualTrackStartFadeMs = preSwitchFadeMs
                }
                val inlineResult = if (softNext) {
                    ffmpegPlayer.requestUsbSameProfileTrackSwitch(
                        nextPath = song.path,
                        reason = reason,
                    )
                } else {
                    FfmpegAudioPlayer.UsbManualSwitchResult.REJECTED
                }
                if (inlineResult == FfmpegAudioPlayer.UsbManualSwitchResult.TIMED_OUT) {
                    AppLogger.e(
                        TAG,
                        "USB same-profile switch feeder acknowledgement timed out; " +
                            "stop safely instead of overlapping a cold reopen reason=$reason",
                    )
                    stopUsbExclusiveAfterFatalFailure(
                        reason = "usb_same_profile_switch_ack_timeout",
                        notifyNativeDetached = false,
                    )
                    return
                }
                val inlineUsbSwitch =
                    inlineResult == FfmpegAudioPlayer.UsbManualSwitchResult.COMMITTED
                if (inlineUsbSwitch) {
                    // Same-profile handoff: keep the active feeder,
                    // USB handle/interface/alt/event/submit threads and URB pool.
                    // The feeder owns the native PCM-generation cut.
                    val usbFadeMs = preSwitchFadeMs
                    // pendingManualTrackStartFadeMs was armed before feeder acknowledgement;
                    // onUsbPlaybackDataFlowing consumes it when the first new-track PCM enters
                    // the native ring. Do not clear it here before that callback has a chance.
                    ffmpegPlayer.usbReuseEngineForNextStart = false
                    playInternalAfterSwitch = false
                    AppLogger.i(
                        TAG,
                        "manual USB same-profile switch committed without transport restart: " +
                            "title=${song.title} fadeMs=$usbFadeMs reason=$reason"
                    )
                } else {
                    // Only profile changes or a failed prepared-next handoff enter
                    // the serialized cold path.
                    val usbFadeMs = preSwitchFadeMs.takeIf { it > 0 }
                        ?: usbExclusiveManualTrackFadeMs().also { fadeMs ->
                            fadeUsbExclusiveTrackBoundary(
                                fadeMs = fadeMs,
                                reason = reason,
                            )
                        }
                    if (!isUsbHardwareVolumeRouteActive()) {
                        pendingManualTrackStartFadeMs = usbFadeMs
                    }
                    ffmpegPlayer.stopForManualTrackSwitch(reason)
                    ffmpegPlayer.usbReuseEngineForNextStart = false
                    applyUsbNoDataSafetyVolume("manual_switch_full_reinit:$reason")
                    AppLogger.w(
                        TAG,
                        "manual switch serialized full-reinit path: softNext=$softNext " +
                            "inlineResult=$inlineResult reason=$reason"
                    )
                }
            } else if (!preservePausedState && manualTransitionMs > 0) {
                // Normal output keeps manual switching non-blocking. Reference-style
                // SHORT_FADE is a short two-slot crossfade, while CROSSFADE uses the same
                // overlap renderer with the configured duration. Unsafe/incompatible pairs
                // fall back to the serialized fade-out/reopen/fade-in envelope below.
                val current = _currentSong.value
                val pathOnlyCrossfadeIsSafe = current != null &&
                    current.path != song.path &&
                    current.cueOffsetMs == 0L && current.cueTrackIndex == 0 &&
                    song.cueOffsetMs == 0L && song.cueTrackIndex == 0
                // Library metadata is not the renderer geometry. The pending FFmpeg decoder is
                // opened against the active output format, so a 44.1 kHz source followed by a
                // 96 kHz source may still produce matching decoded PCM for this live renderer.
                // Only the actual prepared decoder/native slot may accept or reject overlap.
                val sourceMetadataGeometryMatches = current != null &&
                    (current.sampleRate <= 0 || song.sampleRate <= 0 || current.sampleRate == song.sampleRate) &&
                    (current.channelCount <= 0 || song.channelCount <= 0 || current.channelCount == song.channelCount) &&
                    current.isDsdFormat == song.isDsdFormat
                val manualMode = TransitionPreferences.manualTrackTransitionMode
                val transitionDecision = ManualTrackTransitionPolicy.decide(
                    mode = manualMode,
                    pathOnlyCrossfadeIsSafe = pathOnlyCrossfadeIsSafe,
                )
                val inlineEligible = transitionDecision.attemptOverlap
                val inlineStarted = if (inlineEligible) {
                    ffmpegPlayer.requestManualCrossfadeTo(
                        nextPath = song.path,
                        durationMs = manualTransitionMs,
                        targetDurationMs = song.duration,
                        reason = reason,
                        shouldContinue = isStillLatestRequest,
                    )
                } else {
                    AppLogger.i(
                        TAG,
                        "MANUAL_CROSSFADE_DECISION result=FALLBACK reason=source_item_not_overlap_safe " +
                            "target=${song.path.substringAfterLast('/')} mode=$manualMode " +
                            "pathOnlySafe=$pathOnlyCrossfadeIsSafe " +
                            "sourceMetadataGeometryMatches=$sourceMetadataGeometryMatches",
                    )
                    false
                }

                // A newer tap can arrive while FFmpeg is opening/probing the prepared-next
                // decoder above. PlaybackEventQueue conflates pending PLAY events but cannot
                // interrupt the event already executing.  Never let that obsolete request commit
                // song/queue state or leave an active crossfade behind after the newer tap won.
                if (!isStillLatestRequest()) {
                    ffmpegPlayer.clearNextSong()
                    AppLogger.w(
                        TAG,
                        "manual Android switch discarded after slow prepare: stale title=${song.title} " +
                            "inlineStarted=$inlineStarted rate=${current?.sampleRate}->${song.sampleRate} " +
                            "channels=${current?.channelCount}->${song.channelCount} reason=$reason"
                    )
                    return
                }

                if (inlineStarted) {
                    playInternalAfterSwitch = false
                    PlayerSwitchTrace.mark(
                        "transport_crossfade_inline_active",
                        "reason=$reason fadeMs=$manualTransitionMs deferUi=$deferUiCommitToRenderer",
                    )
                    // Reference publishes the logical track/cursor as soon as next/previous is
                    // accepted. Only defer the UI when an already-running renderer owns the
                    // handoff; an idle/paused cold-start queue has no live timeline to wait for.
                    // Waiting for TRACK_STARTED in that case lets audio move while the title,
                    // artwork and gesture owner remain on the old row.
                    AppLogger.i(
                        TAG,
                        "manual switch inline crossfade ACTIVE; " +
                            "uiCommit=${if (deferUiCommitToRenderer) "track_started" else "queue_cursor"} " +
                            "title=${song.title} fadeMs=$manualTransitionMs reason=$reason",
                    )
                } else {
                    // Both short and full manual crossfade modes reach this branch only when
                    // the pair cannot be overlapped safely or the prepared-next/native path failed.
                    // Keep exactly one decoder/output owner: fade the currently audible renderer
                    // down first, then let playInternal() retire it and queue the latest target,
                    // whose start fade is armed below. A newer tap cancels this wait through the
                    // transport token so an obsolete switch cannot commit after the latest target.
                    // PCM consumption completes the envelope on the renderer thread. Waiting for
                    // it must never sleep the Compose/main thread: doing so froze artwork and
                    // lyric animation for the entire fade duration before the real switch began.
                    // The setting is the complete short transition duration, not the duration of
                    // each half. Applying it once to OUT and again to IN doubled the perceived
                    // wait before a manual switch. A natural EOF has no audible outgoing renderer,
                    // so it keeps the complete duration for the incoming fade.
                    val outgoingFadeMs = ((manualSequentialFallbackMs + 1) / 2).coerceAtLeast(1)
                    val fadedOut = withContext(Dispatchers.IO) {
                        ffmpegPlayer.fadeOutForTransitionBlocking(
                            durationMs = outgoingFadeMs,
                            reason = "$reason:manual_track_out",
                            shouldContinue = isStillLatestRequest,
                        )
                    }
                    PlayerSwitchTrace.mark(
                        "transport_sequential_out_done",
                        "reason=$reason fadedOut=$fadedOut outMs=$outgoingFadeMs",
                    )
                    if (!isStillLatestRequest()) {
                        ffmpegPlayer.clearNextSong()
                        AppLogger.w(
                            TAG,
                            "manual Android switch discarded during outgoing fade: " +
                                "stale title=${song.title} fadedOut=$fadedOut reason=$reason"
                        )
                        return
                    }
                    val incomingFadeMs = if (fadedOut) {
                        (manualSequentialFallbackMs - outgoingFadeMs).coerceAtLeast(1)
                    } else {
                        manualSequentialFallbackMs
                    }
                    ffmpegPlayer.armNextStartFadeIn(incomingFadeMs, "$reason:manual_track_in")
                    AppLogger.i(
                        TAG,
                        "manual switch sequential fade-out/in: title=${song.title} " +
                            "requestedMs=$manualTransitionMs fallbackMs=$manualSequentialFallbackMs " +
                                "outMs=$outgoingFadeMs inMs=$incomingFadeMs " +
                            "fadedOut=$fadedOut mode=$manualMode overlapEligible=$inlineEligible " +
                            "pathOnlySafe=$pathOnlyCrossfadeIsSafe " +
                            "sourceMetadataGeometryMatches=$sourceMetadataGeometryMatches " +
                            "rate=${current?.sampleRate}->${song.sampleRate} " +
                            "channels=${current?.channelCount}->${song.channelCount} reason=$reason"
                    )
                }
            }

            // Re-check immediately before committing controller/UI identity.  This closes the
            // small window between a successful prepared-next start and the state commit below.
            // USB-exclusive has its own feeder-acknowledged handoff semantics and is intentionally
            // left on that path; this guard is for shared Android output, including Android USB.
            if (!_usbExclusiveActive.value && !isStillLatestRequest()) {
                ffmpegPlayer.clearNextSong()
                AppLogger.w(TAG, "manual Android switch dropped before state commit: stale title=${song.title} reason=$reason")
                return
            }

            if (deferUiCommitToRenderer) {
                // Freeze the exact target context only after the request has survived all stale
                // generation checks. This one context owns the later TRACK_STARTED publication for
                // both live two-slot handoffs and cold sequential reopen paths.
                pendingRendererUiCommit = PendingRendererUiCommit(
                    song = song,
                    queue = queue.toList(),
                    index = index,
                    prepareFollowingAfterCommit = playInternalAfterSwitch,
                )
                PlayerSwitchTrace.mark(
                    "transport_defer_ui_commit",
                    "reason=$reason targetId=${song.id} index=$index playInternal=$playInternalAfterSwitch",
                )
            }

            // Keep repeat/shuffle/original-queue flags while the manual switch is in flight.
            // Replacing the object with a fresh PlayQueue here silently cleared isShuffle and
            // made the following next/previous action observe a different queue state.
            if (!deferUiCommitToRenderer) {
                pendingRendererUiCommit = null
                _queue.value = _queue.value.copy(songs = queue, currentIndex = index)
                clearPlaybackSourceOverrideIfOwnerChanged(song, "manual_switch")
                _currentSong.value = song
                _position.value = 0L
                _duration.value = song.duration
                PlayerSwitchTrace.mark(
                    "transport_immediate_ui_commit",
                    "reason=$reason targetId=${song.id} index=$index",
                )
                queueControlCoordinator.onManualTargetCommitted(song)
                persistSelectedSongForColdStart(song)
                clearRequestedSongIfCommitted(song)
                AppPreferences.Player.lastPosition = 0L
                if (preservePausedState) {
                    // Next/previous inherits PAUSED. Keep the old decoder silent and publish the
                    // selected queue row immediately; explicit Play will open this row instead of
                    // accidentally resuming the retiring renderer.
                    playInternalAfterSwitch = false
                    pausedSelectionPendingStart = true
                    stopProgressUpdate()
                    smForceTransition(PlayState.PAUSED, "manual_switch_preserve_paused")
                } else {
                    pausedSelectionPendingStart = false
                    smForceTransition(
                        if (playInternalAfterSwitch) PlayState.PREPARING else PlayState.PLAYING,
                        "manual_switch_preparing",
                    )
                }
            } else {
                // Reference-style ownership: keep the old logical current track until native
                // confirms that the first pending PCM frames entered the render timeline.
                smForceTransition(PlayState.PLAYING, "manual_switch_wait_track_started")
            }

            if (playInternalAfterSwitch) {
                PlayerSwitchTrace.mark(
                    "transport_play_internal_dispatch",
                    "reason=$reason targetId=${song.id} deferUi=$deferUiCommitToRenderer",
                )
                playInternal(
                    song = song,
                    queue = queue,
                    index = index,
                    deferPublicCommitToRenderer = deferUiCommitToRenderer,
                )
            }
        } finally {
            transportTransitioning = false
        }
    }

    private fun playManualSwitchFromStart(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        reason: String,
        primeUiSelection: Boolean = true,
        persistOutgoingPositionInWorker: Boolean = false,
    ) {
        // Queue gestures/buttons can arrive faster than a short outgoing fade completes. Share
        // the transport generation with explicit play requests so an obsolete switch stops its
        // fade and can never commit after the user's latest target.
        val requestToken = latestPlayRequestToken.incrementAndGet()
        PlayerSwitchTrace.mark(
            "transport_event_submit",
            "reason=$reason token=$requestToken targetId=${song.id} index=$index primeUi=$primeUiSelection persistOutgoing=$persistOutgoingPositionInWorker",
        )
        if (primeUiSelection) primeSongSelectionForUi(song)
        // The public controller scope is Main.immediate because UI-facing StateFlows are also
        // coordinated here. Launching manual transport on that scope from a button/gesture callback
        // starts executing synchronously on the current Main stack until the first real suspension.
        // Delaying the command by one or two vsyncs therefore only moved decoder/transition work to
        // a later UI frame. Route it through the same serialized PLAY event queue as explicit play():
        // PlaybackEventQueue consumes PLAY on Dispatchers.Default, conflates pending PLAY requests,
        // and the shared request token still makes an already-running obsolete switch fail closed.
        eventQueue.submit(
            PlaybackEventQueue.PlaybackEvent.PlayEvent(
                song = song,
                queue = queue,
                index = index,
                handler = manualEvent@{ queuedSong, queuedQueue, queuedIndex ->
                    if (requestToken != latestPlayRequestToken.get()) return@manualEvent
                    PlayerSwitchTrace.mark(
                        "transport_event_worker",
                        "reason=$reason token=$requestToken targetId=${queuedSong.id} index=$queuedIndex thread=${Thread.currentThread().name}",
                    )
                    // Artwork gestures must keep all persistence/queue/controller work off the
                    // pointer + ArtworkPager settle frame. The PlaybackEventQueue consumer is Default.
                    if (persistOutgoingPositionInWorker) savePosition()
                    transportMutex.withLock {
                        if (requestToken != latestPlayRequestToken.get()) return@withLock
                        playManualSwitchFromStartLocked(
                            queuedSong,
                            queuedQueue,
                            queuedIndex,
                            reason,
                        ) {
                            requestToken == latestPlayRequestToken.get()
                        }
                    }
                },
            )
        )
    }

    fun next(): AudioFile? = queueControlCoordinator.next()

    fun previous(): AudioFile? =
        queueControlCoordinator.previous(restartCurrentAfterThreshold = true)

    /** Non-artwork navigation that always moves to the previous queue item. */
    fun previousTrackWithoutRestart(): AudioFile? =
        queueControlCoordinator.previous(restartCurrentAfterThreshold = false)

    /** Artwork swipes never mutate requested-song/queue cursor state on the pointer frame. */
    fun previousTrackFromArtworkGesture(): AudioFile? =
        queueControlCoordinator.previousFromArtworkGesture()

    fun nextTrackFromArtworkGesture(): AudioFile? =
        queueControlCoordinator.nextFromArtworkGesture()

    fun selectExistingQueueIndex(index: Int, reason: String = "queue_index_selection"): AudioFile? =
        queueControlCoordinator.selectVisibleQueueIndex(index, reason)

    fun selectVisibleQueueIndex(index: Int, reason: String = "visible_queue_selection"): AudioFile? =
        queueControlCoordinator.selectVisibleQueueIndex(index, reason)

    fun previewNextSong(): AudioFile? = queueControlCoordinator.previewNextSong()

    /** Resolves neighbours from a caller-owned queue snapshot, not a later StateFlow emission. */
    fun previewNextSong(queue: PlayQueue): AudioFile? = queueControlCoordinator.previewNextSong(queue)

    fun previewPreviousSong(): AudioFile? = queueControlCoordinator.previewPreviousSong()

    /** Resolves neighbours from a caller-owned queue snapshot, not a later StateFlow emission. */
    fun previewPreviousSong(queue: PlayQueue): AudioFile? =
        queueControlCoordinator.previewPreviousSong(queue)

    /** Resolves a carousel lane without mutating the active queue or shuffle cursor. */
    fun previewRelativeSong(queue: PlayQueue, offset: Int): AudioFile? =
        queueControlCoordinator.previewRelativeSong(queue, offset)

    /**
     * Repeat/shuffle changes share the player command lane with PLAY. Reference applies these on
     * its player-service handler so a mode change cannot race a renderer handoff or stale next
     * decoder. Re-plan the next track after the mode has committed, while keeping the current
     * song and timeline untouched.
     */
    private fun submitPlaybackModeCommand(reason: String, command: () -> Unit) {
        if (isReleased) return
        eventQueue.submit(
            PlaybackEventQueue.PlaybackEvent.ModeEvent(reason) {
                transportMutex.withLock {
                    if (isReleased) return@withLock
                    command()
                    gaplessControlCoordinator.prepareNextSong()
                    saveState()
                }
            },
        )
    }

    fun toggleRepeatMode() = submitPlaybackModeCommand("toggle_repeat") {
        queueControlCoordinator.toggleRepeatMode()
    }

    fun setRepeatMode(mode: RepeatMode) = submitPlaybackModeCommand("set_repeat:$mode") {
        queueControlCoordinator.setRepeatMode(mode)
    }

    fun toggleShuffle() = submitPlaybackModeCommand("toggle_shuffle") {
        queueControlCoordinator.toggleShuffle()
    }

    fun cyclePlayMode() = submitPlaybackModeCommand("cycle_play_mode") {
        queueControlCoordinator.cyclePlayMode()
    }

    fun setPlayMode(mode: PlayMode) = submitPlaybackModeCommand("set_play_mode:$mode") {
        queueControlCoordinator.setPlayMode(mode)
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
        AppPreferences.Player.usbHardwareVolumeStep = UsbHardwareVolumeMath.uiToStep(uiVolume)
        AppLogger.w(TAG, "SET_USER_VOLUME_UNIFIED volume=$uiVolume step=${AppPreferences.Player.usbHardwareVolumeStep} reason=$reason")
        applyComposedVolume()
    }

    /** Queue mutations use the same serialized owner as Reference's queue DB commands. */
    private fun submitQueueMutation(reason: String, mutation: () -> Unit) {
        if (isReleased) return
        eventQueue.submit(
            PlaybackEventQueue.PlaybackEvent.GenericEvent("QUEUE_$reason") {
                transportMutex.withLock {
                    if (isReleased) return@withLock
                    mutation()
                    gaplessControlCoordinator.prepareNextSong()
                    saveState()
                }
            },
        )
    }

    fun addToQueue(song: AudioFile) = submitQueueMutation("ADD_PRIORITY") {
        queueControlCoordinator.addToPriorityQueue(song)
    }

    fun getPriorityQueue(): List<AudioFile> = queueControlCoordinator.priorityQueueSnapshot()

    fun clearPriorityQueue() = submitQueueMutation("CLEAR_PRIORITY") {
        queueControlCoordinator.clearPriorityQueue()
    }

    fun adoptVisibleQueueSnapshot(songs: List<AudioFile>, currentIndex: Int) =
        submitQueueMutation("ADOPT_SNAPSHOT") {
            queueControlCoordinator.adoptVisibleQueueSnapshot(songs, currentIndex)
        }

    fun playNext(song: AudioFile) = submitQueueMutation("PLAY_NEXT") {
        queueControlCoordinator.playNext(song)
    }

    fun removeFromQueue(index: Int) = submitQueueMutation("REMOVE_INDEX") {
        queueControlCoordinator.removeFromQueue(index)
    }

    fun removeSongsFromQueue(songs: Collection<AudioFile>) = submitQueueMutation("REMOVE_SONGS") {
        queueControlCoordinator.removeSongsFromQueue(songs)
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerController.startMinutes(minutes)
    }

    fun startCustomSleepTimer(durationMs: Long) {
        sleepTimerController.startCustomDuration(durationMs)
    }

    fun startSleepTimerSongs(count: Int) {
        sleepTimerController.startSongCount(count)
    }

    fun enableStopAfterCurrent() {
        sleepTimerController.stopAfterCurrent()
    }

    fun cancelSleepTimer() {
        sleepTimerController.cancel()
    }

    fun getSleepTimerSongsRemaining(): Int = sleepTimerController.songsRemaining()

    fun isStopAfterCurrentEnabled(): Boolean = sleepTimerController.isStopAfterCurrentEnabled()

    fun isSleepTimerActive(): Boolean = sleepTimerController.isActive()

    fun getSleepTimerMode(): Int = sleepTimerController.mode()

    private fun requestSleepTimerPlayerExit() {
        val exitAction: suspend () -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                context.stopService(android.content.Intent(context, PlayerService::class.java))
                context.sendBroadcast(
                    android.content.Intent(ACTION_SLEEP_TIMER_EXIT_APP)
                        .setPackage(context.packageName)
                )
            }
        }
        if (eventQueue.isRunning) {
            // pause() itself is serialized through this queue. Appending the exit command after
            // that PAUSE makes teardown deterministic even for USB-exclusive output: backend pause
            // and resource handoff finish before the foreground service/task are removed.
            eventQueue.submit(
                PlaybackEventQueue.PlaybackEvent.GenericEvent("SLEEP_TIMER_EXIT") {
                    exitAction()
                }
            )
        } else {
            scope.launch { exitAction() }
        }
    }

    /** 根据歌曲 ReplayGain 标签和用户设置，计算并应用增益因子。 */
    private fun applyReplayGain(song: AudioFile) {
        val prefs = AppPreferences.Player
        volumeControlCoordinator.applyReplayGain(
            song = song,
            settings = PlayerVolumeControlCoordinator.ReplayGainSettings(
                normalizationEnabled = prefs.volumeNormalizationEnabled,
                replayGainEnabled = prefs.replayGainEnabled,
                replayGainMode = prefs.replayGainMode,
            ),
        )
    }

    private fun applyUsbVolume(
        profile: com.rawsmusic.module.player.usb.UsbOutputProfile,
        reason: String,
    ) {
        volumeControlCoordinator.applyUsbVolume(profile, reason)
    }

    private fun applyComposedVolume() {
        volumeControlCoordinator.applyComposedVolume()
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
        playbackProgressController.start()
    }

    private fun stopProgressUpdate() {
        playbackProgressController.stop()
    }

    private fun saveState() {
        playbackStatePersistenceController.saveState()
    }

    /** Persist a complete snapshot synchronously when the OS is preparing to kill the process. */
    fun persistForMemoryTermination(): Boolean {
        return playbackStatePersistenceController.persistForMemoryTermination()
    }

    private fun savePosition() {
        playbackStatePersistenceController.savePosition()
    }

    private fun restoreState() {
        applyComposedVolume()
        androidSpatialPlaybackController.restoreSettings()
        // 恢复立体声扩展设置
        audioEffectsSessionCoordinator.restoreSettings()
    }

    /**
     * 恢复上次播放的歌曲（但不自动播放）
     */
    fun restoreLastSong(): AudioFile? {
        if (restoreSnapshotApplied.get()) return _currentSong.value
        return synchronized(restoreSnapshotLock) {
            if (restoreSnapshotApplied.get()) return@synchronized _currentSong.value
            val restored = restoreControlCoordinator.restoreLastSong()
            // currentSong may have been seeded by the service before the queue snapshot was
            // hydrated. Still execute restoreControlCoordinator once so the player page gets the
            // persisted multi-row queue instead of retaining a one-song seed.
            restoreSnapshotApplied.set(true)
            restored ?: _currentSong.value
        }
    }

    fun release() {
        Log.w(TAG, "=== PlayerController.release() CALLED ===")
        if (isReleased) return
        isReleased = true
        usbHardwareVolumeCoordinator.close()
        usbColdStartRearmJob?.cancel()
        usbColdStartRearmJob = null
        usbPermissionActivationJob?.cancel()
        usbPermissionActivationJob = null
        usbRenderSwitchReleaseJob?.cancel()
        usbRenderSwitchReleaseJob = null
        PlayerRuntimeRegistry.detachController(this, "controller_release")
        clearReleasedInstance(this)
        // saveState() reads requested-or-current identity, so a teardown during the serialized
        // play handoff cannot overwrite the newly selected item with the previous song.
        saveState()
        clearPendingUiSelection()
        metadataEnrichmentCoordinator.cancel()
        duplicatePlayRequestGate.clear()
        _playbackSourceOverride.value = null
        usbBackgroundPlaybackCoordinator.reset()
        stopProgressUpdate()
        androidAudioInterruptionController.release()
        deviceControlRouteCoordinator.close()
        deviceControlRuntime.manager.close()
        androidBluetoothOutputController.release()
        MusicSourcePlaybackController.uninstallBackend(musicSourcePlaybackBackend)
        musicSourcePlaybackBackend.close()
        scope.cancel()
        audioEffectsSessionCoordinator.release()

        disableUsbExclusive(preserveUserIntent = true)
        volumeRouteCoordinator.release("controller_release")
        androidPlaybackServiceController.release("controller_release")
        usbSystemAudioKeepAlive.stop("controller_release")
        try { usbExclusiveManager.unregister() } catch (_: Exception) {}

        try {
            androidSpatialPlaybackController.close()
        } catch (_: Exception) {}
        try {
            ffmpegPlayer.release()
        } catch (_: Exception) {}
    }
    /** Delegates audio-session and effect state to the standalone coordinator. */
    fun getAudioSessionId(): Int = audioEffectsSessionCoordinator.audioSessionId()

    fun setEqualizerController(reinitFn: ((newSessionId: Int) -> Unit)?) =
        audioEffectsSessionCoordinator.setEqualizerController(reinitFn)

    fun setStereoWidenFactor(factor: Float) = audioEffectsSessionCoordinator.setStereoWidenFactor(factor)

    fun setCrossfeedEnabled(enabled: Boolean) = audioEffectsSessionCoordinator.setCrossfeedEnabled(enabled)

    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) =
        audioEffectsSessionCoordinator.setCrossfeedParams(lowCutFreq, highCutFreq, attenuationDB)

    fun restoreCrossfeedSettings() = audioEffectsSessionCoordinator.restoreSettings()


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
