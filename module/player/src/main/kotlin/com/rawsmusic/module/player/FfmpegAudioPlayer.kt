package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbAudioFormatPolicy
import com.rawsmusic.module.player.usb.UsbBitPerfectPolicyGate
import com.rawsmusic.module.player.usb.UsbExclusiveCutoverCoordinator
import com.rawsmusic.module.player.usb.UsbSilentKind
import com.rawsmusic.module.player.usb.buildSupportedUsbDsdModeConfig
import com.rawsmusic.module.player.dsp.FfmpegDspCoordinator

/**
 * 基于 FFmpeg + AudioTrack 的音频播放器
 *
 * 线程安全设计：
 * - 单线程 Executor 串行执行所有操作（转码、播放、停止）
 * - 避免多线程并发访问 AudioTrack
 *
 * DSP 处理链：
 * - 优先使用 NativeDSPEngine (C++ JNI) 进行立体声展宽处理
 * - JNI 加载失败时自动回退到 StereoWidenModule (纯 Kotlin)
 */
class FfmpegAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "FfmpegAudioPlayer"
        private const val PCM_BUFFER_SIZE = 8192
        private const val RING_BUFFER_READ_TIMEOUT_MS = 2000L
        private const val PAUSED_SEEK_COMMIT_WAIT_MS = 750L
        private const val GAPLESS_PREOPEN_WINDOW_MS = 12_000L
        private const val USB_NATIVE_PREFILL_TARGET_MS = 180L
        private const val USB_NATIVE_PREFILL_MIN_MS = 40L
        private const val USB_NATIVE_PREFILL_TIMEOUT_MS = 420L
        // Keep more headroom than the original low-latency path, but do not build
        // multi-second native queues.  Long queues make pause/seek stop tails and
        // analog transition noise worse; Android background freezes must be handled
        // by foreground-service/wakelock policy instead.
        private const val USB_NATIVE_LOW_WATER_MS = 140L
        private const val USB_NATIVE_TARGET_WATER_MS = 320L
        private const val USB_NATIVE_HIGH_WATER_MS = 620L
        private const val USB_NO_FEEDBACK_LOW_WATER_MS = 96L
        private const val USB_NO_FEEDBACK_TARGET_WATER_MS = 192L
        private const val USB_NO_FEEDBACK_HIGH_WATER_MS = 360L
        private const val USB_NO_FEEDBACK_WRITE_CHUNK_MS = 12L
        // No-feedback queue accounting: libusb has a large in-flight
        // transfer queue that is not the same as Kotlin's app-side native ring.
        // On MIUI/HyperOS the USB event thread can complete transfers in bursts,
        // so a low-rate 44.1k stream may hover just above the old 360 ms high
        // water mark and get misclassified as a dead DAC. Reserve a shadow
        // budget for the in-flight ISO queue before declaring producer high-water.
        private const val USB_NO_FEEDBACK_INFLIGHT_GUARD_TRANSFERS = 160
        // Fixed-pacer streams should not be destructively reopened just
        // because the app-side native ring is high. On MIUI/Android 16, libusb
        // callbacks can be scheduler-limited for several seconds while ISO OUT is
        // still alive. Keep the modeled stream running and let native pacing/URB
        // depth absorb it.
        private const val USB_FIXED_PACER_DRAIN_GRACE_MS = 20_000L
        private const val USB_FIXED_PACER_ZERO_OUTPUT_GRACE_MS = 5_000L
        private const val USB_NO_FEEDBACK_FAST_REPREPARE_COOLDOWN_MS = 4_500L
        private const val USB_NO_FEEDBACK_STABLE_REPREPARE_COOLDOWN_MS = 15_000L
        private const val USB_NO_FEEDBACK_ZERO_OUTPUT_REPREPARE_MS = 1_800L
        private const val USB_NO_FEEDBACK_MODERATE_UNDER_OUTPUT_REPREPARE_MS = 12_000L
        private const val USB_NO_FEEDBACK_TARGET_WATER_FAKE_PLAYBACK_GRACE_MS = 900L
        private const val USB_MANUAL_SWITCH_ACK_TIMEOUT_MS = 4_000L
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR, COMPLETED }

    enum class UsbManualSwitchResult { REJECTED, COMMITTED, FAILED, TIMED_OUT }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onPositionChanged(positionMs: Long, durationMs: Long) {}
        fun onError(message: String) {}
        /** Gapless/Crossfade 切歌回调 — 通知 PlayerController 更新队列索引 */
        fun onGaplessSongChanged(newPath: String) {}
    }

    var listener: Listener? = null
    var onPcmWaveformFrame: ((buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, validBitsPerSample: Int, sampleEncoding: Int) -> Unit)? = null
    /** Android AudioPolicy has seen a USB audio output route. PlayerController may use this as
     * a fast fallback trigger when UsbManager attach broadcasts arrive late or are filtered by OEM ROMs. */
    var onAndroidUsbAudioRouteAdded: (() -> Unit)? = null

    private var _state = State.IDLE
    val state: State get() = _state
    @Volatile
    private var stateChangedAtElapsedMs = SystemClock.elapsedRealtime()
    val stateAgeMs: Long
        get() = (SystemClock.elapsedRealtime() - stateChangedAtElapsedMs).coerceAtLeast(0L)

    private var _durationMs = 0L
    val durationMs: Long get() = _durationMs

    /**
     * 安全地将位置限制在 [0, _durationMs] 范围内。
     * 当 _durationMs <= 0（未知时长）时，仅保证非负。
     */
    private fun Long.coerceToDuration(): Long {
        return if (_durationMs > 0) this.coerceIn(0L, _durationMs) else this.coerceAtLeast(0L)
    }

    private fun usbDeviceBytesPerSecond(engine: UsbAudioEngine, fallbackBytesPerSec: Long): Long {
        // Native ring buffer stores final USB device-format bytes.
        // Device B/s = sampleRate * channels * selectedSubslotBytes,
        // not sampleRate * channels * ceil(validBits/8).
        val runtime = engine.getRuntimeFormat()
        val computed = if (runtime.isValid) {
            runtime.sampleRate.toLong() * runtime.channels.toLong() * runtime.subslotBytes.toLong()
        } else 0L
        val native = engine.getOutputBytesPerSecond().toLong()
        if (computed > 0L) {
            if (native > 0L && native != computed) {
                AppLogger.w(
                    TAG,
                    "USB deviceBPS corrected from runtime: native=$native computed=$computed " +
                        "sr=${runtime.sampleRate} ch=${runtime.channels} validBits=${runtime.validBits} " +
                        "subslot=${runtime.subslotBytes} frame=${runtime.frameBytes}"
                )
            }
            return computed
        }
        return if (native > 0L) native else fallbackBytesPerSec
    }

    @Volatile private var _positionMs = 0L
    val positionMs: Long get() = _positionMs
    @Volatile private var hardwarePositionOffsetMs = 0L

    private var _audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    val audioSessionId: Int get() = _audioSessionId

    private val playbackWorker = PlaybackWorkerController(TAG)

    private var audioTrack: AudioTrack? = null
    val audioTrackRef: AudioTrack? get() = audioTrack
    private val audioTrackLifecycle = AudioTrackLifecycleController(TAG) {
        val detached = audioTrack
        audioTrack = null
        detached
    }
    private val tempWavRebuildCoordinator by lazy {
        AudioTrackTempWavRebuildCoordinator(
            tag = TAG,
            playbackWorker = playbackWorker,
            audioTrackLifecycle = audioTrackLifecycle,
            isReleased = { isReleased.get() },
            startPlaybackFromOffset = { offset, playPath, generation, isSeek, sourcePath ->
                startPlaybackFromOffset(offset, playPath, generation, isSeek, sourcePath)
            },
        )
    }
    private val nativeAudioEngineLock = Any()
    private var nativeAudioEngine: NativeAudioEngine? = null
    private val nativeAudioEngineLifecycle = NativeAudioEngineLifecycleController(
        tag = TAG,
        currentProvider = { synchronized(nativeAudioEngineLock) { nativeAudioEngine } },
        detachCurrent = {
            synchronized(nativeAudioEngineLock) {
                val detached = nativeAudioEngine
                nativeAudioEngine = null
                detached
            }
        }
    )

    private fun replaceNativeAudioEngine(replacement: NativeAudioEngine): NativeAudioEngine? =
        synchronized(nativeAudioEngineLock) {
            val previous = nativeAudioEngine
            nativeAudioEngine = replacement
            previous
        }

    private fun detachNativeAudioEngineIfOwned(expected: NativeAudioEngine): NativeAudioEngine? =
        synchronized(nativeAudioEngineLock) {
            if (nativeAudioEngine === expected) {
                nativeAudioEngine = null
                expected
            } else {
                null
            }
        }

    // AudioTrack hardware timestamp state is isolated from the player loop.
    private val audioTrackPositionTracker = AudioTrackPositionTracker()
    private val audioTrackPositionUpdater = AudioTrackPositionUpdater(
        hardwarePositionMs = { sampleRate -> getHardwarePositionMs(sampleRate) },
        useHardwareTimestamp = { useHardwareTimestamp.get() },
        isFlushPending = { needsAudioTrackFlush.get() }
    )
    private var tempWavFile: File? = null
    private val playbackSession = PlaybackSessionState(TAG)
    private var sourcePath: String?
        get() = playbackSession.sessionSourcePath
        set(value) { playbackSession.sessionSourcePath = value }
    private var currentPath: String?
        get() = playbackSession.currentTrackPath
        set(value) {
            playbackSession.currentTrackPath = value
        }
    private var resampledPath: String? = null
    private val ffmpegAudioCache = FfmpegAudioCache(context)
    private val androidAudioTrackFactory = AndroidAudioTrackFactory(context)
    private val audioTrackPcmWriter = AudioTrackPcmWriter { probedEncoding }
    private val outputVolumeCoordinator by lazy {
        FfmpegOutputVolumeCoordinator(
            tag = TAG,
            setStoredVolume = { volume = it },
            setNativeVolume = { value, reason -> nativeAudioEngineLifecycle.setVolumeCurrent(value, reason) },
            audioTrack = { audioTrack },
            usbExclusiveMode = { usbExclusiveMode },
        )
    }
    private val pcmOutputConversion = PcmOutputConversionController(
        isStrictUsbBitPerfectPath = ::isStrictUsbBitPerfectPath,
        isUsbRawDsdDirectActive = { usbRawDsdDirectActive },
    )
    private val decoderChunkWriter by lazy {
        FfmpegDecoderChunkWriter(
            tag = TAG,
            useFloatOutput = { useFloatOutput },
            usePacked24Output = { usePacked24Output },
            wavBitsPerSample = { wavBitsPerSample },
            pcmOutputConversion = pcmOutputConversion,
            floatBuffer = { decoderFloatBuf },
            setFloatBuffer = { decoderFloatBuf = it },
            packed24Buffer = { decoderPacked24Buf },
            setPacked24Buffer = { decoderPacked24Buf = it },
        )
    }
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    @Volatile private var androidSpatialAudioRebuildPending = false
    @Volatile
    private var seekPositionMs = -1L
    @Volatile
    private var queuedStartSeekMs = -1L

    private var decoderHandle: Long = 0L
    private val decoderPathResolver = DecoderPathResolver(context, TAG)
    private val decoderOpenHelper = FFmpegDecoderOpenHelper(
        tag = TAG,
        pathResolver = decoderPathResolver,
        isSafeMode = { safeMode },
        isStrictUsbBitPerfectPath = { isStrictUsbBitPerfectPath() }
    )
    private var ringBuffer: RingBuffer? = null
    @Volatile
    private var decoderThread: Thread? = null
    /** 解码器是否已到达 EOF（未关闭 ring buffer，streaming loop 继续消费剩余数据） */
    @Volatile
    private var decoderDone = false
    /** Active decoder thread stop token. Each decoder thread captures its own token. */
    @Volatile
    private var decoderStopToken = DecoderStopToken("initial")
    private val decoderThreadStarter by lazy {
        FfmpegDecoderThreadStarter(
            tag = TAG,
            ownsDecoder = { source, generation, handle, ring, token ->
                playbackSession.isCurrent(source, generation) &&
                    decoderHandle == handle &&
                    ringBuffer === ring &&
                    decoderStopToken === token &&
                    !token.isStopRequested
            },
            runDecoder = { handle, ring, generation, source, token, chunkSize ->
                decoderLoopCoordinator.run(handle, ring, generation, source, token, chunkSize)
            },
            onThreadCreated = { decoderThread = it },
        )
    }
    @Volatile
    private var pendingSeekMs = -1L
    @Volatile
    private var pendingSeekSerial = 0L
    private val pausedSeekCommitGate = PausedSeekCommitGate()
    private val seekOutputBarrier = SeekOutputBarrier()
    private val needsAudioTrackFlush = java.util.concurrent.atomic.AtomicBoolean(false)
    private val useHardwareTimestamp = java.util.concurrent.atomic.AtomicBoolean(true)
    /** seekTo() EOF 分支将 handle 所有权转移给新解码线程时设为 true，旧线程 finally 不要关闭 handle */
    private val decoderHandleTransferred = java.util.concurrent.atomic.AtomicBoolean(false)
    private val decoderLoopCoordinator by lazy {
        FfmpegDecoderLoopCoordinator(
            tag = TAG,
            decoderChunkWriter = decoderChunkWriter,
            isPlaying = { isPlaying.get() },
            setPlaying = { isPlaying.set(it) },
            isReleased = { isReleased.get() },
            isStillCurrentPlayback = ::isStillCurrentPlayback,
            consumePendingSeek = {
                pendingSeekMs.takeIf { it >= 0L }.also {
                    if (it != null) pendingSeekMs = -1L
                }
            },
            pendingSeekSerial = { pendingSeekSerial },
            setPositionMs = { _positionMs = it },
            markAudioTrackFlush = { needsAudioTrackFlush.set(true) },
            flushNativePcmBuffer = ::flushNativePcmBufferForSeek,
            pausedSeekCommitGate = pausedSeekCommitGate,
            seekOutputBarrier = seekOutputBarrier,
            activeDecoderHandle = { decoderHandle },
            activeRingBuffer = { ringBuffer },
            activeStopToken = { decoderStopToken },
            markDecoderDone = { decoderDone = true },
            setState = ::setState,
            onPlaybackError = ::onPlaybackError,
            decoderHandleTransferred = decoderHandleTransferred,
            clearDecoderHandleIfMatches = { handle ->
                if (decoderHandle == handle) decoderHandle = 0L
            },
        )
    }

    private val pauseLock = Object()

    // ==================== Gapless / Crossfade ====================
    /** 下一首歌路径 — 由 PlayerController 在切歌前设置 */
    var nextSongPath: String?
        get() = playbackSession.preparedNextTrackPath
        set(value) {
            // Once a crossfade is in progress, do not let external updates to
            // nextSongPath clear the prepared decoder mid-transition.
            val locked = crossfadeTransition.targetPath
            if (locked != null && value != locked) {
                AppLogger.w(TAG, "nextSongPath setter ignored during active crossfade target=$locked incoming=$value")
                return
            }
            playbackSession.preparedNextTrackPath = value
        }
    /** Crossfade 时长（毫秒），0 = 仅 gapless 无缝播放 */
    var crossfadeDurationMs: Int
        get() = playbackSession.crossfadeDurationMs
        set(value) { playbackSession.crossfadeDurationMs = value }
    private val crossfadeTransition = CrossfadeTransitionController(
        tag = TAG,
        convertS32ToS16 = { source, length, destination, sourceBits ->
            pcmOutputConversion.convertS32ToS16(source, length, destination, sourceBits)
        },
        convertS32ToS24 = { source, length, destination, sourceBits ->
            pcmOutputConversion.convertS32ToS24(source, length, destination, sourceBits)
        }
    )
    private val playbackFadeRuntime = PlaybackFadeRuntime(
        tag = TAG,
        isPlaying = { isPlaying.get() },
        isReleased = { isReleased.get() },
        isPlayingState = { _state == State.PLAYING },
        shouldBypass = { usbRawDsdDirectActive },
        useFloatOutput = { useFloatOutput },
        usePacked24Output = { usePacked24Output }
    )
    private val playbackFadeCoordinator by lazy {
        FfmpegPlaybackFadeCoordinator(
            runtime = playbackFadeRuntime,
            useFloatOutput = { useFloatOutput },
            usePacked24Output = { usePacked24Output },
            pausePlayback = ::pause,
        )
    }
    @Volatile private var manualCrossfadeRequested = false
    @Volatile private var manualCrossfadeTargetPath: String? = null
    @Volatile private var manualCrossfadeGeneration: Int = -1

    // Manual USB next: the active USB feeder remains the sole
    // transport owner. UI/controller threads only publish a decoder-switch
    // request; the feeder performs the native ring cut and decoder handoff at a
    // deterministic write-loop boundary without stop/start/close/reclaim.
    private val decoderHandoff = DecoderHandoffController(TAG)
    private val decoderLifecycleRetirer = DecoderLifecycleRetirer(TAG, decoderHandoff)
    private val streamingAudioTrackRebuildCoordinator by lazy {
        AudioTrackStreamingRebuildCoordinator(
            tag = TAG,
            playbackWorker = playbackWorker,
            audioTrackLifecycle = audioTrackLifecycle,
            isReleased = { isReleased.get() },
            stopPlaybackFlags = {
                isPlaying.set(false)
                isPaused.set(false)
            },
            closeRingBuffer = { ringBuffer?.close() },
            detachAudioTrack = {
                audioTrackLifecycle.detach(reason = "audio_track_rebuild_streaming")
            },
            stopDetachedAudioTrack = { track ->
                audioTrackLifecycle.stopDetached(track, reason = "audio_track_rebuild_streaming")
            },
            cancelPlaybackWorker = {
                playbackWorker.cancelCurrent("audio_track_rebuild", interrupt = false)
            },
            beginInternalRestart = { savedPath ->
                playbackSession.beginInternalRestart(
                    reason = "audio_track_rebuild",
                    currentTrackPath = savedPath,
                )
            },
            setPreparing = { setState(State.PREPARING) },
            captureDecoder = { detachActiveDecoderForRetire("audio_track_rebuild") },
            retireDecoder = { target, _ ->
                val result = retireDetachedDecoder(
                    target = target,
                    reason = "audio_track_rebuild",
                    joinTimeoutMs = 3000L,
                )
                if (result != null && result.oldThreadAliveAfterJoin) {
                    decoderThread = target.thread
                }
                ringBuffer = null
                AppLogger.w(TAG, "rebuildAudioTrack executor closeOwner=${result?.closeOwner}")
            },
            setSeekPosition = { seekPositionMs = it },
            isStillCurrentPlayback = { sourcePath, generation ->
                isStillCurrentPlayback(sourcePath, generation)
            },
            prepareAndStartPlayback = { sourcePath, generation ->
                prepareAndStartPlayback(sourcePath = sourcePath, generation = generation)
            },
            setError = { setState(State.ERROR) },
        )
    }
    private val playbackTrackCommitter = PlaybackTrackCommitter(TAG)
    private val androidPlaybackTargetResolver = AndroidPlaybackTargetResolver(context, TAG)
    private val usbPlaybackTargetResolver = UsbPlaybackTargetResolver(TAG)
    private val playbackTargetCoordinator = PlaybackTargetCoordinator(
        tag = TAG,
        usbResolver = usbPlaybackTargetResolver,
        androidResolver = androidPlaybackTargetResolver,
    )
    private val gaplessNextDecoder = GaplessNextDecoder(
        tag = TAG,
        resolvePath = { path -> decoderPathResolver.resolve(path) },
        isGenerationCurrent = { generation -> generation == playbackSession.generation }
    )
    private val usbSameProfileTrackSwitchCoordinator: UsbSameProfileTrackSwitchCoordinator = UsbSameProfileTrackSwitchCoordinator(
        tag = TAG,
        acknowledgementTimeoutMs = USB_MANUAL_SWITCH_ACK_TIMEOUT_MS,
        isUsbExclusive = { usbExclusiveMode },
        isPlayingState = { _state == State.PLAYING },
        isPlaying = { isPlaying.get() },
        isReleased = { isReleased.get() },
        isNativeRunning = { UsbAudioEngine.isInitialized() && UsbAudioEngine.isRunning() },
        currentGeneration = { playbackSession.generation },
        snapshotPrepared = { generation -> gaplessNextDecoder.snapshotFor(generation) },
        closeNextDecoder = { closeNextDecoder() },
        setNextSongPath = { nextSongPath = it },
        setCrossfadeDurationMs = { crossfadeDurationMs = it },
        prepareNextDecoder = { path, generation -> prepareNextDecoder(path, generation) },
        currentFormat = {
            UsbSameProfileTrackSwitchCoordinator.PcmFormat(
                sampleRate = wavSampleRate,
                channels = wavChannels,
                bitsPerSample = wavBitsPerSample,
            )
        },
        wakeFeeder = { synchronized(pauseLock) { pauseLock.notifyAll() } },
    )
    private val nextDecoderPrepareEpoch = AtomicLong(0L)
    private val gaplessRequestCoordinator by lazy {
        FfmpegGaplessRequestCoordinator(
            tag = TAG,
            clearNextRequest = playbackSession::clearNextRequest,
            bumpPrepareEpoch = { nextDecoderPrepareEpoch.incrementAndGet() },
            manualRequested = { manualCrossfadeRequested },
            setManualRequested = { manualCrossfadeRequested = it },
            manualTargetPath = { manualCrossfadeTargetPath },
            setManualTargetPath = { manualCrossfadeTargetPath = it },
            manualGeneration = { manualCrossfadeGeneration },
            setManualGeneration = { manualCrossfadeGeneration = it },
            cancelUsbTrackSwitch = { reason -> usbSameProfileTrackSwitchCoordinator.cancelPending(reason) },
            resetCrossfade = crossfadeTransition::reset,
            clearGaplessDecoder = gaplessNextDecoder::clear,
            isCurrentPlayback = ::isStillCurrentPlayback,
            currentGeneration = { playbackSession.generation },
            currentSourcePath = { playbackSession.sessionSourcePath },
        )
    }
    private val gaplessTrackSwitchCoordinator by lazy {
        FfmpegGaplessTrackSwitchCoordinator(
            tag = TAG,
            nextPath = { nextSongPath },
            currentGeneration = { playbackSession.generation },
            consumePrepared = { path, generation -> gaplessNextDecoder.consumeIfPathMatches(path, generation) },
            prepareNext = ::prepareNextDecoder,
            applyPrepared = ::applyGaplessPreparedTrack,
            retireCurrentDecoder = ::retireCurrentDecoderForGapless,
            onFormatChanged = {
                audioTrackLifecycle.detachAndRelease(
                    reason = "gapless_format_changed",
                    stop = true,
                    flush = true,
                )
                rebuildAudioTrack()
            },
            closeNextDecoder = ::closeNextDecoder,
            ringBufferCapacity = { sampleRate, channels ->
                decoderHandoff.ringBufferCapacity(
                    sampleRate = sampleRate,
                    channels = channels,
                    bytesPerSample = outputBytesPerSample,
                    minCapacity = PCM_BUFFER_SIZE * 8,
                )
            },
            installRingBuffer = { ringBuffer = it },
            startDecoder = ::startGaplessDecoder,
            commitTrack = ::commitGaplessTrack,
            resetRealtimeSeparation = ::resetRealtimeSeparationAtTrackBoundary,
        )
    }
    private val nextDecoderPrepareExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS Next Decoder").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private fun detachActiveDecoderForRetire(label: String): DecoderLifecycleRetirer.Target {
        val target = DecoderLifecycleRetirer.Target(
            handle = decoderHandle,
            thread = decoderThread,
            ringBuffer = ringBuffer,
            stopToken = decoderStopToken,
            decoderDone = decoderDone,
            label = label
        )
        decoderHandle = 0L
        decoderThread = null
        ringBuffer = null
        return target
    }

    private fun retireDetachedDecoder(
        target: DecoderLifecycleRetirer.Target,
        reason: String,
        joinTimeoutMs: Long = 1L
    ): DecoderHandoffController.RetireResult? {
        return decoderLifecycleRetirer.retire(
            target = target,
            reason = reason,
            joinTimeoutMs = joinTimeoutMs
        )
    }

    private fun isStillCurrentPlayback(sourcePath: String, generation: Int): Boolean {
        val currentGen = playbackSession.generation
        val currentSource = playbackSession.sessionSourcePath
        val ok = playbackSession.isCurrent(sourcePath, generation)
        if (!ok) {
            AppLogger.w(
                TAG,
                "=== Playback request obsolete, aborting: " +
                    "reqGen=$generation currentGen=$currentGen " +
                    "reqSource=$sourcePath currentSource=$currentSource"
            )
        }
        return ok
    }

    private fun discardReadIfSeekCrossed(
        token: SeekOutputBarrier.ReadToken,
        lane: String
    ): Boolean {
        return when (seekOutputBarrier.finishRead(token)) {
            SeekOutputBarrier.ReadDecision.Accept -> false
            SeekOutputBarrier.ReadDecision.Discard -> {
                AppLogger.d(TAG, "Seek barrier: discard read lane=$lane ${seekOutputBarrier.describe()}")
                true
            }
            SeekOutputBarrier.ReadDecision.AcceptAndRelease -> {
                // Flush immediately before the first buffer that began after the decoder committed
                // the newest seek. This buffer is guaranteed to be post-seek data.
                needsAudioTrackFlush.set(true)
                AppLogger.d(TAG, "Seek barrier: release lane=$lane ${seekOutputBarrier.describe()}")
                false
            }
        }
    }

    private fun abortPlaybackStageIfObsolete(
        sourcePath: String,
        generation: Int,
        stage: String,
        closeRingBuffer: Boolean = false,
        closeDecoderHandle: Boolean = false
    ): Boolean {
        val currentGen = playbackSession.generation
        val currentSource = playbackSession.sessionSourcePath
        val interrupted = Thread.currentThread().isInterrupted
        val obsolete = !playbackSession.isCurrent(sourcePath, generation)
        if (!obsolete && !interrupted) return false

        AppLogger.w(
            TAG,
            "Abort playback stage=$stage reqGen=$generation currentGen=$currentGen " +
                "reqSource=$sourcePath currentSource=$currentSource interrupted=$interrupted " +
                "closeRing=$closeRingBuffer closeDecoder=$closeDecoderHandle"
        )

        isPlaying.set(false)

        if (closeRingBuffer) {
            val oldRing = ringBuffer
            ringBuffer = null
            runCatching { oldRing?.close() }
                .onFailure { AppLogger.w(TAG, "Abort playback stage=$stage close ring buffer failed", it) }
        }

        if (closeDecoderHandle) {
            val oldHandle = decoderHandle
            decoderHandle = 0L
            if (oldHandle != 0L) {
                runCatching { FFmpegBridge.closeDecoder(oldHandle) }
                    .onFailure {
                        AppLogger.w(
                            TAG,
                            "Abort playback stage=$stage close decoder failed handle=$oldHandle",
                            it
                        )
                    }
            }
        }

        return true
    }

    private var wavDataOffset = 0L
    private var wavDataSize = 0L
    var wavSampleRate = 48000; private set
    var wavChannels = 2; private set
    var wavBitsPerSample = 16; private set
    private var wavFormatTag = 1
    @Volatile
    private var usbRawDsdDirectActive = false

    @Volatile
    private var probedEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    private val playbackErrorPolicy by lazy {
        PlaybackErrorPolicy(TAG) { message -> listener?.onError(message) }
    }
    private val playbackStateCoordinator by lazy {
        FfmpegPlaybackStateCoordinator(
            tag = TAG,
            isPlaying = { isPlaying.get() },
            hasAudioTrack = { audioTrack != null },
            currentState = { _state },
            setCurrentState = { _state = it },
            setStateChangedAt = { stateChangedAtElapsedMs = it },
            notifyStateChanged = { state -> listener?.onStateChanged(state) },
            reportError = playbackErrorPolicy::reportError,
            reportSuccess = playbackErrorPolicy::reportSuccess,
        )
    }
    private val outputFormatGuard by lazy {
        FfmpegOutputFormatGuard(
            tag = TAG,
            isUsbExclusive = { usbExclusiveMode },
            isStrictBitPerfect = ::isStrictUsbBitPerfectPath,
        )
    }
    private val safeMode: Boolean get() = playbackErrorPolicy.safeMode
    private val usbHardRecoveryAttemptsMs = java.util.ArrayDeque<Long>()

    /** 当前 AudioTrack 的格式快照 — 用于检测格式变化触发主动重建 */
    private val audioTrackFormatMonitor = AudioTrackFormatMonitor(TAG)

    /**
     * 检测 AudioTrack 格式是否与解码器输出格式不同
     * 当切换歌曲导致采样率/通道/编码变化时返回 true，触发主动重建
     */
    private fun isTrackFormatChanged(): Boolean =
        audioTrackFormatMonitor.hasChanged(wavSampleRate, wavChannels, probedEncoding)

    private fun resetUsbHardRecoveryFuse(reason: String) {
        synchronized(usbHardRecoveryAttemptsMs) {
            usbHardRecoveryAttemptsMs.clear()
        }
        AppLogger.i(TAG, "USB hard recovery fuse reset: reason=$reason")
    }

    /** AudioTrack 创建后记录当前格式快照 */
    private fun snapshotTrackFormat(sampleRate: Int, channelConfig: Int, encoding: Int) {
        audioTrackFormatMonitor.snapshot(sampleRate, channelConfig, encoding)
    }

    val bufferSizeInFrames: Int
        get() = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                audioTrack?.bufferSizeInFrames ?: 0
            } else 0
        } catch (_: Exception) { 0 }

    val latencyMs: Int
        get() {
            val track = audioTrack ?: return 0
            return try {
                val method = track.javaClass.getMethod("getLatency")
                method.invoke(track) as? Int ?: 0
            } catch (_: Exception) { 0 }
        }

    /**
     * 获取硬件级精确播放位置（毫秒），委托给 AudioTrackPositionTracker
     * 返回 -1 表示 timestamp 不可用
     */
    private fun getHardwarePositionMs(sampleRate: Int): Long {
        val rawPosition = audioTrackPositionTracker.hardwarePositionMs(audioTrack, sampleRate)
        return if (rawPosition >= 0L) rawPosition + hardwarePositionOffsetMs else rawPosition
    }

    private fun resetAudioTrackPositionTracker() {
        audioTrackPositionTracker.reset()
        audioTrackPositionUpdater.reset()
    }

    private fun enableHardwarePositionTracking() {
        resetAudioTrackPositionTracker()
        useHardwareTimestamp.set(true)
    }

    private fun disableHardwarePositionTracking() {
        useHardwareTimestamp.set(false)
        resetAudioTrackPositionTracker()
    }

    private var volume = 1.0f

    private val androidAudioRouteController = AndroidAudioRouteController(
        context = context,
        isUsbExclusiveMode = { usbExclusiveMode },
        isRouteMutable = { _state == State.PLAYING || _state == State.PAUSED },
        stateDescription = { _state.name },
        isReleased = { isReleased.get() },
        nativeAudioEngineProvider = { nativeAudioEngine },
        audioTrackProvider = { audioTrack },
        recreateAudioTrackInline = { forceSco, forcedDevice -> recreateAudioTrackInline(forceSco, forcedDevice) },
        runOnPlaybackExecutor = { block -> playbackWorker.execute("android_audio_route", block) },
        wakePlaybackLoop = { /* consumePendingAndroidAudioTrackRouteRebuild is polled in the write loop */ },
        onAndroidUsbAudioRouteAdded = { onAndroidUsbAudioRouteAdded?.invoke() }
    )
    private val audioTrackRebuildCoordinator by lazy {
        AndroidAudioTrackRebuildCoordinator(
            context = context,
            tag = TAG,
            audioTrackFactory = androidAudioTrackFactory,
            audioTrackLifecycle = audioTrackLifecycle,
            routeController = androidAudioRouteController,
            readSampleRate = { wavSampleRate },
            readChannels = { wavChannels },
            readEncoding = { probedEncoding },
            stateName = { _state.name },
            shouldResumePlayback = { _state == State.PLAYING },
            createWithFallback = ::createAudioTrackWithFallback,
            onTrackCreated = { newTrack, sampleRate, channelConfig, encoding ->
                audioTrack = newTrack
                _audioSessionId = newTrack.audioSessionId
                snapshotTrackFormat(sampleRate, channelConfig, encoding)
                setVolume(volume)
            },
            disableHardwarePositionTracking = ::disableHardwarePositionTracking,
        )
    }

    private val audioOutputLifecycleCoordinator by lazy {
        FfmpegAudioOutputLifecycleCoordinator(
            context = context,
            tag = TAG,
            routeController = androidAudioRouteController,
            isReleased = { isReleased.get() },
            isUsbExclusiveMode = { usbExclusiveMode },
            state = { _state },
            isPlaying = { isPlaying.get() },
            positionMs = { _positionMs },
            currentPath = { currentPath },
            sourcePath = { sourcePath },
            tempWavFile = { tempWavFile },
            resampledPath = { resampledPath },
            wavSampleRate = { wavSampleRate },
            wavChannels = { wavChannels },
            wavBitsPerSample = { wavBitsPerSample },
            wavDataSize = { wavDataSize },
            generation = { playbackSession.generation },
            audioTrackProvider = { audioTrack },
            rebuildTempWav = { request, lap ->
                tempWavRebuildCoordinator.submit(
                    positionMs = request.positionMs,
                    sampleRate = request.sampleRate,
                    channels = request.channels,
                    bitsPerSample = request.bitsPerSample,
                    dataSize = request.dataSize,
                    seekPath = request.seekPath,
                    originalSourcePath = request.originalSourcePath,
                    generation = request.generation,
                    lap = lap,
                )
            },
            rebuildStreaming = { request, lap ->
                streamingAudioTrackRebuildCoordinator.rebuild(
                    positionMs = request.positionMs,
                    path = request.seekPath,
                    lap = lap,
                )
            },
            attemptUsbRecovery = { sampleRate, bits, channels, path, generation ->
                attemptUsbRecovery(sampleRate, bits, channels, path, generation)
            },
            rebuildForSco = { audioTrackRebuildCoordinator.rebuildForSco() },
            rebuildAfterScoDisconnected = { audioTrackRebuildCoordinator.rebuildAfterScoDisconnected() },
            spatialRebuildPending = { androidSpatialAudioRebuildPending },
            setSpatialRebuildPending = { pending -> androidSpatialAudioRebuildPending = pending },
        )
    }

    private val releaseCoordinator by lazy {
        FfmpegAudioReleaseCoordinator(
            tag = TAG,
            released = isReleased,
            clearFade = playbackFadeRuntime::clear,
            closeDecoderPath = decoderPathResolver::close,
            clearUsbPostStartVolumeRestore = usbPostStartVolumeRestoreGate::clear,
            unregisterAudioDeviceCallback = ::unregisterAudioDeviceCallback,
            cancelPlaybackWorker = { playbackWorker.cancelCurrent("release", interrupt = false) },
            resetPlaybackState = {
                isPlaying.set(false)
                isPaused.set(false)
                androidSpatialAudioRebuildPending = false
            },
            closeNextDecoder = ::closeNextDecoder,
            nextDecoderExecutor = nextDecoderPrepareExecutor,
            resetDecoderState = {
                val oldDecoderTarget = detachActiveDecoderForRetire("release")
                retireDetachedDecoder(oldDecoderTarget, "release", joinTimeoutMs = 1L)
                decoderDone = false
                decoderStopToken = DecoderStopToken("release-idle")
                decoderHandleTransferred.set(false)
                pendingSeekMs = -1L
                pausedSeekCommitGate.clear()
                seekOutputBarrier.clear()
            },
            invalidatePlaybackSession = {
                playbackSession.invalidate("release", clearCurrentTrack = true)
            },
            shutdownPlaybackWorker = { playbackWorker.shutdown("release") },
            releaseAudioTrack = {
                audioTrackLifecycle.detachAndRelease(
                    reason = "release",
                    stop = true,
                    flush = false,
                )
            },
            releaseNativeAudioEngine = {
                nativeAudioEngineLifecycle.detachAndClose(
                    reason = "release",
                    stop = true,
                    flush = false,
                )
            },
            clearUsbExclusiveMode = {
                if (usbExclusiveMode) {
                    // PlayerController owns the global USB handle. Only clear this player's
                    // local mode flag during terminal teardown.
                    usbExclusiveMode = false
                }
            },
            releaseDspEngine = ::releaseDspEngine,
            closePcmConversion = pcmOutputConversion::close,
            clearPlaybackFiles = {
                tempWavFile = null
                sourcePath = null
                resampledPath = null
            },
            setIdle = { setState(State.IDLE) },
        )
    }

    private val playbackDspProcessor = FfmpegDspCoordinator(
        isBitPerfectBypassActive = { isStrictUsbBitPerfectPath() },
        reportBitPerfectBypass = { bit, reason -> logUsbBitPerfectBypassOnce(bit, reason) },
        isFloatOutputActive = { useFloatOutput },
        isPacked24OutputActive = { usePacked24Output },
        isRegularAndroidOutputActive = {
            // RawSMusic-owned PCM DSP is valid for every Android backend,
            // including Direct. Only USB exclusive remains bit-perfect bypass.
            !usbExclusiveMode
        }
    )
    private val dspRuntimeCoordinator by lazy {
        FfmpegDspRuntimeCoordinator(
            tag = TAG,
            processor = playbackDspProcessor,
            pcmOutputConversion = pcmOutputConversion,
            doublePrecisionEnabled = {
                AppPreferences.Player.internalDoublePrecisionProcessingEnabled
            },
            markAudioTrackFlush = { needsAudioTrackFlush.set(true) },
            flushNativePcm = { reason -> nativeAudioEngineLifecycle.flushCurrent(reason) },
            disableHardwarePositionTracking = ::disableHardwarePositionTracking,
        )
    }

    var internalDoublePrecisionProcessing: Boolean
        get() = playbackDspProcessor.internalDoublePrecisionProcessing
        set(value) { playbackDspProcessor.internalDoublePrecisionProcessing = value }

    /** Stateful dither applied only when integer PCM is reduced in bit depth. */
    var pcmDitherMode: Int
        get() = pcmOutputConversion.mode
        set(value) {
            pcmOutputConversion.mode = value
        }

    var stereoWidenFactor: Float
        get() = playbackDspProcessor.stereoWidenFactor
        set(value) { playbackDspProcessor.stereoWidenFactor = value }

    var realtimeStemEnabled: Boolean
        get() = playbackDspProcessor.realtimeStemEnabled
        set(value) { playbackDspProcessor.realtimeStemEnabled = value }

    var realtimeStemMode: Int
        get() = playbackDspProcessor.realtimeStemMode
        set(value) { playbackDspProcessor.realtimeStemMode = value }

    var realtimeStemStrength: Float
        get() = playbackDspProcessor.realtimeStemStrength
        set(value) { playbackDspProcessor.realtimeStemStrength = value }

    fun setAndroidDvc(enabled: Boolean, gain: Float, noDvcHeadroomDb: Float) =
        playbackDspProcessor.setAndroidDvc(enabled, gain, noDvcHeadroomDb)

    var androidBinauralSpatialEnabled: Boolean
        get() = playbackDspProcessor.androidBinauralSpatialEnabled
        set(value) { playbackDspProcessor.androidBinauralSpatialEnabled = value }

    var androidBinauralSpatialIntensity: Float
        get() = playbackDspProcessor.androidBinauralSpatialIntensity
        set(value) { playbackDspProcessor.androidBinauralSpatialIntensity = value }

    var androidBinauralSpatialRoom: Float
        get() = playbackDspProcessor.androidBinauralSpatialRoom
        set(value) { playbackDspProcessor.androidBinauralSpatialRoom = value }

    var androidBinauralBrirEnabled: Boolean
        get() = playbackDspProcessor.androidBinauralBrirEnabled
        set(value) { playbackDspProcessor.androidBinauralBrirEnabled = value }

    var androidBinauralSeparation: Float
        get() = playbackDspProcessor.androidBinauralSeparation
        set(value) { playbackDspProcessor.androidBinauralSeparation = value }

    var androidBinauralHeadSizeCentimeters: Float
        get() = playbackDspProcessor.androidBinauralHeadSizeCentimeters
        set(value) { playbackDspProcessor.androidBinauralHeadSizeCentimeters = value }

    var androidBinauralPinnaDetail: Float
        get() = playbackDspProcessor.androidBinauralPinnaDetail
        set(value) { playbackDspProcessor.androidBinauralPinnaDetail = value }

    var androidBinauralHeadTrackingEnabled: Boolean
        get() = playbackDspProcessor.androidBinauralHeadTrackingEnabled
        set(value) { playbackDspProcessor.androidBinauralHeadTrackingEnabled = value }

    fun setAndroidBinauralHeadPose(x: Float, y: Float, z: Float, w: Float) =
        playbackDspProcessor.setAndroidBinauralHeadPose(x, y, z, w)

    private var decoderFloatBuf: ByteArray? = null
    private var decoderPacked24Buf: ByteArray? = null
    private val pcmOutputPolicy = FfmpegPcmOutputPolicy(
        context = context,
        isUsbExclusive = { usbExclusiveMode },
        probedEncoding = { probedEncoding },
        bitsPerSample = { wavBitsPerSample },
        decoderSampleRate = { wavSampleRate },
    )

    /** 暴露 DSP 引擎给 PEQ 控制器使用 */
    val dspEngine: com.rawsmusic.module.player.dsp.NativeDSPEngine? get() = playbackDspProcessor.engine

    /** 当前是否使用浮点输出（非 USB 且位深 > 16） */
    private val useFloatOutput: Boolean
        get() = pcmOutputPolicy.useFloatOutput

    /** Android packed 24-bit 输出。FFmpegBridge 对 24/32bit 统一解码为 S32LE，写入前需要转为 3-byte packed。 */
    private val usePacked24Output: Boolean
        get() = pcmOutputPolicy.usePacked24Output

    private fun playbackBytesPerSample(): Int = pcmOutputPolicy.playbackBytesPerSample()

    private val useNativePcmOutput: Boolean
        get() = pcmOutputPolicy.useNativePcmOutput

    private fun preferredNativeOutputDeviceId(): Int =
        androidAudioRouteController.preferredNativeOutputDeviceId()

    private fun retargetNativeOutputDevice(reason: String, forcedDeviceId: Int = 0): Boolean =
        androidAudioRouteController.retargetNativeOutputDevice(reason, forcedDeviceId)

    fun repairAndroidOutputRouteAfterDeviceChange(reason: String, forceRebuild: Boolean = true) {
        androidAudioRouteController.repairAndroidOutputRoute(reason, forceRebuild)
    }

    private fun consumePendingAndroidAudioTrackRouteRebuild(): AudioTrack? =
        androidAudioRouteController.consumePendingAudioTrackRouteRebuild()

    private val outputBytesPerSample: Int
        get() = pcmOutputPolicy.outputBytesPerSample

    /** 公开播放状态 — 供 PlayerController 查询 */
    val isPlayingNow: Boolean get() = isPlaying.get()

    var usbExclusiveMode = false
    var usbBitPerfectMode = false
    // Per-track strict bit-perfect gate. USB PCM transport is capped at S32LE.
    // 64-bit source files must be decoded/down-converted to 32-bit before USB output.
    private var usbStrictBitPerfectForCurrentTrack = false
    /** Controller may set this for same-format manual next: keep native USB engine and skip prepare/reinit once. */
    @Volatile
    var usbReuseEngineForNextStart: Boolean = false
    var usbActualOutputSampleRate = 0
    var usbPrepareForPlayback: ((sampleRate: Int, bitDepth: Int, channels: Int, srcFilePath: String?) -> Boolean)? = null
    /** All native USB starts are serialized by UsbExclusiveManager's Transport owner. */
    var usbStartStreaming: ((reason: String) -> Boolean)? = null
    var onUsbTransportLost: (() -> Unit)? = null
    var onUsbPlaybackStarted: (() -> Unit)? = null
    var onUsbPlaybackStopped: (() -> Unit)? = null
    var onUsbPlaybackDataFlowing: (() -> Unit)? = null

    private val usbAudibleWatchdog by lazy {
        UsbAudibleWatchdog(
            isUsbExclusive = { usbExclusiveMode },
            isReleased = { isReleased.get() },
            isPlaying = { isPlaying.get() },
            isSerialCurrent = ::isUsbPlaybackSerialCurrent,
            onPlaybackDataFlowing = { onUsbPlaybackDataFlowing?.invoke() },
            tag = TAG,
        )
    }
    private val usbExclusiveCutoverCoordinator by lazy {
        UsbExclusiveCutoverCoordinator(
            stopPlayback = ::stop,
            cancelPlaybackWorker = { reason, interrupt -> playbackWorker.cancelCurrent(reason, interrupt) },
            awaitWorkerIdle = playbackWorker::awaitIdle,
            decoderThread = { decoderThread },
            tag = TAG,
        )
    }
    private val playbackStopStateCoordinator by lazy {
        PlaybackStopStateCoordinator(
            clearFade = playbackFadeRuntime::clear,
            invalidateUsbSerial = ::invalidateUsbPlaybackSerial,
            resetUsbRecoveryFuse = ::resetUsbHardRecoveryFuse,
            resetPlaybackFlags = {
                isPlaying.set(false)
                isPaused.set(false)
                androidSpatialAudioRebuildPending = false
                decoderDone = false
                decoderHandleTransferred.set(false)
            },
            clearSeekState = {
                pendingSeekMs = -1L
                pausedSeekCommitGate.clear()
                seekOutputBarrier.clear()
                queuedStartSeekMs = -1L
                seekPositionMs = -1L
            },
            clearUsbState = {
                usbRawDsdDirectActive = false
            },
            tag = TAG,
        )
    }
    private val playbackResourceStopCoordinator by lazy {
        PlaybackResourceStopCoordinator(
            cancelPlaybackWorker = { playbackWorker.cancelCurrent("stop", interrupt = false) },
            retireDecoder = {
                val oldDecoderTarget = detachActiveDecoderForRetire("stop")
                retireDetachedDecoder(oldDecoderTarget, "stop", joinTimeoutMs = 1L)
            },
            closeNextDecoder = ::closeNextDecoder,
            closeDecoderPath = { decoderPathResolver.close("stop") },
            clearUsbPostStartRestoreGate = { usbPostStartVolumeRestoreGate.clear("stop") },
            invalidatePlaybackSession = { playbackSession.invalidate("stop") },
            releaseAudioTrack = {
                audioTrackLifecycle.detachAndRelease(
                    reason = "stop",
                    stop = true,
                    flush = false,
                )
            },
            closeNativeAudioEngine = {
                nativeAudioEngineLifecycle.detachAndClose(
                    reason = "stop",
                    stop = true,
                    flush = false,
                )
            },
            isUsbExclusive = { usbExclusiveMode },
            releaseDsp = ::releaseDspEngine,
            setStopped = { setState(State.STOPPED) },
            tag = TAG,
        )
    }
    /**
     * Called when the stream health model identifies a USB failure.
     * Returns true only when the controller has taken ownership of a destructive
     * full-reopen. The feeder must not run its own teardown in that case.
     */
    var onUsbStreamHealthFailure: ((kind: UsbSilentKind, reason: String) -> Boolean)? = null
    /** Controller-side guard for situations where hard reopen is more dangerous than waiting. */
    var shouldDeferUsbHardRecovery: ((reason: String) -> Boolean)? = null
    /** nativeStart 前回调：只准备 PCM/session envelope，不得写 Feature Unit。 */
    var onBeforeUsbNativeStart: (() -> Unit)? = null
    private val usbPostStartVolumeRestoreGate = UsbPostStartVolumeRestoreGate(TAG) {
        onUsbPlaybackDataFlowing?.invoke()
    }

    private fun armUsbPostStartVolumeRestore(reason: String) {
        usbPostStartVolumeRestoreGate.arm(reason)
    }

    private fun maybeRestoreUsbVolumeRoute(reason: String, nativeBuffered: Int) {
        usbPostStartVolumeRestoreGate.maybeRestore(reason, nativeBuffered)
    }

    fun setSuppressAndroidExternalRouteForUsbCutover(enabled: Boolean, reason: String) {
        androidAudioRouteController.setSuppressExternalRouteForUsbCutover(enabled, reason)
    }

    private fun shouldDeferHardUsbRecovery(reason: String): Boolean {
        return runCatching { shouldDeferUsbHardRecovery?.invoke(reason) == true }.getOrDefault(false)
    }

    private fun armUsbAudibleColdStartWatchdog(reason: String) {
        if (!usbExclusiveMode) return
        usbAudibleWatchdog.arm(usbPlaybackSerial.get(), reason)
    }

    private fun startUsbEngineWithSafety(reason: String): Boolean {
        AppLogger.i(TAG, "startUsbEngineWithSafety: reason=$reason")
        onBeforeUsbNativeStart?.invoke()
        return try {
            val ok = usbStartStreaming?.invoke(reason) ?: UsbAudioEngine.start()
            if (ok) armUsbAudibleColdStartWatchdog(reason)
            ok
        } catch (t: Throwable) {
            AppLogger.e(TAG, "startUsbEngineWithSafety failed: reason=$reason", t)
            false
        }
    }

    // USB 播放序列号：切歌/stop/release 前递增，旧线程 token 不一致则禁止 nativeStart
    private val usbPlaybackSerial = java.util.concurrent.atomic.AtomicLong(0)

    fun nextUsbPlaybackSerial(): Long = usbPlaybackSerial.incrementAndGet()

    fun isUsbPlaybackSerialCurrent(serial: Long): Boolean = usbPlaybackSerial.get() == serial

    fun invalidateUsbPlaybackSerial(reason: String) {
        val v = usbPlaybackSerial.incrementAndGet()
        android.util.Log.i("FfmpegPlayer", "USB playback serial invalidated: serial=$v reason=$reason")
    }

    private val usbBitPerfectPolicyGate by lazy {
        UsbBitPerfectPolicyGate(
            isUsbExclusive = { usbExclusiveMode },
            isBitPerfectEnabled = { usbBitPerfectMode },
            isStrictForCurrentTrack = { usbStrictBitPerfectForCurrentTrack },
            tag = TAG,
        )
    }

    private fun isStrictUsbBitPerfectPath(): Boolean =
        usbBitPerfectPolicyGate.isStrictPath()

    private fun logUsbBitPerfectBypassOnce(bit: Long, reason: String) {
        usbBitPerfectPolicyGate.logBypassOnce(bit, reason)
    }

    private fun resetUsbBitPerfectPolicyLog() {
        usbBitPerfectPolicyGate.resetLog()
    }

    private fun flushUsbNativeBufferForSeek(reason: String) {
        if (!usbExclusiveMode) return
        try {
            val h = UsbAudioEngine.currentHandle
            if (h != 0L) {
                AppLogger.w(TAG, "USB seek: nativePrepareForSeek warm seek, reason=$reason")
                UsbAudioEngine.nativePrepareForSeek(h, 80, reason)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "USB seek flush failed", t)
        }
    }

    private fun flushNativePcmBufferForSeek(reason: String) {
        if (usbExclusiveMode) return
        try {
            AppLogger.w(TAG, "Native PCM seek flush: clearing native backend buffer, reason=$reason")
            nativeAudioEngineLifecycle.flushCurrent("seek_$reason")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Native PCM seek flush failed", t)
        }
    }

    /** DSP 引擎重新初始化后的回调，用于重新连接 PEQ 控制器 */
    var onDspEngineReinit: (() -> Unit)? = null
        set(value) {
            field = value
            playbackDspProcessor.onEngineReinit = value
        }

    fun startUsbStreamingIfNeeded() {
        if (usbExclusiveMode && _state == State.PLAYING) {
            AppLogger.i(TAG, "startUsbStreamingIfNeeded: triggering native start")
            if (startUsbEngineWithSafety("startUsbStreamingIfNeeded")) {
                armUsbPostStartVolumeRestore("startUsbStreamingIfNeeded")
            }
        }
    }

    private var bytesReadTotal = 0L
    private var bytesWrittenTotal = 0L
    private var bytesNativeAcceptedTotal = 0L
    private var lastThroughputTime = 0L
    private var lastBytesRead = 0L
    private var lastBytesWritten = 0L
    private var lastBytesNativeAccepted = 0L
    private var nativeWriteCallCount = 0L
    private var pumpReadCount = 0L

    private val waveformDispatcher by lazy {
        PcmWaveformDispatcher(
            frameCallback = { onPcmWaveformFrame },
            sampleEncoding = { bitsPerSample ->
                UsbAudioFormatPolicy.visualizerSampleEncoding(
                    bitsPerSample = bitsPerSample,
                    useFloatOutput = useFloatOutput,
                    usePacked24Output = usePacked24Output,
                )
            },
        )
    }

    private fun dispatchWaveformFrame(buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        waveformDispatcher.dispatch(buffer, read, channels, sampleRate, bitsPerSample)
    }

    fun releaseAudioTrackForUsb() {
        audioTrackLifecycle.detachAndRelease(
            reason = "usb_exclusive_mode",
            stop = true,
            flush = false
        )
    }

    fun stopForUsbExclusiveCutover(timeoutMs: Long = 2_500L): Boolean {
        return usbExclusiveCutoverCoordinator.stop(timeoutMs)
    }

    // ==================== v2 Playback fade / manual crossfade API ====================

    fun suppressNextStartFadeIn(reason: String) {
        playbackFadeCoordinator.suppressNextStartFadeIn(reason)
    }

    fun armNextStartFadeIn(durationMs: Int, reason: String) {
        playbackFadeCoordinator.armNextStartFadeIn(durationMs, reason)
    }

    fun updateNextSongPlan(nextPath: String?, durationMs: Int, reason: String) {
        this.nextSongPath = nextPath
        crossfadeDurationMs = durationMs.coerceAtLeast(0)
        if (nextPath.isNullOrBlank() || !usbExclusiveMode || _state != State.PLAYING || !isPlaying.get()) {
            nextDecoderPrepareEpoch.incrementAndGet()
            return
        }

        val generation = playbackSession.generation
        val epoch = nextDecoderPrepareEpoch.incrementAndGet()
        nextDecoderPrepareExecutor.execute {
            if (epoch != nextDecoderPrepareEpoch.get() ||
                generation != playbackSession.generation ||
                this.nextSongPath != nextPath ||
                isReleased.get()
            ) {
                return@execute
            }
            if (gaplessNextDecoder.pathFor(generation) == nextPath) return@execute
            val ok = prepareNextDecoder(nextPath, generation)
            AppLogger.i(
                TAG,
                "USB next decoder producer finished: ok=$ok path=$nextPath gen=$generation reason=$reason"
            )
        }
    }

    fun requestManualCrossfadeTo(nextPath: String, durationMs: Int, reason: String): Boolean {
        if (nextPath.isBlank() || _state != State.PLAYING || !isPlaying.get() || isReleased.get()) return false
        if (isStrictUsbBitPerfectPath()) {
            logUsbBitPerfectBypassOnce(1L shl 8, "manual crossfade")
            return false
        }
        val generation = playbackSession.generation
        closeNextDecoder()
        nextSongPath = nextPath
        crossfadeDurationMs = durationMs.coerceAtLeast(1)
        if (!prepareNextDecoder(nextPath, generation)) {
            closeNextDecoder()
            playbackSession.clearNextRequest("manual_crossfade_prepare_failed")
            return false
        }
        val prepared = gaplessNextDecoder.snapshotFor(generation)
        if (prepared == null || prepared.path != nextPath || !canCrossfadePreparedNext(prepared)) {
            closeNextDecoder()
            playbackSession.clearNextRequest("manual_crossfade_incompatible")
            return false
        }
        manualCrossfadeTargetPath = nextPath
        manualCrossfadeGeneration = generation
        manualCrossfadeRequested = true
        AppLogger.i(TAG, "Manual crossfade requested: path=$nextPath durationMs=$crossfadeDurationMs gen=$generation reason=$reason")
        return true
    }

    /**
     * Queue an immediate same-profile USB track switch on the active feeder.
     *
     * This is deliberately not a new play() request. It keeps the current
     * libusb handle, claimed interface, alt setting, event owner, submit owner,
     * feedback transfer and ISO pool alive. Only decoder ownership and the PCM
     * generation change while the current and next decoder states are coordinated.
     */
    suspend fun requestUsbSameProfileTrackSwitch(
        nextPath: String,
        reason: String,
    ): UsbManualSwitchResult = usbSameProfileTrackSwitchCoordinator.request(nextPath, reason)

    fun fadeOutForTransitionBlocking(durationMs: Int, reason: String): Boolean {
        return playbackFadeCoordinator.fadeOutForTransitionBlocking(durationMs, reason)
    }

    private fun armConfiguredStartFade(reason: String) {
        playbackFadeCoordinator.armConfiguredStartFade(reason)
    }

    private fun armSeekFadeIn(durationMs: Int, reason: String) {
        playbackFadeCoordinator.armSeekFadeIn(durationMs, reason)
    }

    fun armDefaultStartFadeIn(durationMs: Int, reason: String) {
        playbackFadeCoordinator.armDefaultStartFadeIn(durationMs, reason)
    }

    fun pauseWithFadeBlocking(durationMs: Int, reason: String) {
        playbackFadeCoordinator.pauseWithFadeBlocking(durationMs, reason)
    }

    private fun applyPlaybackFade(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean = useFloatOutput,
        outputIsPacked24: Boolean = usePacked24Output
    ) {
        playbackFadeCoordinator.process(
            buffer = buffer,
            offset = offset,
            length = length,
            sampleRate = sampleRate,
            frameSize = frameSize,
            bitsPerSample = bitsPerSample,
            outputIsFloat = outputIsFloat,
            outputIsPacked24 = outputIsPacked24
        )
    }

    fun play(path: String) {
        AppLogger.w(TAG, "=== play() called, path=$path")
        RealtimePlaybackPcmProcessorRegistry.reset("new_play_request")
        resetUsbBitPerfectPolicyLog()
        resetUsbHardRecoveryFuse("new_play_request")
        if (isReleased.get()) return

        // 关闭上一首的 SAF PFD
        decoderPathResolver.close("play_new_request")
        usbPostStartVolumeRestoreGate.clear("new_play_request")

        // 清除上一首的 gapless/crossfade 状态
        closeNextDecoder()
        playbackSession.clearNextRequest("play_new_request")

        registerAudioDeviceCallback()

        // Signal the old playback loop before cancelling its Future. Native USB calls may ignore
        // thread interruption, so Future.isDone alone cannot prove the single worker is available.
        isPlaying.set(false)
        isPaused.set(false)
        androidSpatialAudioRebuildPending = false
        playbackWorker.cancelCurrent("play_new_request", interrupt = true)

        // Retire any previous decoder before changing the playback session token.
        // This covers both the active-thread case and the EOF case where a decoder
        // handle is kept alive for seek; dropping decoderHandle to 0 without this
        // step would leak the native handle.
        val oldDecoderTarget = detachActiveDecoderForRetire("play_new_request")
        retireDetachedDecoder(oldDecoderTarget, "play_new_request", joinTimeoutMs = 1L)

        val generation = playbackSession.beginNewSession(path)

        decoderDone = false
        decoderStopToken = DecoderStopToken("play-$generation")
        decoderHandleTransferred.set(false)
        pendingSeekMs = -1L
        pausedSeekCommitGate.clear()
        seekOutputBarrier.clear()
        usbRawDsdDirectActive = false
        seekPositionMs = queuedStartSeekMs
        queuedStartSeekMs = -1L
        _positionMs = if (seekPositionMs > 0L) seekPositionMs else 0L
        hardwarePositionOffsetMs = _positionMs
        AppLogger.i(TAG, "RESTORE_TRACE play_session generation=$generation queuedSeek=$seekPositionMs initialPosition=$_positionMs hwOffset=$hardwarePositionOffsetMs")
        _durationMs = 0L

        audioTrackLifecycle.detachAndRelease(
            reason = "play_new_request",
            stop = true,
            flush = true
        )
        // OpenSL/AAudio own a native queue independent of AudioTrack. Retire it before
        // preparing the replacement track so no old PCM can continue under the new UI.
        nativeAudioEngineLifecycle.detachAndClose(
            reason = "play_new_request",
            stop = true,
            flush = true
        )

        // A cancelled Future can still be executing inside uninterruptible native
        // code. Never create a parallel replacement worker while USB is process-global.
        val replacementReady = playbackWorker.ensureAvailableForReplacement(
            reason = "play_new_request",
            timeoutMs = if (usbExclusiveMode) 2_500L else 1_500L
        )
        if (!replacementReady) {
            AppLogger.e(TAG, "play refused: previous playback worker did not actually exit")
            isPlaying.set(false)
            setState(State.ERROR)
            listener?.onError("旧播放线程尚未退出，已阻止并发启动以保护 USB 设备")
            return
        }

        sourcePath = path
        currentPath = path
        setState(State.PREPARING)

        val sourcePath = path
        playbackWorker.submit("play_prepare") {
            try {
                prepareAndStartPlayback(sourcePath = sourcePath, generation = generation)
            } catch (e: InterruptedException) {
                AppLogger.w(TAG, "=== play task interrupted (song switch)")
            } catch (e: LinkageError) {
                if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                    MusicSourceResolvedStreamRegistry.lookup(sourcePath)?.let { entry ->
                        AppLogger.e(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} LINKAGE_ERROR generation=${entry.generation} " +
                                "stage=prepare message=${e.message.orEmpty().take(512)}",
                            e
                        )
                    }
                    AppLogger.e(TAG, "play failed: native linkage error", e)
                    setState(State.ERROR)
                    listener?.onError("在线播放 native 接口不匹配: ${e.message}")
                }
            } catch (e: Exception) {
                if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                    AppLogger.e(TAG, "play failed", e)
                    setState(State.ERROR)
                    listener?.onError("播放失败: ${e.message}")
                }
            }
        }
    }

    fun queueStartSeekPosition(positionMs: Long, reason: String) {
        queuedStartSeekMs = positionMs.coerceAtLeast(-1L)
        AppLogger.i(TAG, "queueStartSeekPosition: pos=$queuedStartSeekMs reason=$reason")
    }

    private fun prepareAndStartPlayback(sourcePath: String, generation: Int) {
        AppLogger.d(TAG, "prepareAndStartPlayback: source=$sourcePath gen=$generation")
        MusicSourceResolvedStreamRegistry.lookup(sourcePath)?.let { entry ->
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} PLAYER_PREPARE generation=${entry.generation} " +
                    "usbExclusive=$usbExclusiveMode bitPerfect=$usbBitPerfectMode " +
                    "url=${OnlinePlaybackDiagnostics.safeUrl(sourcePath)}"
            )
        }
        usbStrictBitPerfectForCurrentTrack = false
        usbRawDsdDirectActive = false
        _durationMs = decoderOpenHelper.probeDuration(sourcePath)
        AppLogger.d(TAG, "probeDuration: ${_durationMs}ms")
        if (!isStillCurrentPlayback(sourcePath, generation)) return

        var usbTargetSr = 0; var usbTargetBits = 0; var usbTargetCh = 0
        var usbPcmToDsdActive = false
        var usbSourceIsDsd = false
        var androidSourceIsDsd = false
        var atTargetRate = 0; var atTargetBits = 0; var atTargetCh = 0
        var sourceDsdMode: com.rawsmusic.module.player.usb.UsbDsdModeConfig? = null

        if (usbExclusiveMode && abortPlaybackStageIfObsolete(sourcePath, generation, "prepare_usb_probe")) {
            return
        }
        val resolvedTarget = playbackTargetCoordinator.resolve(
            sourcePath = sourcePath,
            usbExclusive = usbExclusiveMode,
            usbBitPerfectMode = usbBitPerfectMode,
        )
        resolvedTarget.usb?.let { target ->
            usbStrictBitPerfectForCurrentTrack = target.strictBitPerfect
            sourceDsdMode = target.sourceDsdMode
            usbPcmToDsdActive = target.pcmToDsdMode != null
            usbSourceIsDsd = target.sourceIsDsd
            usbTargetSr = target.sampleRate
            usbTargetBits = target.bitsPerSample
            usbTargetCh = target.channels
        }
        resolvedTarget.android?.let { target ->
            atTargetRate = target.sampleRate
            atTargetBits = target.bitsPerSample
            atTargetCh = target.channels
            probedEncoding = target.encoding
            androidSourceIsDsd = target.sourceIsDsd
        }

        if (usbExclusiveMode) {
            if (abortPlaybackStageIfObsolete(sourcePath, generation, "prepare_before_usb_decoder_open")) return
            val useRawDsdDirectDecoder = sourceDsdMode != null
            AppLogger.i(
                TAG,
                if (useRawDsdDirectDecoder) {
                    "USB raw DSD decoder: opening $sourcePath, targetCh=$usbTargetCh dsdMode=$sourceDsdMode"
                } else if (usbPcmToDsdActive) {
                    "USB PCM->DSD decoder: opening $sourcePath, sourceRate=$usbTargetSr, sourceBits=$usbTargetBits, targetCh=$usbTargetCh"
                } else {
                    "USB streaming decoder: opening $sourcePath, targetSr=$usbTargetSr, targetBits=$usbTargetBits, targetCh=$usbTargetCh"
                }
            )
            val handle = if (useRawDsdDirectDecoder) {
                decoderOpenHelper.openExact(sourcePath, 0, 1, usbTargetCh)
            } else if (usbSourceIsDsd) {
                // A PCM-only DAC still needs the raw DSD demuxer. Bypass generic safe-mode
                // fallbacks so the native DSD-to-PCM decimator receives its intended rate.
                decoderOpenHelper.openExact(
                    sourcePath,
                    usbTargetSr,
                    usbTargetBits.coerceAtLeast(32),
                    usbTargetCh
                )
            } else {
                decoderOpenHelper.openWithFallback(sourcePath, usbTargetSr, usbTargetBits, usbTargetCh)
            }
            if (handle == 0L) {
                AppLogger.e(
                    TAG,
                    if (useRawDsdDirectDecoder) {
                        "USB raw DSD decoder: openDecoder failed"
                    } else {
                        "USB streaming decoder: openDecoder failed (all fallbacks)"
                    }
                )
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("FFmpeg 流式解码器打开失败")
                }
                return
            }
            decoderHandle = handle

            if (!isStillCurrentPlayback(sourcePath, generation)) {
                FFmpegBridge.closeDecoder(handle)
                decoderHandle = 0L
                return
            }

            wavSampleRate = FFmpegBridge.getDecoderSampleRate(handle)
            wavChannels = FFmpegBridge.getDecoderChannels(handle)
            wavBitsPerSample = FFmpegBridge.getDecoderBitsPerSample(handle)
            _durationMs = FFmpegBridge.getDecoderDuration(handle).let { if (it <= 0) 0L else it }
            wavDataOffset = 0
            wavFormatTag = 1
            usbActualOutputSampleRate = wavSampleRate
            usbRawDsdDirectActive = useRawDsdDirectDecoder && wavBitsPerSample == 1
            if (!usbRawDsdDirectActive &&
                !verifyUsbBitPerfectDecoderFormat(wavSampleRate, wavBitsPerSample, wavChannels, usbTargetSr, usbTargetBits, usbTargetCh)
            ) {
                FFmpegBridge.closeDecoder(handle)
                decoderHandle = 0L
                isPlaying.set(false)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("USB 完美比特模式无法保持源格式")
                }
                return
            }
            AppLogger.i(
                TAG,
                if (usbRawDsdDirectActive) {
                    "USB raw DSD decoder: rawByteRate=${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms"
                } else {
                    "USB streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms"
                }
            )

            val bytesPerSample = UsbAudioFormatPolicy.decoderBytesPerSample(wavBitsPerSample)
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 3).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            AppLogger.i(TAG, "USB RingBuffer created: ${ringCapacity} bytes (${PlaybackBufferMath.durationMsForBytes(ringCapacity, bytesPerSec.toLong())}ms)")

            isPlaying.set(true)

            val frameSize = wavChannels * UsbAudioFormatPolicy.decoderBytesPerSample(wavBitsPerSample)
            val usbChunkSize = ((16384 / frameSize) * frameSize).coerceAtLeast(frameSize * 256)
            AppLogger.i(TAG, "USB decoder chunk: $usbChunkSize bytes (decoderFrameSize=$frameSize)")
            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                val seekOk = FFmpegBridge.seekDecoder(handle, seekMs)
                AppLogger.i(TAG, "RESTORE_TRACE usb_decoder_seek target=$seekMs ok=$seekOk")
                rb.clear()
                _positionMs = seekMs
            }

            if (
                abortPlaybackStageIfObsolete(
                    sourcePath,
                    generation,
                    stage = "prepare_before_usb_stream_start",
                    closeRingBuffer = true,
                    closeDecoderHandle = true
                )
            ) {
                return
            }

            initDspEngine()
            armConfiguredStartFade("play_start_usb")
            startUsbStreamingPlayback(sourcePath, generation, handle, rb, usbChunkSize)
        } else {
            AppLogger.i(TAG, "Streaming decoder: opening $sourcePath, targetRate=$atTargetRate, targetBits=$atTargetBits, targetCh=$atTargetCh")
            val handle = if (androidSourceIsDsd) {
                AppLogger.i(
                    TAG,
                    "Android DSD-to-PCM decoder: source=$sourcePath target=${atTargetRate}Hz/${atTargetBits}bit/${atTargetCh}ch"
                )
                decoderOpenHelper.openExact(
                    sourcePath,
                    atTargetRate,
                    atTargetBits.coerceIn(16, 32),
                    atTargetCh
                )
            } else {
                decoderOpenHelper.openWithFallback(sourcePath, atTargetRate, atTargetBits, atTargetCh)
            }
            if (handle == 0L) {
                AppLogger.e(TAG, "Streaming decoder: openDecoder failed (all fallbacks)")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("FFmpeg 流式解码器打开失败")
                }
                return
            }
            decoderHandle = handle

            if (!isStillCurrentPlayback(sourcePath, generation)) {
                FFmpegBridge.closeDecoder(handle)
                decoderHandle = 0L
                return
            }

            wavSampleRate = FFmpegBridge.getDecoderSampleRate(handle)
            wavChannels = FFmpegBridge.getDecoderChannels(handle)
            wavBitsPerSample = FFmpegBridge.getDecoderBitsPerSample(handle)
            _durationMs = FFmpegBridge.getDecoderDuration(handle).let { if (it <= 0) 0L else it }
            wavDataOffset = 0
            wavFormatTag = 1
            AppLogger.i(TAG, "Streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms")

            val bytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 2).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            AppLogger.i(TAG, "RingBuffer created: ${ringCapacity} bytes (${PlaybackBufferMath.durationMsForBytes(ringCapacity, bytesPerSec.toLong())}ms)")

            isPlaying.set(true)

            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                val seekOk = FFmpegBridge.seekDecoder(handle, seekMs)
                AppLogger.i(TAG, "RESTORE_TRACE decoder_seek target=$seekMs ok=$seekOk")
                rb.clear()
                _positionMs = seekMs
            }

            decoderDone = false  // 重置解码完成标志
            decoderHandleTransferred.set(false)

            val startToken = decoderStopToken
            if (!startDecoderThread(sourcePath, generation, handle, rb, startToken)) {
                return
            }

            val onlineEntry = MusicSourceResolvedStreamRegistry.lookup(sourcePath)
            val dspStartedAt = SystemClock.elapsedRealtime()
            onlineEntry?.let { entry ->
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} DSP_INIT_START generation=${entry.generation} " +
                        "format=${wavSampleRate}Hz/${wavBitsPerSample}bit/${wavChannels}ch"
                )
            }
            initDspEngine()
            onlineEntry?.let { entry ->
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} DSP_INIT_END generation=${entry.generation} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - dspStartedAt}"
                )
            }
            armConfiguredStartFade("play_start")
            startStreamingPlayback(sourcePath, generation)
        }
    }

    fun pause() {
        if (_state != State.PLAYING) {
            AppLogger.w(TAG, "=== pause(): state=$_state, NOT PLAYING, skipping ===")
            return
        }
        AppLogger.w(TAG, "=== pause(): PAUSING, isPlaying=${isPlaying.get()}, audioTrack=${audioTrack != null}, trackState=${audioTrack?.playState} ===")
        if (usbExclusiveMode) {
            // Phase 22: direct FfmpegAudioPlayer.pause() is often triggered by
            // external/background focus or service callbacks.  In USB exclusive
            // mode, stopping ISO here causes pop/current noise and unwanted
            // forced pauses when launching other apps.  User-visible pause goes
            // through PlayerController.pause(), which performs the explicit
            // transition.
            AppLogger.w(TAG, "pause(): USB exclusive direct pause ignored; use PlayerController.pause for user pause")
            return
        }
        isPaused.set(true)
        nativeAudioEngineLifecycle.pauseCurrent("pause")
        try { audioTrack?.pause() } catch (_: Exception) {}
        setState(State.PAUSED)
    }

    /**
     * 只暂停 decoder/playback thread，不调用 UsbAudioEngine.pause()。
     * USB 独占模式下，由 PlayerController 控制先 fade-out 再 enterStandby。
     */
    fun pauseDecoderOnly(reason: String) {
        AppLogger.w(TAG, "=== pauseDecoderOnly(): reason=$reason state=$_state ===")
        if (_state != State.PLAYING) return
        isPaused.set(true)
        if (!usbExclusiveMode) {
            nativeAudioEngineLifecycle.pauseCurrent("pauseDecoderOnly_$reason")
            try { audioTrack?.pause() } catch (_: Exception) {}
        }
        // 不调用 UsbAudioEngine.pause()，由调用方控制 USB standby
        setState(State.PAUSED)
    }

    fun keepPausedAfterUsbSeek(reason: String) {
        AppLogger.w(TAG, "=== keepPausedAfterUsbSeek(): reason=$reason state=$_state ===")
        isPaused.set(true)
        setState(State.PAUSED)
    }

    /**
     * 手动切歌时停止 decoder，不调用 UsbAudioEngine.pause()。
     * 由 PlayerController 控制先 fade-out 再 stop/flush USB。
     */
    fun stopForManualTrackSwitch(reason: String) {
        AppLogger.w(TAG, "=== stopForManualTrackSwitch(): reason=$reason state=$_state ===")
        isPaused.set(true)
        nativeAudioEngineLifecycle.stopCurrent("manualTrackSwitch_$reason")
        try { audioTrack?.pause() } catch (_: Exception) {}
        try { audioTrack?.flush() } catch (_: Exception) {}
        setState(State.IDLE)
    }

    private fun applyPausedSeekBeforeResume() {
        val result = pausedSeekCommitGate.awaitLatest(PAUSED_SEEK_COMMIT_WAIT_MS) ?: return
        // Keep the playback clock pinned to the lyric timestamp selected while paused. Hardware
        // position remains disabled until the post-seek output flush establishes the new origin.
        _positionMs = result.targetMs
        disableHardwarePositionTracking()
        needsAudioTrackFlush.set(true)
        AppLogger.i(
            TAG,
            "paused seek before resume: target=${result.targetMs} serial=${result.serial} committed=${result.committed}"
        )
    }

    fun resume(): Boolean {
        AppLogger.w(TAG, "=== resume(): state=$_state, isPlaying=${isPlaying.get()}, audioTrack=${audioTrack != null}, trackState=${audioTrack?.playState} ===")
        if (_state != State.PAUSED) {
            AppLogger.w(TAG, "=== resume(): state=$_state, NOT PAUSED, returning false ===")
            return false
        }

        applyPausedSeekBeforeResume()

        if (!usbExclusiveMode && androidSpatialAudioRebuildPending) {
            androidSpatialAudioRebuildPending = false
            AppLogger.i(TAG, "resume(): applying deferred Android spatial-audio output rebuild")
            rebuildAudioTrack()
            return true
        }

        if (!usbExclusiveMode) {
            val nativeEngine = nativeAudioEngineLifecycle.currentOrNull()
            if (nativeEngine != null && isPlaying.get()) {
                armSeekFadeIn(PlaybackTransitionRuntime.transportFadeMs, "resume_native")
                isPaused.set(false)
                synchronized(pauseLock) { pauseLock.notifyAll() }
                nativeAudioEngineLifecycle.startCurrent("resume")
                setState(State.PLAYING)
                return true
            }
            val track = audioTrack
            // resume 前主动检测 track 有效性
            // 无论什么触发 resume（UI、通知栏、蓝牙、音频焦点），都保证 track 可用
            if (track == null || !isPlaying.get()) {
                AppLogger.w(TAG, "resume(SOS): audioTrack=$track, isPlaying=${isPlaying.get()}, rebuilding")
                rebuildAudioTrack()
                return true
            }
            if (track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                AppLogger.w(TAG, "resume(SOS): audioTrack STOPPED, rebuilding")
                rebuildAudioTrack()
                return true
            }
            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                AppLogger.w(TAG, "resume(SOS): audioTrack UNINITIALIZED, rebuilding")
                rebuildAudioTrack()
                return true
            }
            // 格式变化检测 — 比较当前 AudioTrack 格式与解码器输出格式
            if (isTrackFormatChanged()) {
                AppLogger.w(TAG, "resume(SOS): format changed (${audioTrackFormatMonitor.snapshotDescription()} -> ${audioTrackFormatMonitor.currentDescription(wavSampleRate, wavChannels, probedEncoding)}), rebuilding")
                rebuildAudioTrack()
                return true
            }
            armSeekFadeIn(PlaybackTransitionRuntime.transportFadeMs, "resume_audiotrack")
            isPaused.set(false)
            synchronized(pauseLock) { pauseLock.notifyAll() }
            try { track.play() } catch (_: Exception) {}
            setState(State.PLAYING)
            return true
        }

        // USB 独占模式：必须先确保引擎就绪，再解除暂停
        AppLogger.i(TAG, "resume(): resuming USB streaming, engine init=${UsbAudioEngine.isInitialized()} running=${UsbAudioEngine.isRunning()}")

        // 0. 快速路径：引擎正在运行且无需重建 → 直接解除暂停，不要 stop 引擎
        //    USB 独占设备不需要像 AudioTrack 那样走 pause/resume 生命周期
        val engineRunning = UsbAudioEngine.isRunning()
        val needsReinit = UsbAudioEngine.isPolicyChangedSinceInit() == true || !UsbAudioEngine.isInitialized()

        if (engineRunning && !needsReinit) {
            AppLogger.i(TAG, "resume(): USB engine running fine, skipping stop/reinit, just unpause")
            armSeekFadeIn(PlaybackTransitionRuntime.transportFadeMs, "resume_usb")
            isPaused.set(false)
            synchronized(pauseLock) { pauseLock.notifyAll() }
            setState(State.PLAYING)
            return true
        }

        // 1. 先尝试软恢复：不要 stopAndFlush，因为它会清空 ring buffer，
        //    后续 nativeStart 只能先发静音，后台冷启动/恢复会短暂无声。
        if (!needsReinit && UsbAudioEngine.isInitialized() && UsbAudioEngine.currentHandle != 0L) {
            val softStarted = startUsbEngineWithSafety("resume_soft")
            if (softStarted) {
                armUsbPostStartVolumeRestore("resume_soft")
                AppLogger.i(TAG, "resume(): soft recovery succeeded (stop + start, buffer preserved)")
                armSeekFadeIn(PlaybackTransitionRuntime.transportFadeMs, "resume_usb_soft")
                isPaused.set(false)
                synchronized(pauseLock) { pauseLock.notifyAll() }
                setState(State.PLAYING)
                return true
            }
            AppLogger.w(TAG, "resume(): soft start failed, falling through to hard recovery")
            // 软恢复失败 → 说明引擎内部坏了，需要硬恢复
        }

        // 2. Reconfigure only through the Controller-provided manager callback.
        // FfmpegAudioPlayer must never release/reclaim the process-global USB
        // session itself; doing so races the controller's full-reopen path.
        if (needsReinit || !UsbAudioEngine.isInitialized()) {
            AppLogger.w(TAG, "resume(): policy changed or engine not initialized; requesting serialized prepare")
            val prepared = usbPrepareForPlayback?.invoke(
                wavSampleRate, wavBitsPerSample, wavChannels, currentPath
            ) ?: false
            if (!prepared) {
                AppLogger.e(TAG, "resume(): serialized prepareForPlayback failed")
                isPlaying.set(false)
                setState(State.ERROR)
                listener?.onError("USB 设备恢复失败，请重新插拔设备")
                onUsbStreamHealthFailure?.invoke(
                    UsbSilentKind.TransportError,
                    "resume_prepare_failed"
                )
                return false
            }
        }

        // 3. Start only the prepared session. A failed start poisons this handle;
        // destructive recovery belongs exclusively to PlayerController.
        val started = startUsbEngineWithSafety("resume_start")
        if (!started) {
            AppLogger.e(TAG, "resume(): USB start failed; delegating full recovery")
            isPlaying.set(false)
            setState(State.ERROR)
            listener?.onError("USB 音频流启动失败")
            onUsbStreamHealthFailure?.invoke(
                UsbSilentKind.TransportError,
                "resume_native_start_failed"
            )
            return false
        }
        armUsbPostStartVolumeRestore("resume_start")

        // 4. USB 引擎就绪，解除暂停状态（解码器线程 + pump loop 恢复运行）
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        try { audioTrack?.play() } catch (_: Exception) {}
        setState(State.PLAYING)
        AppLogger.i(TAG, "resume(): USB streaming resumed successfully")
        return true
    }

    /**
     * 播放中遇到 USB 错误时尝试 recovery。
     * 策略：先软恢复（stopAndFlush + start，不清 ring buffer），失败才走完整 teardown。
     * USB 独占设备尽量不释放，避免 4 秒 buffer 重填。
     */
    private fun attemptUsbRecovery(
        sampleRate: Int, bits: Int, channels: Int,
        sourcePath: String, generation: Int,
        forceProfileReinit: Boolean = false,
        profileReprepareOnly: Boolean = false
    ): Boolean {
        if (!isStillCurrentPlayback(sourcePath, generation)) return false

        val feedbackUnsafe = UsbAudioEngine.feedbackLooksUnsafeForPacer()
        val forceHardProfileReinit = forceProfileReinit || feedbackUnsafe
        if (feedbackUnsafe) {
            AppLogger.w(TAG, "attemptUsbRecovery: feedback state is unsafe (${UsbAudioEngine.getFeedbackState()}), but native should normally degrade to fixed no-feedback pacing before hard recovery")
            // Skip soft restart: it would reuse the same handle/profile and keep the broken feedback path alive.
        }

        if (profileReprepareOnly) {
            // StreamConfig retry: a policy change such as
            // RetryWithoutFeedback must actually re-run prepareForPlayback so
            // PlayerController can apply the pending profile flags.  Do not send
            // it through the destructive hard-recovery fuse; that fuse exists to
            // protect MIUI from repeated release/reclaim loops, and it was
            // preventing the modeled noFeedback=true retry from being applied.
            AppLogger.w(
                TAG,
                "attemptUsbRecovery: profile reprepare only; keep USB device model, " +
                    "apply pending recovery profile without hard-recovery fuse"
            )
            if (!isStillCurrentPlayback(sourcePath, generation) || !isPlaying.get()) return false
            val ok = usbPrepareForPlayback?.invoke(sampleRate, bits, channels, sourcePath) ?: false
            if (!ok) {
                AppLogger.e(TAG, "attemptUsbRecovery: profile reprepare prepareForPlayback failed")
                return false
            }
            if (!isStillCurrentPlayback(sourcePath, generation) || !isPlaying.get()) return false
            val started = startUsbEngineWithSafety("recovery_profile_reprepare")
            if (!started) {
                AppLogger.e(TAG, "attemptUsbRecovery: profile reprepare nativeStart failed")
                return false
            }
            armUsbPostStartVolumeRestore("recovery_profile_reprepare")
            AppLogger.i(TAG, "attemptUsbRecovery: profile reprepare succeeded")
            return true
        }

        // ── 第 1 步：软恢复──────
        // 不要 stopAndFlush；它会清空 ring buffer，导致 nativeStart 先输出静音。
        AppLogger.w(TAG, "attemptUsbRecovery: trying soft restart without flushing ring buffer forceProfileReinit=$forceProfileReinit")
        if (!isStillCurrentPlayback(sourcePath, generation) || !isPlaying.get()) return false

        // 检查引擎是否仍然有效（handle != 0, initialized）
        if (!forceHardProfileReinit && UsbAudioEngine.isInitialized() && UsbAudioEngine.currentHandle != 0L) {
            val softStarted = startUsbEngineWithSafety("recovery_soft")
            if (softStarted) {
                armUsbPostStartVolumeRestore("recovery_soft")
                AppLogger.i(TAG, "attemptUsbRecovery: soft recovery succeeded (buffer preserved)")
                return true
            }
            AppLogger.w(TAG, "attemptUsbRecovery: soft start failed, falling through to hard recovery")
        } else {
            AppLogger.w(TAG, "attemptUsbRecovery: engine not initialized, falling through to hard recovery")
        }

        // ── 第 2 步：交给 Controller 作为唯一 full-reopen owner ──
        // The feeder must never release/reopen the process-global USB engine.
        // A controller-owned reopen returns true from the callback; either way
        // this feeder exits its local recovery attempt without touching lifecycle.
        val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
            UsbSilentKind.TransportError,
            "feeder_soft_recovery_failed_forceProfileReinit=$forceProfileReinit feedbackUnsafe=$feedbackUnsafe"
        ) == true
        AppLogger.w(
            TAG,
            "attemptUsbRecovery: destructive recovery delegated to controller owner=$controllerOwnsRecovery"
        )
        return false
    }

    fun stop() {
        AppLogger.w(TAG, "=== stop() called")
        playbackStopStateCoordinator.reset("stop")
        playbackResourceStopCoordinator.stop("stop")
    }

    fun seekTo(positionMs: Long, usbPrepareAlreadyDone: Boolean = false, keepPaused: Boolean = false) {
        AppLogger.w(TAG, "=== seekTo($positionMs), decoderHandle=$decoderHandle, decoderDone=$decoderDone, usbPrepareAlreadyDone=$usbPrepareAlreadyDone, keepPaused=$keepPaused")
        RealtimePlaybackPcmProcessorRegistry.reset("seek_to")

        seekPositionMs = -1L
        pausedSeekCommitGate.clear()
        seekOutputBarrier.clear()

        if (_state == State.PLAYING || _state == State.PAUSED) {
            if (decoderHandle != 0L) {
                val handle = decoderHandle
                val rb = ringBuffer
                if (handle != 0L && rb != null) {
                    if (decoderDone) {
                        // 解码器已 EOF 但 handle 仍存活：直接 seek + 清 rb + 重启解码线程
                        AppLogger.w(TAG, "=== seekTo after EOF: seeking decoder handle=$handle to $positionMs")
                        // 关键：先标记 handle 所有权已转移，防止旧线程 finally 关闭 handle
                        decoderHandleTransferred.set(true)
                        rb.clear()
                        if (!usbPrepareAlreadyDone) {
                            flushUsbNativeBufferForSeek("seek_after_eof")
                        }
                        flushNativePcmBufferForSeek("seek_after_eof")
                        _positionMs = positionMs
                        disableHardwarePositionTracking()
                        needsAudioTrackFlush.set(true)
                        // 重启解码线程，从 seek 位置继续解码
                        val gen = playbackSession.generation
                        val src = playbackSession.sessionSourcePath ?: sourcePath ?: return
                        val serial = ++pendingSeekSerial
                        if (!usbExclusiveMode) seekOutputBarrier.arm(serial, positionMs)
                        if (keepPaused) pausedSeekCommitGate.arm(serial, positionMs)
                        thread(start = true, isDaemon = true, name = "FfmpegDecoder-SeekResume") {
                            try {
                                val token = decoderStopToken
                                if (
                                    serial != pendingSeekSerial ||
                                    !playbackSession.isCurrent(src, gen) ||
                                    decoderHandle != handle ||
                                    ringBuffer !== rb ||
                                    token.isStopRequested
                                ) {
                                    seekOutputBarrier.cancel(serial)
                                    return@thread
                                }
                                rb.clear()
                                if (!FFmpegBridge.seekDecoder(handle, positionMs)) {
                                    seekOutputBarrier.cancel(serial)
                                    return@thread
                                }
                                if (
                                    serial != pendingSeekSerial ||
                                    !playbackSession.isCurrent(src, gen) ||
                                    decoderHandle != handle ||
                                    ringBuffer !== rb ||
                                    token.isStopRequested
                                ) return@thread
                                rb.clear()
                                pausedSeekCommitGate.markCommitted(serial)
                                seekOutputBarrier.markCommitted(serial)
                                decoderDone = false
                                decoderThread = Thread.currentThread()
                                decoderLoopCoordinator.run(handle, rb, gen, src, token)
                            } catch (t: Throwable) {
                                if (isStillCurrentPlayback(src, gen) && !isReleased.get()) {
                                    AppLogger.e(TAG, "seek resume after EOF failed", t)
                                    setState(State.ERROR)
                                }
                            }
                        }
                        return
                    }
                    // 解码线程仍在运行：Non-blocking seek，decoder 线程会处理实际 seek
                    // 先禁用硬件时间戳并设置 flush 标志，再设置 _positionMs，确保 streaming 线程
                    // 在这之后的迭代中不会用旧硬件位置覆盖 seekTarget
                    disableHardwarePositionTracking()
                    val seekSerial = ++pendingSeekSerial
                    pendingSeekMs = positionMs
                    if (!usbExclusiveMode) seekOutputBarrier.arm(seekSerial, positionMs)
                    if (keepPaused) pausedSeekCommitGate.arm(seekSerial, positionMs)
                    rb.clear()
                    _positionMs = positionMs
                    needsAudioTrackFlush.set(true)
                    if (!usbPrepareAlreadyDone) {
                        flushUsbNativeBufferForSeek("seek_pending")
                    }
                    flushNativePcmBufferForSeek("seek_pending")

                    // 二次检查：防止竞态 — 解码线程可能在我们设置 pendingSeekMs 之前
                    // 就已设置 decoderDone=true 并退出，导致 pendingSeekMs 永远不会被处理，
                    // streaming loop 随后看到 EOF → COMPLETED → 重新播放
                    if (decoderDone) {
                        pendingSeekMs = -1L
                        AppLogger.w(TAG, "=== seekTo RACE: decoderDone became true after initial check, handling as EOF seek to $positionMs")
                        decoderHandleTransferred.set(true)
                        rb.clear()
                        if (!usbPrepareAlreadyDone) {
                            flushUsbNativeBufferForSeek("seek_race_after_eof")
                        }
                        flushNativePcmBufferForSeek("seek_race_after_eof")
                        _positionMs = positionMs
                        needsAudioTrackFlush.set(true)
                        armSeekFadeIn(PlaybackTransitionRuntime.seekFadeMs, "seek_race_after_eof")
                        val gen = playbackSession.generation
                        val src = playbackSession.sessionSourcePath ?: sourcePath ?: return
                        val serial = ++pendingSeekSerial
                        if (!usbExclusiveMode) seekOutputBarrier.arm(serial, positionMs)
                        if (keepPaused) pausedSeekCommitGate.arm(serial, positionMs)
                        thread(start = true, isDaemon = true, name = "FfmpegDecoder-SeekResume") {
                            try {
                                val token = decoderStopToken
                                if (
                                    serial != pendingSeekSerial ||
                                    !playbackSession.isCurrent(src, gen) ||
                                    decoderHandle != handle ||
                                    ringBuffer !== rb ||
                                    token.isStopRequested
                                ) {
                                    seekOutputBarrier.cancel(serial)
                                    return@thread
                                }
                                rb.clear()
                                if (!FFmpegBridge.seekDecoder(handle, positionMs)) {
                                    seekOutputBarrier.cancel(serial)
                                    return@thread
                                }
                                if (
                                    serial != pendingSeekSerial ||
                                    !playbackSession.isCurrent(src, gen) ||
                                    decoderHandle != handle ||
                                    ringBuffer !== rb ||
                                    token.isStopRequested
                                ) return@thread
                                rb.clear()
                                pausedSeekCommitGate.markCommitted(serial)
                                seekOutputBarrier.markCommitted(serial)
                                decoderDone = false
                                decoderThread = Thread.currentThread()
                                decoderLoopCoordinator.run(handle, rb, gen, src, token)
                            } catch (t: Throwable) {
                                if (isStillCurrentPlayback(src, gen) && !isReleased.get()) {
                                    AppLogger.e(TAG, "seek resume after EOF race failed", t)
                                    setState(State.ERROR)
                                }
                            }
                        }
                    }
                    armSeekFadeIn(PlaybackTransitionRuntime.seekFadeMs, "seek_after_eof")
                    return
                }
            }

            if (tempWavFile == null || wavDataSize == 0L) {
                seekPositionMs = positionMs
                return
            }

            val frameSize = wavChannels * playbackBytesPerSample()
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val targetByteOffset = (positionMs.toDouble() * bytesPerMs).toLong().coerceIn(0, (wavDataSize - 1).coerceAtLeast(0))
            val alignedOffset = (targetByteOffset / frameSize) * frameSize

            isPlaying.set(false)
            isPaused.set(false)

            audioTrackLifecycle.detachAndRelease(
                reason = "seek_restart",
                stop = true,
                flush = true
            )

            setState(State.STOPPED)

            val seekPath = if (usbExclusiveMode) resampledPath ?: currentPath else currentPath
            val originalSourcePath = this.sourcePath ?: currentPath ?: return
            playbackWorker.submit("seek_restart") {
                try {
                    if (isReleased.get()) return@submit
                    val gen = playbackSession.generation
                    startPlaybackFromOffset(alignedOffset, seekPath ?: return@submit, gen, isSeek = true, sourcePath = originalSourcePath)
                } catch (e: Exception) {
                    if (!isReleased.get()) {
                        AppLogger.e(TAG, "seek playback failed", e)
                        setState(State.ERROR)
                    }
                }
            }
        } else {
            seekPositionMs = positionMs
        }
        armSeekFadeIn(PlaybackTransitionRuntime.seekFadeMs, "seek_pending")
    }

    fun setVolume(vol: Float) = outputVolumeCoordinator.setVolume(vol)

    // ==================== Gapless / Crossfade API ====================

    /** 清除下一首歌设置（切歌完成后调用） */
    fun clearNextSong() = gaplessRequestCoordinator.clearNextSong()

    private fun closeNextDecoder() = gaplessRequestCoordinator.closeNextDecoder()

    private fun clearManualCrossfadeRequest(reason: String) =
        gaplessRequestCoordinator.clearManualCrossfadeRequest(reason)

    private fun isManualCrossfadeTrigger(path: String, generation: Int): Boolean =
        gaplessRequestCoordinator.isManualCrossfadeTrigger(path, generation)

    private fun shouldAbortStreamingForObsoleteRequest(sourcePath: String, generation: Int, reason: String): Boolean =
        gaplessRequestCoordinator.shouldAbortStreamingForObsoleteRequest(sourcePath, generation, reason)

    /**
     * 预打开下一首歌的解码器，用于 gapless/crossfade
     * 返回 true 表示预打开成功
     */
    private fun prepareNextDecoder(path: String, generation: Int = playbackSession.generation): Boolean {
        if (generation != playbackSession.generation) {
            AppLogger.w(
                TAG,
                "Gapless: skip prepare for obsolete generation path=$path " +
                    "reqGen=$generation currentGen=${playbackSession.generation}"
            )
            return false
        }
        if (isStrictUsbBitPerfectPath()) {
            AppLogger.d(TAG, "Strict USB bit-perfect: pre-opening next decoder without crossfade path=$path")
        }
        return gaplessNextDecoder.prepare(path, wavSampleRate, wavBitsPerSample, wavChannels, generation)
    }

    private fun canCrossfadePreparedNext(next: GaplessNextDecoder.Prepared): Boolean {
        val sameRate = next.sampleRate == wavSampleRate
        val sameChannels = next.channels == wavChannels
        // 允许 16/24/32 在同一输出容器中安全转换；采样率/声道不一致时不能直接逐样本混音。
        val compatibleBits = next.bitsPerSample > 0 && wavBitsPerSample > 0
        val ok = sameRate && sameChannels && compatibleBits
        if (!ok) {
            AppLogger.w(
                TAG,
                "Crossfade disabled for incompatible next format: " +
                    "cur=${wavSampleRate}/${wavChannels}/${wavBitsPerSample} " +
                    "next=${next.sampleRate}/${next.channels}/${next.bitsPerSample} " +
                    "packed24=$usePacked24Output float=$useFloatOutput"
            )
        }
        return ok
    }

    private fun retireCurrentDecoderForGapless() {
        val oldHandle = decoderHandle
        val oldRingBuffer = ringBuffer
        val oldStopToken = decoderStopToken
        decoderHandle = 0L
        decoderDone = false
        val retireResult = decoderHandoff.retireOldDecoder(
            oldHandle = oldHandle,
            oldThread = decoderThread,
            oldRingBuffer = oldRingBuffer,
            joinTimeoutMs = 500L,
            stopToken = oldStopToken,
            reason = "gapless_handoff",
        )
        if (!retireResult.oldThreadAliveAfterJoin) decoderThread = null
    }

    private fun applyGaplessPreparedTrack(prepared: GaplessNextDecoder.Prepared): Boolean {
        val formatChanged = audioTrack != null && (
            prepared.sampleRate != audioTrack?.sampleRate ||
                outputBytesPerSample != (audioTrack?.audioFormat?.let {
                    if (it == AudioFormat.ENCODING_PCM_16BIT) 2 else 4
                } ?: 0)
            )
        decoderHandle = prepared.handle
        wavSampleRate = prepared.sampleRate
        wavChannels = prepared.channels
        wavBitsPerSample = prepared.bitsPerSample
        currentPath = prepared.path
        AppLogger.d(
            TAG,
            "Gapless: format check: wavSampleRate=$wavSampleRate(" +
                "wavBitsPerSample=$wavBitsPerSample, wavChannels=$wavChannels) " +
                "audioTrack.sampleRate=${audioTrack?.sampleRate} " +
                "audioFormat=${audioTrack?.audioFormat} formatChanged=$formatChanged",
        )
        return formatChanged
    }

    private fun startGaplessDecoder(newRingBuffer: RingBuffer, generation: Int, path: String) {
        val handle = decoderHandle
        val src = playbackSession.sessionSourcePath ?: path
        decoderStopToken = DecoderStopToken("gapless-$generation-${System.nanoTime()}")
        val newStopToken = decoderStopToken
        decoderThread = decoderHandoff.startDecoderThread(
            name = "FFmpegDecoder-Gapless",
            handle = handle,
            generation = generation,
            sourcePath = src,
            stopToken = newStopToken,
        ) { h, g, s, token -> decoderLoopCoordinator.run(h, newRingBuffer, g, s, token) }
    }

    private fun commitGaplessTrack(path: String, startPositionMs: Long) {
        playbackTrackCommitter.commit(
            reason = "gapless",
            path = path,
            decoderHandle = decoderHandle,
            startPositionMs = startPositionMs,
            durationProvider = { h -> decoderHandoff.decoderDurationMs(h) },
            setCurrentPath = {
                currentPath = it
                playbackSession.commitCurrentTrack(it)
            },
            setPositionMs = { _positionMs = it },
            setDurationMs = { _durationMs = it },
            resetHardwarePosition = { disableHardwarePositionTracking() },
            clearNextRequest = { playbackSession.clearNextRequest("gapless_commit") },
            listener = listener,
        )
    }

    /**
     * 切换到下一首歌（gapless 无缝切换）
     * 在当前歌曲 EOF 时调用，返回 true 表示切换成功
     */
    private fun switchToNextSong(startPositionMs: Long = 0L): Boolean {
        return gaplessTrackSwitchCoordinator.switch(startPositionMs)
    }

    fun release() {
        releaseCoordinator.release()
    }

    private fun registerAudioDeviceCallback() {
        audioOutputLifecycleCoordinator.registerAudioDeviceCallback()
    }

    private fun unregisterAudioDeviceCallback() {
        audioOutputLifecycleCoordinator.unregisterAudioDeviceCallback()
    }

    private fun rebuildAudioTrack() {
        audioOutputLifecycleCoordinator.rebuildAudioTrack()
    }

    fun rebuildAfterSourceFileMutation() {
        audioOutputLifecycleCoordinator.rebuildAfterSourceFileMutation()
    }

    /**
     * Rebuilds the regular Android output so a new Spatializer AUTO/NEVER declaration is applied.
     * USB exclusive is deliberately untouched. A paused stream is rebuilt lazily on resume so a
     * settings toggle never starts playback by itself.
     */
    fun onAndroidSpatialAudioPreferenceChanged(): Boolean {
        return audioOutputLifecycleCoordinator.onAndroidSpatialAudioPreferenceChanged()
    }

    /**
     * 后台恢复时检查并重建 AudioTrack
     * 主动检测 + 立即重建
     * @return true 如果触发了重建
     */
    fun ensureTrackValidAfterBackground(): Boolean {
        return audioOutputLifecycleCoordinator.ensureTrackValidAfterBackground()
    }

    /**
     * 写循环内热重建 AudioTrack
     *
     * 当写循环中检测到 AudioTrack 无效（null/STOPPED/UNINITIALIZED/write失败）时，
     * 不退出循环，而是就地重建 AudioTrack 并继续播放。
     * 仅替换输出 track，不重启解码器和 ring buffer，实现无缝恢复。
     *
     * @return 新的 AudioTrack 实例，失败返回 null
     */
    private fun recreateAudioTrackInline(forceSco: Boolean = false, forcedDevice: AudioDeviceInfo? = null): AudioTrack? {
        return audioTrackRebuildCoordinator.recreate(forceSco, forcedDevice)
    }

    /**
     * SCO 连接成功后重建 AudioTrack
     * 确保新的 AudioTrack 使用正确的 SCO AudioAttributes，音频路由到 SCO 通道
     */
    fun rebuildAudioTrackForSco() {
        audioOutputLifecycleCoordinator.rebuildAudioTrackForSco()
    }

    /**
     * SCO 断开后重建 AudioTrack，切回 USAGE_MEDIA 属性
     * SCO 断开后 AudioTrack 仍使用 VOICE_COMMUNICATION + SCO preferredDevice 会导致无声
     */
    fun rebuildAudioTrackForScoDisconnected() {
        audioOutputLifecycleCoordinator.rebuildAudioTrackForScoDisconnected()
    }

    private fun initDspEngine() {
        dspRuntimeCoordinator.init(wavSampleRate, wavChannels)
    }

    private fun releaseDspEngine() {
        dspRuntimeCoordinator.release()
    }

    private fun usbNeedsS32ToPacked24(runtime: UsbAudioEngine.UsbRuntimeFormat): Boolean {
        return outputFormatGuard.needsKotlinPacked24(
            runtime = runtime,
            decoderBits = wavBitsPerSample,
            decoderChannels = wavChannels,
        )
    }

    private fun processDsp(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = dspRuntimeCoordinator.process(buffer, read, channels, sampleRate, bitsPerSample)

    private fun processDspAfterRealtime(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ): Int = dspRuntimeCoordinator.processAfterRealtime(buffer, read, channels, sampleRate, bitsPerSample)

    private fun resetRealtimeSeparationAtTrackBoundary(reason: String): Boolean {
        return dspRuntimeCoordinator.resetRealtimeSeparationAtTrackBoundary(reason)
    }

    fun selectUsbTargetSampleRatePublic(srcSr: Int): Int =
        usbPlaybackTargetResolver.selectTargetSampleRate(srcSr)

    private fun createAudioTrackWithFallback(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
        audioAttributes: AudioAttributes
    ): AudioTrack? {
        return androidAudioTrackFactory.createWithFallback(
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            encoding = encoding,
            bufferSize = bufferSize,
            audioAttributes = audioAttributes,
            safeMode = safeMode
        )
    }

    private fun verifyUsbBitPerfectDecoderFormat(
        actualRate: Int,
        actualBits: Int,
        actualChannels: Int,
        targetRate: Int,
        targetBits: Int,
        targetChannels: Int
    ): Boolean {
        return outputFormatGuard.verifyBitPerfectDecoderFormat(
            actualRate = actualRate,
            actualBits = actualBits,
            actualChannels = actualChannels,
            targetRate = targetRate,
            targetBits = targetBits,
            targetChannels = targetChannels,
        )
    }

    private fun onPlaybackError(msg: String) {
        playbackStateCoordinator.onPlaybackError(msg)
    }

    private fun onPlaybackSuccess() {
        playbackStateCoordinator.onPlaybackSuccess()
    }

    private fun setState(state: State) {
        playbackStateCoordinator.setState(state)
    }

    private fun getCacheFile(
        path: String,
        atTargetRate: Int = 0,
        atTargetBits: Int = 0,
        usbTargetSr: Int = 0,
        usbTargetBits: Int = 0,
        usbTargetCh: Int = 0,
    ): File = ffmpegAudioCache.getCacheFile(
        path = path,
        usbExclusiveMode = usbExclusiveMode,
        usbBitPerfectMode = usbBitPerfectMode,
        atTargetRate = atTargetRate,
        atTargetBits = atTargetBits,
        usbTargetSr = usbTargetSr,
        usbTargetBits = usbTargetBits,
        usbTargetCh = usbTargetCh,
    )

    private fun startDecoderThread(
        sourcePath: String,
        generation: Int,
        handle: Long,
        rb: RingBuffer,
        stopToken: DecoderStopToken,
        decodeChunkSize: Int = 16384
    ): Boolean {
        return decoderThreadStarter.start(
            sourcePath = sourcePath,
            generation = generation,
            handle = handle,
            ringBuffer = rb,
            stopToken = stopToken,
            decodeChunkSize = decodeChunkSize,
        )
    }

    private fun startStreamingPlayback(sourcePath: String, generation: Int) {
        if (useNativePcmOutput && startNativeStreamingPlayback(sourcePath, generation)) {
            return
        }

        // Native streaming 失败回退时，finally 块会将 isPlaying 设为 false，
        // 需要恢复为 true 以保证 AudioTrack 路径正常运行
        if (!isPlaying.get() && isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
            isPlaying.set(true)
        }

        var rb = ringBuffer ?: return
        enableHardwarePositionTracking()  // 新歌开始时启用硬件时间戳
        needsAudioTrackFlush.set(false)  // 重置 flush 标志

        // 蓝牙 SCO 模式处理
        val useSco = AudioOutputManager.shouldUseScoMode(context)
        // SCO 尚未连接时，先用 MEDIA 属性创建 AudioTrack，避免等待 SCO 连接期间的静默期
        // SCO 连接后 rebuildAudioTrackForSco() 会自动切换到 VOICE_COMMUNICATION + SCO 路由
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val scoActive = am?.isBluetoothScoOn == true
        val spec = androidAudioTrackFactory.buildSpec(
            wavSampleRate = wavSampleRate,
            wavChannels = wavChannels,
            probedEncoding = probedEncoding,
            useSco = useSco,
            scoActive = scoActive,
            applyScoDownsample = true,
            scoDownsampleEnabled = AppPreferences.Player.bluetoothScoDownsample
        )
        val useScoAttributes = spec.useScoAttributes
        val channelConfig = spec.channelConfig
        val actualSampleRate = spec.sampleRate
        val actualEncoding = spec.encoding
        val bufSize = spec.bufferSizeInBytes

        AppLogger.i(TAG, "SCO check: useSco=$useSco, scoActive=$scoActive, useScoAttributes=$useScoAttributes, bluetoothScoMode=${AppPreferences.Player.bluetoothScoMode}, wavSampleRate=$wavSampleRate, isHfpOnly=${AudioOutputManager.isBluetoothHfpOnlyDevice(context)}, isAnyBt=${AudioOutputManager.isAnyBluetoothDeviceConnected(context)}")

        if (useScoAttributes && AppPreferences.Player.bluetoothScoDownsample) {
            AppLogger.i(TAG, "SCO downsample: 16kHz")
        }

        AppLogger.i(TAG, "Streaming playback: rate=$actualSampleRate, encoding=$actualEncoding, bits=$wavBitsPerSample, sco=$useSco, scoActive=$scoActive, channel=$channelConfig")

        var lifecycleInterrupted = false
        try {
            val attributes = spec.audioAttributes

            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            androidAudioRouteController.applyPreferredDeviceToAudioTrack(
                reason = "streaming_playback_start",
                useScoAttributes = useScoAttributes,
                allowDirectPreferredDevice = !useSco,
                trackOverride = track
            )

            audioTrack = track
            _audioSessionId = track.audioSessionId

            // 记录格式快照
            snapshotTrackFormat(actualSampleRate, channelConfig, actualEncoding)

            setVolume(volume)

            val buffer = ByteArray(PCM_BUFFER_SIZE)
            val frameSize = wavChannels * playbackBytesPerSample()
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val readLimit = PcmFrameAligner.readLimit(buffer.size, frameSize)

            // 预填充 AudioTrack 内部缓冲区，然后启动播放。
            // 关键：必须在写入第一个 chunk 后立即调用 track.play()，
            // 否则 AudioTrack 内部缓冲区写满后 write() 返回 0，导致 prefill 阻塞数秒。
            val prefillTargetBytes = PcmFrameAligner.alignUp((bytesPerMs * 200).toInt(), frameSize)  // 200ms
            var prefillBytesWritten = 0
            var trackStarted = false
            val prefillStart = System.currentTimeMillis()
            AppLogger.i(TAG, "Prefill: target=${prefillTargetBytes}B (${prefillTargetBytes.toLong() * 1000L / (actualSampleRate.toLong() * frameSize)}ms) frameSize=$frameSize readLimit=$readLimit")
            while (prefillBytesWritten < prefillTargetBytes && isPlaying.get() && !isReleased.get()) {
                val remaining = prefillTargetBytes - prefillBytesWritten
                val maxRead = PcmFrameAligner.readLimit(minOf(readLimit, remaining), frameSize)
                if (maxRead <= 0) break
                val read = rb.readWithTimeout(buffer, 0, maxRead, 500)
                if (read == -1) {
                    if (decoderDone && rb.available() == 0) break
                    continue
                }
                if (read <= 0) break
                val alignedRead = PcmFrameAligner.alignDown(read, frameSize)
                if (alignedRead <= 0) continue
                // Prefill is audible PCM too. Run it through the same DSP path as the main
                // streaming loop so online/local playback does not begin with a raw 200 ms burst.
                val processedRead = processDsp(
                    buffer,
                    alignedRead,
                    wavChannels,
                    wavSampleRate,
                    wavBitsPerSample,
                )
                val processedAlignedRead = PcmFrameAligner.alignDown(processedRead, frameSize)
                if (processedAlignedRead <= 0) continue
                val writeMode = if (trackStarted) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING
                val written = audioTrackPcmWriter.write(track, buffer, 0, processedAlignedRead, writeMode)
                if (written > 0) {
                    prefillBytesWritten += PcmFrameAligner.alignDown(written, frameSize)
                    // 第一次成功写入后立即启动播放，让 AudioTrack 开始消费缓冲区。
                    if (!trackStarted) {
                        if (!isPlaying.get() || isReleased.get() || !isStillCurrentPlayback(sourcePath, generation)) break
                        track.play()
                        trackStarted = true
                        AppLogger.i(TAG, "AudioTrack.play() invoked (after first write): playState=${audioTrack?.playState}, bufferSizeInFrames=${audioTrack?.bufferSizeInFrames}, audioSessionId=${audioTrack?.audioSessionId}")
                    }
                } else if (written == 0) {
                    if (!trackStarted && System.currentTimeMillis() - prefillStart > 200L) {
                        if (!isPlaying.get() || isReleased.get() || !isStillCurrentPlayback(sourcePath, generation)) break
                        track.play()
                        trackStarted = true
                        AppLogger.w(TAG, "AudioTrack.play() invoked to unblock prefill after non-blocking write=0")
                    } else {
                        Thread.sleep(2)
                    }
                } else {
                    AppLogger.w(TAG, "Prefill write failed=$written, starting loop recovery")
                    break
                }
            }
            if (!isPlaying.get() || isReleased.get() || !isStillCurrentPlayback(sourcePath, generation)) {
                AppLogger.w(TAG, "Prefill aborted before play: isPlaying=${isPlaying.get()} released=${isReleased.get()} current=${isStillCurrentPlayback(sourcePath, generation)}")
                return
            }
            // 如果循环中没有成功写入任何数据（例如 rb 立即 EOF），仍然启动播放。
            if (!trackStarted) {
                track.play()
                trackStarted = true
                AppLogger.i(TAG, "AudioTrack.play() invoked (no prefill data): audioSessionId=${audioTrack?.audioSessionId}")
            }
            val prefillElapsed = System.currentTimeMillis() - prefillStart
            AppLogger.i(TAG, "Prefill done: ${prefillBytesWritten}B in ${prefillElapsed}ms")

            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            AppLogger.i(TAG, "Streaming AudioTrack created, sessionId=$_audioSessionId")

            // 内联恢复计数器：防止无限重建循环
            var inlineRecoveryCount = 0
            val maxInlineRecoveryAttempts = 3

            // 跟踪写入 AudioTrack 的总字节数（含 prefill），用于 EOF 后 drain 和低频状态日志。
            val streamMetrics = AudioTrackStreamMetrics(TAG).apply {
                seedWrittenBytes(prefillBytesWritten.toLong())
            }

            AppLogger.i(TAG, "Streaming loop init: crossfadeDurationMs=$crossfadeDurationMs, nextSongPath=$nextSongPath, _durationMs=$_durationMs, prefillBytes=$prefillBytesWritten")

            while (isPlaying.get() && !isReleased.get()) {
                if (shouldAbortStreamingForObsoleteRequest(sourcePath, generation, "loop_start")) {
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                // Crossfade 启动检测：自动模式在当前歌曲剩余时间 <= crossfade 时长时开始；
                // 手动切歌模式由 PlayerController 预打开下一首后立即拉起。
                if (!crossfadeTransition.active && crossfadeDurationMs > 0 && nextSongPath != null && _durationMs > 0) {
                    val path = nextSongPath!!
                    val manualTrigger = isManualCrossfadeTrigger(path, generation)
                    val remainingMs = (_durationMs - _positionMs).coerceAtLeast(1L)
                    if (remainingMs % 5000 < 50 || manualTrigger) {
                        AppLogger.d(TAG, "Crossfade check: pos=$_positionMs dur=$_durationMs rem=$remainingMs xfadeDur=$crossfadeDurationMs next=$nextSongPath manual=$manualTrigger")
                    }
                    if (manualTrigger || remainingMs in 1L..crossfadeDurationMs.toLong()) {
                        val preparedOk = gaplessNextDecoder.pathFor(generation) == path || prepareNextDecoder(path, generation)
                        if (shouldAbortStreamingForObsoleteRequest(sourcePath, generation, "after_prepare_crossfade")) {
                            break
                        }
                        if (preparedOk) {
                            val prepared = gaplessNextDecoder.snapshotFor(generation)
                            if (prepared != null && prepared.path == path && canCrossfadePreparedNext(prepared)) {
                                if (crossfadeTransition.start(
                                        targetPath = path,
                                        durationMs = crossfadeDurationMs,
                                        sampleRate = wavSampleRate,
                                        bufferSize = buffer.size,
                                        remainingMs = if (manualTrigger) crossfadeDurationMs.toLong() else remainingMs
                                    )
                                ) {
                                    if (manualTrigger) clearManualCrossfadeRequest("manual_crossfade_started")
                                }
                            } else {
                                if (manualTrigger) clearManualCrossfadeRequest("manual_crossfade_incompatible")
                                crossfadeTransition.reset("incompatible_next_format")
                            }
                        } else if (manualTrigger) {
                            clearManualCrossfadeRequest("manual_crossfade_prepare_failed")
                        }
                    }
                }

                // Gapless pre-open: prepare the next decoder before the current stream reaches EOF.
                // Opening at EOF is audible on slower devices because it blocks the handoff path.
                if (!crossfadeTransition.active && crossfadeDurationMs <= 0 && nextSongPath != null && _durationMs > 0 && !gaplessNextDecoder.isPreparedFor(generation)) {
                    val remainingMs = _durationMs - _positionMs
                    if (remainingMs in 1L..GAPLESS_PREOPEN_WINDOW_MS) {
                        prepareNextDecoder(nextSongPath!!, generation)
                    }
                    if (shouldAbortStreamingForObsoleteRequest(sourcePath, generation, "after_gapless_preopen")) {
                        break
                    }
                }

                val preparedCrossfade = if (crossfadeTransition.active) {
                    val target = crossfadeTransition.targetPath
                    gaplessNextDecoder.snapshotFor(generation)?.takeIf { target == null || it.path == target }
                } else null
                if (crossfadeTransition.active && preparedCrossfade == null) {
                    AppLogger.w(TAG, "Crossfade: active without prepared next decoder; resetting transition")
                    crossfadeTransition.reset("missing_prepared_next")
                }
                val nextCrossfade = if (crossfadeTransition.active) preparedCrossfade else null
                val seekReadToken = seekOutputBarrier.beginRead()
                var read: Int
                if (crossfadeTransition.active && nextCrossfade != null) {
                    // Crossfade 模式：从当前和下一个解码器分别读取，混合
                    val xfReadStart = System.nanoTime()
                    val currentRead = rb.readWithTimeout(buffer, 0, readLimit, RING_BUFFER_READ_TIMEOUT_MS)
                    val xfReadMs = (System.nanoTime() - xfReadStart) / 1_000_000.0
                    if (xfReadMs > 100) {
                        AppLogger.w(TAG, "Crossfade: rb.readWithTimeout took ${"%.1f".format(xfReadMs)}ms, rbAvail=${rb.available()}B decoderDone=$decoderDone")
                    }
                    if (currentRead == -1) {
                        // 超时，检查是否仍在播放
                        if (!isPlaying.get()) break
                        // 解码器已完成 → 当前歌曲解码完毕
                        // 关键修复：不等 ring buffer 完全消费，立刻强制切换
                        // 否则需要等旧 ring buffer 中剩余的 ~760KB 数据消费完（3-4 秒）才能 switch
                        if (decoderDone) {
                            val xfadeElapsedMs = crossfadeTransition.elapsedMs(bytesPerMs)
                            AppLogger.d(TAG, "Crossfade: current song decoder EOF, force switch (xfadeElapsed=${xfadeElapsedMs}ms, rbAvail=${rb.available()}B) — 关闭旧 rb 跳过 760KB 残数据")
                            crossfadeTransition.reset("current_decoder_eof")
                            // 关闭旧 ring buffer（丢弃剩余 ~760KB 旧数据），让 readWithTimeout 下次返回 0
                            // 旧歌已经淡出到几乎无声（crossfade 进度 ~99%），丢弃 760KB 听感无感
                            // 但能省下 3-4 秒卡顿
                            try { rb.close() } catch (_: Exception) {}
                            if (switchToNextSong()) {
                                _positionMs = xfadeElapsedMs
                                disableHardwarePositionTracking()
                                rb = ringBuffer!!
                                continue
                            }
                            isPlaying.set(false)
                            if (isStillCurrentPlayback(sourcePath, generation)) {
                                onPlaybackSuccess(); setState(State.COMPLETED)
                            }
                            break
                        }
                        continue
                    }
                    if (currentRead <= 0) {
                        // 当前歌曲在 crossfade 期间结束，提前切换
                        val xfadeElapsedMs = crossfadeTransition.elapsedMs(bytesPerMs)
                        AppLogger.d(TAG, "Crossfade: current song ended during crossfade, xfadeElapsed=${xfadeElapsedMs}ms")
                        crossfadeTransition.reset("current_read_eof")
                        if (switchToNextSong()) {
                            _positionMs = xfadeElapsedMs
                            disableHardwarePositionTracking()
                            rb = ringBuffer!!
                            continue
                        }
                        isPlaying.set(false)
                        if (isStillCurrentPlayback(sourcePath, generation)) {
                            onPlaybackSuccess(); setState(State.COMPLETED)
                        }
                        break
                    }
                    val mixResult = crossfadeTransition.mixNextIntoCurrent(
                        currentBuf = buffer,
                        currentRead = currentRead,
                        next = nextCrossfade,
                        frameSize = frameSize,
                        outputIsFloat = useFloatOutput,
                        outputIsPacked24 = usePacked24Output,
                        bitsPerSample = wavBitsPerSample
                    )
                    if (mixResult.completed) {
                            val xfadeStart = System.nanoTime()
                            fun xlap(name: String) {
                                val ms = (System.nanoTime() - xfadeStart) / 1_000_000.0
                                AppLogger.d(TAG, "Crossfade complete lap[$name] = ${"%.1f".format(ms)}ms")
                            }
                            if (shouldAbortStreamingForObsoleteRequest(sourcePath, generation, "crossfade_complete")) {
                                break
                            }
                            val xfadeCommitPositionMs = crossfadeTransition.elapsedMs(bytesPerMs)
                            val expectedCrossfadePath = crossfadeTransition.targetPath ?: nextSongPath
                            val prep = expectedCrossfadePath?.let { gaplessNextDecoder.consumeIfPathMatches(it, generation) }
                            if (prep == null || prep.handle == 0L || prep.sampleRate <= 0 || prep.channels <= 0 || prep.bitsPerSample <= 0) {
                                AppLogger.w(
                                    TAG,
                                    "Crossfade: COMPLETE aborted because prepared next decoder is invalid " +
                                        "expected=$expectedCrossfadePath prep=$prep gen=$generation session=${playbackSession.describe()}"
                                )
                                crossfadeTransition.reset("complete_without_valid_next")
                                clearManualCrossfadeRequest("complete_without_valid_next")
                                continue
                            }
                            AppLogger.d(TAG, "Crossfade: COMPLETE start: prevFormat(sr=$wavSampleRate,ch=$wavChannels,bits=$wavBitsPerSample) nextFormat(sr=${prep.sampleRate},ch=${prep.channels},bits=${prep.bitsPerSample}) decoderHandle=$decoderHandle nextHandle=${prep.handle} audioTrack=${audioTrack != null} (sampleRate=${audioTrack?.sampleRate})")
                            crossfadeTransition.reset("complete")
                            xlap("start")
                            // 关闭旧解码器，切换到新解码器
                            val oldHandle = decoderHandle
                            decoderHandle = prep.handle
                            // The old decoder often reaches EOF at the same time the crossfade
                            // completes. Carrying that flag into the replacement ring buffer makes
                            // the streaming loop treat a short warm-up timeout as another EOF. That
                            // race is also why a seek immediately after handoff could sound broken.
                            decoderDone = false
                            decoderHandleTransferred.set(false)
                            wavSampleRate = prep.sampleRate
                            wavChannels = prep.channels
                            wavBitsPerSample = prep.bitsPerSample
                            currentPath = prep.path
                            xlap("after-switch-handles")
                            val oldRingBuffer = ringBuffer
                            val oldStopToken = decoderStopToken
                            val retireResult = decoderHandoff.retireOldDecoder(
                                oldHandle = oldHandle,
                                oldThread = decoderThread,
                                oldRingBuffer = oldRingBuffer,
                                joinTimeoutMs = 200L,
                                stopToken = oldStopToken,
                                reason = "crossfade_handoff"
                            )
                            if (!retireResult.oldThreadAliveAfterJoin) decoderThread = null

                            // 重建 RingBuffer 并启动新解码器线程
                            val newBufSize = decoderHandoff.ringBufferCapacity(
                                sampleRate = wavSampleRate,
                                channels = wavChannels,
                                bytesPerSample = outputBytesPerSample,
                                minCapacity = PCM_BUFFER_SIZE * 8
                            )
                            ringBuffer = RingBuffer(newBufSize)
                            rb = ringBuffer!!
                            xlap("after-new-ringbuffer")
                            val h = decoderHandle; val g = playbackSession.generation; val s = playbackSession.sessionSourcePath ?: sourcePath
                            decoderStopToken = DecoderStopToken("crossfade-$g-${System.nanoTime()}")
                            val newStopToken = decoderStopToken
                            decoderThread = decoderHandoff.startDecoderThread(
                                name = "FFmpegDecoder-XFade",
                                handle = h,
                                generation = g,
                                sourcePath = s,
                                stopToken = newStopToken
                            ) { handle, gen, src, token -> decoderLoopCoordinator.run(handle, rb, gen, src, token) }
                            xlap("after-start-decoder-thread")
                            val commit = playbackTrackCommitter.commit(
                                reason = "crossfade",
                                path = prep.path,
                                decoderHandle = decoderHandle,
                                startPositionMs = xfadeCommitPositionMs,
                                durationProvider = { h -> decoderHandoff.decoderDurationMs(h) },
                                setCurrentPath = {
                                    currentPath = it
                                    playbackSession.commitCurrentTrack(it)
                                },
                                setPositionMs = { _positionMs = it },
                                setDurationMs = { _durationMs = it },
                                resetHardwarePosition = { disableHardwarePositionTracking() },
                                clearNextRequest = { playbackSession.clearNextRequest("crossfade_commit") },
                                listener = listener
                            )
                            val realtimeBoundaryReset =
                                resetRealtimeSeparationAtTrackBoundary("crossfade_commit")
                            AppLogger.d(TAG, "Crossfade: COMPLETE committed position=${commit.positionMs} duration=${commit.durationMs}")
                            AppLogger.d(TAG, "Crossfade: COMPLETE done, total = ${"%.1f".format((System.nanoTime() - xfadeStart) / 1_000_000.0)}ms")
                            if (realtimeBoundaryReset) {
                                // This buffer still contains the old track's crossfade tail.
                                // Start the rebuilt model pipeline from the next track-only read.
                                continue
                            }
                        }
                    read = currentRead
                } else {
                    // 正常模式：从 RingBuffer 读取（带超时防止永久阻塞）
                    val readStart = System.nanoTime()
                    read = rb.readWithTimeout(buffer, 0, readLimit, RING_BUFFER_READ_TIMEOUT_MS)
                    val readMs = (System.nanoTime() - readStart) / 1_000_000.0
                    if (readMs > 100) {
                        AppLogger.w(TAG, "Streaming: rb.readWithTimeout took ${"%.1f".format(readMs)}ms, rbAvail=${rb.available()}B decoderDone=$decoderDone")
                    }
                    if (read == -1) {
                        // 超时，检查是否仍在播放
                        if (!isPlaying.get()) break
                        // 解码器已完成且 buffer 为空 → 真正的 EOF
                        if (decoderDone && rb.available() == 0) {
                            AppLogger.i(TAG, "Streaming: decoderDone + buffer empty → EOF")
                            read = 0  // 落入下方 read <= 0 EOF 处理
                        } else {
                            continue
                        }
                    }
                }

                if (discardReadIfSeekCrossed(seekReadToken, "audiotrack")) {
                    continue
                }

                var drainedRealtimeOutput = false
                if (read <= 0 && RealtimePlaybackPcmProcessorRegistry.isActive()) {
                    val drained = RealtimePlaybackPcmProcessorRegistry.drain(buffer, readLimit)
                    when {
                        drained > 0 -> {
                            read = drained
                            drainedRealtimeOutput = true
                        }
                        drained == 0 -> continue
                    }
                }

                if (read <= 0) {
                    // Gapless: EOF 时尝试无缝切换到下一首歌
                    if (switchToNextSong()) {
                        rb = ringBuffer!!
                        AppLogger.d(TAG, "Gapless: switched to next song, continuing write loop")
                        continue
                    }
                    AppLogger.w(TAG, "Streaming: EOF from ring buffer, draining AudioTrack...")

                    // Drain: 等待 AudioTrack 播放完内部缓冲区中的剩余数据
                    // 防止短音频文件在解码器快速完成时被截断
                    AudioTrackDrainHelper.drain(
                        track = audioTrack,
                        totalBytesWritten = streamMetrics.totalBytesWrittenToTrack,
                        frameSize = frameSize,
                        label = "Streaming",
                        isPlaying = { isPlaying.get() },
                        isReleased = { isReleased.get() }
                    )

                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val processedRead = if (drainedRealtimeOutput) {
                    processDspAfterRealtime(
                        buffer, read, wavChannels, wavSampleRate, wavBitsPerSample
                    )
                } else {
                    processDsp(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)
                }
                if (processedRead <= 0) continue
                dispatchWaveformFrame(
                    buffer, processedRead, wavChannels, wavSampleRate, wavBitsPerSample
                )

                consumePendingAndroidAudioTrackRouteRebuild()

                if (shouldAbortStreamingForObsoleteRequest(sourcePath, generation, "before_audio_output_write")) {
                    break
                }

                var track2 = audioTrack
                if (track2 == null) {
                    // 内联热重建：track 被系统回收时就地重建，不退出循环
                    if (inlineRecoveryCount < maxInlineRecoveryAttempts) {
                        inlineRecoveryCount++
                        AppLogger.w(TAG, "Streaming: audioTrack is null, inline recovery #$inlineRecoveryCount")
                        val newTrack = recreateAudioTrackInline()
                        if (newTrack != null) {
                            track2 = newTrack
                            // 不 continue，让新 track 接下来走 write 路径
                        } else {
                            AppLogger.e(TAG, "Streaming: inline recovery failed, breaking")
                            break
                        }
                    } else {
                        AppLogger.e(TAG, "Streaming: audioTrack null after $maxInlineRecoveryAttempts recovery attempts, breaking")
                        break
                    }
                }
                if (track2.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    AppLogger.w(TAG, "Streaming: Track STOPPED externally, attempting restart")
                    try {
                        track2.play()
                        disableHardwarePositionTracking()
                    } catch (e: Exception) {
                        // play() 失败说明 track 已死，尝试内联热重建
                        if (inlineRecoveryCount < maxInlineRecoveryAttempts) {
                            inlineRecoveryCount++
                            AppLogger.w(TAG, "Streaming: Track restart failed: ${e.message}, inline recovery #$inlineRecoveryCount")
                            val newTrack = recreateAudioTrackInline()
                            if (newTrack != null) {
                                track2 = newTrack
                            } else {
                                AppLogger.e(TAG, "Streaming: inline recovery after STOPPED failed, breaking")
                                break
                            }
                        } else {
                            AppLogger.e(TAG, "Streaming: Track restart failed after $maxInlineRecoveryAttempts recovery attempts, breaking")
                            break
                        }
                    }
                }
                if (track2.playState == AudioTrack.PLAYSTATE_PAUSED && !isPaused.get()) {
                    AppLogger.w(TAG, "Streaming: Track PAUSED externally (not by user), resuming")
                    try { track2.play() } catch (_: Exception) {}
                }

                // seek 后 flush AudioTrack，清除旧音频数据，让硬件位置从正确起点开始
                if (needsAudioTrackFlush.compareAndSet(true, false)) {
                    try { track2.flush() } catch (_: Exception) {}
                    // flush 后硬件位置从 0 开始，暂时不信任硬件位置，用增量方式
                    disableHardwarePositionTracking()
                    AppLogger.w(TAG, ">>> FLUSH AudioTrack done, _positionMs=$_positionMs, switched to incremental mode")
                }

                val alignedWriteLen = PcmFrameAligner.alignDown(processedRead, frameSize)
                applyPlaybackFade(buffer, 0, alignedWriteLen, actualSampleRate, frameSize, wavBitsPerSample)
                var writeResult = audioTrackPcmWriter.write(track2, buffer, 0, alignedWriteLen)
                if (writeResult < 0) {
                    // write 失败：track 可能已被系统回收，尝试内联热重建后重试
                    if (inlineRecoveryCount < maxInlineRecoveryAttempts) {
                        inlineRecoveryCount++
                        AppLogger.w(TAG, "Streaming: write failed=$writeResult, inline recovery #$inlineRecoveryCount")
                        val newTrack = recreateAudioTrackInline()
                        if (newTrack != null) {
                            // 重试写入同一个 buffer（数据已从 ring buffer 读出，丢失会中断播放）
                            val retryResult = audioTrackPcmWriter.write(newTrack, buffer, 0, alignedWriteLen)
                            if (retryResult >= 0) {
                                AppLogger.w(TAG, "Streaming: write retry succeeded after inline recovery")
                                track2 = newTrack
                                writeResult = retryResult
                            } else {
                                AppLogger.e(TAG, "Streaming: write retry also failed=$retryResult, breaking")
                                break
                            }
                        } else {
                            AppLogger.e(TAG, "Streaming: inline recovery after write failure failed, breaking")
                            break
                        }
                    } else {
                        AppLogger.e(TAG, "Streaming: write failed=$writeResult after $maxInlineRecoveryAttempts recovery attempts, breaking")
                        break
                    }
                }

                // 成功写入后重置恢复计数器，允许未来再次恢复
                if (inlineRecoveryCount > 0) {
                    AppLogger.i(TAG, "Streaming: write succeeded after recovery, resetting counter (was $inlineRecoveryCount)")
                    inlineRecoveryCount = 0
                }

                streamMetrics.recordWriteResult(writeResult)
                streamMetrics.maybeLog(track2, rb.available(), _positionMs)

                val posUpdate = audioTrackPositionUpdater.updateStreaming(
                    currentPositionMs = _positionMs,
                    bytesAdvanced = writeResult.coerceAtLeast(0),
                    bytesPerMs = bytesPerMs,
                    sampleRate = actualSampleRate,
                    durationMs = _durationMs
                )
                _positionMs = posUpdate.positionMs
                if (kotlin.math.abs(posUpdate.positionMs - posUpdate.previousPositionMs) > 2000) {
                    AppLogger.w(TAG, ">>> STREAMING pos JUMP: ${posUpdate.previousPositionMs} -> ${posUpdate.positionMs} (hwPos=${posUpdate.hardwarePositionMs}, useHw=${posUpdate.usedHardware}, write=$writeResult)")
                }
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }

        } catch (_: InterruptedException) {
            lifecycleInterrupted = true
            AppLogger.i(
                TAG,
                "Streaming playback interrupted for lifecycle replacement: source=$sourcePath gen=$generation"
            )
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "Streaming playback EXCEPTION", e)
            }
        } finally {
            if (!lifecycleInterrupted && isPlaying.get() && isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                isPlaying.set(false)
                if (_state == State.PLAYING) {
                    setState(State.PAUSED)
                }
            }
            AppLogger.w(TAG, "Streaming playback END, isPlaying=${isPlaying.get()}, state=$_state")
        }
    }

    private fun startNativeStreamingPlayback(sourcePath: String, generation: Int): Boolean {
        var rb = ringBuffer ?: return false
        val mode = AudioOutputManager.getCurrentOutputMode(context)
        if (mode == AudioOutputMode.AUDIO_TRACK) return false
        if (!NativeAudioEngine.isSupported(mode)) return false

        disableHardwarePositionTracking()
        needsAudioTrackFlush.set(false)

        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val actualChannels = wavChannels.coerceIn(1, 2)
        if (usePacked24Output) {
            // NativeAudioEngine currently supports I16/FLOAT/I32.  Packed 24-bit is
            // handled by AudioTrack with S32LE -> S24LE conversion in the decoder
            // pump, matching Android's ENCODING_PCM_24BIT_PACKED contract.
            return false
        }
        val requestedEncoding = if (mode == AudioOutputMode.OPENSL_ES) {
            AudioFormat.ENCODING_PCM_16BIT
        } else {
            probedEncoding
        }
        val nativeBitsPerSample = if (requestedEncoding == AudioFormat.ENCODING_PCM_16BIT) 16 else wavBitsPerSample
        val frameSize = actualChannels * if (requestedEncoding == AudioFormat.ENCODING_PCM_16BIT) 2 else 4
        val bytesPerMs = (actualSampleRate * frameSize).toDouble() / 1000.0
        val bufferFrames = ((actualSampleRate * 120L) / 1000L).toInt().coerceAtLeast(512)

        val engine = NativeAudioEngine.create(
            requestedMode = mode,
            sampleRate = actualSampleRate,
            channels = actualChannels,
            encoding = requestedEncoding,
            bufferFrames = bufferFrames,
            preferredDeviceId = preferredNativeOutputDeviceId(),
            spatializationBehavior = AndroidSpatialAudio.aaudioSpatializationBehavior(context, mode),
            contentSpatialized = AndroidSpatialAudio.aaudioContentSpatialized(mode)
        ) ?: return false

        val replacedEngine = replaceNativeAudioEngine(engine)
        nativeAudioEngineLifecycle.closeDetached(
            engine = replacedEngine,
            reason = "native_pcm_output_replace_existing",
            stop = true,
            flush = true
        )
        audioTrackLifecycle.detachAndRelease(
            reason = "native_pcm_output_start",
            stop = true,
            flush = true
        )
        _audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
        engine.setVolume(volume)

        val buffer = ByteArray(PCM_BUFFER_SIZE)
        val writeBuffer = ByteArray(PCM_BUFFER_SIZE)
        val readLimit = PcmFrameAligner.readLimit(buffer.size, frameSize)
        var totalBytesWritten = 0L
        var started = false
        val positionAccumulator = FractionalPlaybackPositionAccumulator()

        var lifecycleInterrupted = false

        fun writeToEngine(src: ByteArray, read: Int, sourceBits: Int = wavBitsPerSample): Int {
            val data: ByteArray
            val len: Int
            if (engine.encoding == AudioFormat.ENCODING_PCM_16BIT && sourceBits > 16) {
                len = pcmOutputConversion.convertS32ToS16(src, read, writeBuffer, sourceBits)
                data = writeBuffer
            } else {
                data = src
                len = read
            }
            if (!started) {
                if (!engine.start()) {
                    AppLogger.e(TAG, "Native streaming: start failed for ${engine.actualMode}")
                    return -1
                }
                started = true
            }
            return engine.write(data, 0, len)
        }

        try {
            AppLogger.i(TAG, "Native streaming playback: requested=$mode actual=${engine.actualMode} rate=$actualSampleRate ch=$actualChannels encoding=${engine.encoding} sourceBits=$wavBitsPerSample")

            val prefillTargetBytes = (bytesPerMs * 80).toInt().coerceAtLeast(frameSize * 16)
            var prefillBytesWritten = 0
            val prefillStart = System.currentTimeMillis()
            while (prefillBytesWritten < prefillTargetBytes && isPlaying.get() && !isReleased.get()) {
                val read = rb.readWithTimeout(buffer, 0, minOf(buffer.size, prefillTargetBytes - prefillBytesWritten), 500)
                if (read == -1) {
                    if (decoderDone && rb.available() == 0) break
                    continue
                }
                if (read <= 0) break
                val processedRead = processDsp(
                    buffer, read, actualChannels, actualSampleRate, wavBitsPerSample
                )
                if (processedRead <= 0) continue
                dispatchWaveformFrame(
                    buffer, processedRead, actualChannels, actualSampleRate, wavBitsPerSample
                )
                val written = writeToEngine(buffer, processedRead, wavBitsPerSample)
                if (written < 0) return false
                prefillBytesWritten += written
                totalBytesWritten += written
            }
            if (!started && !engine.start()) return false

            AppLogger.i(TAG, "Native prefill done: ${prefillBytesWritten}B in ${System.currentTimeMillis() - prefillStart}ms")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            var lastStreamLogMs = System.currentTimeMillis()
            var writeCyclesSinceLog = 0
            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "Native streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                if (!crossfadeTransition.active && crossfadeDurationMs > 0 && nextSongPath != null && _durationMs > 0) {
                    val path = nextSongPath!!
                    val manualTrigger = isManualCrossfadeTrigger(path, generation)
                    val remainingMs = (_durationMs - _positionMs).coerceAtLeast(1L)
                    if (manualTrigger || remainingMs in 1L..crossfadeDurationMs.toLong()) {
                        val preparedOk = gaplessNextDecoder.pathFor(generation) == path ||
                            prepareNextDecoder(path, generation)
                        val prepared = gaplessNextDecoder.snapshotFor(generation)
                        if (preparedOk && prepared != null && prepared.path == path && canCrossfadePreparedNext(prepared)) {
                            if (crossfadeTransition.start(
                                    targetPath = path,
                                    durationMs = crossfadeDurationMs,
                                    sampleRate = actualSampleRate,
                                    bufferSize = buffer.size,
                                    remainingMs = if (manualTrigger) crossfadeDurationMs.toLong() else remainingMs
                                )
                            ) {
                                if (manualTrigger) clearManualCrossfadeRequest("native_manual_crossfade_started")
                            }
                        } else if (manualTrigger) {
                            clearManualCrossfadeRequest("native_manual_crossfade_prepare_failed")
                        }
                    }
                }

                val preparedCrossfade = if (crossfadeTransition.active) {
                    val target = crossfadeTransition.targetPath
                    gaplessNextDecoder.snapshotFor(generation)?.takeIf { target == null || it.path == target }
                } else {
                    null
                }
                if (crossfadeTransition.active && preparedCrossfade == null) {
                    crossfadeTransition.reset("native_missing_prepared_next")
                }

                val seekReadToken = seekOutputBarrier.beginRead()
                var read: Int
                val bufferSourceBits = wavBitsPerSample
                if (crossfadeTransition.active && preparedCrossfade != null) {
                    val currentRead = rb.readWithTimeout(buffer, 0, readLimit, RING_BUFFER_READ_TIMEOUT_MS)
                    if (currentRead == -1) {
                        if (!isPlaying.get()) break
                        if (decoderDone && rb.available() == 0) {
                            read = 0
                        } else {
                            continue
                        }
                    } else {
                        read = currentRead
                    }

                    if (read > 0) {
                        val decoderBytesPerSample = if (bufferSourceBits <= 16) 2 else 4
                        val decoderFrameSize = actualChannels * decoderBytesPerSample
                        val decoderBytesPerMs = actualSampleRate.toDouble() * decoderFrameSize.toDouble() / 1000.0
                        val mixResult = crossfadeTransition.mixNextIntoCurrent(
                            currentBuf = buffer,
                            currentRead = read,
                            next = preparedCrossfade,
                            frameSize = decoderFrameSize,
                            outputIsFloat = false,
                            outputIsPacked24 = false,
                            bitsPerSample = bufferSourceBits
                        )
                        if (mixResult.completed) {
                            val elapsedMs = crossfadeTransition.elapsedMs(decoderBytesPerMs)
                            crossfadeTransition.reset("native_complete")
                            if (switchToNextSong(startPositionMs = elapsedMs)) {
                                positionAccumulator.reset()
                                rb = ringBuffer ?: break
                                if (RealtimePlaybackPcmProcessorRegistry.isActive()) {
                                    // Do not feed the just-completed mixed tail into the new
                                    // track's freshly reset model pipeline.
                                    continue
                                }
                            }
                        }
                    }
                } else {
                    read = rb.readWithTimeout(buffer, 0, readLimit, RING_BUFFER_READ_TIMEOUT_MS)
                    if (read == -1) {
                        if (!isPlaying.get()) break
                        if (decoderDone && rb.available() == 0) {
                            read = 0
                        } else {
                            continue
                        }
                    }
                }

                if (discardReadIfSeekCrossed(seekReadToken, "native")) {
                    continue
                }

                var drainedRealtimeOutput = false
                if (read <= 0 && RealtimePlaybackPcmProcessorRegistry.isActive()) {
                    val drained = RealtimePlaybackPcmProcessorRegistry.drain(buffer, readLimit)
                    when {
                        drained > 0 -> {
                            read = drained
                            drainedRealtimeOutput = true
                        }
                        drained == 0 -> continue
                    }
                }

                if (read <= 0) {
                    if (switchToNextSong()) {
                        positionAccumulator.reset()
                        rb = ringBuffer!!
                        AppLogger.d(TAG, "Native gapless: switched to next song, continuing write loop")
                        continue
                    }

                    val framesWritten = engine.getFramesWritten()
                    val drainStart = System.currentTimeMillis()
                    val maxDrainMs = 3000L
                    while (isPlaying.get() && !isReleased.get()) {
                        val expectedMs = if (actualSampleRate > 0) (framesWritten * 1000L / actualSampleRate) else 0L
                        if (_positionMs >= expectedMs.coerceAtMost(_durationMs)) break
                        if (System.currentTimeMillis() - drainStart > maxDrainMs) break
                        Thread.sleep(20)
                    }

                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val processedRead = if (drainedRealtimeOutput) {
                    processDspAfterRealtime(
                        buffer, read, actualChannels, actualSampleRate, bufferSourceBits
                    )
                } else {
                    processDsp(buffer, read, actualChannels, actualSampleRate, bufferSourceBits)
                }
                if (processedRead <= 0) continue
                dispatchWaveformFrame(
                    buffer, processedRead, actualChannels, actualSampleRate, bufferSourceBits
                )

                if (needsAudioTrackFlush.compareAndSet(true, false)) {
                    engine.flush()
                    disableHardwarePositionTracking()
                    positionAccumulator.reset()
                    totalBytesWritten = 0L
                    AppLogger.w(TAG, ">>> FLUSH Native PCM done, _positionMs=$_positionMs")
                }

                val written = writeToEngine(buffer, processedRead, bufferSourceBits)
                if (written < 0) {
                    if (retargetNativeOutputDevice("native_write_failed_$written")) {
                        AppLogger.w(TAG, "Native streaming: write failed=$written, output retargeted in-place; retry next buffer")
                        continue
                    }
                    AppLogger.e(TAG, "Native streaming: write failed=$written, falling back is not safe mid-buffer")
                    break
                }

                totalBytesWritten += written
                writeCyclesSinceLog++

                val prevPosMs = _positionMs
                val flushPending = needsAudioTrackFlush.get()
                if (!flushPending) {
                    _positionMs = positionAccumulator.advance(
                        currentPositionMs = _positionMs,
                        bytesAdvanced = written,
                        bytesPerMs = bytesPerMs,
                        durationMs = _durationMs
                    )
                }
                if (kotlin.math.abs(_positionMs - prevPosMs) > 2000) {
                    AppLogger.w(TAG, ">>> NATIVE pos JUMP: $prevPosMs -> $_positionMs (written=$written)")
                }
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStreamLogMs >= 5000) {
                    AppLogger.d(TAG, "Native streaming status: mode=${engine.actualMode}, writeCycles=$writeCyclesSinceLog, totalWritten=${totalBytesWritten}B, frames=${engine.getFramesWritten()}, rb.avail=${rb.available()}, _posMs=$_positionMs")
                    lastStreamLogMs = nowMs
                    writeCyclesSinceLog = 0
                }
            }
        } catch (_: InterruptedException) {
            lifecycleInterrupted = true
            AppLogger.i(
                TAG,
                "Native streaming playback interrupted for lifecycle replacement: source=$sourcePath gen=$generation"
            )
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "Native streaming playback EXCEPTION", e)
            }
        } finally {
            if (!lifecycleInterrupted && isPlaying.get() && isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                isPlaying.set(false)
                if (_state == State.PLAYING) {
                    setState(State.PAUSED)
                }
            }
            val ownedEngine = detachNativeAudioEngineIfOwned(engine)
            if (ownedEngine == null) {
                AppLogger.d(
                    TAG,
                    "Native streaming finalizer ignored replacement engine: " +
                        "source=$sourcePath gen=$generation mode=${engine.actualMode}"
                )
            }
            nativeAudioEngineLifecycle.closeDetached(
                engine = engine,
                reason = "native_streaming_finally_owned=${ownedEngine != null}",
                stop = true,
                flush = false
            )
            AppLogger.w(TAG, "Native streaming playback END, isPlaying=${isPlaying.get()}, state=$_state")
        }
        return true
    }

    private fun startUsbStreamingPlayback(
        sourcePath: String,
        generation: Int,
        sessionDecoderHandle: Long,
        sessionRingBuffer: RingBuffer,
        decodeChunkSize: Int = 16384
    ) {
        // The USB PCM feeder is part of the real-time audio path, not ordinary executor work.
        // If an OEM deprioritizes it, native keeps submitting valid silence and playback appears
        // alive while the audible source has stopped.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }.onFailure {
            AppLogger.w(TAG, "Unable to raise USB PCM feeder thread priority", it)
        }
        var rb = sessionRingBuffer
        val rawDsdTransport = wavBitsPerSample <= 1
        if (
            !playbackSession.isCurrent(sourcePath, generation) ||
            decoderHandle != sessionDecoderHandle ||
            ringBuffer !== rb ||
            decoderStopToken.isStopRequested
        ) {
            AppLogger.w(
                TAG,
                "USB streaming start rejected: stale ownership source=$sourcePath gen=$generation " +
                    "handle=$sessionDecoderHandle currentHandle=$decoderHandle sameRing=${ringBuffer === rb}"
            )
            return
        }

        val engine = UsbAudioEngine

        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val sourcePcmFrameSize = wavChannels * UsbAudioFormatPolicy.decoderBytesPerSample(wavBitsPerSample)
        var usbRuntimeFormat = engine.getRuntimeFormat()
        var usbDeviceFrameSize = usbRuntimeFormat.frameBytes.takeIf { it > 0 } ?: sourcePcmFrameSize
        var convertUsbS32ToPacked24 = false
        val expectedBytesPerSec = actualSampleRate.toLong() * sourcePcmFrameSize.toLong()

        AppLogger.i(TAG, "========== USB Streaming PRE-Prepare ==========")
        AppLogger.i(TAG, "  sampleRate=$actualSampleRate channels=$wavChannels bits=$wavBitsPerSample")
        AppLogger.i(TAG, "  sourceFrameSize=$sourcePcmFrameSize expectedSourceBytesPerSec=$expectedBytesPerSec")
        AppLogger.i(TAG, "  engine init=${engine.isInitialized()}, running=${engine.isRunning()}")
        AppLogger.i(TAG, "  engine.currentHandle=0x${java.lang.Long.toUnsignedString(engine.currentHandle, 16)}")
        AppLogger.i(TAG, "  engine transferCapacity(BEFORE prepare)=${engine.getTransferCapacityBytes()}")
        AppLogger.i(TAG, "  engine outputBytesPerSec(BEFORE prepare)=${engine.getOutputBytesPerSecond()}")
        AppLogger.i(TAG, "================================================")

        bytesReadTotal = 0L
        bytesWrittenTotal = 0L
        bytesNativeAcceptedTotal = 0L
        lastThroughputTime = System.currentTimeMillis()
        lastBytesRead = 0L
        lastBytesWritten = 0L
        lastBytesNativeAccepted = 0L
        nativeWriteCallCount = 0L

        val prepareOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath) ?: true
        if (!prepareOk) {
            isPlaying.set(false)
            AppLogger.e(TAG, "USB streaming: prepareForPlayback failed")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 设备准备失败")
            }
            return
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) return

        if (!engine.isInitialized()) {
            isPlaying.set(false)
            AppLogger.e(TAG, "USB streaming: engine not initialized")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 引擎初始化失败")
            }
            return
        }

        val finalDeviceSampleRate = engine.getOutputSampleRate()
        if (finalDeviceSampleRate > 0) {
            usbActualOutputSampleRate = finalDeviceSampleRate
            AppLogger.i(TAG, "USB streaming: final device sampleRate=$finalDeviceSampleRate, sourceDecoderRate=$actualSampleRate")
        }
        usbRuntimeFormat = engine.getRuntimeFormat()
        usbDeviceFrameSize = usbRuntimeFormat.frameBytes.takeIf { it > 0 } ?: sourcePcmFrameSize
        convertUsbS32ToPacked24 = usbNeedsS32ToPacked24(usbRuntimeFormat)
        if (convertUsbS32ToPacked24) {
            AppLogger.w(
                TAG,
                "USB PCM container conversion active: decoder=S32LE/${sourcePcmFrameSize}B-frame " +
                    "-> device=S24LE-packed/${usbDeviceFrameSize}B-frame " +
                    "runtime=${usbRuntimeFormat.sampleRate}Hz/${usbRuntimeFormat.channels}ch " +
                    "validBits=${usbRuntimeFormat.validBits} subslot=${usbRuntimeFormat.subslotBytes}"
            )
        }

        decoderDone = false
        decoderHandleTransferred.set(false)
        if (decoderThread?.isAlive != true) {
            val startToken = decoderStopToken
            if (!startDecoderThread(sourcePath, generation, sessionDecoderHandle, rb, startToken, decodeChunkSize)) {
                AppLogger.w(TAG, "USB streaming: decoder start rejected as stale")
                return
            }
        }

        val prefillTargetMs = 120L
        val prefillTargetBytes = (expectedBytesPerSec * prefillTargetMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val prefillTimeoutMs = 320L
        val minAcceptableBytes = (expectedBytesPerSec * 24 / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        AppLogger.i(TAG, "=== USB PREFILL START === target=${prefillTargetBytes}B (${prefillTargetMs}ms) minAcceptable=${minAcceptableBytes}B timeout=${prefillTimeoutMs}ms")
        AppLogger.i(TAG, "=== USB PREFILL rb state: available=${rb.available()} isEof=${rb.isEof()} isClosed=${rb.isClosed()}")
        val prefillStart = System.currentTimeMillis()
        var prefillWaitCount = 0
        while (isPlaying.get() && !isReleased.get()) {
            if (System.currentTimeMillis() - prefillStart > prefillTimeoutMs) {
                AppLogger.w(TAG, "USB prefill TIMEOUT after ${prefillTimeoutMs}ms: rb.available=${rb.available()} rb.isEof=${rb.isEof()} rb.isClosed=${rb.isClosed()} waitCount=$prefillWaitCount")
                break
            }
            val buffered = rb.available()
            if (buffered >= prefillTargetBytes) {
                AppLogger.i(TAG, "=== USB PREFILL TARGET REACHED: ${buffered}B >= ${prefillTargetBytes}B after ${System.currentTimeMillis() - prefillStart}ms, waitCount=$prefillWaitCount")
                break
            }
            if (rb.isEof()) {
                AppLogger.w(TAG, "USB prefill: RingBuffer EOF detected (rb.available=${rb.available()}) after ${System.currentTimeMillis() - prefillStart}ms")
                break
            }
            prefillWaitCount++
            if (prefillWaitCount % 100 == 0) {
                AppLogger.d(TAG, "USB prefill waiting: #$prefillWaitCount rb.available=${rb.available()} elapsed=${System.currentTimeMillis() - prefillStart}ms")
            }
            LockSupport.parkNanos(2_000_000L)
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) return

        val bufferedBytes = rb.available()
        AppLogger.i(TAG, "=== USB PREFILL END === buffered=${bufferedBytes}B (${bufferedBytes.toLong() * 1000L / expectedBytesPerSec}ms) rb.isEof=${rb.isEof()} rb.isClosed=${rb.isClosed()} isPlaying=${isPlaying.get()}")

        if (bufferedBytes < minAcceptableBytes && !rb.isEof()) {
            // 不把"Java 侧预填不足"作为硬错误，继续启动 USB 流
            AppLogger.w(TAG, "USB prefill insufficient, continue with native silence startup: ${bufferedBytes}B < ${minAcceptableBytes}B")
        }

        val preStartDeviceBytesPerSec = usbDeviceBytesPerSecond(engine, expectedBytesPerSec)
        val nativePrefillTargetBytes = PlaybackBufferMath.bytesForDuration(preStartDeviceBytesPerSec, USB_NATIVE_PREFILL_TARGET_MS)
        val nativePrefillMinBytes = PlaybackBufferMath.bytesForDuration(preStartDeviceBytesPerSec, USB_NATIVE_PREFILL_MIN_MS)
        val nativePrefillTimeoutMs = USB_NATIVE_PREFILL_TIMEOUT_MS
        val nativePrefillBuffer = ByteArray((sourcePcmFrameSize * 4096).coerceAtLeast(sourcePcmFrameSize * 256))
        var usbPacked24WriteBuffer: ByteArray? = null
        val nativePrefillStart = System.currentTimeMillis()
        var nativePrefillFailed = false

        // 重置 session 状态，让 native 写入检查通过（streamState -> PREPARED）
        engine.resetSessionForPlayback("before_usb_native_prefill")

        AppLogger.i(TAG, "=== USB NATIVE PREFILL START === target=${nativePrefillTargetBytes}B min=${nativePrefillMinBytes}B deviceBps=$preStartDeviceBytesPerSec rb.available=${rb.available()}")
        while (isPlaying.get() && !isReleased.get() && engine.getBufferUsedBytes() < nativePrefillTargetBytes) {
            if (System.currentTimeMillis() - nativePrefillStart > nativePrefillTimeoutMs) {
                AppLogger.w(TAG, "USB native prefill TIMEOUT: native=${engine.getBufferUsedBytes()}/${nativePrefillTargetBytes} rb.available=${rb.available()}")
                break
            }
            val read = rb.readWithTimeout(nativePrefillBuffer, 0, nativePrefillBuffer.size, 20)
            if (read == -1) {
                if (decoderDone && rb.available() == 0) break
                continue
            }
            if (read <= 0) break

            val alignedBytes = read - (read % sourcePcmFrameSize)
            if (alignedBytes <= 0) continue

            if (!rawDsdTransport) {
                processDsp(nativePrefillBuffer, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)
                applyPlaybackFade(
                    nativePrefillBuffer,
                    0,
                    alignedBytes,
                    actualSampleRate,
                    sourcePcmFrameSize,
                    wavBitsPerSample,
                    outputIsFloat = false,
                    outputIsPacked24 = false
                )
                dispatchWaveformFrame(
                    nativePrefillBuffer,
                    alignedBytes,
                    wavChannels,
                    wavSampleRate,
                    wavBitsPerSample
                )
            }

            val writeData: ByteArray
            val writeLength: Int
            val writeFrameSize: Int
            if (convertUsbS32ToPacked24) {
                val needed = (alignedBytes / sourcePcmFrameSize) * usbDeviceFrameSize
                var packed = usbPacked24WriteBuffer
                if (packed == null || packed.size < needed) {
                    packed = ByteArray(needed)
                    usbPacked24WriteBuffer = packed
                }
                val packedBuf = packed!!
                    writeLength = pcmOutputConversion.convertS32ToS24(
                        nativePrefillBuffer,
                        alignedBytes,
                        packedBuf,
                        wavBitsPerSample,
                    )
                writeData = packedBuf
                writeFrameSize = usbDeviceFrameSize
            } else {
                writeData = nativePrefillBuffer
                writeLength = alignedBytes
                writeFrameSize = sourcePcmFrameSize
            }

            var writeOffset = 0
            var consecutiveZeroWrites = 0
            while (writeOffset < writeLength && isPlaying.get() && !isReleased.get()) {
                if (engine.getBufferUsedBytes() >= nativePrefillTargetBytes) break
                val written = engine.write(writeData, writeOffset, writeLength - writeOffset)
                when {
                    written > 0 -> {
                        consecutiveZeroWrites = 0
                        val alignedWritten = written - (written % writeFrameSize)
                        if (alignedWritten <= 0) break
                        writeOffset += alignedWritten
                        bytesWrittenTotal += written.toLong()
                        bytesNativeAcceptedTotal += written.toLong()
                    }
                    written == 0 -> {
                        if (++consecutiveZeroWrites >= 100) break
                        LockSupport.parkNanos(1_000_000L)
                    }
                    else -> {
                        AppLogger.e(TAG, "USB native prefill write error: $written")
                        nativePrefillFailed = true
                        break
                    }
                }
            }
            if (nativePrefillFailed) break
        }

        val nativePrefillAfter = engine.getBufferUsedBytes()
        AppLogger.i(TAG, "=== USB NATIVE PREFILL END === native=${nativePrefillAfter}B (${nativePrefillAfter.toLong() * 1000L / preStartDeviceBytesPerSec}ms) rb.available=${rb.available()} failed=$nativePrefillFailed")
        if (nativePrefillFailed) {
            isPlaying.set(false)
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB native 预填写入失败")
            }
            return
        }
        if (nativePrefillAfter < nativePrefillMinBytes && !rb.isEof()) {
            AppLogger.w(TAG, "USB native prefill below min, starting anyway: native=${nativePrefillAfter}B < ${nativePrefillMinBytes}B")
        }

        if (
            !playbackSession.isCurrent(sourcePath, generation) ||
            decoderHandle != sessionDecoderHandle ||
            ringBuffer !== rb ||
            decoderStopToken.isStopRequested
        ) {
            AppLogger.w(TAG, "USB streaming native start rejected: decoder ownership changed")
            return
        }

        if (!startUsbEngineWithSafety("usb_streaming_start")) {
            val policyChanged = engine.isPolicyChangedSinceInit()
            AppLogger.w(TAG, "USB streaming: start failed, reinit retry (policyChanged=$policyChanged)")
            engine.release()
            val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath) ?: false
            if (reinitOk && engine.isInitialized()) {
                if (!startUsbEngineWithSafety("usb_streaming_reinit_start")) {
                    isPlaying.set(false)
                    AppLogger.e(TAG, "USB streaming: start failed after reinit")
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        listener?.onError("USB 音频流启动失败")
                    }
                    return
                }
            } else {
                isPlaying.set(false)
                AppLogger.e(TAG, "USB streaming: reinit failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 引擎重新初始化失败")
                }
                return
            }
        }
        armUsbPostStartVolumeRestore("usb_streaming_native_start")
        onUsbPlaybackStarted?.invoke()
        AppLogger.i(TAG, "USB streaming: nativeStart OK")

        if (isStillCurrentPlayback(sourcePath, generation)) {
            setState(State.PLAYING)
        }

        // Model: native owns ISO packet/transfer scheduling; Kotlin only feeds
        // device-format PCM in time-sized chunks. Transfer capacity is diagnostic, not pacing.
        val deviceBytesPerSec = usbDeviceBytesPerSecond(engine, expectedBytesPerSec)
        val transferCapacityBytes = engine.getTransferCapacityBytes().let { if (it > 0) it else usbDeviceFrameSize }
        val maxPacketBytes = engine.getMaxPacketBytes()
        val serviceIntervalsPerSecond = engine.getServiceIntervalsPerSecond()
        val nominalBytesPerInterval = engine.getNominalBytesPerInterval()
        val nominalBytesPerTransfer = engine.getNominalBytesPerTransfer()
        val runtime = engine.getRuntimeFormat()
        usbRuntimeFormat = runtime
        usbDeviceFrameSize = runtime.frameBytes.takeIf { it > 0 } ?: usbDeviceFrameSize
        convertUsbS32ToPacked24 = usbNeedsS32ToPacked24(runtime)
        val feedbackState = engine.getFeedbackState()
        val pacingMode = engine.getPacingMode()
        val feedbackDegradedFixedPath =
            runtime.feedbackEndpoint != 0 &&
                (pacingMode == UsbAudioEngine.PacingMode.FeedbackDegradedFixed ||
                    feedbackState == UsbAudioEngine.FeedbackState.SUSPECT ||
                    feedbackState == UsbAudioEngine.FeedbackState.DEGRADED ||
                    feedbackState == UsbAudioEngine.FeedbackState.FAILED)
        val fixedPacingPath =
            pacingMode.isFixedPacer ||
                runtime.feedbackEndpoint == 0 ||
                feedbackDegradedFixedPath
        val conservativeNoFeedbackPath =
            fixedPacingPath &&
                serviceIntervalsPerSecond > 1000
        val pureNoFeedbackBackpressurePath =
            runtime.feedbackEndpoint == 0 &&
                conservativeNoFeedbackPath
        val writeChunkTargetMs = if (conservativeNoFeedbackPath) {
            USB_NO_FEEDBACK_WRITE_CHUNK_MS
        } else {
            32L
        }
        val lowWaterMs = if (conservativeNoFeedbackPath) USB_NO_FEEDBACK_LOW_WATER_MS else USB_NATIVE_LOW_WATER_MS
        val targetWaterMs = if (conservativeNoFeedbackPath) USB_NO_FEEDBACK_TARGET_WATER_MS else USB_NATIVE_TARGET_WATER_MS
        val highWaterMs = if (conservativeNoFeedbackPath) USB_NO_FEEDBACK_HIGH_WATER_MS else USB_NATIVE_HIGH_WATER_MS
        val sourceBytesPerSec = actualSampleRate.toLong() * sourcePcmFrameSize.toLong()
        val maxRead = engine.computeRecommendedWriteChunkBytes(
            sourceBytesPerSec,
            sourcePcmFrameSize,
            targetMs = writeChunkTargetMs
        )
        val buffer = ByteArray(maxRead)

        // Water mark thresholds must use the DEVICE output byte rate (not source PCM rate),
        // because nativeGetBufferUsedBytes() returns the ring buffer usage in device output bytes.
        // For DoP, the device rate is much higher than the source rate (e.g. 2.1MB/s vs 176KB/s).
        val baseLowWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, lowWaterMs)
        val baseTargetWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, targetWaterMs)
        val baseHighWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, highWaterMs)
        val noFeedbackInflightGuard = if (pureNoFeedbackBackpressurePath) {
            (nominalBytesPerTransfer.coerceAtLeast(usbDeviceFrameSize) * USB_NO_FEEDBACK_INFLIGHT_GUARD_TRANSFERS)
                .coerceAtLeast(0)
        } else {
            0
        }
        val lowWater = if (pureNoFeedbackBackpressurePath) {
            maxOf(baseLowWater, noFeedbackInflightGuard / 2)
        } else {
            baseLowWater
        }
        val targetWater = if (pureNoFeedbackBackpressurePath) {
            maxOf(baseTargetWater, noFeedbackInflightGuard)
        } else {
            baseTargetWater
        }
        val highWater = if (pureNoFeedbackBackpressurePath) {
            maxOf(baseHighWater, targetWater + baseHighWater)
        } else {
            baseHighWater
        }
        val targetWaterParkNs = if (pureNoFeedbackBackpressurePath) 2_000_000L else 1_000_000L
        val highWaterParkNs = if (pureNoFeedbackBackpressurePath) 3_000_000L else 2_000_000L
        val backpressureParkNs = if (pureNoFeedbackBackpressurePath) 6_000_000L else 4_000_000L
        var consecutiveWriteZeros = 0
        val maxConsecutiveWriteZeros = 200
        var targetWaterSinceMs = 0L
        var lastTargetWaterDiagMs = 0L
        var highWaterSinceMs = 0L
        var lastHighWaterDiagMs = 0L
        var lastFeedbackDegradedProfileReprepareMs = 0L
        var lastNoFeedbackProfileReprepareMs = 0L
        var noFeedbackFullReopenRequested = false

        AppLogger.i(TAG, "========== USB Streaming POST-Start Parameters ==========")
        AppLogger.i(TAG, "  transferCapacity=$transferCapacityBytes maxPacket=$maxPacketBytes intervalsPerSec=$serviceIntervalsPerSecond")
        AppLogger.i(TAG, "  nominalInterval=$nominalBytesPerInterval nominalTransfer=$nominalBytesPerTransfer readChunk=$maxRead chunkMs=$writeChunkTargetMs conservativeNoFb=$conservativeNoFeedbackPath pureNoFbBackpressure=$pureNoFeedbackBackpressurePath feedbackDegradedFixed=$feedbackDegradedFixedPath fixedPacing=$fixedPacingPath feedback=$feedbackState pacing=$pacingMode")
        AppLogger.i(TAG, "  deviceBytesPerSec=$deviceBytesPerSec sourceBytesPerSec=$sourceBytesPerSec expectedSourceBytesPerSec=$expectedBytesPerSec")
        AppLogger.i(TAG, "  sourceFrame=$sourcePcmFrameSize deviceFrame=$usbDeviceFrameSize convertS32ToPacked24=$convertUsbS32ToPacked24")
        AppLogger.i(TAG, "  lowWater=$lowWater targetWater=$targetWater highWater=$highWater waterMs=$lowWaterMs/$targetWaterMs/$highWaterMs noFbInflightGuard=$noFeedbackInflightGuard")
        AppLogger.i(TAG, "  engine init=${engine.isInitialized()}, running=${engine.isRunning()} session=${engine.getStreamSessionId()}")
        AppLogger.i(TAG, "  nativeBuffered=${engine.nativeGetBufferUsedBytes()}")
        AppLogger.i(TAG, "  engine.getOutputBytesPerSecond()=${engine.getOutputBytesPerSecond()}")
        AppLogger.i(TAG, "  runtimeFormat=${runtime.sampleRate}Hz/${runtime.channels}ch validBits=${runtime.validBits} subslot=${runtime.subslotBytes} frame=${runtime.frameBytes} bps=${runtime.bytesPerSecond} iface=${runtime.iface} alt=${runtime.alt} outEp=0x${runtime.outEndpoint.toString(16)} fbEp=0x${runtime.feedbackEndpoint.toString(16)}")
        AppLogger.i(TAG, "==========================================================")
        AppLogger.i(TAG, "=== USB WRITE LOOP ENTERING === rb.available=${rb.available()} rb.isEof=${rb.isEof()} rb.isClosed=${rb.isClosed()} isPlaying=${isPlaying.get()}")
        var loopIteration = 0
        var restartNativeAfterSeekFlush = false
        var lifecycleInterrupted = false

        fun switchDecoderInsideUsbFeeder(
            manual: Boolean,
            reason: String,
            requestedTargetPath: String? = null,
        ): Boolean {
            val targetPath = requestedTargetPath ?: nextSongPath ?: return false
            var prepared = gaplessNextDecoder.snapshotFor(generation)
            if (prepared == null && !manual && prepareNextDecoder(targetPath, generation)) {
                prepared = gaplessNextDecoder.snapshotFor(generation)
            }
            if (prepared == null ||
                prepared.path != targetPath ||
                prepared.sampleRate != wavSampleRate ||
                prepared.channels != wavChannels ||
                prepared.bitsPerSample != wavBitsPerSample
            ) {
                AppLogger.w(
                    TAG,
                    "USB feeder switch rejected: incompatible/unprepared target=$targetPath " +
                        "current=${wavSampleRate}/${wavBitsPerSample}/${wavChannels} " +
                        "next=${prepared?.sampleRate}/${prepared?.bitsPerSample}/${prepared?.channels} " +
                        "manual=$manual reason=$reason"
                )
                return false
            }

            if (manual) {
                // The current feeder is the only owner allowed to cut the native PCM
                // generation. Endpoint, clock, alt setting, event thread, submit
                // thread and URB pool stay alive.
                engine.flushForNextTrack("uapp_same_profile_feeder:$reason")
                armUsbPostStartVolumeRestore("usb_same_profile_feeder:$reason")
            }

            val switched = switchToNextSong()
            val nextRing = ringBuffer
            if (!switched || nextRing == null) {
                AppLogger.e(TAG, "USB feeder decoder switch failed target=$targetPath manual=$manual reason=$reason")
                return false
            }

            rb = nextRing
            decoderDone = false
            consecutiveWriteZeros = 0
            targetWaterSinceMs = 0L
            highWaterSinceMs = 0L
            bytesReadTotal = 0L
            lastBytesRead = 0L
            lastThroughputTime = System.currentTimeMillis()
            AppLogger.i(
                TAG,
                "USB feeder decoder switch complete: target=$targetPath manual=$manual " +
                    "session=${engine.getStreamSessionId()} nativeRunning=${engine.isRunning()} reason=$reason"
            )
            return true
        }

        try {
            while (isPlaying.get() && !isReleased.get()) {
                loopIteration++
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "USB streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val manualRequest = usbSameProfileTrackSwitchCoordinator.takePendingRequest()
                if (manualRequest != null) {
                    val reason = manualRequest.reason.ifBlank { "manual_usb_switch" }
                    val generationMatches = manualRequest.generation == generation
                    val committed = generationMatches &&
                        manualRequest.targetPath == nextSongPath &&
                        switchDecoderInsideUsbFeeder(
                            manual = true,
                            reason = reason,
                            requestedTargetPath = manualRequest.targetPath,
                        )
                    manualRequest.completion.complete(committed)
                    if (committed) {
                        continue
                    }
                    AppLogger.e(
                        TAG,
                        "USB manual same-profile switch could not be committed on feeder: " +
                            "serial=${manualRequest.serial} generationMatches=$generationMatches " +
                            "target=${manualRequest.targetPath} reason=$reason",
                    )
                }

                val bufferedBeforeRead = engine.nativeGetBufferUsedBytes()
                if (pureNoFeedbackBackpressurePath && bufferedBeforeRead >= targetWater && bufferedBeforeRead <= highWater) {
                    // In no-feedback mode, target-water is a diagnostic line, not
                    // a hard producer stop. Xiaomi/TP55 can hit the target depth quickly and
                    // then starve if we stop feeding too early. Keep topping up toward
                    // high-water; only use the target-water hold as a fake-playback detector.
                    val nowMs = System.currentTimeMillis()
                    if (targetWaterSinceMs == 0L) targetWaterSinceMs = nowMs
                    val targetWaterHoldMs = nowMs - targetWaterSinceMs
                    highWaterSinceMs = 0L
                    val completedUsbBps = engine.getCompletedUsbBytesPerSecond()
                    val scheduledUsbBps = engine.getScheduledUsbBytesPerSecond()
                    val targetZeroOutput =
                        completedUsbBps <= 0L &&
                            scheduledUsbBps < deviceBytesPerSec * 25L / 100L
                    val targetSevereUnderOutput =
                        completedUsbBps in 1 until (deviceBytesPerSec * 15L / 100L) &&
                            scheduledUsbBps < deviceBytesPerSec * 40L / 100L
                    val targetModerateUnderOutput =
                        completedUsbBps in 0 until (deviceBytesPerSec * 35L / 100L) &&
                            scheduledUsbBps < deviceBytesPerSec * 60L / 100L
                    val canTargetWaterReprepare =
                        rb.available() > 0 &&
                            targetModerateUnderOutput &&
                            targetWaterHoldMs >= USB_NO_FEEDBACK_TARGET_WATER_FAKE_PLAYBACK_GRACE_MS &&
                            nowMs - lastNoFeedbackProfileReprepareMs >= USB_NO_FEEDBACK_FAST_REPREPARE_COOLDOWN_MS
                    if (canTargetWaterReprepare) {
                        lastNoFeedbackProfileReprepareMs = nowMs
                        AppLogger.w(
                            TAG,
                            "USB no-feedback target-water fake playback: profile reprepare " +
                                "hold=${targetWaterHoldMs}ms nativeBuf=$bufferedBeforeRead target=$targetWater " +
                                "completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec " +
                                "rb.available=${rb.available()} zero=$targetZeroOutput severe=$targetSevereUnderOutput"
                        )
                        val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                            UsbSilentKind.UsbNotOutputting,
                            "no_feedback_target_fake_playback_profile_reprepare"
                        ) == true
                        if (controllerOwnsRecovery) {
                            AppLogger.w(TAG, "USB target-water recovery delegated; feeder exiting")
                            isPlaying.set(false)
                            break
                        }
                        val recovered = attemptUsbRecovery(
                            actualSampleRate,
                            wavBitsPerSample,
                            wavChannels,
                            sourcePath,
                            generation,
                            forceProfileReinit = true,
                            profileReprepareOnly = true
                        )
                        if (recovered) {
                            targetWaterSinceMs = 0L
                            lastTargetWaterDiagMs = 0L
                            lastHighWaterDiagMs = 0L
                            consecutiveWriteZeros = 0
                            java.util.concurrent.locks.LockSupport.parkNanos(backpressureParkNs)
                            continue
                        }
                        AppLogger.w(
                            TAG,
                            "USB no-feedback target-water fake playback reprepare failed; keep current stream alive " +
                                "hold=${targetWaterHoldMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                "expected=$deviceBytesPerSec"
                        )
                    }
                    if (loopIteration <= 5 || nowMs - lastTargetWaterDiagMs >= 1000L) {
                        lastTargetWaterDiagMs = nowMs
                        AppLogger.d(
                            TAG,
                            "USB no-feedback target-water observe: nativeBuf=$bufferedBeforeRead " +
                                "target=$targetWater high=$highWater hold=${targetWaterHoldMs}ms " +
                                "completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec " +
                                "rb.available=${rb.available()}"
                        )
                    }
                }
                if (bufferedBeforeRead > highWater) {
                    targetWaterSinceMs = 0L
                    lastTargetWaterDiagMs = 0L
                    val nowMs = System.currentTimeMillis()
                    if (highWaterSinceMs == 0L) highWaterSinceMs = nowMs
                    val stallMs = nowMs - highWaterSinceMs
                    val completedUsbBps = engine.getCompletedUsbBytesPerSecond()
                    val scheduledUsbBps = engine.getScheduledUsbBytesPerSecond()
                    if (loopIteration <= 3 || nowMs - lastHighWaterDiagMs >= 1000L) {
                        lastHighWaterDiagMs = nowMs
                        AppLogger.w(TAG, "USB HIGH-WATER: loop=$loopIteration stall=${stallMs}ms nativeBuf=$bufferedBeforeRead highWater=$highWater " +
                            "completedUsbBps=$completedUsbBps scheduledUsbBps=$scheduledUsbBps deviceBPS=$deviceBytesPerSec " +
                            "rb.available=${rb.available()} bytesReadTotal=$bytesReadTotal bytesWrittenTotal=$bytesWrittenTotal")
                    }
                    val underOutput = completedUsbBps in 1 until (deviceBytesPerSec * 70L / 100L)
                    val noCompletedYet = completedUsbBps <= 0L && stallMs >= 1800L
                    val currentFeedbackState = engine.getFeedbackState()
                    val currentPacingMode = engine.getPacingMode()
                    val dynamicRuntime = engine.getRuntimeFormat()
                    val dynamicNoFeedbackFixedPath =
                        dynamicRuntime.feedbackEndpoint == 0 ||
                            (currentPacingMode == UsbAudioEngine.PacingMode.NoFeedbackFixed &&
                                currentFeedbackState == UsbAudioEngine.FeedbackState.NONE)
                    val currentFixedPacingPath = fixedPacingPath ||
                        currentPacingMode.isFixedPacer ||
                        dynamicNoFeedbackFixedPath ||
                        currentFeedbackState == UsbAudioEngine.FeedbackState.SUSPECT ||
                        currentFeedbackState == UsbAudioEngine.FeedbackState.DEGRADED ||
                        currentFeedbackState == UsbAudioEngine.FeedbackState.FAILED
                    val fixedPacerAlive = currentFixedPacingPath && (scheduledUsbBps > 0L || completedUsbBps > 0L)
                    val fixedPacerNoOutput = currentFixedPacingPath && scheduledUsbBps <= 0L && completedUsbBps <= 0L
                    val severeNoFeedbackUnderOutput =
                        dynamicNoFeedbackFixedPath &&
                            completedUsbBps > 0L &&
                            scheduledUsbBps > 0L &&
                            completedUsbBps < deviceBytesPerSec * 20L / 100L &&
                            scheduledUsbBps < deviceBytesPerSec * 35L / 100L
                    val pacingRepairEligible =
                        currentFixedPacingPath &&
                            serviceIntervalsPerSecond > 1000 &&
                            fixedPacerAlive
                    val recoveryStallMs = when {
                        feedbackDegradedFixedPath -> 1500L
                        severeNoFeedbackUnderOutput -> 1400L
                        fixedPacerAlive -> USB_FIXED_PACER_DRAIN_GRACE_MS
                        fixedPacerNoOutput -> USB_FIXED_PACER_ZERO_OUTPUT_GRACE_MS
                        pacingRepairEligible -> 2600L
                        else -> 1200L
                    }
                    if (stallMs >= recoveryStallMs && rb.available() > 0 && (underOutput || noCompletedYet)) {
                        val noFeedbackTransportAlive =
                            dynamicNoFeedbackFixedPath &&
                                (completedUsbBps > 0L || scheduledUsbBps > 0L)
                        if (noFeedbackTransportAlive) {
                            // No-feedback model: the StreamConfig is still valid,
                            // but an alive OUT path can be stuck with its native ring above
                            // high-water while completed/scheduled throughput stays far below
                            // the target.  Do not hard-reopen or change alt/clock. Restart
                            // only the current ISO transfer queue so Xiaomi/TP55 can recover
                            // from the red/blue relock loop without an audible destructive
                            // device reset.
                            val noFeedbackZeroOutput = completedUsbBps <= 0L &&
                                scheduledUsbBps < deviceBytesPerSec * 25L / 100L
                            val noFeedbackSevereUnderOutput =
                                completedUsbBps in 1 until (deviceBytesPerSec * 8L / 100L) &&
                                    scheduledUsbBps < deviceBytesPerSec * 25L / 100L
                            val noFeedbackReprepareGraceMs = if (noFeedbackZeroOutput || noFeedbackSevereUnderOutput) {
                                USB_NO_FEEDBACK_ZERO_OUTPUT_REPREPARE_MS
                            } else {
                                USB_NO_FEEDBACK_MODERATE_UNDER_OUTPUT_REPREPARE_MS
                            }
                            val noFeedbackReprepareCooldownMs = if (noFeedbackZeroOutput || noFeedbackSevereUnderOutput) {
                                USB_NO_FEEDBACK_FAST_REPREPARE_COOLDOWN_MS
                            } else {
                                USB_NO_FEEDBACK_STABLE_REPREPARE_COOLDOWN_MS
                            }
                            val canProfileReprepare =
                                stallMs >= noFeedbackReprepareGraceMs &&
                                    completedUsbBps < deviceBytesPerSec * 55L / 100L &&
                                    scheduledUsbBps < deviceBytesPerSec * 95L / 100L &&
                                    nowMs - lastNoFeedbackProfileReprepareMs >= noFeedbackReprepareCooldownMs
                            if (canProfileReprepare) {
                                lastNoFeedbackProfileReprepareMs = nowMs
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback high-water under-output: profile reprepare " +
                                        "stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                        "expected=$deviceBytesPerSec nativeBuf=$bufferedBeforeRead high=$highWater " +
                                        "runtimeFb=0x${dynamicRuntime.feedbackEndpoint.toString(16)} pacing=$currentPacingMode"
                                )
                                val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                                    UsbSilentKind.UsbNotOutputting,
                                    "no_feedback_high_water_under_output_profile_reprepare"
                                ) == true
                                if (controllerOwnsRecovery) {
                                    AppLogger.w(TAG, "USB high-water profile recovery delegated; feeder exiting")
                                    isPlaying.set(false)
                                    break
                                }
                                val recovered = attemptUsbRecovery(
                                    actualSampleRate,
                                    wavBitsPerSample,
                                    wavChannels,
                                    sourcePath,
                                    generation,
                                    forceProfileReinit = true,
                                    profileReprepareOnly = true
                                )
                                if (recovered) {
                                    highWaterSinceMs = 0L
                                    lastHighWaterDiagMs = 0L
                                    consecutiveWriteZeros = 0
                                    java.util.concurrent.locks.LockSupport.parkNanos(backpressureParkNs)
                                    continue
                                }
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback high-water profile reprepare failed; same-profile ISO restart disabled " +
                                        "completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec"
                                )
                            }
                            if (severeNoFeedbackUnderOutput) {
                                val recentlyReprepared =
                                    nowMs - lastNoFeedbackProfileReprepareMs < USB_NO_FEEDBACK_FAST_REPREPARE_COOLDOWN_MS
                                if (!noFeedbackFullReopenRequested &&
                                    (recentlyReprepared || stallMs >= noFeedbackReprepareGraceMs + 1_200L)
                                ) {
                                    noFeedbackFullReopenRequested = true
                                    AppLogger.e(
                                        TAG,
                                        "USB no-feedback high-water severe under-output: request full reopen " +
                                            "stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                            "expected=$deviceBytesPerSec nativeBuf=$bufferedBeforeRead high=$highWater " +
                                            "recentlyReprepared=$recentlyReprepared runtimeFb=0x${dynamicRuntime.feedbackEndpoint.toString(16)} " +
                                            "pacing=$currentPacingMode"
                                    )
                                    val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                                        UsbSilentKind.UsbNotOutputting,
                                        "no_feedback_high_water_severe_under_output_full_reopen"
                                    ) == true
                                    if (controllerOwnsRecovery) {
                                        AppLogger.w(TAG, "USB no-feedback full reopen delegated; feeder exiting")
                                        isPlaying.set(false)
                                        break
                                    }
                                    noFeedbackFullReopenRequested = false
                                    java.util.concurrent.locks.LockSupport.parkNanos(12_000_000L)
                                    continue
                                }
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback high-water severe under-output: waiting for same-profile restart window " +
                                        "stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                        "expected=$deviceBytesPerSec nativeBuf=$bufferedBeforeRead high=$highWater " +
                                        "runtimeFb=0x${dynamicRuntime.feedbackEndpoint.toString(16)} pacing=$currentPacingMode"
                                )
                                java.util.concurrent.locks.LockSupport.parkNanos(8_000_000L)
                                continue
                            }
                            AppLogger.w(
                                TAG,
                                "USB no-feedback high-water backpressure: keep stream alive " +
                                    "stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                    "expected=$deviceBytesPerSec nativeBuf=$bufferedBeforeRead high=$highWater"
                            )
                            highWaterSinceMs = 0L
                            java.util.concurrent.locks.LockSupport.parkNanos(12_000_000L)
                            continue
                        }
                        if (pureNoFeedbackBackpressurePath &&
                            currentFixedPacingPath &&
                            bufferedBeforeRead >= targetWater &&
                            completedUsbBps < deviceBytesPerSec * 30L / 100L &&
                            stallMs >= 1500L
                        ) {
                            val targetZeroOutput = completedUsbBps <= 0L && scheduledUsbBps <= 0L
                            val targetSevereUnderOutput =
                                completedUsbBps in 1 until (deviceBytesPerSec * 8L / 100L) &&
                                    scheduledUsbBps < deviceBytesPerSec * 25L / 100L
                            val targetReprepareGraceMs = if (targetZeroOutput || targetSevereUnderOutput) {
                                USB_NO_FEEDBACK_ZERO_OUTPUT_REPREPARE_MS
                            } else {
                                USB_NO_FEEDBACK_MODERATE_UNDER_OUTPUT_REPREPARE_MS
                            }
                            val targetReprepareCooldownMs = if (targetZeroOutput || targetSevereUnderOutput) {
                                USB_NO_FEEDBACK_FAST_REPREPARE_COOLDOWN_MS
                            } else {
                                USB_NO_FEEDBACK_STABLE_REPREPARE_COOLDOWN_MS
                            }
                            if (stallMs >= targetReprepareGraceMs &&
                                nowMs - lastNoFeedbackProfileReprepareMs >= targetReprepareCooldownMs
                            ) {
                                lastNoFeedbackProfileReprepareMs = nowMs
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback target-water under-output: profile reprepare " +
                                        "stall=${stallMs}ms grace=$targetReprepareGraceMs cooldown=$targetReprepareCooldownMs " +
                                        "nativeBuf=$bufferedBeforeRead target=$targetWater " +
                                        "completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec " +
                                        "rb.available=${rb.available()}"
                                )
                                val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                                    UsbSilentKind.UsbNotOutputting,
                                    "no_feedback_target_under_output_profile_reprepare"
                                ) == true
                                if (controllerOwnsRecovery) {
                                    AppLogger.w(TAG, "USB target-water profile recovery delegated; feeder exiting")
                                    isPlaying.set(false)
                                    break
                                }
                                val recovered = attemptUsbRecovery(
                                    actualSampleRate,
                                    wavBitsPerSample,
                                    wavChannels,
                                    sourcePath,
                                    generation,
                                    forceProfileReinit = true,
                                    profileReprepareOnly = true
                                )
                                if (recovered) {
                                    highWaterSinceMs = 0L
                                    lastHighWaterDiagMs = 0L
                                    consecutiveWriteZeros = 0
                                    java.util.concurrent.locks.LockSupport.parkNanos(backpressureParkNs)
                                    continue
                                }
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback target-water profile reprepare failed; keep current stream alive " +
                                        "without same-profile ISO restart nativeBuf=$bufferedBeforeRead completed=$completedUsbBps " +
                                        "scheduled=$scheduledUsbBps expected=$deviceBytesPerSec"
                                )
                            }
                        }
                        val schedulerLimited =
                            currentFixedPacingPath &&
                                (completedUsbBps > 0L || scheduledUsbBps > 0L) &&
                                scheduledUsbBps < (deviceBytesPerSec * 85L / 100L)

                        if (feedbackDegradedFixedPath &&
                            currentFixedPacingPath &&
                            (currentFeedbackState == UsbAudioEngine.FeedbackState.DEGRADED ||
                                currentPacingMode == UsbAudioEngine.PacingMode.FeedbackDegradedFixed) &&
                            completedUsbBps < deviceBytesPerSec * 70L / 100L &&
                            scheduledUsbBps < deviceBytesPerSec * 70L / 100L &&
                            stallMs >= 1500L
                        ) {
                            AppLogger.w(
                                TAG,
                                "USB feedback-degraded fixed pacer under-output: retry without feedback " +
                                    "stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                    "expected=$deviceBytesPerSec fbState=$currentFeedbackState pacing=$currentPacingMode"
                            )
                            val zeroCompletionFeedbackDeadlock = completedUsbBps <= 0L &&
                                scheduledUsbBps < deviceBytesPerSec * 25L / 100L
                            val feedbackRetryCooldownMs = if (zeroCompletionFeedbackDeadlock) 1_800L else 3_500L
                            val retryAllowed = nowMs - lastFeedbackDegradedProfileReprepareMs >= feedbackRetryCooldownMs
                            if (!retryAllowed) {
                                AppLogger.w(
                                    TAG,
                                    "USB feedback-degraded profile retry suppressed to avoid storm: " +
                                        "since=${nowMs - lastFeedbackDegradedProfileReprepareMs}ms " +
                                        "cooldown=$feedbackRetryCooldownMs zeroCompletion=$zeroCompletionFeedbackDeadlock"
                                )
                                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L)
                                continue
                            }
                            lastFeedbackDegradedProfileReprepareMs = nowMs
                            val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                                UsbSilentKind.FeedbackInvalid,
                                "feedback_degraded_under_output"
                            ) == true
                            if (controllerOwnsRecovery) {
                                AppLogger.w(TAG, "USB feedback recovery delegated; feeder exiting")
                                isPlaying.set(false)
                                break
                            }
                            val recovered = attemptUsbRecovery(
                                actualSampleRate,
                                wavBitsPerSample,
                                wavChannels,
                                sourcePath,
                                generation,
                                forceProfileReinit = true,
                                profileReprepareOnly = true
                            )
                            if (recovered) {
                                highWaterSinceMs = 0L
                                lastHighWaterDiagMs = 0L
                                consecutiveWriteZeros = 0
                                continue
                            }
                            AppLogger.w(
                                TAG,
                                "USB feedback-degraded profile reprepare failed; keep current stream alive " +
                                    "without falling into hard-recovery fuse storm"
                            )
                            java.util.concurrent.locks.LockSupport.parkNanos(12_000_000L)
                            continue
                        }

                        if (schedulerLimited && stallMs < USB_FIXED_PACER_DRAIN_GRACE_MS) {
                            // Fixed/no-feedback path: feedback health and app-side
                            // high-water are diagnostics, not a reason to rebuild the USB device.
                            // Some no-feedback fixed-pacer streams show completed/scheduled
                            // throughput for several seconds while the native ring stays high; reopening here makes the DAC relock
                            // and stops playback.  Keep throttling the producer and allow native
                            // queue depth / measured interval repair to stabilize.
                            if (nowMs - lastHighWaterDiagMs >= 1000L || loopIteration <= 5) {
                                AppLogger.w(TAG, "USB fixed-pacer drain is slow: defer hard recovery stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec feedbackState=$currentFeedbackState pacing=$currentPacingMode")
                            }
                            java.util.concurrent.locks.LockSupport.parkNanos(8_000_000L)
                            continue
                        }

                        if (fixedPacerAlive) {
                            val severeNoFeedbackFixedUnderOutput =
                                severeNoFeedbackUnderOutput && stallMs >= USB_NO_FEEDBACK_ZERO_OUTPUT_REPREPARE_MS
                            if (severeNoFeedbackFixedUnderOutput &&
                                nowMs - lastNoFeedbackProfileReprepareMs >= USB_NO_FEEDBACK_STABLE_REPREPARE_COOLDOWN_MS
                            ) {
                                lastNoFeedbackProfileReprepareMs = nowMs
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback fixed-pacer severe under-output: profile reprepare " +
                                        "stall=${stallMs}ms cooldown=${USB_NO_FEEDBACK_STABLE_REPREPARE_COOLDOWN_MS}ms " +
                                        "completed=$completedUsbBps scheduled=$scheduledUsbBps " +
                                        "expected=$deviceBytesPerSec nativeBuf=$bufferedBeforeRead high=$highWater " +
                                        "runtimeFb=0x${dynamicRuntime.feedbackEndpoint.toString(16)} feedbackState=$currentFeedbackState pacing=$currentPacingMode"
                                )
                                val controllerOwnsRecovery = onUsbStreamHealthFailure?.invoke(
                                    UsbSilentKind.UsbNotOutputting,
                                    "no_feedback_fixed_pacer_severe_under_output_profile_reprepare"
                                ) == true
                                if (controllerOwnsRecovery) {
                                    AppLogger.w(TAG, "USB fixed-pacer recovery delegated; feeder exiting")
                                    isPlaying.set(false)
                                    break
                                }
                                val recovered = attemptUsbRecovery(
                                    actualSampleRate,
                                    wavBitsPerSample,
                                    wavChannels,
                                    sourcePath,
                                    generation,
                                    forceProfileReinit = true,
                                    profileReprepareOnly = true
                                )
                                if (recovered) {
                                    highWaterSinceMs = 0L
                                    lastHighWaterDiagMs = 0L
                                    consecutiveWriteZeros = 0
                                    java.util.concurrent.locks.LockSupport.parkNanos(backpressureParkNs)
                                    continue
                                }
                                AppLogger.w(
                                    TAG,
                                    "USB no-feedback severe under-output profile reprepare failed; " +
                                        "same-profile ISO restart disabled completed=$completedUsbBps " +
                                        "scheduled=$scheduledUsbBps expected=$deviceBytesPerSec"
                                )
                            }
                            // Even after the grace window, an alive fixed-pacer stream should not
                            // trigger destructive reopen from the Kotlin feeder.  But an alive
                            // no-feedback stream that is completing only ~1% of expected is not
                            // healthy backpressure; it must hit the same-profile transfer restart
                            // path above instead of being logged forever as keep-alive.
                            AppLogger.w(TAG, "USB fixed-pacer still draining below target; keep stream alive stall=${stallMs}ms completed=$completedUsbBps scheduled=$scheduledUsbBps expected=$deviceBytesPerSec feedbackState=$currentFeedbackState pacing=$currentPacingMode runtimeFb=0x${dynamicRuntime.feedbackEndpoint.toString(16)}")
                            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L)
                            continue
                        }

                        if (shouldDeferHardUsbRecovery("high_water_stall")) {
                            AppLogger.w(
                                TAG,
                                "USB high-water stall recovery deferred by controller: " +
                                    "stall=${stallMs}ms completedUsbBps=$completedUsbBps " +
                                    "scheduledUsbBps=$scheduledUsbBps expected=$deviceBytesPerSec"
                            )
                            java.util.concurrent.locks.LockSupport.parkNanos(8_000_000L)
                            continue
                        }

                        val feedbackUnsafe = engine.feedbackLooksUnsafeForPacer()
                        val kind = if (feedbackUnsafe) UsbSilentKind.FeedbackInvalid else UsbSilentKind.UsbNotOutputting
                        AppLogger.w(TAG, "USB high-water stall detected: stall=${stallMs}ms threshold=${recoveryStallMs}ms completedUsbBps=$completedUsbBps scheduledUsbBps=$scheduledUsbBps expected=$deviceBytesPerSec feedbackState=$currentFeedbackState pacing=$currentPacingMode fixedPacer=$currentFixedPacingPath pacingRepairEligible=$pacingRepairEligible; attempting generic recovery kind=$kind")
                        // attemptUsbRecovery performs the optional soft start and then
                        // invokes the single controller-owned destructive recovery callback.
                        // Do not notify once here and a second time inside the helper.
                        val recovered = attemptUsbRecovery(
                            actualSampleRate,
                            wavBitsPerSample,
                            wavChannels,
                            sourcePath,
                            generation,
                            forceProfileReinit = true
                        )
                        if (recovered) {
                            highWaterSinceMs = 0L
                            lastHighWaterDiagMs = 0L
                            consecutiveWriteZeros = 0
                            continue
                        }
                        AppLogger.e(TAG, "USB high-water stall recovery failed")
                        isPlaying.set(false)
                        break
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(highWaterParkNs)
                    continue
                } else {
                    targetWaterSinceMs = 0L
                    lastTargetWaterDiagMs = 0L
                    highWaterSinceMs = 0L
                }

                if (needsAudioTrackFlush.compareAndSet(true, false)) {
                    val seekPosMs = _positionMs
                    bytesReadTotal = (seekPosMs.toDouble() * expectedBytesPerSec.toDouble() / 1000.0).toLong()
                    restartNativeAfterSeekFlush = usbExclusiveMode && !engine.isRunning()
                    AppLogger.w(TAG, ">>> USB seek flush: reset bytesReadTotal to $bytesReadTotal (seekPos=${seekPosMs}ms, restartNative=$restartNativeAfterSeekFlush)")
                }

                if (loopIteration <= 3 || loopIteration % 1000 == 0) {
                    AppLogger.d(TAG, "USB write loop #$loopIteration: BEFORE rb.read rb.available=${rb.available()} rb.isEof=${rb.isEof()} rb.isClosed=${rb.isClosed()} nativeBuf=$bufferedBeforeRead maxRead=$maxRead")
                }

                val readLimit = if (pureNoFeedbackBackpressurePath) {
                    val roomToHighWater = (highWater - bufferedBeforeRead).coerceAtLeast(sourcePcmFrameSize)
                    val rawLimit = minOf(maxRead, roomToHighWater)
                    (rawLimit - (rawLimit % sourcePcmFrameSize)).coerceAtLeast(sourcePcmFrameSize)
                } else {
                    maxRead
                }
                val read = rb.readWithTimeout(buffer, 0, readLimit, RING_BUFFER_READ_TIMEOUT_MS)
                if (read == -1) {
                    // 超时，检查是否仍在播放
                    if (!isPlaying.get()) break
                    // 解码器已完成且 buffer 为空 → 真正的 EOF
                    if (decoderDone && rb.available() == 0) {
                        if (nextSongPath != null && switchDecoderInsideUsbFeeder(false, "natural_eof_timeout")) {
                            continue
                        }
                        AppLogger.i(TAG, "USB streaming: decoderDone + buffer empty → EOF")
                        isPlaying.set(false)
                        if (isStillCurrentPlayback(sourcePath, generation)) {
                            onPlaybackSuccess()
                            setState(State.COMPLETED)
                        }
                        break
                    }
                    continue
                }
                if (read <= 0) {
                    if (decoderDone && nextSongPath != null && switchDecoderInsideUsbFeeder(false, "natural_eof_read_$read")) {
                        continue
                    }
                    AppLogger.w(TAG, "USB streaming: EOF from ring buffer (read=$read, rb.available=${rb.available()}, rb.isEof=${rb.isEof()}, rb.isClosed=${rb.isClosed()}, loopIteration=$loopIteration)")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val alignedBytes = read - (read % sourcePcmFrameSize)
                if (alignedBytes <= 0) continue

                bytesReadTotal += alignedBytes

                if (!rawDsdTransport) {
                    processDsp(buffer, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)
                    applyPlaybackFade(
                        buffer,
                        0,
                        alignedBytes,
                        actualSampleRate,
                        sourcePcmFrameSize,
                        wavBitsPerSample,
                        outputIsFloat = false,
                        outputIsPacked24 = false
                    )
                    dispatchWaveformFrame(
                        buffer,
                        alignedBytes,
                        wavChannels,
                        wavSampleRate,
                        wavBitsPerSample
                    )
                }

                val writeData: ByteArray
                val writeLength: Int
                val writeFrameSize: Int
                if (convertUsbS32ToPacked24) {
                    val needed = (alignedBytes / sourcePcmFrameSize) * usbDeviceFrameSize
                    var packed = usbPacked24WriteBuffer
                    if (packed == null || packed.size < needed) {
                        packed = ByteArray(needed)
                        usbPacked24WriteBuffer = packed
                    }
                    val packedBuf = packed!!
                    writeLength = pcmOutputConversion.convertS32ToS24(
                        buffer,
                        alignedBytes,
                        packedBuf,
                        wavBitsPerSample,
                    )
                    writeData = packedBuf
                    writeFrameSize = usbDeviceFrameSize
                } else {
                    writeData = buffer
                    writeLength = alignedBytes
                    writeFrameSize = sourcePcmFrameSize
                }

                var writeOffset = 0
                while (writeOffset < writeLength && isPlaying.get() && !isReleased.get()) {
                    val remaining = writeLength - writeOffset
                    val written = engine.write(writeData, writeOffset, remaining)
                    when {
                        written > 0 -> {
                            consecutiveWriteZeros = 0
                            val alignedWritten = written - (written % writeFrameSize)
                            if (alignedWritten <= 0) {
                                AppLogger.w(TAG, "USB streaming: non-frame write: $written frameSize=$writeFrameSize")
                                break
                            }
                            writeOffset += alignedWritten
                            bytesWrittenTotal += written
                            bytesNativeAcceptedTotal += written

                            val nowBuffered = engine.nativeGetBufferUsedBytes()
                            if (engine.isRunning()) {
                                maybeRestoreUsbVolumeRoute("usb_streaming_write_loop", nowBuffered)
                            }
                            if (nowBuffered >= highWater) {
                                java.util.concurrent.locks.LockSupport.parkNanos(highWaterParkNs)
                            }
                            // Diagnostic: first few writes in each outer iteration
                            if (loopIteration <= 5 && writeOffset == alignedWritten) {
                                AppLogger.d(TAG, "  USB inner write: written=$written nowBuffered=$nowBuffered highWater=$highWater writeOffset=$writeOffset/$writeLength")
                            }
                        }
                        written == 0 -> {
                            val nativeBuffered = runCatching { engine.nativeGetBufferUsedBytes() }.getOrDefault(0)
                            val running = runCatching { engine.isRunning() }.getOrDefault(false)
                            val backpressure = running && nativeBuffered >= lowWater
                            if (backpressure) {
                                // nativeWriteHandle returns 0 for a full-enough native ring as
                                // flow-control, not only for a dead engine. Low-rate/no-feedback
                                // DACs such as 44.1k/16bit can sit above the native soft limit for
                                // long stretches; treating that as failure causes hard recovery loops,
                                // stutter, LED jumping and silence. Keep the pump alive and wait for
                                // native drain instead.
                                if (consecutiveWriteZeros > 0 || loopIteration <= 5 || loopIteration % 1000 == 0) {
                                    AppLogger.d(TAG, "USB streaming: write backpressure nativeBuffered=$nativeBuffered low=$lowWater target=$targetWater running=$running")
                                }
                                consecutiveWriteZeros = 0
                                java.util.concurrent.locks.LockSupport.parkNanos(backpressureParkNs)
                                continue
                            }

                            consecutiveWriteZeros++
                            if (consecutiveWriteZeros >= maxConsecutiveWriteZeros) {
                                // 连续返回0且 native buffer 不足，才认为引擎可能已死并尝试恢复。
                                AppLogger.w(TAG, "USB streaming: write returned 0 ${consecutiveWriteZeros} times, attempting recovery... nativeBuffered=$nativeBuffered low=$lowWater running=$running")
                                val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                                if (recovered) {
                                    AppLogger.i(TAG, "USB streaming: recovery after write-0 succeeded")
                                    consecutiveWriteZeros = 0
                                    continue
                                }
                                AppLogger.e(TAG, "USB streaming: recovery after write-0 failed, stopping")
                                isPlaying.set(false)
                                break
                            }
                            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L)
                        }
                        written == UsbAudioEngine.ERR_TRANSPORT_LOST ||
                        written == UsbAudioEngine.ERR_USB_IO -> {
                            // USB 传输丢失/IO 错误：尝试恢复（可能是后台被抢占后的暂时中断）
                            AppLogger.w(TAG, "USB streaming: transport/IO error: $written, attempting recovery...")
                            val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                            if (recovered) {
                                AppLogger.i(TAG, "USB streaming: recovery after transport/IO error succeeded")
                                consecutiveWriteZeros = 0
                                continue
                            }
                            AppLogger.e(TAG, "USB streaming: recovery after transport/IO error failed, stopping")
                            isPlaying.set(false)
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_INITIALIZED -> {
                            // handle 突然失效，尝试一次 reinit + restart
                            AppLogger.w(TAG, "USB streaming: ERR_NOT_INITIALIZED, attempting recovery...")
                            val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                            if (recovered) {
                                AppLogger.i(TAG, "USB streaming: recovery succeeded, resuming write")
                                consecutiveWriteZeros = 0
                                continue
                            }
                            AppLogger.e(TAG, "USB streaming: recovery failed, stopping")
                            isPlaying.set(false)
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_RUNNING -> {
                            // 引擎停止（后台被抢占）：尝试恢复而非直接退出
                            AppLogger.w(TAG, "USB streaming: engine stopped (ERR_NOT_RUNNING), attempting recovery...")
                            val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                            if (recovered) {
                                AppLogger.i(TAG, "USB streaming: recovery after ERR_NOT_RUNNING succeeded")
                                consecutiveWriteZeros = 0
                                continue
                            }
                            AppLogger.e(TAG, "USB streaming: recovery after ERR_NOT_RUNNING failed, stopping")
                            isPlaying.set(false)
                            break
                        }
                        written == -32 -> {
                            AppLogger.w(TAG, "USB streaming: EPIPE, exiting")
                            isPlaying.set(false)
                            break
                        }
                        else -> {
                            AppLogger.e(TAG, "USB streaming: write failed: $written")
                            isPlaying.set(false)
                            break
                        }
                    }
                }

                if (restartNativeAfterSeekFlush && writeOffset > 0 && !engine.isRunning()) {
                    AppLogger.w(TAG, "USB seek flush: restarting native stream after first new chunk, nativeBuffered=${engine.nativeGetBufferUsedBytes()}")
                    val restarted = startUsbEngineWithSafety("usb_seek_restart")
                    if (restarted) {
                        restartNativeAfterSeekFlush = false
                        armUsbPostStartVolumeRestore("usb_seek_restart")
                        onUsbPlaybackStarted?.invoke()
                        AppLogger.w(TAG, "USB seek flush: native stream restarted")
                    } else {
                        AppLogger.e(TAG, "USB seek flush: native restart failed")
                        val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                        if (recovered) {
                            restartNativeAfterSeekFlush = false
                            armUsbPostStartVolumeRestore("usb_seek_recovery_restart")
                            onUsbPlaybackStarted?.invoke()
                            AppLogger.i(TAG, "USB seek flush: recovery restart succeeded")
                        } else {
                            isPlaying.set(false)
                            break
                        }
                    }
                }

                // Skip position update if a seek flush is pending — prevents overwriting seekTarget
                // with stale bytesReadTotal (same pattern as AudioTrack path line 1448)
                if (!needsAudioTrackFlush.get()) {
                    _positionMs = (bytesReadTotal.toDouble() / (expectedBytesPerSec.toDouble() / 1000.0)).toLong().coerceToDuration()
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        listener?.onPositionChanged(_positionMs, _durationMs)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastThroughputTime >= 5000) {
                    val elapsed = (now - lastThroughputTime).toDouble() / 1000.0
                    val readRate = ((bytesReadTotal - lastBytesRead) / elapsed).toInt()
                    val writeRate = ((bytesWrittenTotal - lastBytesWritten) / elapsed).toInt()
                    val bufferUsed = engine.getBufferUsedBytes()
                    AppLogger.i(TAG, """USB Streaming Stats:
  sourceRate=$wavSampleRate targetRate=$actualSampleRate pcmFrameSize=$sourcePcmFrameSize
  expectedBytesPerSec=$expectedBytesPerSec readChunk=$maxRead transferCapacity=$transferCapacityBytes
  decoded=${readRate}B/s submitted=${writeRate}B/s completedUsb=${engine.getCompletedUsbBytesPerSecond()}B/s
  nativeBuffered=$bufferUsed ringBuffer=${rb.available()}B""".trimIndent())
                    lastThroughputTime = now
                    lastBytesRead = bytesReadTotal
                    lastBytesWritten = bytesWrittenTotal
                    lastBytesNativeAccepted = bytesNativeAcceptedTotal
                }
            }
        } catch (_: InterruptedException) {
            lifecycleInterrupted = true
            AppLogger.i(
                TAG,
                "USB streaming playback interrupted for lifecycle replacement: source=$sourcePath gen=$generation"
            )
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "USB streaming playback EXCEPTION", e)
            }
        } finally {
            if (!lifecycleInterrupted && isPlaying.get() && isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                isPlaying.set(false)
                if (_state == State.PLAYING) {
                    setState(State.PAUSED)
                }
            }
            AppLogger.w(TAG, "USB streaming playback END, isPlaying=${isPlaying.get()}, state=$_state")
        }
    }

    private fun startUsbExclusivePlayback(startByteOffset: Long, playPath: String, sampleRate: Int, generation: Int, isSeek: Boolean = false, sourcePath: String) {
        android.os.Process.setThreadPriority(-18)

        if (
            abortPlaybackStageIfObsolete(
                sourcePath,
                generation,
                stage = "usb_exclusive_before_prepare",
                closeRingBuffer = true,
                closeDecoderHandle = true
            )
        ) {
            return
        }

        val file = tempWavFile ?: run {
            AppLogger.e(TAG, "startUsbExclusivePlayback: tempWavFile is null, aborting")
            return
        }
        if (!usbExclusiveMode) {
            AppLogger.e(TAG, "startUsbExclusivePlayback: usbExclusiveMode=false, aborting")
            return
        }
        val engine = UsbAudioEngine

        val actualSampleRate = sampleRate

        AppLogger.i(TAG, "========== Audio Parameters ==========")
        AppLogger.i(TAG, "Source audio:")
        AppLogger.i(TAG, "  sampleRate=$wavSampleRate")
        AppLogger.i(TAG, "  channels=$wavChannels")
        AppLogger.i(TAG, "  bitDepth=$wavBitsPerSample")
        AppLogger.i(TAG, "  duration=${_durationMs}ms")
        AppLogger.i(TAG, "  dataOffset=$wavDataOffset (0=raw PCM, >0=WAV header)")
        AppLogger.i(TAG, "Decoder output:")
        AppLogger.i(TAG, "  sampleFormat=${when { wavBitsPerSample <= 16 -> "S16LE"; wavBitsPerSample <= 24 -> "S24LE"; else -> "S32LE" }} (after FFmpeg transcode)")
        val actualBytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
        AppLogger.i(TAG, "  bytesPerFrame=${wavChannels * actualBytesPerSample}")
        AppLogger.i(TAG, "  bytesPerSec=${wavSampleRate * wavChannels * actualBytesPerSample}")
        AppLogger.i(TAG, "USB output target:")
        val usbFrameSize = wavChannels * actualBytesPerSample
        val usbBytesPerSec = actualSampleRate.toLong() * usbFrameSize.toLong()
        AppLogger.i(TAG, "  sampleRate=$actualSampleRate")
        AppLogger.i(TAG, "  channels=$wavChannels")
        AppLogger.i(TAG, "  bits=$wavBitsPerSample")
        AppLogger.i(TAG, "  frameSize=$usbFrameSize")
        AppLogger.i(TAG, "  requiredBytesPerSec=$usbBytesPerSec")
        AppLogger.i(TAG, "  init=${UsbAudioEngine.isInitialized()}, running=${UsbAudioEngine.isRunning()}")
        AppLogger.i(TAG, "  isSeek=$isSeek")
        AppLogger.i(TAG, "=======================================")

        bytesReadTotal = 0L
        bytesWrittenTotal = 0L
        bytesNativeAcceptedTotal = 0L
        lastThroughputTime = System.currentTimeMillis()
        lastBytesRead = 0L
        lastBytesWritten = 0L
        lastBytesNativeAccepted = 0L
        nativeWriteCallCount = 0L
        pumpReadCount = 0L

        val reuseEngineForNext = usbReuseEngineForNextStart &&
            engine.isInitialized() && engine.isRunning() && !engine.isPolicyChangedSinceInit()
        val skipReinit = (isSeek || reuseEngineForNext) && engine.isInitialized() && engine.isRunning()
        if (skipReinit) {
            if (reuseEngineForNext) {
                AppLogger.i(TAG, "NEXT: USB already initialized/running with same runtime, skipping prepare/reinit once")
            } else {
                AppLogger.i(TAG, "SEEK: USB already initialized and running, skipping reinit")
            }
        } else {
            if (usbReuseEngineForNextStart) {
                AppLogger.w(TAG, "NEXT: reuse requested but not safe, falling back to prepare/reinit")
            }
            val prepareOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: true
            if (!prepareOk) {
                usbReuseEngineForNextStart = false
                isPlaying.set(false)
                AppLogger.e(TAG, "USB prepareForPlayback failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 设备准备失败")
                }
                return
            }
        }

        usbReuseEngineForNextStart = false

        if (!isStillCurrentPlayback(sourcePath, generation)) {
            AppLogger.w(TAG, "=== Song changed after USB prepare, aborting")
            return
        }

        if (!engine.isInitialized()) {
            isPlaying.set(false)
            AppLogger.e(TAG, "USB engine not initialized after prepareForPlayback")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 引擎初始化失败")
            }
            return
        }

        val pcmFrameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
        val sourceBytesPerSec = wavSampleRate.toLong() * pcmFrameSize.toLong()
        val expectedBytesPerSec = actualSampleRate.toLong() * pcmFrameSize.toLong()
        val deviceBytesPerSecForPrefill = usbDeviceBytesPerSecond(engine, expectedBytesPerSec)
        val prefillTargetBytes = PlaybackBufferMath.bytesForDuration(deviceBytesPerSecForPrefill, USB_NATIVE_PREFILL_TARGET_MS)
        val prefillTimeoutMs = USB_NATIVE_PREFILL_TIMEOUT_MS
        val minAcceptableBytes = PlaybackBufferMath.bytesForDuration(deviceBytesPerSecForPrefill, USB_NATIVE_PREFILL_MIN_MS)

        AppLogger.i(TAG, "=== PREFILL START ===")
        AppLogger.i(TAG, "  target=${prefillTargetBytes}B (${USB_NATIVE_PREFILL_TARGET_MS}ms) min=${minAcceptableBytes}B sourceBytesPerSec=$sourceBytesPerSec deviceBytesPerSec=$deviceBytesPerSecForPrefill timeout=${prefillTimeoutMs}ms")
        val bufferBefore = UsbAudioEngine.getBufferUsedBytes()
        AppLogger.i(TAG, "  buffer before prefill: used=$bufferBefore")

        var fis: FileInputStream? = null
        var buffer: ByteArray? = null
        var totalBytesRead = startByteOffset
        val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
        require(frameSize > 0) { "Invalid USB PCM frameSize: channels=$wavChannels bits=$wavBitsPerSample" }
        val bufferFrames = 2048
        val usbPcmBufferSize = frameSize * bufferFrames
        AppLogger.i(TAG, "USB PCM: frameSize=$frameSize, bufferSize=$usbPcmBufferSize ($bufferFrames frames)")
        val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

        try {
            fis = FileInputStream(file)
            buffer = ByteArray(usbPcmBufferSize)
            val skipTarget = wavDataOffset + startByteOffset
            var bytesSkipped = 0L
            AppLogger.i(TAG, "Skip check: wavDataOffset=$wavDataOffset startByteOffset=$startByteOffset skipTarget=$skipTarget bytesSkipped=$bytesSkipped")
            while (bytesSkipped < skipTarget) {
                val skipped = fis.skip(skipTarget - bytesSkipped)
                if (skipped <= 0) {
                    val dummy = ByteArray(minOf(4096, (skipTarget - bytesSkipped).toInt()))
                    val r = fis.read(dummy)
                    if (r <= 0) {
                        AppLogger.e(TAG, "Skip failed at $bytesSkipped/$skipTarget")
                        return
                    }
                    bytesSkipped += r
                } else {
                    bytesSkipped += skipped
                }
            }
            AppLogger.i(TAG, "Skip done: $bytesSkipped bytes skipped to PCM data start")

            var prefillStartTime = System.currentTimeMillis()
            var prefillBytesWritten = 0
            var prefillFailed = false
            var consecutiveWriteZeros = 0
            val maxConsecutiveWriteZeros = 100

            while (isPlaying.get() && !isReleased.get() && engine.getBufferUsedBytes() < prefillTargetBytes) {
                if (System.currentTimeMillis() - prefillStartTime > prefillTimeoutMs) {
                    AppLogger.w(TAG, "Pre-fill timeout: native=${engine.getBufferUsedBytes()}/${prefillTargetBytes}B sourceConsumed=${prefillBytesWritten}B")
                    break
                }

                val elapsed = System.currentTimeMillis() - prefillStartTime
                val buffered = engine.getBufferUsedBytes()
                val progress = if (prefillTargetBytes > 0) (buffered * 100 / prefillTargetBytes) else 0
                if (elapsed % 500 < 20) {
                    AppLogger.i(TAG, "Pre-fill: ${buffered}B/${prefillTargetBytes}B ($progress%)")
                }

                val toRead = buffer.size
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) {
                    AppLogger.w(TAG, "Pre-fill EOF during pre-fill")
                    break
                }

                val alignedBytes = read - (read % frameSize)
                if (alignedBytes <= 0) {
                    AppLogger.w(TAG, "Dropping non-frame-aligned tail: read=$read frameSize=$frameSize")
                    continue
                }
                if (alignedBytes != read) {
                    AppLogger.w(TAG, "Trim prefill to frame boundary: read=$read aligned=$alignedBytes dropped=${read - alignedBytes}")
                }

                processDsp(buffer, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)

                var bytesToWrite: Int = alignedBytes
                var writeOffset: Int = 0
                while (bytesToWrite > 0 && isPlaying.get() && !isReleased.get()) {
                    val written: Int = engine.write(buffer, writeOffset, bytesToWrite)
                    when {
                        written > 0 -> {
                            consecutiveWriteZeros = 0
                            writeOffset += written
                            bytesToWrite -= written
                            prefillBytesWritten += written
                            bytesNativeAcceptedTotal += written.toLong()
                        }
                        written == 0 -> {
                            consecutiveWriteZeros++
                            if (consecutiveWriteZeros >= maxConsecutiveWriteZeros) {
                                AppLogger.e(TAG, "Pre-fill write returned 0 too many times ($consecutiveWriteZeros), aborting")
                                prefillFailed = true
                                break
                            }
                            LockSupport.parkNanos(1_000_000L)
                        }
                        else -> {
                            AppLogger.e(TAG, "Pre-fill write error: $written")
                            prefillFailed = true
                            break
                        }
                    }
                }
                if (prefillFailed) break
            }

            if (prefillFailed) {
                isPlaying.set(false)
                AppLogger.e(TAG, "=== PREFILL FAILED ===")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 预填写入失败")
                }
                return
            }

            val bufferAfter = engine.getBufferUsedBytes()
            AppLogger.i(TAG, "=== PREFILL END ===")
            AppLogger.i(TAG, "  source consumed=${prefillBytesWritten}B")
            AppLogger.i(TAG, "  buffer before=$bufferBefore after=$bufferAfter")
            val bufferGrowth = bufferAfter - bufferBefore
            AppLogger.i(TAG, "  buffer growth=${bufferGrowth}B (net bytes written to native ring)")

            if (bufferAfter < minAcceptableBytes) {
                val ms = bufferAfter.toLong() * 1000L / deviceBytesPerSecForPrefill
                AppLogger.w(TAG, "=== PREFILL BELOW MIN, starting anyway: ${bufferAfter}B (${ms}ms) < ${minAcceptableBytes}B (${USB_NATIVE_PREFILL_MIN_MS}ms)")
            }

            if (!isStillCurrentPlayback(sourcePath, generation)) {
                AppLogger.w(TAG, "=== Song changed after prefill, aborting")
                return
            }

            // Serial check: reject if stop/reconfigure happened during prefill
            val serial = nextUsbPlaybackSerial()
            if (!isUsbPlaybackSerialCurrent(serial)) {
                AppLogger.w(TAG, "=== nativeStart aborted: stale serial=$serial ===")
                isPlaying.set(false)
                return
            }

            AppLogger.i(TAG, "=== CALL nativeStart serial=$serial ===")

            // 等待 native ring 中有足够真实 PCM 数据再 start，防止 DAC FIFO 饿空
            val prefillMinMs = if (com.rawsmusic.module.player.PlayerController.getInstanceOrNull()?.isUsbCriticalStartup() == true) 220 else 120
            val deviceBps = UsbAudioEngine.getOutputBytesPerSecond().takeIf { it > 0 } ?: 576000
            val needBytes = deviceBps * prefillMinMs / 1000
            val prefillDeadline = SystemClock.elapsedRealtime() + 1500
            while (SystemClock.elapsedRealtime() < prefillDeadline) {
                val buffered = UsbAudioEngine.getBufferUsedBytes()
                if (buffered >= needBytes) {
                    AppLogger.i(TAG, "USB prefill ready: buffered=$buffered need=$needBytes minMs=$prefillMinMs")
                    break
                }
                Thread.sleep(8)
            }
            val finalBuffered = UsbAudioEngine.getBufferUsedBytes()
            if (finalBuffered < needBytes) {
                AppLogger.w(TAG, "USB prefill timeout: buffered=$finalBuffered need=$needBytes — starting anyway")
            }

            // nativeStart 前仅执行软件侧安全准备；硬件 Feature Unit 保持当前设备值。
            if (
                abortPlaybackStageIfObsolete(
                    sourcePath,
                    generation,
                    stage = "usb_exclusive_before_native_start",
                    closeRingBuffer = true
                )
            ) {
                return
            }

            if (!startUsbEngineWithSafety("usb_exclusive_initial_start")) {
                val policyChanged = engine.isPolicyChangedSinceInit()
                AppLogger.w(
                    TAG,
                    "=== nativeStart failed, attempting full reinit via prepareForPlayback (policyChanged=$policyChanged) ==="
                )
                engine.release()
                val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: false
                if (reinitOk && engine.isInitialized()) {
                    AppLogger.i(TAG, "=== Reinit OK, retrying start ===")
                    if (!startUsbEngineWithSafety("usb_exclusive_reinit_start")) {
                        isPlaying.set(false)
                        AppLogger.e(TAG, "=== nativeStart FAILED after reinit ===")
                        if (isStillCurrentPlayback(sourcePath, generation)) {
                            setState(State.ERROR)
                            listener?.onError("USB 音频流启动失败（重试后）")
                        }
                        return
                    }
                    AppLogger.i(TAG, "=== nativeStart OK after reinit ===")
                } else {
                    isPlaying.set(false)
                    AppLogger.e(TAG, "=== Reinit FAILED via prepareForPlayback ===")
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        listener?.onError("USB 音频引擎重新初始化失败")
                    }
                    return
                }
            }
            armUsbPostStartVolumeRestore("usb_exclusive_native_start")
            onUsbPlaybackStarted?.invoke()
            AppLogger.i(TAG, "=== nativeStart OK ===")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            lastThroughputTime = System.currentTimeMillis()
            lastBytesRead = 0L
            lastBytesWritten = 0L
            lastBytesNativeAccepted = 0L
            nativeWriteCallCount = 0L
            pumpReadCount = 0L
            var pumpConsecutiveWriteZeros = 0
            val pumpMaxConsecutiveWriteZeros = 200

            // Water mark thresholds must use the DEVICE output byte rate (not source PCM rate),
            // because nativeGetBufferUsedBytes() returns ring buffer usage in device output bytes.
            val deviceBytesPerSec = usbDeviceBytesPerSecond(engine, expectedBytesPerSec)
                    val lowWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, USB_NATIVE_LOW_WATER_MS)
                    val targetWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, USB_NATIVE_TARGET_WATER_MS)
                    val highWater = PlaybackBufferMath.bytesForDuration(deviceBytesPerSec, USB_NATIVE_HIGH_WATER_MS)
            AppLogger.i(TAG, "USB legacy watermarks: deviceBytesPerSec=$deviceBytesPerSec sourceBytesPerSec=$sourceBytesPerSec low=$lowWater target=$targetWater high=$highWater")

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "=== USB: Song changed during playback, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val bufferedBeforeRead = engine.nativeGetBufferUsedBytes()
                if (bufferedBeforeRead > highWater) {
                    LockSupport.parkNanos(2_000_000L)
                    continue
                }

                val buf = buffer ?: break
                val read = fis.read(buf)
                if (read <= 0) {
                    AppLogger.w(TAG, "=== USB: EOF")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val alignedBytes = read - (read % frameSize)
                if (alignedBytes <= 0) {
                    AppLogger.w(TAG, "Dropping non-frame-aligned tail: read=$read frameSize=$frameSize")
                    continue
                }

                totalBytesRead += alignedBytes
                bytesReadTotal += alignedBytes

                processDsp(buf, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)
                dispatchWaveformFrame(buf, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)

                var writeOffset = 0
                while (writeOffset < alignedBytes && isPlaying.get() && !isReleased.get()) {
                    nativeWriteCallCount++
                    val remaining = alignedBytes - writeOffset
                    val written = engine.write(buf, writeOffset, remaining)
                    when {
                        written > 0 -> {
                            pumpConsecutiveWriteZeros = 0
                            val alignedWritten = written - (written % frameSize)
                            if (alignedWritten <= 0) {
                                AppLogger.w(TAG, "USB nativeWrite returned non-frame bytes: written=$written frameSize=$frameSize")
                                break
                            }
                            writeOffset += alignedWritten
                            bytesWrittenTotal += written
                            bytesNativeAcceptedTotal += written

                            val nowBuffered = engine.nativeGetBufferUsedBytes()
                            if (engine.isRunning()) {
                                maybeRestoreUsbVolumeRoute("usb_exclusive_write_loop", nowBuffered)
                            }
                            if (nowBuffered >= targetWater) {
                                LockSupport.parkNanos(1_000_000L)
                            }
                        }
                        written == 0 -> {
                            pumpConsecutiveWriteZeros++
                            if (pumpConsecutiveWriteZeros >= pumpMaxConsecutiveWriteZeros) {
                                AppLogger.e(TAG, "USB nativeWrite returned 0 too many times ($pumpConsecutiveWriteZeros), stopping playback")
                                isPlaying.set(false)
                                break
                            }
                            LockSupport.parkNanos(5_000_000L)
                        }
                        written == UsbAudioEngine.ERR_TRANSPORT_LOST ||
                        written == UsbAudioEngine.ERR_USB_IO -> {
                            AppLogger.e(TAG, "USB transport lost during playback: $written")
                            isPlaying.set(false)
                            onUsbTransportLost?.invoke()
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_RUNNING -> {
                            AppLogger.e(TAG, "USB engine stopped during playback")
                            isPlaying.set(false)
                            break
                        }
                        written == -32 -> {
                            AppLogger.w(TAG, "USB nativeWrite returned -EPIPE, writer exiting")
                            isPlaying.set(false)
                            break
                        }
                        else -> {
                            AppLogger.e(TAG, "USB write failed: $written")
                            isPlaying.set(false)
                            break
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastThroughputTime >= 1000) {
                    val elapsed = (now - lastThroughputTime).toDouble() / 1000.0
                    val readRate = ((bytesReadTotal - lastBytesRead) / elapsed).toInt()
                    val writeRate = ((bytesWrittenTotal - lastBytesWritten) / elapsed).toInt()
                    val nativeAcceptedRate = ((bytesNativeAcceptedTotal - lastBytesNativeAccepted) / elapsed).toInt()
                    val bufferUsed = engine.getBufferUsedBytes()
                    val expectedRate = actualSampleRate.toLong() * pcmFrameSize.toLong()

                    AppLogger.i(TAG, """PCM Pump Stats:
  sourceRate=$wavSampleRate targetRate=$actualSampleRate pcmFrameSize=$pcmFrameSize
  expectedBytesPerSec=$expectedRate
  decoded=${readRate}B/s submitted=${writeRate}B/s nativeAccepted=${nativeAcceptedRate}B/s
  nativeBuffered=$bufferUsed nativeWriteCalls=$nativeWriteCallCount
  decoderEof=false""".trimIndent())
                    lastThroughputTime = now
                    lastBytesRead = bytesReadTotal
                    lastBytesWritten = bytesWrittenTotal
                    lastBytesNativeAccepted = bytesNativeAcceptedTotal
                    nativeWriteCallCount = 0
                }

                _positionMs = (totalBytesRead.toDouble() / bytesPerMs).toLong().coerceAtLeast(0L)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "=== USB playback EXCEPTION", e)
            }
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            // 不在 playback loop finally 里 hard stop USB。
            // USB 生命周期由 PlayerController / UsbExclusiveManager 控制。
            AppLogger.i(TAG, "USB playback loop finally: keep USB engine alive")
            onUsbPlaybackStopped?.invoke()
            AppLogger.i(TAG, "=== USB playback END")
        }
    }

    private fun startPlaybackFromOffset(startByteOffset: Long, playPath: String, generation: Int, isSeek: Boolean = false, sourcePath: String = playPath) {
        val file = tempWavFile ?: return

        if (!isStillCurrentPlayback(sourcePath, generation)) {
            AppLogger.w(TAG, "=== Song changed before playback start, aborting: reqGen=$generation currentGen=${playbackSession.generation} currentSource=${playbackSession.sessionSourcePath}")
            return
        }

        isPlaying.set(true)
        AppLogger.w(TAG, "=== startPlaybackFromOffset($startByteOffset), path=$playPath, usbExclusive=$usbExclusiveMode, isSeek=$isSeek")

        // SCO 模式处理：强制单声道 + 16BIT + 合适采样率
        val useSco = AudioOutputManager.shouldUseScoMode(context)
        val am2 = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val scoActive2 = am2?.isBluetoothScoOn == true
        val spec = androidAudioTrackFactory.buildSpec(
            wavSampleRate = wavSampleRate,
            wavChannels = wavChannels,
            probedEncoding = probedEncoding,
            useSco = useSco,
            scoActive = scoActive2,
            usbExclusiveMode = usbExclusiveMode,
            wavBitsPerSample = wavBitsPerSample,
            applyScoDownsample = true,
            scoDownsampleEnabled = AppPreferences.Player.bluetoothScoDownsample
        )
        val useScoAttributes = spec.useScoAttributes
        val channelConfig = spec.channelConfig
        val actualSampleRate = spec.sampleRate
        val actualEncoding = spec.encoding
        val bufSize = spec.bufferSizeInBytes
        AppLogger.i(TAG, "AudioTrack encoding: actualEncoding=$actualEncoding, wavBits=$wavBitsPerSample, wavFormatTag=$wavFormatTag, sco=$useSco, scoActive=$scoActive2, useScoAttrs=$useScoAttributes")

        if (usbExclusiveMode) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                var speakerDevice: android.media.AudioDeviceInfo? = null
                for (device in devices) {
                    if (device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerDevice = device
                        break
                    }
                }
                val existingTrack = audioTrack
                if (existingTrack != null && speakerDevice != null && android.os.Build.VERSION.SDK_INT >= 23) {
                    existingTrack.preferredDevice = speakerDevice
                    AppLogger.i(TAG, "USB Exclusive: Forced AudioTrack to phone speaker")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to route AudioTrack to speaker", e)
            }

            startUsbExclusivePlayback(startByteOffset, playPath, actualSampleRate, generation, isSeek, sourcePath)
            return
        }

        try {
            val attributes = spec.audioAttributes

            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            androidAudioRouteController.applyPreferredDeviceToAudioTrack(
                reason = "file_playback_start",
                useScoAttributes = useScoAttributes,
                allowDirectPreferredDevice = !useSco,
                trackOverride = track
            )

            audioTrack = track
            _audioSessionId = track.audioSessionId

            // 记录格式快照
            snapshotTrackFormat(actualSampleRate, channelConfig, actualEncoding)

            setVolume(volume)
            track.play()

            if (startByteOffset > 0) {
                try { track.flush() } catch (_: Exception) {}
            }
            
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            AppLogger.w(TAG, "=== AudioTrack created, sessionId=$_audioSessionId")

        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation)) {
                AppLogger.e(TAG, "AudioTrack creation failed", e)
                setState(State.ERROR)
                onPlaybackError("AudioTrack 创建失败: ${e.message}")
            }
            return
        }

        var fis: FileInputStream? = null
        try {
            try {
                fis = FileInputStream(file)
            } catch (e: java.io.FileNotFoundException) {
                AppLogger.e(TAG, "Audio file not found: $file", e)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("音频文件不存在: ${file.name}")
                }
                return
            }
            val skipTarget = wavDataOffset + startByteOffset
            var bytesSkipped = 0L
            while (bytesSkipped < skipTarget) {
                val skipped = fis.skip(skipTarget - bytesSkipped)
                if (skipped <= 0) {
                    val dummy = ByteArray(minOf(4096, (skipTarget - bytesSkipped).toInt()))
                    val r = fis.read(dummy)
                    if (r <= 0) {
                        AppLogger.e(TAG, "Skip failed at $bytesSkipped/$skipTarget")
                        return
                    }
                    bytesSkipped += r
                } else {
                    bytesSkipped += skipped
                }
            }

            val buffer = ByteArray(PCM_BUFFER_SIZE)
            var totalBytesRead = startByteOffset
            var totalBytesWrittenToTrack = 0L
            val frameSize = wavChannels * playbackBytesPerSample()
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val readLimit = PcmFrameAligner.readLimit(buffer.size, frameSize)

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "=== Song changed during playback, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val read = fis.read(buffer, 0, readLimit)
                if (read <= 0) {
                    AppLogger.w(TAG, "=== play EOF, draining AudioTrack...")

                    // Drain: 等待 AudioTrack 播放完内部缓冲区中的剩余数据
                    AudioTrackDrainHelper.drain(
                        track = audioTrack,
                        totalBytesWritten = totalBytesWrittenToTrack,
                        frameSize = frameSize,
                        label = "File playback",
                        isPlaying = { isPlaying.get() },
                        isReleased = { isReleased.get() }
                    )

                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                totalBytesRead += read

                val processedRead = processDsp(
                    buffer, read, wavChannels, wavSampleRate, wavBitsPerSample
                )
                if (processedRead <= 0) continue
                dispatchWaveformFrame(
                    buffer, processedRead, wavChannels, wavSampleRate, wavBitsPerSample
                )

                val track = audioTrack
                if (track == null) {
                    AppLogger.w(TAG, "=== play: audioTrack is null, breaking")
                    break
                }
                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    AppLogger.w(TAG, "=== play: Track STOPPED, breaking")
                    break
                }
                if (track.playState == AudioTrack.PLAYSTATE_PAUSED && !isPaused.get()) {
                    AppLogger.w(TAG, "=== play: Track PAUSED externally (not by user), resuming")
                    try { track.play() } catch (_: Exception) {}
                }

                val result = audioTrackPcmWriter.write(
                    track,
                    buffer,
                    0,
                    PcmFrameAligner.alignDown(processedRead, frameSize),
                )
                if (result < 0) {
                    AppLogger.w(TAG, "=== play: write failed=$result, breaking")
                    break
                }
                if (result > 0) totalBytesWrittenToTrack += result

                val posUpdate = audioTrackPositionUpdater.updateAbsolute(
                    currentPositionMs = _positionMs,
                    bytesReadTotal = totalBytesRead,
                    bytesPerMs = bytesPerMs,
                    sampleRate = actualSampleRate,
                    durationMs = _durationMs
                )
                _positionMs = posUpdate.positionMs
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }

            fis.close()
        } catch (e: java.io.FileNotFoundException) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "=== play file not found", e)
                setState(State.ERROR)
                onPlaybackError("音频文件丢失")
            }
        } catch (e: java.io.IOException) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "=== play IO error", e)
                setState(State.ERROR)
                onPlaybackError("文件读取错误（SD卡弹出或文件损坏）")
            }
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "=== play EXCEPTION", e)
                onPlaybackError("播放异常: ${e.message}")
            }
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            AppLogger.w(TAG, "=== play END")
        }
    }
}
