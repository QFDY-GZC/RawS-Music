package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import com.rawsmusic.module.player.dsp.StereoWidenModule
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.usb.UsbAudioEngine

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
    }

    enum class State { IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR, COMPLETED }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onPositionChanged(positionMs: Long, durationMs: Long) {}
        fun onError(message: String) {}
    }

    var listener: Listener? = null

    private var _state = State.IDLE
    val state: State get() = _state

    private var _durationMs = 0L
    val durationMs: Long get() = _durationMs

    private var _positionMs = 0L
    val positionMs: Long get() = _positionMs

    private var _audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    val audioSessionId: Int get() = _audioSessionId

    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FfmpegAudioPlayer-Worker").apply { isDaemon = true }
    }
    private var currentTask: Future<*>? = null

    private var audioTrack: AudioTrack? = null
    private var tempWavFile: File? = null
    private var sourcePath: String? = null
    private var currentPath: String? = null  // 当前正在播放/解码的歌曲路径
    private var resampledPath: String? = null  // 重采样后的实际播放路径（用于seek时传给USB管理器）
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private var seekPositionMs = -1L

    // ========== Streaming decoder (zero-disk AudioTrack mode) ==========
    private var decoderHandle: Long = 0L
    private var ringBuffer: RingBuffer? = null
    @Volatile
    private var decoderThread: Thread? = null

    // ---------- 播放 token 机制，防止快速切歌时旧任务干扰 ----------
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var activeSourcePath: String? = null

    /**
     * 检查当前 generation 是否仍然有效。
     * 用于后台准备完成后判断本次请求是否已被新播放覆盖。
     */
    private fun isStillCurrentPlayback(sourcePath: String, generation: Int): Boolean {
        val currentGen = playGeneration.get()
        val currentSource = activeSourcePath
        val ok = generation == currentGen && currentSource == sourcePath
        if (!ok) {
            Log.w(
                TAG,
                "=== Playback request obsolete, aborting: " +
                    "reqGen=$generation currentGen=$currentGen " +
                    "reqSource=$sourcePath currentSource=$currentSource"
            )
        }
        return ok
    }

    private var wavDataOffset = 0L
    private var wavDataSize = 0L
    var wavSampleRate = 48000; private set
    var wavChannels = 2; private set
    var wavBitsPerSample = 16; private set
    private var wavFormatTag = 1  // 1=PCM, 3=IEEE_FLOAT

    /**
     * 缓存 AudioTrack 模式下探测到的设备最佳编码。
     * 由 convertToWavWithRate() 设置，startPlaybackFromOffset() 消费。
     * 避免重复探测，同时确保 WAV 格式与 AudioTrack 编码一致。
     */
    @Volatile
    private var probedEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    // ========== 安全模式：连续错误后自动降级 ==========
    private var consecutiveErrors = 0
    private val maxErrorsBeforeSafeMode = 3
    @Volatile
    private var safeMode = false

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

    private var volume = 1.0f

    @Volatile
    var stereoWidenFactor: Float = 0f
        set(value) {
            field = value
            stereoWidenModule.factor = value
            nativeDspEngine?.setStereoWiden(value)
        }

    private val stereoWidenModule = StereoWidenModule()
    private var nativeDspEngine: NativeDSPEngine? = null
    private var useNativeDsp = false

    var usbExclusiveMode = false
    var usbBitPerfectMode = false
    var usbActualOutputSampleRate = 0
    var usbPrepareForPlayback: ((sampleRate: Int, bitDepth: Int, channels: Int, srcFilePath: String?) -> Boolean)? = null
    var onUsbTransportLost: (() -> Unit)? = null
    var onUsbPlaybackStarted: (() -> Unit)? = null
    var onUsbPlaybackStopped: (() -> Unit)? = null

    /**
     * 当 USB 授权成功后，如果播放器已经在运行，立即启动 USB streaming。
     * 由 PlayerController.onDeviceReady 调用。
     */
    fun startUsbStreamingIfNeeded() {
        if (usbExclusiveMode && _state == State.PLAYING) {
            Log.i(TAG, "startUsbStreamingIfNeeded: triggering native start")
            try {
                UsbAudioEngine.start()
            } catch (_: Exception) {}
        }
    }

    // 吞吐量统计
    private var bytesReadTotal = 0L
    private var bytesWrittenTotal = 0L
    private var bytesNativeAcceptedTotal = 0L
    private var lastThroughputTime = 0L
    private var lastBytesRead = 0L
    private var lastBytesWritten = 0L
    private var lastBytesNativeAccepted = 0L
    private var nativeWriteCallCount = 0L
    private var pumpReadCount = 0L

    /**
     * USB 独占模式前释放 AudioTrack，防止残余 AudioTrack 继续向 USB 设备写数据
     */
    fun releaseAudioTrackForUsb() {
        val oldTrack = audioTrack
        if (oldTrack != null) {
            audioTrack = null
            try { oldTrack.stop() } catch (_: Exception) {}
            try { oldTrack.release() } catch (_: Exception) {}
            Log.i(TAG, "AudioTrack released for USB exclusive mode")
        }
    }

    fun play(path: String) {
        Log.w(TAG, "=== play() called, path=$path")
        if (isReleased.get()) return

        val generation = playGeneration.incrementAndGet()
        activeSourcePath = path

        isPlaying.set(false)
        isPaused.set(false)
        _positionMs = 0L
        _durationMs = 0L

        // Stop decoder thread
        decoderThread?.interrupt()
        decoderThread = null

        // Close old streaming decoder
        if (decoderHandle != 0L) {
            FFmpegBridge.closeDecoder(decoderHandle)
            decoderHandle = 0L
        }
        ringBuffer?.close()
        ringBuffer = null

        val oldTrack = audioTrack
        audioTrack = null
        oldTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.flush() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }

        currentTask?.cancel(false)
        currentTask = null

        sourcePath = path
        currentPath = path
        setState(State.PREPARING)

        val sourcePath = path
        currentTask = executor.submit {
            try {
                prepareAndStartPlayback(sourcePath = sourcePath, generation = generation)
            } catch (e: InterruptedException) {
                Log.w(TAG, "=== play task interrupted (song switch)")
            } catch (e: Exception) {
                if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                    Log.e(TAG, "play failed", e)
                    setState(State.ERROR)
                    listener?.onError("播放失败: ${e.message}")
                }
            }
        }
    }

    private fun prepareAndStartPlayback(sourcePath: String, generation: Int) {
        Log.d(TAG, "prepareAndStartPlayback: source=$sourcePath gen=$generation")
        _durationMs = probeDuration(sourcePath)
        Log.d(TAG, "probeDuration: ${_durationMs}ms")
        if (!isStillCurrentPlayback(sourcePath, generation)) return

        // ---- 提前确定设备能力，供缓存 key 和转码使用 ----
        var usbTargetSr = 0; var usbTargetBits = 0; var usbTargetCh = 0
        var atTargetRate = 0; var atTargetBits = 0

        if (usbExclusiveMode) {
            val srcSr = FFmpegBridge.probeSampleRate(sourcePath)
            usbTargetSr = if (usbBitPerfectMode) srcSr else selectUsbTargetSampleRate(srcSr)
            val srcBits = FFmpegBridge.probeBitsPerSample(sourcePath)
            val srcCh = FFmpegBridge.probeChannelCount(sourcePath)
            Log.i(TAG, "USB probe: srcSr=$srcSr srcBits=$srcBits srcCh=$srcCh bitPerfect=$usbBitPerfectMode")
            // srcBits=0 表示探测失败（有损格式），强制回退到 16
            val safeSrcBits = if (srcBits > 0) srcBits else 16
            val safeSrcCh = if (srcCh > 0) srcCh else 2
            usbTargetBits = if (usbBitPerfectMode) safeSrcBits else when {
                safeSrcBits <= 16 -> 16
                safeSrcBits <= 24 -> 24
                else -> 32
            }
            usbTargetCh = if (usbBitPerfectMode) safeSrcCh else safeSrcCh.coerceAtLeast(2)
            Log.i(TAG, "USB target: sr=$usbTargetSr bits=$usbTargetBits ch=$usbTargetCh (safeSrcBits=$safeSrcBits safeSrcCh=$safeSrcCh)")
        } else {
            // AudioTrack 模式：暴力探测设备实际支持的最高采样率+最佳编码
            val userTargetRate = AudioOutputManager.getTargetSampleRate()
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val (probedRate, probedEnc) = AudioOutputManager.probeRateAndEncoding(userTargetRate, channelConfig, context)
            atTargetRate = probedRate
            atTargetBits = AudioOutputManager.encodingToFFmpegBits(probedEnc)
            probedEncoding = probedEnc
            Log.i(TAG, "Device capability: rate=$atTargetRate, encoding=$probedEnc -> bits=$atTargetBits")
        }

        if (usbExclusiveMode) {
            // USB 独占模式：流式解码管道（零磁盘 I/O）
            Log.i(TAG, "USB streaming decoder: opening $sourcePath, targetSr=$usbTargetSr, targetBits=$usbTargetBits, targetCh=$usbTargetCh")
            val handle = openDecoderWithFallback(sourcePath, usbTargetSr, usbTargetBits, usbTargetCh)
            if (handle == 0L) {
                Log.e(TAG, "USB streaming decoder: openDecoder failed (all fallbacks)")
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

            // 从解码器获取音频格式
            wavSampleRate = FFmpegBridge.getDecoderSampleRate(handle)
            wavChannels = FFmpegBridge.getDecoderChannels(handle)
            wavBitsPerSample = FFmpegBridge.getDecoderBitsPerSample(handle)
            _durationMs = FFmpegBridge.getDecoderDuration(handle)
            wavDataOffset = 0
            wavFormatTag = 1  // 解码器统一输出 S32LE (PCM)，不是 float
            usbActualOutputSampleRate = wavSampleRate
            Log.i(TAG, "USB streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms")

            // 创建环形缓冲区（3 秒音频数据，USB 需要更大缓冲）
            val bytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 3).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            Log.i(TAG, "USB RingBuffer created: ${ringCapacity} bytes (${ringCapacity * 1000 / bytesPerSec}ms)")

            // 设置播放状态（必须在启动解码线程前）
            isPlaying.set(true)

            // 启动解码线程：FFmpeg decode → ring buffer
            // chunk size 对齐到帧大小的整数倍（~16KB），确保 USB 写入时数据对齐
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4  // 24bit/32bit 都用 S32LE (4B/sample)
            val usbChunkSize = ((16384 / frameSize) * frameSize).coerceAtLeast(frameSize * 256)
            Log.i(TAG, "USB decoder chunk: $usbChunkSize bytes (frameSize=$frameSize)")
            startDecoderThread(sourcePath, generation, usbChunkSize)

            // seek 支持：如果 play() 前有 pending seek
            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                FFmpegBridge.seekDecoder(handle, seekMs)
                rb.clear()
                _positionMs = seekMs
            }

            initDspEngine()
            startUsbStreamingPlayback(sourcePath, generation)
        } else {
            // AudioTrack 模式：流式解码管道（零磁盘 I/O）
            Log.i(TAG, "Streaming decoder: opening $sourcePath, targetRate=$atTargetRate, targetBits=$atTargetBits")
            val handle = openDecoderWithFallback(sourcePath, atTargetRate, atTargetBits, 2)
            if (handle == 0L) {
                Log.e(TAG, "Streaming decoder: openDecoder failed (all fallbacks)")
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

            // 从解码器获取音频格式
            wavSampleRate = FFmpegBridge.getDecoderSampleRate(handle)
            wavChannels = FFmpegBridge.getDecoderChannels(handle)
            wavBitsPerSample = FFmpegBridge.getDecoderBitsPerSample(handle)
            _durationMs = FFmpegBridge.getDecoderDuration(handle)
            wavDataOffset = 0
            wavFormatTag = 1  // 解码器统一输出 S32LE (PCM)，不是 float
            Log.i(TAG, "Streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms")

            // 创建环形缓冲区（2 秒音频数据）
            val bytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 2).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            Log.i(TAG, "RingBuffer created: ${ringCapacity} bytes (${ringCapacity * 1000 / bytesPerSec}ms)")

            // 设置播放状态（必须在启动解码线程前，否则解码线程会因 isPlaying=false 立即退出）
            isPlaying.set(true)

            // 启动解码线程：FFmpeg decode → ring buffer
            startDecoderThread(sourcePath, generation)

            // seek 支持：如果 play() 前有 pending seek
            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                FFmpegBridge.seekDecoder(handle, seekMs)
                rb.clear()
                _positionMs = seekMs
            }

            initDspEngine()
            startStreamingPlayback(sourcePath, generation)
        }
    }

    fun pause() {
        if (_state != State.PLAYING) return
        isPaused.set(true)
        try { audioTrack?.pause() } catch (_: Exception) {}
        if (usbExclusiveMode) {
            Log.i(TAG, "pause(): pausing USB streaming (keeping handle)")
            try { UsbAudioEngine.pause() } catch (_: Exception) {}
        }
        setState(State.PAUSED)
    }

    fun resume() {
        if (_state != State.PAUSED) return
        isPaused.set(false)
        try { audioTrack?.play() } catch (_: Exception) {}
        if (usbExclusiveMode) {
            Log.i(TAG, "resume(): resuming USB streaming")
            val started = try { UsbAudioEngine.start() } catch (_: Exception) { false }
            if (!started) {
                if (UsbAudioEngine.isPolicyChangedSinceInit() == true) {
                    Log.w(TAG, "resume(): policy changed since init, attempting reinit via prepareForPlayback")
                    val ok = usbPrepareForPlayback?.invoke(
                        wavSampleRate, wavBitsPerSample, wavChannels, currentPath
                    ) ?: false
                    if (ok) {
                        try { UsbAudioEngine.start() } catch (_: Exception) {}
                    }
                }
                if (!UsbAudioEngine.isRunning()) {
                    Log.e(TAG, "resume(): USB start failed, remaining paused")
                    setState(State.PAUSED)
                    return
                }
            }
        }
        setState(State.PLAYING)
    }

    fun stop() {
        Log.w(TAG, "=== stop() called")

        isPlaying.set(false)
        isPaused.set(false)
        seekPositionMs = -1L

        // Stop decoder thread
        decoderThread?.interrupt()
        decoderThread = null

        // Close streaming decoder
        if (decoderHandle != 0L) {
            FFmpegBridge.closeDecoder(decoderHandle)
            decoderHandle = 0L
        }
        ringBuffer?.close()
        ringBuffer = null

        currentTask?.cancel(false)
        currentTask = null

        val oldTrack = audioTrack
        audioTrack = null
        oldTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }

        if (usbExclusiveMode) {
            Log.i(TAG, "stop(): stopping USB engine (after isPlaying=false)")
            try { UsbAudioEngine.stop() } catch (_: Exception) {}
        }

        releaseDspEngine()

        setState(State.STOPPED)
    }

    fun seekTo(positionMs: Long) {
        Log.w(TAG, "=== seekTo($positionMs)")

        _positionMs = positionMs
        seekPositionMs = -1L

        if (_state == State.PLAYING || _state == State.PAUSED) {
            // Streaming decoder path (both USB and AudioTrack)
            if (decoderHandle != 0L) {
                // Seek the decoder and clear ring buffer
                val handle = decoderHandle
                val rb = ringBuffer
                if (handle != 0L && rb != null) {
                    // Stop decoder thread temporarily
                    decoderThread?.interrupt()
                    decoderThread = null

                    FFmpegBridge.seekDecoder(handle, positionMs)
                    rb.clear()

                    _positionMs = positionMs

                    // Restart decoder thread
                    val srcPath = sourcePath ?: return
                    val gen = playGeneration.get()
                    if (usbExclusiveMode) {
                        val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4  // 24bit/32bit 都用 S32LE (4B/sample)
                        val usbChunkSize = ((16384 / frameSize) * frameSize).coerceAtLeast(frameSize * 256)
                        startDecoderThread(srcPath, gen, usbChunkSize)
                    } else {
                        startDecoderThread(srcPath, gen)
                    }

                    // Resume playback if paused
                    if (_state == State.PAUSED) {
                        isPaused.set(false)
                        if (usbExclusiveMode) {
                            try { UsbAudioEngine.start() } catch (_: Exception) {}
                        } else {
                            try { audioTrack?.play() } catch (_: Exception) {}
                        }
                        setState(State.PLAYING)
                    }
                    return
                }
            }

            // File-based path (USB mode or fallback)
            if (tempWavFile == null || wavDataSize == 0L) {
                seekPositionMs = positionMs
                return
            }

            // 解码器对 >16bit 统一输出 S32LE (4B/sample)
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val targetByteOffset = (positionMs.toDouble() * bytesPerMs).toLong().coerceIn(0, (wavDataSize - 1).coerceAtLeast(0))
            val alignedOffset = (targetByteOffset / frameSize) * frameSize

            isPlaying.set(false)
            isPaused.set(false)

            val oldTrack = audioTrack
            audioTrack = null
            oldTrack?.let {
                try { it.stop() } catch (_: Exception) {}
                try { it.flush() } catch (_: Exception) {}
                try { it.release() } catch (_: Exception) {}
            }

            setState(State.STOPPED)

            val seekPath = if (usbExclusiveMode) resampledPath ?: currentPath else currentPath
            val originalSourcePath = this.sourcePath ?: currentPath ?: return
            currentTask = executor.submit {
                try {
                    if (isReleased.get()) return@submit
                    val gen = playGeneration.get()
                    startPlaybackFromOffset(alignedOffset, seekPath ?: return@submit, gen, isSeek = true, sourcePath = originalSourcePath)
                } catch (e: Exception) {
                    if (!isReleased.get()) {
                        Log.e(TAG, "seek playback failed", e)
                        setState(State.ERROR)
                    }
                }
            }
        } else {
            seekPositionMs = positionMs
        }
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                audioTrack?.setVolume(volume)
            } else {
                @Suppress("DEPRECATION")
                audioTrack?.setStereoVolume(volume, volume)
            }
        } catch (_: Exception) {}
    }

    fun release() {
        Log.w(TAG, "=== release() called")
        if (isReleased.getAndSet(true)) return

        isPlaying.set(false)
        isPaused.set(false)

        // Stop decoder thread
        decoderThread?.interrupt()
        decoderThread = null

        // Close streaming decoder
        if (decoderHandle != 0L) {
            FFmpegBridge.closeDecoder(decoderHandle)
            decoderHandle = 0L
        }
        ringBuffer?.close()
        ringBuffer = null

        currentTask?.cancel(false)
        currentTask = null
        executor.shutdown()

        // 释放 Track
        val oldTrack = audioTrack
        audioTrack = null
        oldTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }

        if (usbExclusiveMode) {
            try { UsbAudioEngine.release() } catch (_: Exception) {}
            usbExclusiveMode = false
        }

        releaseDspEngine()

        tempWavFile = null
        sourcePath = null
        resampledPath = null
        setState(State.IDLE)
    }

    /**
     * 初始化 DSP 引擎：优先尝试 NativeDSPEngine (C++ JNI)，
     * 失败时回退到 StereoWidenModule (纯 Kotlin)
     */
    private fun initDspEngine() {
        if (nativeDspEngine == null) {
            try {
                val engine = NativeDSPEngine()
                engine.init(wavSampleRate, wavChannels)
                engine.setStereoWiden(stereoWidenFactor)
                nativeDspEngine = engine
                useNativeDsp = true
                Log.d(TAG, "DSP: NativeDSPEngine initialized, sr=$wavSampleRate, ch=$wavChannels")
            } catch (e: Exception) {
                Log.w(TAG, "DSP: NativeDSPEngine init failed, fallback to StereoWidenModule", e)
                nativeDspEngine = null
                useNativeDsp = false
            }
        } else {
            nativeDspEngine?.let {
                try {
                    it.release()
                    it.init(wavSampleRate, wavChannels)
                    it.setStereoWiden(stereoWidenFactor)
                    useNativeDsp = true
                    Log.d(TAG, "DSP: NativeDSPEngine reinitialized, sr=$wavSampleRate, ch=$wavChannels")
                } catch (e: Exception) {
                    Log.w(TAG, "DSP: NativeDSPEngine reinit failed, fallback to StereoWidenModule", e)
                    nativeDspEngine = null
                    useNativeDsp = false
                }
            }
        }
    }

    /**
     * 释放 DSP 引擎资源
     */
    private fun releaseDspEngine() {
        nativeDspEngine?.release()
        nativeDspEngine = null
        useNativeDsp = false
    }

    /**
     * 处理音频缓冲区：根据当前配置选择 Native 或 Kotlin DSP
     */
    @Volatile
    private var dspLogTick = 0L

    private fun processDsp(buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        if (usbExclusiveMode) return
        if (stereoWidenFactor <= 0.01f) return
        if (dspLogTick % 200L == 0L) {
            Log.w(TAG, "DSP: active, factor=$stereoWidenFactor, native=$useNativeDsp, engine=${nativeDspEngine != null}, ch=$channels, bits=$bitsPerSample")
        }
        dspLogTick++
        if (useNativeDsp && nativeDspEngine != null && bitsPerSample == 16) {
            val shortCount = read / 2
            val shortArray = ShortArray(shortCount)
            val bb = java.nio.ByteBuffer.wrap(buffer, 0, read).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().get(shortArray)
            val result = nativeDspEngine!!.process(shortArray, shortCount, channels)
            if (result == 0) {
                bb.position(0)
                bb.asShortBuffer().put(shortArray)
                return
            }
            Log.w(TAG, "DSP: Native process failed (result=$result), fallback to Kotlin")
        }
        stereoWidenModule.process(buffer, read, channels, sampleRate, bitsPerSample)
    }

    private fun getTargetSampleRate(): Int {
        val userRate = AudioOutputManager.getTargetSampleRate()
        return if (userRate > 0) userRate else wavSampleRate.coerceAtLeast(44100)
    }

    private val USB_SUPPORTED_SAMPLE_RATES = intArrayOf(
        44100, 48000, 88200, 96000, 176400, 192000
    )

    fun selectUsbTargetSampleRatePublic(srcSr: Int): Int = selectUsbTargetSampleRate(srcSr)

    private fun selectUsbTargetSampleRate(srcSr: Int): Int {
        if (srcSr <= 0) return 48000
        var best = USB_SUPPORTED_SAMPLE_RATES[0]
        for (r in USB_SUPPORTED_SAMPLE_RATES) {
            if (r >= srcSr) {
                best = r
                break
            }
            best = r
        }
        Log.i(TAG, "selectUsbTargetSampleRate: srcSr=$srcSr -> $best")
        return best
    }

    /**
     * 根据实际 WAV 数据的比特深度和格式返回对应的 AudioFormat 编码
     * 注意：必须与 convertToWav() 输出格式一致
     */
    private fun getEncodingForWavData(): Int {
        return when {
            // Android 8~10 的 ENCODING_PCM_FLOAT 可能被支持但实际写入会失败，强制回退
            wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT in 26..28 ->
                AudioFormat.ENCODING_PCM_16BIT
            // 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
            // Android 不直接支持 S32LE，需转换为 float 给 AudioTrack
            wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT >= 26 ->
                AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

    /**
     * 带降级的 AudioTrack 创建：采样率+编码逐级回退
     * 某些设备声称支持高采样率但实际创建 AudioTrack 会崩溃
     */
    private fun createAudioTrackWithFallback(
        sampleRate: Int,
        channelConfig: Int,
        encoding: Int,
        bufferSize: Int,
        audioAttributes: AudioAttributes
    ): AudioTrack? {
        val safeSampleRate = if (safeMode) 44100 else sampleRate
        val safeEncoding = if (safeMode) AudioFormat.ENCODING_PCM_16BIT else encoding

        val fallbackRates = listOf(safeSampleRate, 48000, 44100, 32000, 24000, 16000).distinct()
        val fallbackEncodings = listOf(safeEncoding, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT).distinct()

        for (rate in fallbackRates) {
            for (enc in fallbackEncodings) {
                try {
                    val minBuf = AudioTrack.getMinBufferSize(rate, channelConfig, enc)
                    val buf = if (minBuf <= 0) bufferSize else maxOf(minBuf, bufferSize)
                    val format = AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(enc)
                        .setChannelMask(channelConfig)
                        .build()
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(buf)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    // 立即测试 play 是否异常
                    track.play()
                    track.pause()
                    Log.i(TAG, "AudioTrack created: rate=$rate encoding=$enc (requested: rate=$sampleRate enc=$encoding)")
                    return track
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack fallback failed: rate=$rate enc=$enc", e)
                }
            }
        }
        Log.e(TAG, "AudioTrack creation failed for all fallback combinations")
        return null
    }

    /**
     * 带降级的解码器打开：目标参数失败时逐级降低
     */
    private fun openDecoderWithFallback(path: String, targetSr: Int, targetBits: Int, targetCh: Int): Long {
        val safeTargetBits = if (safeMode) 16 else targetBits
        val safeTargetSr = if (safeMode) 44100 else targetSr

        val fallbackBits = listOf(safeTargetBits, 16, 24).distinct()
        val fallbackRates = listOf(safeTargetSr, 48000, 44100).distinct()

        for (bits in fallbackBits) {
            for (rate in fallbackRates) {
                val handle = FFmpegBridge.openDecoder(path, rate, bits, targetCh)
                if (handle != 0L) {
                    if (bits != targetBits || rate != targetSr) {
                        Log.w(TAG, "Decoder opened with degraded params: ${rate}Hz/${bits}bit (requested: ${targetSr}Hz/${targetBits}bit)")
                    }
                    return handle
                }
            }
        }
        Log.e(TAG, "Decoder failed for all fallback combinations")
        return 0L
    }

    /**
     * 安全模式错误处理：连续错误后自动降级到最保守配置
     */
    private fun onPlaybackError(msg: String) {
        consecutiveErrors++
        Log.e(TAG, "Playback error #$consecutiveErrors: $msg")
        if (consecutiveErrors >= maxErrorsBeforeSafeMode && !safeMode) {
            safeMode = true
            Log.w(TAG, "=== SAFE MODE ACTIVATED after $consecutiveErrors consecutive errors ===")
        }
        listener?.onError(msg)
    }

    /**
     * 播放成功时重置错误计数
     */
    private fun onPlaybackSuccess() {
        if (consecutiveErrors > 0) {
            Log.i(TAG, "Playback successful, resetting error count (was $consecutiveErrors)")
            consecutiveErrors = 0
        }
        if (safeMode) {
            Log.i(TAG, "=== SAFE MODE DEACTIVATED ===")
            safeMode = false
        }
    }

    private fun setState(state: State) {
        _state = state
        listener?.onStateChanged(state)
    }

    private fun probeDuration(path: String): Long {
        return try {
            FFmpegBridge.probeDuration(path)
        } catch (e: Exception) {
            Log.e(TAG, "FFprobe failed", e)
            0L
        }
    }

    private fun convertToWav(inputPath: String, outputPath: File): Boolean {
        val targetRate = AudioOutputManager.getTargetSampleRate()
        val targetBits = AudioOutputManager.getTargetBitDepth().let { depth ->
            when {
                depth >= 32 && android.os.Build.VERSION.SDK_INT >= 26 -> 32
                depth >= 24 && android.os.Build.VERSION.SDK_INT >= 26 -> 24
                else -> 16
            }
        }
        Log.i(TAG, "convertToWav: targetRate=$targetRate, targetBits=$targetBits")
        val ret = FFmpegBridge.convertToWav(inputPath, outputPath.absolutePath, targetRate, targetBits, 2)
        return ret == 0 && outputPath.exists() && outputPath.length() > 44
    }

    /**
     * 使用已确定的设备支持采样率和比特深度进行转码。
     * targetBits 由 prepareAndStartPlayback 中的设备能力探测决定，
     * 确保 WAV 文件格式与 AudioTrack 编码一致，避免音调异常或写入失败。
     */
    private fun convertToWavWithRate(inputPath: String, outputPath: File, targetRate: Int, targetBits: Int): Boolean {
        Log.i(TAG, "convertToWavWithRate: targetRate=$targetRate, targetBits=$targetBits, usbExclusive=$usbExclusiveMode")
        val ret = FFmpegBridge.convertToWav(inputPath, outputPath.absolutePath, targetRate, targetBits, 2)
        return ret == 0 && outputPath.exists() && outputPath.length() > 44
    }

    /**
     * 转换音频为裸 PCM，无 WAV 头。
     * bitsPerSample: 16->s16le, 24->s24le, 32->s32le
     */
    private fun convertToRawPcm(inputPath: String, outputPath: File, targetSampleRate: Int, bitsPerSample: Int = 16, channels: Int = 2): Boolean {
        Log.i(TAG, "convertToRawPcm: ${targetSampleRate}Hz/${channels}ch/${bitsPerSample}bit, no WAV header")
        val ret = FFmpegBridge.convertToRawPcm(inputPath, outputPath.absolutePath, targetSampleRate, bitsPerSample, channels)
        if (ret != 0 || !outputPath.exists() || outputPath.length() <= 0) {
            Log.e(TAG, "convertToRawPcm failed: ret=$ret exists=${outputPath.exists()} size=${outputPath.length()}")
            return false
        }
        val bytesPerSample = when {
            bitsPerSample <= 16 -> 2
            bitsPerSample <= 24 -> 3
            else -> 4
        }
        val bytesPerSec = targetSampleRate * channels * bytesPerSample
        val durationSecApprox = outputPath.length().toDouble() / bytesPerSec.toDouble()
        Log.i(TAG, "convertToRawPcm done: size=${outputPath.length()} bytes, approx ${"%.1f".format(durationSecApprox)}s")
        return true
    }

    private fun getCacheFile(path: String, atTargetRate: Int = 0, atTargetBits: Int = 0,
                             usbTargetSr: Int = 0, usbTargetBits: Int = 0, usbTargetCh: Int = 0): File {
        val cacheDir = File(context.cacheDir, "ffmpeg_audio")
        cacheDir.mkdirs()
        trimCacheDir(cacheDir, 500L * 1024 * 1024) // 限制缓存 500MB
        if (usbExclusiveMode) {
            val bpTag = if (usbBitPerfectMode) "_bp" else ""
            val hash = (path + "_usb_raw_pcm" + "_r$usbTargetSr" + "_b$usbTargetBits" + "_c$usbTargetCh" + bpTag).hashCode().toString(16)
            return File(cacheDir, "$hash.pcm")
        } else {
            // AudioTrack 模式：使用探测后的实际 rate 和 bits 作为缓存 key
            // 确保设备变更后（如 USB→蓝牙）不会复用不兼容的缓存
            val rateTag = if (atTargetRate > 0) "_r$atTargetRate" else "_orig"
            val bitsTag = "_b$atTargetBits"
            val hash = (path + rateTag + bitsTag).hashCode().toString(16)
            return File(cacheDir, "$hash.wav")
        }
    }

    /** 清理目录使其不超过 maxSize，删除最旧的文件 */
    private fun trimCacheDir(dir: File, maxSize: Long) {
        try {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var totalSize = files.sumOf { it.length() }
            for (file in files) {
                if (totalSize <= maxSize) break
                Log.i(TAG, "Trimming cache: deleting ${file.name} (${file.length()} bytes)")
                totalSize -= file.length()
                file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun parseWavHeader(file: File) {
        try {
            val raf = RandomAccessFile(file, "r")
            try {
                val riff = ByteArray(4)
                raf.read(riff)
                if (String(riff) != "RIFF") return
                raf.skipBytes(4)
                val wave = ByteArray(4)
                raf.read(wave)
                var chunkId = ByteArray(4)
                var chunkSize: Int
                while (raf.filePointer < raf.length() - 8) {
                    raf.read(chunkId)
                    chunkSize = readLittleEndianInt(raf).toInt()
                    val chunkName = String(chunkId)
                    if (chunkName == "fmt ") {
                        wavFormatTag = readLittleEndianShort(raf)
                        wavChannels = readLittleEndianShort(raf)
                        wavSampleRate = readLittleEndianInt(raf).toInt()
                        raf.skipBytes(6)
                        wavBitsPerSample = readLittleEndianShort(raf)
                        if (chunkSize > 16) raf.skipBytes(chunkSize - 16)
                    } else if (chunkName == "data") {
                        wavDataSize = chunkSize.toLong()
                        wavDataOffset = raf.filePointer
                        break
                    } else {
                        raf.skipBytes(chunkSize)
                        if (chunkSize % 2 != 0) raf.skipBytes(1)
                    }
                }
                // 详细日志：确认 WAV 解析结果
                // 注意：解码器对 >16bit 统一输出 S32LE (4B/sample)，WAV 文件也是 4B/sample
                val actualBytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
                val blockAlign = wavChannels * actualBytesPerSample
                val byteRate = wavSampleRate * blockAlign
                val formatName = if (wavFormatTag == 3) "IEEE_FLOAT" else "PCM"
                Log.i(TAG, "WAV parse result: sampleRate=$wavSampleRate channels=$wavChannels " +
                        "bits=$wavBitsPerSample formatTag=$wavFormatTag($formatName) " +
                        "dataOffset=$wavDataOffset dataSize=$wavDataSize " +
                        "byteRate=$byteRate blockAlign=$blockAlign bytesPerSample=$actualBytesPerSample")
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseWavHeader failed", e)
        }
    }

    private fun readLittleEndianInt(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.read(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readLittleEndianShort(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.read(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun startAudioTrackPlayback(sourcePath: String, generation: Int) {
        val file = tempWavFile ?: return
        var startOffset = 0L
        if (seekPositionMs > 0 && wavDataSize > 0L) {
            // 解码器对 >16bit 统一输出 S32LE (4B/sample)
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val targetOffset = (seekPositionMs.toDouble() * bytesPerMs).toLong().coerceIn(0, (wavDataSize - 1).coerceAtLeast(0))
            startOffset = (targetOffset / frameSize) * frameSize
            seekPositionMs = -1L
        }
        // 首次播放时也使用 resampledPath（如果存在）
        val playPath = if (usbExclusiveMode) resampledPath ?: sourcePath else sourcePath
        startPlaybackFromOffset(startOffset, playPath, generation, sourcePath = sourcePath)
    }

    /**
     * 启动解码线程：持续从 FFmpeg 解码器读取 PCM 数据写入 ring buffer。
     * @param decodeChunkSize 解码缓冲区大小（字节）。USB 模式下应为 packetSize 的整数倍。
     */
    private fun startDecoderThread(sourcePath: String, generation: Int, decodeChunkSize: Int = 16384) {
        val handle = decoderHandle
        val rb = ringBuffer
        if (handle == 0L || rb == null) return

        decoderThread = thread(name = "FfmpegDecoder", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val decodeBuffer = ByteArray(decodeChunkSize)

            Log.i(TAG, "Decoder thread started")
            try {
                while (isPlaying.get() && !isReleased.get()) {
                    if (!isStillCurrentPlayback(sourcePath, generation)) {
                        Log.w(TAG, "Decoder thread: song changed, exiting")
                        break
                    }

                    // 检查 ring buffer 是否有足够空间
                    val rbAvailable = rb.available()
                    val rbCapacity = decodeBuffer.size  // use decode buffer size as reference
                    if (rb.isClosed()) break

                    // 从解码器读取
                    val decoded = FFmpegBridge.decodeChunk(handle, decodeBuffer, 0, decodeBuffer.size)
                    when {
                        decoded > 0 -> {
                            // AudioTrack 模式 + >16bit：解码器输出 S32LE，需转换为 float 给 AudioTrack
                            var writeData = decodeBuffer
                            var writeLen = decoded
                            if (!usbExclusiveMode && wavBitsPerSample > 16) {
                                val sampleCount = decoded / 4
                                val floatBuf = ByteArray(sampleCount * 4)
                                val sb = java.nio.ByteBuffer.wrap(decodeBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val fb = java.nio.ByteBuffer.wrap(floatBuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until sampleCount) {
                                    val s32 = sb.getInt(i * 4)
                                    fb.putFloat(i * 4, s32.toFloat() / 2147483648.0f)
                                }
                                writeData = floatBuf
                                writeLen = floatBuf.size
                            }
                            val written = rb.write(writeData, 0, writeLen)
                            if (written < 0) {
                                Log.w(TAG, "Decoder thread: ring buffer closed")
                                break
                            }
                        }
                        decoded == -1 -> {
                            // EOF
                            Log.i(TAG, "Decoder thread: EOF reached")
                            rb.close()
                            break
                        }
                        else -> {
                            // Error
                            Log.e(TAG, "Decoder thread: decode error: $decoded")
                            rb.close()
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Decoder thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Decoder thread fatal error", e)
                // 解码线程异常不应导致应用崩溃，安全停止并通知
                try {
                    isPlaying.set(false)
                    rb.close()
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        onPlaybackError("解码线程异常: ${e.message}")
                    }
                } catch (notifyErr: Exception) {
                    Log.e(TAG, "Error notifying decoder failure", notifyErr)
                }
            } finally {
                Log.i(TAG, "Decoder thread ended")
            }
        }
    }

    /**
     * 流式播放：从 ring buffer 读取 PCM 数据写入 AudioTrack。
     * 替代原来的文件 I/O 播放路径。
     */
    private fun startStreamingPlayback(sourcePath: String, generation: Int) {
        val rb = ringBuffer ?: return

        val channelConfig = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val actualEncoding = probedEncoding
        Log.i(TAG, "Streaming playback: rate=$actualSampleRate, encoding=$actualEncoding, bits=$wavBitsPerSample")

        val bufSize = AudioTrack.getMinBufferSize(actualSampleRate, channelConfig, actualEncoding)
            .coerceAtLeast(PCM_BUFFER_SIZE)

        try {
            val attributes = AudioOutputManager.buildAudioAttributes(context)

            // 防御性降级：AudioTrack 创建失败时逐级降低采样率和编码
            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            // Direct HiRes 模式：设置首选设备（异常安全）
            if (AudioOutputManager.getCurrentOutputMode(context) == com.rawsmusic.core.common.model.AudioOutputMode.DIRECT) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    try {
                        AudioOutputManager.getPreferredDeviceForDirect(context)?.let { device ->
                            track.preferredDevice = device
                            Log.d(TAG, "Direct mode: preferred device set to ${device.productName}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Setting preferred device failed, using default", e)
                    }
                }
            }

            audioTrack = track
            _audioSessionId = track.audioSessionId

            setVolume(volume)
            track.play()

            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            Log.i(TAG, "Streaming AudioTrack created, sessionId=$_audioSessionId")

            // 播放循环：ring buffer → AudioTrack
            val buffer = ByteArray(PCM_BUFFER_SIZE)
            // 解码器对 >16bit 统一输出 S32LE (4B/sample)
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    Log.w(TAG, "Streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }

                // 从 ring buffer 读取
                val read = rb.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // EOF or closed
                    Log.w(TAG, "Streaming: EOF from ring buffer")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                processDsp(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)

                val track2 = audioTrack
                if (track2 == null) {
                    Log.w(TAG, "Streaming: audioTrack is null, breaking")
                    break
                }
                if (track2.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    Log.w(TAG, "Streaming: Track STOPPED, breaking")
                    break
                }

                val result = track2.write(buffer, 0, read)
                if (result < 0) {
                    Log.w(TAG, "Streaming: write failed=$result, breaking")
                    break
                }

                // 更新位置
                _positionMs = ((_positionMs + (read.toDouble() / bytesPerMs))).toLong().coerceIn(0L, _durationMs)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }

        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                Log.e(TAG, "Streaming playback EXCEPTION", e)
            }
        } finally {
            Log.w(TAG, "Streaming playback END")
        }
    }

    /**
     * USB 独占模式流式播放：从 ring buffer 读取 PCM 数据写入 USB 引擎。
     * 完全不经过磁盘，解码器输出直接馈入 USB native buffer。
     */
    private fun startUsbStreamingPlayback(sourcePath: String, generation: Int) {
        val rb = ringBuffer ?: return
        val engine = UsbAudioEngine

        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val pcmFrameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4  // 24bit/32bit 都用 S32LE (4B/sample)
        val expectedBytesPerSec = actualSampleRate * pcmFrameSize
        val packetSize = engine.getPacketSize().let { if (it > 0) it else pcmFrameSize }
        // 读取缓冲区对齐到 packetSize 的整数倍
        val readBufFrames = 2048
        val readBufSize = (pcmFrameSize * readBufFrames).let { size ->
            ((size + packetSize - 1) / packetSize) * packetSize
        }

        Log.i(TAG, "========== USB Streaming Parameters ==========")
        Log.i(TAG, "  sampleRate=$actualSampleRate channels=$wavChannels bits=$wavBitsPerSample")
        Log.i(TAG, "  frameSize=$pcmFrameSize packetSize=$packetSize readBufSize=$readBufSize")
        Log.i(TAG, "  expectedBytesPerSec=$expectedBytesPerSec")
        Log.i(TAG, "  engine init=${engine.isInitialized()}, running=${engine.isRunning()}")
        Log.i(TAG, "===============================================")

        // 初始化统计计数器
        bytesReadTotal = 0L
        bytesWrittenTotal = 0L
        bytesNativeAcceptedTotal = 0L
        lastThroughputTime = System.currentTimeMillis()
        lastBytesRead = 0L
        lastBytesWritten = 0L
        lastBytesNativeAccepted = 0L
        nativeWriteCallCount = 0L

        // 初始化 USB 引擎
        val prepareOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath) ?: true
        if (!prepareOk) {
            isPlaying.set(false)
            Log.e(TAG, "USB streaming: prepareForPlayback failed")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 设备准备失败")
            }
            return
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) return

        if (!engine.isInitialized()) {
            isPlaying.set(false)
            Log.e(TAG, "USB streaming: engine not initialized")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 引擎初始化失败")
            }
            return
        }

        // 预填阶段：等 RingBuffer 积累足够数据再启动 USB
        val prefillTargetMs = 500L  // 500ms
        val prefillTargetBytes = (expectedBytesPerSec * prefillTargetMs / 1000).toInt()
        val prefillTimeoutMs = 5000L
        val minAcceptableBytes = (expectedBytesPerSec * 200 / 1000).toInt()  // 200ms 最低

        Log.i(TAG, "=== USB PREFILL START === target=${prefillTargetBytes}B (${prefillTargetMs}ms)")
        val prefillStart = System.currentTimeMillis()
        while (isPlaying.get() && !isReleased.get()) {
            if (System.currentTimeMillis() - prefillStart > prefillTimeoutMs) {
                Log.w(TAG, "USB prefill timeout")
                break
            }
            val buffered = rb.available()
            if (buffered >= prefillTargetBytes) break
            if (rb.isEof()) break
            Thread.sleep(10)
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) return

        val bufferedBytes = rb.available()
        Log.i(TAG, "=== USB PREFILL END === buffered=${bufferedBytes}B (${bufferedBytes * 1000 / expectedBytesPerSec}ms)")

        if (bufferedBytes < minAcceptableBytes && !rb.isEof()) {
            Log.e(TAG, "USB prefill insufficient: ${bufferedBytes}B < ${minAcceptableBytes}B")
            isPlaying.set(false)
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 预填 buffer 不足")
            }
            return
        }

        // 启动 USB streaming
        if (!engine.start()) {
            if (engine.isPolicyChangedSinceInit()) {
                Log.w(TAG, "USB streaming: policy changed, reinit...")
                engine.release()
                val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath) ?: false
                if (reinitOk && engine.isInitialized()) {
                    if (!engine.start()) {
                        isPlaying.set(false)
                        Log.e(TAG, "USB streaming: start failed after reinit")
                        if (isStillCurrentPlayback(sourcePath, generation)) {
                            setState(State.ERROR)
                            listener?.onError("USB 音频流启动失败")
                        }
                        return
                    }
                } else {
                    isPlaying.set(false)
                    Log.e(TAG, "USB streaming: reinit failed")
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        listener?.onError("USB 引擎重新初始化失败")
                    }
                    return
                }
            } else {
                isPlaying.set(false)
                Log.e(TAG, "USB streaming: start failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 音频流启动失败")
                }
                return
            }
        }
        onUsbPlaybackStarted?.invoke()
        Log.i(TAG, "USB streaming: nativeStart OK")

        if (isStillCurrentPlayback(sourcePath, generation)) {
            setState(State.PLAYING)
        }

        // 播放循环：RingBuffer → USB engine
        val buffer = ByteArray(readBufSize)
        val lowWater = expectedBytesPerSec * 300 / 1000      // 300ms
        val targetWater = expectedBytesPerSec * 500 / 1000   // 500ms
        val highWater = expectedBytesPerSec * 1200 / 1000    // 1.2s
        var consecutiveWriteZeros = 0
        val maxConsecutiveWriteZeros = 200

        try {
            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    Log.w(TAG, "USB streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }

                // 水位检查：USB buffer 过高则等待
                val bufferedBeforeRead = engine.nativeGetBufferUsedBytes()
                if (bufferedBeforeRead > highWater) {
                    java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L)
                    continue
                }

                // 从 RingBuffer 读取（对齐到 packetSize）
                val maxRead = minOf(buffer.size, packetSize * 16)  // 每次最多读 16 个 packet
                val read = rb.read(buffer, 0, maxRead)
                if (read <= 0) {
                    Log.w(TAG, "USB streaming: EOF from ring buffer")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                // 确保写入对齐到 frameSize
                val alignedBytes = read - (read % pcmFrameSize)
                if (alignedBytes <= 0) continue

                bytesReadTotal += alignedBytes

                // DSP 处理
                processDsp(buffer, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)

                // 写入 USB engine
                var writeOffset = 0
                while (writeOffset < alignedBytes && isPlaying.get() && !isReleased.get()) {
                    val remaining = alignedBytes - writeOffset
                    val written = engine.write(buffer, writeOffset, remaining)
                    when {
                        written > 0 -> {
                            consecutiveWriteZeros = 0
                            val alignedWritten = written - (written % pcmFrameSize)
                            if (alignedWritten <= 0) {
                                Log.w(TAG, "USB streaming: non-frame write: $written frameSize=$pcmFrameSize")
                                break
                            }
                            writeOffset += alignedWritten
                            bytesWrittenTotal += written
                            bytesNativeAcceptedTotal += written

                            val nowBuffered = engine.nativeGetBufferUsedBytes()
                            if (nowBuffered >= targetWater) {
                                val durationNs = written * 1_000_000_000L / expectedBytesPerSec
                                java.util.concurrent.locks.LockSupport.parkNanos(durationNs)
                            }
                        }
                        written == 0 -> {
                            consecutiveWriteZeros++
                            if (consecutiveWriteZeros >= maxConsecutiveWriteZeros) {
                                Log.e(TAG, "USB streaming: write returned 0 too many times")
                                isPlaying.set(false)
                                break
                            }
                            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L)
                        }
                        written == UsbAudioEngine.ERR_TRANSPORT_LOST ||
                        written == UsbAudioEngine.ERR_USB_IO -> {
                            Log.e(TAG, "USB streaming: transport lost: $written")
                            isPlaying.set(false)
                            onUsbTransportLost?.invoke()
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_RUNNING -> {
                            Log.e(TAG, "USB streaming: engine stopped")
                            isPlaying.set(false)
                            break
                        }
                        written == -32 -> {
                            Log.w(TAG, "USB streaming: EPIPE, exiting")
                            isPlaying.set(false)
                            break
                        }
                        else -> {
                            Log.e(TAG, "USB streaming: write failed: $written")
                            isPlaying.set(false)
                            break
                        }
                    }
                }

                // 更新位置
                _positionMs = (bytesReadTotal.toDouble() / (expectedBytesPerSec.toDouble() / 1000.0)).toLong().coerceIn(0L, _durationMs)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }

                // 吞吐量统计（每秒）
                val now = System.currentTimeMillis()
                if (now - lastThroughputTime >= 1000) {
                    val elapsed = (now - lastThroughputTime).toDouble() / 1000.0
                    val readRate = ((bytesReadTotal - lastBytesRead) / elapsed).toInt()
                    val writeRate = ((bytesWrittenTotal - lastBytesWritten) / elapsed).toInt()
                    val bufferUsed = engine.getBufferUsedBytes()
                    Log.i(TAG, """USB Streaming Stats:
  sourceRate=$wavSampleRate targetRate=$actualSampleRate pcmFrameSize=$pcmFrameSize
  expectedBytesPerSec=$expectedBytesPerSec packetSize=$packetSize
  decoded=${readRate}B/s submitted=${writeRate}B/s
  nativeBuffered=$bufferUsed ringBuffer=${rb.available()}B""".trimIndent())
                    lastThroughputTime = now
                    lastBytesRead = bytesReadTotal
                    lastBytesWritten = bytesWrittenTotal
                    lastBytesNativeAccepted = bytesNativeAcceptedTotal
                }
            }
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                Log.e(TAG, "USB streaming playback EXCEPTION", e)
            }
        } finally {
            Log.w(TAG, "USB streaming playback END")
        }
    }

    private fun startUsbExclusivePlayback(startByteOffset: Long, playPath: String, sampleRate: Int, generation: Int, isSeek: Boolean = false, sourcePath: String) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val file = tempWavFile ?: run {
            Log.e(TAG, "startUsbExclusivePlayback: tempWavFile is null, aborting")
            return
        }
        if (!usbExclusiveMode) {
            Log.e(TAG, "startUsbExclusivePlayback: usbExclusiveMode=false, aborting")
            return
        }
        val engine = UsbAudioEngine

        val actualSampleRate = sampleRate

        // 打印音频参数详情
        Log.i(TAG, "========== Audio Parameters ==========")
        Log.i(TAG, "Source audio:")
        Log.i(TAG, "  sampleRate=$wavSampleRate")
        Log.i(TAG, "  channels=$wavChannels")
        Log.i(TAG, "  bitDepth=$wavBitsPerSample")
        Log.i(TAG, "  duration=${_durationMs}ms")
        Log.i(TAG, "  dataOffset=$wavDataOffset (0=raw PCM, >0=WAV header)")
        Log.i(TAG, "Decoder output:")
        Log.i(TAG, "  sampleFormat=${when { wavBitsPerSample <= 16 -> "S16LE"; wavBitsPerSample <= 24 -> "S24LE"; else -> "S32LE" }} (after FFmpeg transcode)")
        // 解码器对 >16bit 统一输出 S32LE (4B/sample)
        val actualBytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
        Log.i(TAG, "  bytesPerFrame=${wavChannels * actualBytesPerSample}")
        Log.i(TAG, "  bytesPerSec=${wavSampleRate * wavChannels * actualBytesPerSample}")
        Log.i(TAG, "USB output target:")
        val usbFrameSize = wavChannels * actualBytesPerSample
        val usbBytesPerSec = actualSampleRate * usbFrameSize
        Log.i(TAG, "  sampleRate=$actualSampleRate")
        Log.i(TAG, "  channels=$wavChannels")
        Log.i(TAG, "  bits=$wavBitsPerSample")
        Log.i(TAG, "  frameSize=$usbFrameSize")
        Log.i(TAG, "  requiredBytesPerSec=$usbBytesPerSec")
        Log.i(TAG, "  init=${UsbAudioEngine.isInitialized()}, running=${UsbAudioEngine.isRunning()}")
        Log.i(TAG, "  isSeek=$isSeek")
        Log.i(TAG, "=======================================")

        // 初始化统计计数器
        bytesReadTotal = 0L
        bytesWrittenTotal = 0L
        bytesNativeAcceptedTotal = 0L
        lastThroughputTime = System.currentTimeMillis()
        lastBytesRead = 0L
        lastBytesWritten = 0L
        lastBytesNativeAccepted = 0L
        nativeWriteCallCount = 0L
        pumpReadCount = 0L

        // seek 时如果 USB 已经初始化且格式匹配，跳过重新初始化
        val skipReinit = isSeek && engine.isInitialized() && engine.isRunning()
        if (skipReinit) {
            Log.i(TAG, "SEEK: USB already initialized and running, skipping reinit")
        } else {
            // 通过 UsbExclusiveManager.prepareForPlayback 完成 openDevice + nativeInitUsbDevice
            // Java 不保存 fd，不 claimInterface，不 setInterface
            val prepareOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: true
            if (!prepareOk) {
                isPlaying.set(false)
                Log.e(TAG, "USB prepareForPlayback failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 设备准备失败")
                }
                return
            }
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) {
            Log.w(TAG, "=== Song changed after USB prepare, aborting")
            return
        }

        if (!engine.isInitialized()) {
            isPlaying.set(false)
            Log.e(TAG, "USB engine not initialized after prepareForPlayback")
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 引擎初始化失败")
            }
            return
        }

        // 预填阶段：在 start USB streaming 前先填满 buffer
        val pcmFrameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4  // 24bit/32bit 都用 S32LE (4B/sample)
        val expectedBytesPerSec = actualSampleRate * pcmFrameSize
        val prefillTargetBytes = expectedBytesPerSec / 5  // 200ms 目标，减少启动延迟
        val prefillTimeoutMs = 3000L
        val minAcceptableBytes = expectedBytesPerSec / 5  // 200ms 最低要求

        Log.i(TAG, "=== PREFILL START ===")
        Log.i(TAG, "  target=${prefillTargetBytes}B (200ms) expectedBytesPerSec=$expectedBytesPerSec")
        val bufferBefore = UsbAudioEngine.getBufferUsedBytes()
        Log.i(TAG, "  buffer before prefill: used=$bufferBefore")

        var fis: FileInputStream? = null
        var buffer: ByteArray? = null
        var totalBytesRead = startByteOffset
        // 解码器对 >16bit 统一输出 S32LE (4B/sample)，WAV 文件也是 4B/sample
        val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
        require(frameSize > 0) { "Invalid USB PCM frameSize: channels=$wavChannels bits=$wavBitsPerSample" }
        val bufferFrames = 2048
        val usbPcmBufferSize = frameSize * bufferFrames
        Log.i(TAG, "USB PCM: frameSize=$frameSize, bufferSize=$usbPcmBufferSize ($bufferFrames frames)")
        val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

        try {
            fis = FileInputStream(file)
            buffer = ByteArray(usbPcmBufferSize)
            val skipTarget = wavDataOffset + startByteOffset
            var bytesSkipped = 0L
            Log.i(TAG, "Skip check: wavDataOffset=$wavDataOffset startByteOffset=$startByteOffset skipTarget=$skipTarget bytesSkipped=$bytesSkipped")
            while (bytesSkipped < skipTarget) {
                val skipped = fis.skip(skipTarget - bytesSkipped)
                if (skipped <= 0) {
                    val dummy = ByteArray(minOf(4096, (skipTarget - bytesSkipped).toInt()))
                    val r = fis.read(dummy)
                    if (r <= 0) {
                        Log.e(TAG, "Skip failed at $bytesSkipped/$skipTarget")
                        return
                    }
                    bytesSkipped += r
                } else {
                    bytesSkipped += skipped
                }
            }
            Log.i(TAG, "Skip done: $bytesSkipped bytes skipped to PCM data start")

            var prefillStartTime = System.currentTimeMillis()
            var prefillBytesWritten = 0
            var prefillFailed = false
            var consecutiveWriteZeros = 0
            val maxConsecutiveWriteZeros = 100

            // 预填循环：精确限制到 prefillTargetBytes（200ms），避免单次 read 超限
            while (isPlaying.get() && !isReleased.get() && prefillBytesWritten < prefillTargetBytes) {
                if (System.currentTimeMillis() - prefillStartTime > prefillTimeoutMs) {
                    Log.w(TAG, "Pre-fill timeout: wrote $prefillBytesWritten/${prefillTargetBytes}B")
                    break
                }

                val elapsed = System.currentTimeMillis() - prefillStartTime
                val buffered = engine.getBufferUsedBytes()
                val progress = if (prefillTargetBytes > 0) (buffered * 100 / prefillTargetBytes) else 0
                if (elapsed % 500 < 20) {
                    Log.i(TAG, "Pre-fill: ${buffered}B/${prefillTargetBytes}B ($progress%)")
                }

                val remainingTarget = (prefillTargetBytes - prefillBytesWritten).toInt()
                val toRead = minOf(buffer.size, remainingTarget)
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) {
                    Log.w(TAG, "Pre-fill EOF during pre-fill")
                    break
                }

                val alignedBytes = read - (read % frameSize)
                if (alignedBytes <= 0) {
                    Log.w(TAG, "Dropping non-frame-aligned tail: read=$read frameSize=$frameSize")
                    continue
                }
                if (alignedBytes != read) {
                    Log.w(TAG, "Trim prefill to frame boundary: read=$read aligned=$alignedBytes dropped=${read - alignedBytes}")
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
                                Log.e(TAG, "Pre-fill write returned 0 too many times ($consecutiveWriteZeros), aborting")
                                prefillFailed = true
                                break
                            }
                            Thread.sleep(2)
                        }
                        else -> {
                            Log.e(TAG, "Pre-fill write error: $written")
                            prefillFailed = true
                            break
                        }
                    }
                }
                if (prefillFailed) break
            }

            if (prefillFailed) {
                isPlaying.set(false)
                Log.e(TAG, "=== PREFILL FAILED ===")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 预填写入失败")
                }
                return
            }

            val bufferAfter = engine.getBufferUsedBytes()
            Log.i(TAG, "=== PREFILL END ===")
            Log.i(TAG, "  prefill wrote=${prefillBytesWritten}B")
            Log.i(TAG, "  buffer before=$bufferBefore after=$bufferAfter")
            val bufferGrowth = bufferAfter - bufferBefore
            Log.i(TAG, "  buffer growth=${bufferGrowth}B (net bytes written to native ring)")

            // 检查 buffer 是否足够开始，不足则 abort
            if (bufferAfter < minAcceptableBytes) {
                val ms = bufferAfter * 1000 / expectedBytesPerSec
                Log.e(TAG, "=== PREFILL INSUFFICIENT: only ${bufferAfter}B (${ms}ms), need >= ${minAcceptableBytes}B (200ms)")
                isPlaying.set(false)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 预填 buffer 不足: ${bufferAfter}B (${ms}ms)")
                }
                return
            }

            if (!isStillCurrentPlayback(sourcePath, generation)) {
                Log.w(TAG, "=== Song changed after prefill, aborting")
                return
            }

            // 启动 USB streaming
            Log.i(TAG, "=== CALL nativeStart ===")
            if (!engine.start()) {
                // 策略变更导致 start 失败（-100），需要完整 reinit 后重试
                if (engine.isPolicyChangedSinceInit()) {
                    Log.w(TAG, "=== nativeStart failed due to policy change, attempting full reinit via prepareForPlayback ===")
                    engine.release()
                    val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: false
                    if (reinitOk && engine.isInitialized()) {
                        Log.i(TAG, "=== Reinit OK after policy change, retrying start ===")
                        if (!engine.start()) {
                            isPlaying.set(false)
                            Log.e(TAG, "=== nativeStart FAILED after reinit ===")
                            if (isStillCurrentPlayback(sourcePath, generation)) {
                                setState(State.ERROR)
                                listener?.onError("USB 音频流启动失败（重试后）")
                            }
                            return
                        }
                        Log.i(TAG, "=== nativeStart OK after reinit ===")
                    } else {
                        isPlaying.set(false)
                        Log.e(TAG, "=== Reinit FAILED via prepareForPlayback ===")
                        if (isStillCurrentPlayback(sourcePath, generation)) {
                            setState(State.ERROR)
                            listener?.onError("USB 音频引擎重新初始化失败")
                        }
                        return
                    }
                } else {
                    isPlaying.set(false)
                    Log.e(TAG, "=== nativeStart FAILED ===")
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        listener?.onError("USB 音频流启动失败")
                    }
                    return
                }
            }
            onUsbPlaybackStarted?.invoke()
            Log.i(TAG, "=== nativeStart OK ===")
            Thread.sleep(50)

            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            // 重置统计计数器（预填阶段已计入）
            lastThroughputTime = System.currentTimeMillis()
            lastBytesRead = 0L
            lastBytesWritten = 0L
            lastBytesNativeAccepted = 0L
            nativeWriteCallCount = 0L
            pumpReadCount = 0L
            var pumpConsecutiveWriteZeros = 0
            val pumpMaxConsecutiveWriteZeros = 200

            // 正式播放循环 - 3 级水位控制
            val lowWater = expectedBytesPerSec * 300 / 1000      // 300ms，低于此要尽快补充
            val targetWater = expectedBytesPerSec * 500 / 1000   // 500ms，达到此才 pacing
            val highWater = expectedBytesPerSec * 1200 / 1000    // 1.2s，高于此要等 USB 消费

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    Log.w(TAG, "=== USB: Song changed during playback, breaking")
                    break
                }

                if (isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }

                // 水位检查：buffer 过高则跳过本次 read，等 USB 消费
                val bufferedBeforeRead = engine.nativeGetBufferUsedBytes()
                if (bufferedBeforeRead > highWater) {
                    LockSupport.parkNanos(10_000_000L) // 10ms
                    continue
                }

                val buf = buffer ?: break
                val read = fis.read(buf)
                if (read <= 0) {
                    Log.w(TAG, "=== USB: EOF")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val alignedBytes = read - (read % frameSize)
                if (alignedBytes <= 0) {
                    Log.w(TAG, "Dropping non-frame-aligned tail: read=$read frameSize=$frameSize")
                    continue
                }

                totalBytesRead += alignedBytes
                bytesReadTotal += alignedBytes

                processDsp(buf, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)

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
                                Log.w(TAG, "USB nativeWrite returned non-frame bytes: written=$written frameSize=$frameSize")
                                break
                            }
                            writeOffset += alignedWritten
                            bytesWrittenTotal += written
                            bytesNativeAcceptedTotal += written

                            val nowBuffered = engine.nativeGetBufferUsedBytes()
                            if (nowBuffered >= targetWater) {
                                // buffer 达到目标水位，按实时音频速率 pacing
                                val durationNs = written * 1_000_000_000L / expectedBytesPerSec
                                LockSupport.parkNanos(durationNs)
                            }
                            // 低于目标水位：不 sleep，快速补充
                        }
                        written == 0 -> {
                            pumpConsecutiveWriteZeros++
                            if (pumpConsecutiveWriteZeros >= pumpMaxConsecutiveWriteZeros) {
                                Log.e(TAG, "USB nativeWrite returned 0 too many times ($pumpConsecutiveWriteZeros), stopping playback")
                                isPlaying.set(false)
                                break
                            }
                            LockSupport.parkNanos(5_000_000L) // 5ms
                        }
                        written == UsbAudioEngine.ERR_TRANSPORT_LOST ||
                        written == UsbAudioEngine.ERR_USB_IO -> {
                            Log.e(TAG, "USB transport lost during playback: $written")
                            isPlaying.set(false)
                            onUsbTransportLost?.invoke()
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_RUNNING -> {
                            Log.e(TAG, "USB engine stopped during playback")
                            isPlaying.set(false)
                            break
                        }
                        written == -32 -> {
                            Log.w(TAG, "USB nativeWrite returned -EPIPE, writer exiting")
                            isPlaying.set(false)
                            break
                        }
                        else -> {
                            Log.e(TAG, "USB write failed: $written")
                            isPlaying.set(false)
                            break
                        }
                    }
                }

                // 每秒打印一次吞吐量
                val now = System.currentTimeMillis()
                if (now - lastThroughputTime >= 1000) {
                    val elapsed = (now - lastThroughputTime).toDouble() / 1000.0
                    val readRate = ((bytesReadTotal - lastBytesRead) / elapsed).toInt()
                    val writeRate = ((bytesWrittenTotal - lastBytesWritten) / elapsed).toInt()
                    val nativeAcceptedRate = ((bytesNativeAcceptedTotal - lastBytesNativeAccepted) / elapsed).toInt()
                    val bufferUsed = engine.getBufferUsedBytes()
                    val expectedRate = actualSampleRate * pcmFrameSize

                    Log.i(TAG, """PCM Pump Stats:
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
                Log.e(TAG, "=== USB playback EXCEPTION", e)
            }
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { engine.stop() } catch (_: Exception) {}
            onUsbPlaybackStopped?.invoke()
            Log.i(TAG, "=== USB playback END")
        }
    }

    private fun startPlaybackFromOffset(startByteOffset: Long, playPath: String, generation: Int, isSeek: Boolean = false, sourcePath: String = playPath) {
        val file = tempWavFile ?: return

        if (!isStillCurrentPlayback(sourcePath, generation)) {
            Log.w(TAG, "=== Song changed before playback start, aborting: reqGen=$generation currentGen=${playGeneration.get()} currentSource=$activeSourcePath")
            return
        }

        isPlaying.set(true)
        Log.w(TAG, "=== startPlaybackFromOffset($startByteOffset), path=$playPath, usbExclusive=$usbExclusiveMode, isSeek=$isSeek")

        val channelConfig = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        var actualSampleRate = if (usbExclusiveMode) wavSampleRate.coerceAtLeast(44100) else wavSampleRate.coerceAtLeast(44100)
        // 非USB模式：使用 convertToWavWithRate 探测并缓存的编码，确保与 WAV 文件格式一致
        // USB模式：根据 WAV 数据确定编码
        val actualEncoding = if (usbExclusiveMode) getEncodingForWavData() else probedEncoding
        Log.i(TAG, "AudioTrack encoding: actualEncoding=$actualEncoding, wavBits=$wavBitsPerSample, wavFormatTag=$wavFormatTag")

        // 注意：非 USB 模式下，WAV 文件已在 prepareAndStartPlayback 中使用设备支持的采样率转码，
        // 因此 wavSampleRate 已经是设备支持的采样率，无需再次回退。

        if (usbExclusiveMode) {
            // 强制将 AudioTrack 路由回手机扬声器，防止系统声音从 DAC 发出
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
                    Log.i(TAG, "USB Exclusive: Forced AudioTrack to phone speaker")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to route AudioTrack to speaker", e)
            }

            startUsbExclusivePlayback(startByteOffset, playPath, actualSampleRate, generation, isSeek, sourcePath)
            return
        }

        val bufSize = AudioTrack.getMinBufferSize(actualSampleRate, channelConfig, actualEncoding)
            .coerceAtLeast(PCM_BUFFER_SIZE)

        try {
            val attributes = AudioOutputManager.buildAudioAttributes(context)

            // 防御性降级：AudioTrack 创建失败时逐级降低采样率和编码
            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            // Direct HiRes 模式：设置首选设备（异常安全）
            if (AudioOutputManager.getCurrentOutputMode(context) == com.rawsmusic.core.common.model.AudioOutputMode.DIRECT) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    try {
                        AudioOutputManager.getPreferredDeviceForDirect(context)?.let { device ->
                            track.preferredDevice = device
                            Log.d(TAG, "Direct mode: preferred device set to ${device.productName}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Setting preferred device failed, using default", e)
                    }
                }
            }

            audioTrack = track
            _audioSessionId = track.audioSessionId

            setVolume(volume)
            track.play()

            if (startByteOffset > 0) {
                try { track.flush() } catch (_: Exception) {}
            }
            
            // 只有还是当前歌曲才更新状态
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.PLAYING)
            }

            Log.w(TAG, "=== AudioTrack created, sessionId=$_audioSessionId")

        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation)) {
                Log.e(TAG, "AudioTrack creation failed", e)
                setState(State.ERROR)
                onPlaybackError("AudioTrack 创建失败: ${e.message}")
            }
            return
        }

        // 直接在当前线程播放（单线程模型，不会并发）
        var fis: FileInputStream? = null
        try {
            try {
                fis = FileInputStream(file)
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "Audio file not found: $file", e)
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
                        Log.e(TAG, "Skip failed at $bytesSkipped/$skipTarget")
                        return
                    }
                    bytesSkipped += r
                } else {
                    bytesSkipped += skipped
                }
            }

            val buffer = ByteArray(PCM_BUFFER_SIZE)
            var totalBytesRead = startByteOffset
            // 解码器对 >16bit 统一输出 S32LE (4B/sample)
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

            while (isPlaying.get() && !isReleased.get()) {
                // 每轮循环检查是否还是当前歌曲
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    Log.w(TAG, "=== Song changed during playback, breaking")
                    break
                }

                if (isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }

                val read = fis.read(buffer)
                if (read <= 0) {
                    Log.w(TAG, "=== play EOF")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                totalBytesRead += read

                processDsp(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)

                val track = audioTrack
                if (track == null) {
                    Log.w(TAG, "=== play: audioTrack is null, breaking")
                    break
                }
                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    Log.w(TAG, "=== play: Track STOPPED, breaking")
                    break
                }

                val result = track.write(buffer, 0, read)
                if (result < 0) {
                    Log.w(TAG, "=== play: write failed=$result, breaking")
                    break
                }

                _positionMs = (totalBytesRead.toDouble() / bytesPerMs).toLong().coerceAtLeast(0L)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }

            fis.close()
        } catch (e: java.io.FileNotFoundException) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                Log.e(TAG, "=== play file not found", e)
                setState(State.ERROR)
                onPlaybackError("音频文件丢失")
            }
        } catch (e: java.io.IOException) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                Log.e(TAG, "=== play IO error", e)
                setState(State.ERROR)
                onPlaybackError("文件读取错误（SD卡弹出或文件损坏）")
            }
        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                Log.e(TAG, "=== play EXCEPTION", e)
                onPlaybackError("播放异常: ${e.message}")
            }
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            Log.w(TAG, "=== play END")
        }
    }
}
