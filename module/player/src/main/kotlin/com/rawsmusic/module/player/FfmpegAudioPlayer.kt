package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.rawsmusic.core.common.utils.AppLogger
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
    var onPcmWaveformFrame: ((buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) -> Unit)? = null

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
    val audioTrackRef: AudioTrack? get() = audioTrack
    private var tempWavFile: File? = null
    private var sourcePath: String? = null
    private var currentPath: String? = null
    private var resampledPath: String? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private var seekPositionMs = -1L

    private var decoderHandle: Long = 0L
    private var ringBuffer: RingBuffer? = null
    @Volatile
    private var decoderThread: Thread? = null
    @Volatile
    private var pendingSeekMs = -1L

    private val playGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var activeSourcePath: String? = null
    private val pauseLock = Object()

    private fun isStillCurrentPlayback(sourcePath: String, generation: Int): Boolean {
        val currentGen = playGeneration.get()
        val currentSource = activeSourcePath
        val ok = generation == currentGen && currentSource == sourcePath
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

    private var wavDataOffset = 0L
    private var wavDataSize = 0L
    var wavSampleRate = 48000; private set
    var wavChannels = 2; private set
    var wavBitsPerSample = 16; private set
    private var wavFormatTag = 1

    @Volatile
    private var probedEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

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

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (usbExclusiveMode) return
            if (_state != State.PLAYING && _state != State.PAUSED) return
            val hasOutput = addedDevices?.any { it.isSink } ?: false
            if (hasOutput) {
                AppLogger.i(TAG, "Audio output device added, rebuilding AudioTrack")
                rebuildAudioTrack()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (usbExclusiveMode) return
            if (_state != State.PLAYING && _state != State.PAUSED) return
            val hasOutput = removedDevices?.any { it.isSink } ?: false
            if (hasOutput) {
                AppLogger.i(TAG, "Audio output device removed, rebuilding AudioTrack")
                rebuildAudioTrack()
            }
        }
    }

    private var audioDeviceCallbackRegistered = false

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
    private var dspShortArray: ShortArray? = null
    private var dspByteBuffer: java.nio.ByteBuffer? = null
    private var decoderFloatBuf: ByteArray? = null

    /** 暴露 DSP 引擎给 PEQ 控制器使用 */
    val dspEngine: NativeDSPEngine? get() = nativeDspEngine

    var usbExclusiveMode = false
    var usbBitPerfectMode = false
    var usbActualOutputSampleRate = 0
    var usbPrepareForPlayback: ((sampleRate: Int, bitDepth: Int, channels: Int, srcFilePath: String?) -> Boolean)? = null
    var onUsbTransportLost: (() -> Unit)? = null
    var onUsbPlaybackStarted: (() -> Unit)? = null
    var onUsbPlaybackStopped: (() -> Unit)? = null

    /** DSP 引擎重新初始化后的回调，用于重新连接 PEQ 控制器 */
    var onDspEngineReinit: (() -> Unit)? = null

    fun startUsbStreamingIfNeeded() {
        if (usbExclusiveMode && _state == State.PLAYING) {
            AppLogger.i(TAG, "startUsbStreamingIfNeeded: triggering native start")
            try {
                UsbAudioEngine.start()
            } catch (_: Exception) {}
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
    private var lastWaveformDispatchTime = 0L

    private fun dispatchWaveformFrame(buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        val callback = onPcmWaveformFrame ?: return
        val now = System.currentTimeMillis()
        if (now - lastWaveformDispatchTime < 33L) return
        lastWaveformDispatchTime = now
        val snapshot = buffer.copyOf(read.coerceAtMost(buffer.size))
        callback(snapshot, snapshot.size, channels, sampleRate, bitsPerSample)
    }

    fun releaseAudioTrackForUsb() {
        val oldTrack = audioTrack
        if (oldTrack != null) {
            audioTrack = null
            try { oldTrack.stop() } catch (_: Exception) {}
            try { oldTrack.release() } catch (_: Exception) {}
            AppLogger.i(TAG, "AudioTrack released for USB exclusive mode")
        }
    }

    fun play(path: String) {
        AppLogger.w(TAG, "=== play() called, path=$path")
        if (isReleased.get()) return

        registerAudioDeviceCallback()

        val generation = playGeneration.incrementAndGet()
        activeSourcePath = path

        isPlaying.set(false)
        isPaused.set(false)
        pendingSeekMs = -1L
        _positionMs = 0L
        _durationMs = 0L

        // Don't block waiting for old decoder thread — it will close its own handle in finally
        decoderThread = null

        // Close ring buffer to signal old decoder thread to stop writing
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
                AppLogger.w(TAG, "=== play task interrupted (song switch)")
            } catch (e: Exception) {
                if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                    AppLogger.e(TAG, "play failed", e)
                    setState(State.ERROR)
                    listener?.onError("播放失败: ${e.message}")
                }
            }
        }
    }

    private fun prepareAndStartPlayback(sourcePath: String, generation: Int) {
        AppLogger.d(TAG, "prepareAndStartPlayback: source=$sourcePath gen=$generation")
        _durationMs = probeDuration(sourcePath)
        AppLogger.d(TAG, "probeDuration: ${_durationMs}ms")
        if (!isStillCurrentPlayback(sourcePath, generation)) return

        var usbTargetSr = 0; var usbTargetBits = 0; var usbTargetCh = 0
        var atTargetRate = 0; var atTargetBits = 0

        if (usbExclusiveMode) {
            val srcSr = FFmpegBridge.probeSampleRate(sourcePath)
            usbTargetSr = if (usbBitPerfectMode) srcSr else selectUsbTargetSampleRate(srcSr)
            val srcBits = FFmpegBridge.probeBitsPerSample(sourcePath)
            val srcCh = FFmpegBridge.probeChannelCount(sourcePath)
            AppLogger.i(TAG, "USB probe: srcSr=$srcSr srcBits=$srcBits srcCh=$srcCh bitPerfect=$usbBitPerfectMode")
            val safeSrcBits = if (srcBits > 0) srcBits else 16
            val safeSrcCh = if (srcCh > 0) srcCh else 2
            usbTargetBits = if (usbBitPerfectMode) safeSrcBits else when {
                safeSrcBits <= 16 -> 16
                safeSrcBits <= 24 -> 24
                else -> 32
            }
            usbTargetCh = if (usbBitPerfectMode) safeSrcCh else safeSrcCh.coerceAtLeast(2)
            AppLogger.i(TAG, "USB target: sr=$usbTargetSr bits=$usbTargetBits ch=$usbTargetCh (safeSrcBits=$safeSrcBits safeSrcCh=$safeSrcCh)")
        } else {
            val userTargetRate = AudioOutputManager.getTargetSampleRate()
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val (probedRate, probedEnc) = AudioOutputManager.probeRateAndEncoding(userTargetRate, channelConfig, context)
            atTargetRate = probedRate
            atTargetBits = AudioOutputManager.encodingToFFmpegBits(probedEnc)
            probedEncoding = probedEnc
            AppLogger.i(TAG, "Device capability: rate=$atTargetRate, encoding=$probedEnc -> bits=$atTargetBits")
        }

        if (usbExclusiveMode) {
            AppLogger.i(TAG, "USB streaming decoder: opening $sourcePath, targetSr=$usbTargetSr, targetBits=$usbTargetBits, targetCh=$usbTargetCh")
            val handle = openDecoderWithFallback(sourcePath, usbTargetSr, usbTargetBits, usbTargetCh)
            if (handle == 0L) {
                AppLogger.e(TAG, "USB streaming decoder: openDecoder failed (all fallbacks)")
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
            _durationMs = FFmpegBridge.getDecoderDuration(handle)
            wavDataOffset = 0
            wavFormatTag = 1
            usbActualOutputSampleRate = wavSampleRate
            AppLogger.i(TAG, "USB streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms")

            val bytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 3).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            AppLogger.i(TAG, "USB RingBuffer created: ${ringCapacity} bytes (${ringCapacity * 1000 / bytesPerSec}ms)")

            isPlaying.set(true)

            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val usbChunkSize = ((16384 / frameSize) * frameSize).coerceAtLeast(frameSize * 256)
            AppLogger.i(TAG, "USB decoder chunk: $usbChunkSize bytes (frameSize=$frameSize)")
            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                FFmpegBridge.seekDecoder(handle, seekMs)
                rb.clear()
                _positionMs = seekMs
            }

            startDecoderThread(sourcePath, generation, usbChunkSize)

            initDspEngine()
            startUsbStreamingPlayback(sourcePath, generation)
        } else {
            AppLogger.i(TAG, "Streaming decoder: opening $sourcePath, targetRate=$atTargetRate, targetBits=$atTargetBits")
            val handle = openDecoderWithFallback(sourcePath, atTargetRate, atTargetBits, 2)
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
            _durationMs = FFmpegBridge.getDecoderDuration(handle)
            wavDataOffset = 0
            wavFormatTag = 1
            AppLogger.i(TAG, "Streaming decoder: ${wavSampleRate}Hz/${wavChannels}ch/${wavBitsPerSample}bit, duration=${_durationMs}ms")

            val bytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerSec = wavSampleRate * wavChannels * bytesPerSample
            val ringCapacity = (bytesPerSec * 2).toInt().coerceAtLeast(65536)
            val rb = RingBuffer(ringCapacity)
            ringBuffer = rb
            AppLogger.i(TAG, "RingBuffer created: ${ringCapacity} bytes (${ringCapacity * 1000 / bytesPerSec}ms)")

            isPlaying.set(true)

            if (seekPositionMs > 0) {
                val seekMs = seekPositionMs
                seekPositionMs = -1L
                FFmpegBridge.seekDecoder(handle, seekMs)
                rb.clear()
                _positionMs = seekMs
            }

            startDecoderThread(sourcePath, generation)

            initDspEngine()
            startStreamingPlayback(sourcePath, generation)
        }
    }

    fun pause() {
        if (_state != State.PLAYING) return
        isPaused.set(true)
        try { audioTrack?.pause() } catch (_: Exception) {}
        if (usbExclusiveMode) {
            AppLogger.i(TAG, "pause(): pausing USB streaming (keeping handle)")
            try { UsbAudioEngine.pause() } catch (_: Exception) {}
        }
        setState(State.PAUSED)
    }

    fun resume() {
        if (_state != State.PAUSED) return

        // 非 USB 模式：直接恢复 AudioTrack
        if (!usbExclusiveMode) {
            isPaused.set(false)
            synchronized(pauseLock) { pauseLock.notifyAll() }
            try { audioTrack?.play() } catch (_: Exception) {}
            setState(State.PLAYING)
            return
        }

        // USB 独占模式：必须先确保引擎就绪，再解除暂停
        AppLogger.i(TAG, "resume(): resuming USB streaming, engine init=${UsbAudioEngine.isInitialized()} running=${UsbAudioEngine.isRunning()}")

        // 1. 先停掉旧的 USB 流（释放内核 USB 引用），避免 reinit 时 claim interface 失败
        try { UsbAudioEngine.stop() } catch (_: Exception) {}

        // 2. 检查是否需要重新初始化（策略变更 / 设备断重连）
        val needsReinit = UsbAudioEngine.isPolicyChangedSinceInit() == true || !UsbAudioEngine.isInitialized()

        if (needsReinit) {
            AppLogger.w(TAG, "resume(): policy changed or engine not init, attempting reinit via prepareForPlayback")
            // 确保内核完全释放 USB 资源
            try { UsbAudioEngine.release() } catch (_: Exception) {}
            // 给内核一点时间释放 USB 接口
            Thread.sleep(50)
            var ok = usbPrepareForPlayback?.invoke(
                wavSampleRate, wavBitsPerSample, wavChannels, currentPath
            ) ?: false
            // 首次失败后，做一次更彻底的 release + 更长等待再重试
            if (!ok) {
                AppLogger.w(TAG, "resume(): first prepareForPlayback failed, retrying with full release")
                try { UsbAudioEngine.release() } catch (_: Exception) {}
                Thread.sleep(200)
                ok = usbPrepareForPlayback?.invoke(
                    wavSampleRate, wavBitsPerSample, wavChannels, currentPath
                ) ?: false
            }
            if (!ok) {
                AppLogger.e(TAG, "resume(): prepareForPlayback failed after retry, stopping playback")
                isPlaying.set(false)
                setState(State.ERROR)
                listener?.onError("USB 设备恢复失败，请重新插拔设备")
                onUsbTransportLost?.invoke()
                return
            }
        }

        // 3. 启动 USB 流
        var started = try { UsbAudioEngine.start() } catch (e: Exception) {
            AppLogger.e(TAG, "resume(): UsbAudioEngine.start() threw", e)
            false
        }
        // start 失败且是 reinit 场景，再做一次完整 reinit 循环
        if (!started && needsReinit) {
            AppLogger.w(TAG, "resume(): start failed after reinit, doing full reinit cycle")
            try { UsbAudioEngine.release() } catch (_: Exception) {}
            Thread.sleep(200)
            val ok = usbPrepareForPlayback?.invoke(
                wavSampleRate, wavBitsPerSample, wavChannels, currentPath
            ) ?: false
            if (ok) {
                started = try { UsbAudioEngine.start() } catch (_: Exception) { false }
            }
        }
        if (!started) {
            AppLogger.e(TAG, "resume(): USB start failed, stopping playback")
            isPlaying.set(false)
            setState(State.ERROR)
            listener?.onError("USB 音频流启动失败")
            return
        }

        // 4. USB 引擎就绪，解除暂停状态（解码器线程 + pump loop 恢复运行）
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        try { audioTrack?.play() } catch (_: Exception) {}
        setState(State.PLAYING)
        AppLogger.i(TAG, "resume(): USB streaming resumed successfully")
    }

    /**
     * 播放中遇到 ERR_NOT_INITIALIZED 时尝试单次 recovery：
     * release → sleep → reinit → start，成功返回 true。
     */
    private fun attemptUsbRecovery(
        sampleRate: Int, bits: Int, channels: Int,
        sourcePath: String, generation: Int
    ): Boolean {
        if (!isStillCurrentPlayback(sourcePath, generation)) return false
        try {
            AppLogger.w(TAG, "attemptUsbRecovery: releasing native engine")
            UsbAudioEngine.release()
        } catch (_: Exception) {}
        Thread.sleep(100)
        if (!isStillCurrentPlayback(sourcePath, generation) || !isPlaying.get()) return false
        val ok = usbPrepareForPlayback?.invoke(sampleRate, bits, channels, sourcePath) ?: false
        if (!ok) {
            AppLogger.e(TAG, "attemptUsbRecovery: prepareForPlayback failed")
            return false
        }
        if (!isStillCurrentPlayback(sourcePath, generation) || !isPlaying.get()) return false
        val started = try { UsbAudioEngine.start() } catch (_: Exception) { false }
        if (!started) {
            AppLogger.e(TAG, "attemptUsbRecovery: start failed")
            return false
        }
        AppLogger.i(TAG, "attemptUsbRecovery: success")
        return true
    }

    fun stop() {
        AppLogger.w(TAG, "=== stop() called")

        isPlaying.set(false)
        isPaused.set(false)
        pendingSeekMs = -1L
        seekPositionMs = -1L

        // Don't block waiting for decoder thread — it will close its own handle in finally
        decoderThread = null

        // Close ring buffer to signal decoder thread to stop
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
            AppLogger.i(TAG, "stop(): stopping USB engine (after isPlaying=false)")
            try { UsbAudioEngine.stop() } catch (_: Exception) {}
        }

        releaseDspEngine()

        setState(State.STOPPED)
    }

    fun seekTo(positionMs: Long) {
        AppLogger.w(TAG, "=== seekTo($positionMs)")

        _positionMs = positionMs
        seekPositionMs = -1L

        if (_state == State.PLAYING || _state == State.PAUSED) {
            if (decoderHandle != 0L) {
                val handle = decoderHandle
                val rb = ringBuffer
                if (handle != 0L && rb != null) {
                    // Non-blocking seek: decoder thread will handle the actual seek
                    pendingSeekMs = positionMs
                    _positionMs = positionMs

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

            if (tempWavFile == null || wavDataSize == 0L) {
                seekPositionMs = positionMs
                return
            }

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
                        AppLogger.e(TAG, "seek playback failed", e)
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
        AppLogger.w(TAG, "=== release() called")
        if (isReleased.getAndSet(true)) return

        unregisterAudioDeviceCallback()

        isPlaying.set(false)
        isPaused.set(false)
        pendingSeekMs = -1L

        // Don't block waiting for decoder thread — it will close its own handle in finally
        decoderThread = null

        // Close ring buffer to signal decoder thread to stop
        ringBuffer?.close()
        ringBuffer = null

        currentTask?.cancel(false)
        currentTask = null
        executor.shutdown()

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

    private fun registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.registerAudioDeviceCallback(audioDeviceCallback, null)
                audioDeviceCallbackRegistered = true
                AppLogger.d(TAG, "AudioDeviceCallback registered")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
            }
        }
    }

    private fun unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
            AppLogger.d(TAG, "AudioDeviceCallback unregistered")
        } catch (_: Exception) {}
    }

    private fun rebuildAudioTrack() {
        val pos = _positionMs
        val wasPlaying = _state == State.PLAYING
        val wasPaused = _state == State.PAUSED
        val path = currentPath ?: return

        executor.submit {
            try {
                if (isReleased.get()) return@submit

                val oldTrack = audioTrack
                audioTrack = null
                oldTrack?.let {
                    try { it.stop() } catch (_: Exception) {}
                    try { it.flush() } catch (_: Exception) {}
                    try { it.release() } catch (_: Exception) {}
                }

                if (pos > 0) {
                    val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
                    val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
                    val targetByteOffset = (pos.toDouble() * bytesPerMs).toLong().coerceIn(0, (wavDataSize - 1).coerceAtLeast(0))
                    val alignedOffset = (targetByteOffset / frameSize) * frameSize
                    val seekPath = if (usbExclusiveMode) resampledPath ?: path else path
                    val originalSourcePath = sourcePath ?: path
                    val gen = playGeneration.get()
                    startPlaybackFromOffset(alignedOffset, seekPath, gen, isSeek = true, sourcePath = originalSourcePath)
                } else {
                    val gen = playGeneration.get()
                    startPlaybackFromOffset(0, path, gen, isSeek = false, sourcePath = sourcePath ?: path)
                }
            } catch (e: Exception) {
                if (!isReleased.get()) {
                    AppLogger.e(TAG, "rebuildAudioTrack failed", e)
                }
            }
        }
    }

    private fun initDspEngine() {
        if (nativeDspEngine == null) {
            try {
                val engine = NativeDSPEngine()
                engine.init(wavSampleRate, wavChannels)
                engine.setStereoWiden(stereoWidenFactor)
                nativeDspEngine = engine
                useNativeDsp = true
                AppLogger.d(TAG, "DSP: NativeDSPEngine initialized, sr=$wavSampleRate, ch=$wavChannels")
                onDspEngineReinit?.invoke()
            } catch (e: Exception) {
                AppLogger.w(TAG, "DSP: NativeDSPEngine init failed, fallback to StereoWidenModule", e)
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
                    AppLogger.d(TAG, "DSP: NativeDSPEngine reinitialized, sr=$wavSampleRate, ch=$wavChannels")
                    onDspEngineReinit?.invoke()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "DSP: NativeDSPEngine reinit failed, fallback to StereoWidenModule", e)
                    nativeDspEngine = null
                    useNativeDsp = false
                }
            }
        }
    }

    private fun releaseDspEngine() {
        nativeDspEngine?.release()
        nativeDspEngine = null
        useNativeDsp = false
    }

    @Volatile
    private var dspLogTick = 0L

    private fun processDsp(buffer: ByteArray, read: Int, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        if (usbExclusiveMode && usbBitPerfectMode) return
        if (dspLogTick % 200L == 0L) {
            AppLogger.w(TAG, "DSP: active, factor=$stereoWidenFactor, native=$useNativeDsp, engine=${nativeDspEngine != null}, ch=$channels, bits=$bitsPerSample")
        }
        dspLogTick++
        // Native DSP 包含 PEQ + Crossfeed + StereoWiden，即使 stereoWidenFactor=0 也可能有 PEQ/Crossfeed 需要处理
        if (useNativeDsp && nativeDspEngine != null && bitsPerSample == 16) {
            val shortCount = read / 2
            var shortArr = dspShortArray
            if (shortArr == null || shortArr.size < shortCount) {
                shortArr = ShortArray(shortCount)
                dspShortArray = shortArr
            }
            var bb = dspByteBuffer
            if (bb == null || bb.capacity() < read) {
                bb = java.nio.ByteBuffer.allocate(read).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                dspByteBuffer = bb
            }
            (bb as java.nio.Buffer).clear()
            bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put(buffer, 0, read)
            bb.position(0)
            bb.asShortBuffer().get(shortArr, 0, shortCount)
            val result = nativeDspEngine!!.process(shortArr, shortCount, channels)
            if (result == 0) {
                bb.position(0)
                bb.asShortBuffer().put(shortArr, 0, shortCount)
                bb.position(0)
                bb.get(buffer, 0, read)
                return
            }
            AppLogger.w(TAG, "DSP: Native process failed (result=$result), fallback to Kotlin")
        }
        // Kotlin fallback: 仅当 stereoWidenFactor 足够大时才执行
        if (stereoWidenFactor > 0.01f) {
            stereoWidenModule.process(buffer, read, channels, sampleRate, bitsPerSample)
        }
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
        AppLogger.i(TAG, "selectUsbTargetSampleRate: srcSr=$srcSr -> $best")
        return best
    }

    private fun getEncodingForWavData(): Int {
        return when {
            wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT in 26..28 ->
                AudioFormat.ENCODING_PCM_16BIT
            wavBitsPerSample > 16 && android.os.Build.VERSION.SDK_INT >= 26 ->
                AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

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
                    track.play()
                    track.pause()
                    AppLogger.i(TAG, "AudioTrack created: rate=$rate encoding=$enc (requested: rate=$sampleRate enc=$encoding)")
                    return track
                } catch (e: Exception) {
                    AppLogger.w(TAG, "AudioTrack fallback failed: rate=$rate enc=$enc", e)
                }
            }
        }
        AppLogger.e(TAG, "AudioTrack creation failed for all fallback combinations")
        return null
    }

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
                        AppLogger.w(TAG, "Decoder opened with degraded params: ${rate}Hz/${bits}bit (requested: ${targetSr}Hz/${targetBits}bit)")
                    }
                    return handle
                }
            }
        }
        AppLogger.e(TAG, "Decoder failed for all fallback combinations")
        return 0L
    }

    private fun onPlaybackError(msg: String) {
        consecutiveErrors++
        AppLogger.e(TAG, "Playback error #$consecutiveErrors: $msg")
        if (consecutiveErrors >= maxErrorsBeforeSafeMode && !safeMode) {
            safeMode = true
            AppLogger.w(TAG, "=== SAFE MODE ACTIVATED after $consecutiveErrors consecutive errors ===")
        }
        listener?.onError(msg)
    }

    private fun onPlaybackSuccess() {
        if (consecutiveErrors > 0) {
            AppLogger.i(TAG, "Playback successful, resetting error count (was $consecutiveErrors)")
            consecutiveErrors = 0
        }
        if (safeMode) {
            AppLogger.i(TAG, "=== SAFE MODE DEACTIVATED ===")
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
            AppLogger.e(TAG, "FFprobe failed", e)
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
        AppLogger.i(TAG, "convertToWav: targetRate=$targetRate, targetBits=$targetBits")
        val ret = FFmpegBridge.convertToWav(inputPath, outputPath.absolutePath, targetRate, targetBits, 2)
        return ret == 0 && outputPath.exists() && outputPath.length() > 44
    }

    private fun convertToWavWithRate(inputPath: String, outputPath: File, targetRate: Int, targetBits: Int): Boolean {
        AppLogger.i(TAG, "convertToWavWithRate: targetRate=$targetRate, targetBits=$targetBits, usbExclusive=$usbExclusiveMode")
        val ret = FFmpegBridge.convertToWav(inputPath, outputPath.absolutePath, targetRate, targetBits, 2)
        return ret == 0 && outputPath.exists() && outputPath.length() > 44
    }

    private fun convertToRawPcm(inputPath: String, outputPath: File, targetSampleRate: Int, bitsPerSample: Int = 16, channels: Int = 2): Boolean {
        AppLogger.i(TAG, "convertToRawPcm: ${targetSampleRate}Hz/${channels}ch/${bitsPerSample}bit, no WAV header")
        val ret = FFmpegBridge.convertToRawPcm(inputPath, outputPath.absolutePath, targetSampleRate, bitsPerSample, channels)
        if (ret != 0 || !outputPath.exists() || outputPath.length() <= 0) {
            AppLogger.e(TAG, "convertToRawPcm failed: ret=$ret exists=${outputPath.exists()} size=${outputPath.length()}")
            return false
        }
        val bytesPerSample = when {
            bitsPerSample <= 16 -> 2
            bitsPerSample <= 24 -> 3
            else -> 4
        }
        val bytesPerSec = targetSampleRate * channels * bytesPerSample
        val durationSecApprox = outputPath.length().toDouble() / bytesPerSec.toDouble()
        AppLogger.i(TAG, "convertToRawPcm done: size=${outputPath.length()} bytes, approx ${"%.1f".format(durationSecApprox)}s")
        return true
    }

    private fun getCacheFile(path: String, atTargetRate: Int = 0, atTargetBits: Int = 0,
                             usbTargetSr: Int = 0, usbTargetBits: Int = 0, usbTargetCh: Int = 0): File {
        val cacheDir = File(context.cacheDir, "ffmpeg_audio")
        cacheDir.mkdirs()
        trimCacheDir(cacheDir, 500L * 1024 * 1024)
        if (usbExclusiveMode) {
            val bpTag = if (usbBitPerfectMode) "_bp" else ""
            val hash = (path + "_usb_raw_pcm" + "_r$usbTargetSr" + "_b$usbTargetBits" + "_c$usbTargetCh" + bpTag).hashCode().toString(16)
            return File(cacheDir, "$hash.pcm")
        } else {
            val rateTag = if (atTargetRate > 0) "_r$atTargetRate" else "_orig"
            val bitsTag = "_b$atTargetBits"
            val hash = (path + rateTag + bitsTag).hashCode().toString(16)
            return File(cacheDir, "$hash.wav")
        }
    }

    private fun trimCacheDir(dir: File, maxSize: Long) {
        try {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var totalSize = files.sumOf { it.length() }
            for (file in files) {
                if (totalSize <= maxSize) break
                AppLogger.i(TAG, "Trimming cache: deleting ${file.name} (${file.length()} bytes)")
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
                val actualBytesPerSample = if (wavBitsPerSample <= 16) 2 else 4
                val blockAlign = wavChannels * actualBytesPerSample
                val byteRate = wavSampleRate * blockAlign
                val formatName = if (wavFormatTag == 3) "IEEE_FLOAT" else "PCM"
                AppLogger.i(TAG, "WAV parse result: sampleRate=$wavSampleRate channels=$wavChannels " +
                        "bits=$wavBitsPerSample formatTag=$wavFormatTag($formatName) " +
                        "dataOffset=$wavDataOffset dataSize=$wavDataSize " +
                        "byteRate=$byteRate blockAlign=$blockAlign bytesPerSample=$actualBytesPerSample")
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "parseWavHeader failed", e)
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
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0
            val targetOffset = (seekPositionMs.toDouble() * bytesPerMs).toLong().coerceIn(0, (wavDataSize - 1).coerceAtLeast(0))
            startOffset = (targetOffset / frameSize) * frameSize
            seekPositionMs = -1L
        }
        val playPath = if (usbExclusiveMode) resampledPath ?: sourcePath else sourcePath
        startPlaybackFromOffset(startOffset, playPath, generation, sourcePath = sourcePath)
    }

    private fun startDecoderThread(sourcePath: String, generation: Int, decodeChunkSize: Int = 16384) {
        val handle = decoderHandle
        val rb = ringBuffer
        if (handle == 0L || rb == null) return

        decoderThread = thread(name = "FfmpegDecoder", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val decodeBuffer = ByteArray(decodeChunkSize)

            AppLogger.i(TAG, "Decoder thread started, handle=$handle")
            try {
                while (isPlaying.get() && !isReleased.get()) {
                    if (!isStillCurrentPlayback(sourcePath, generation)) {
                        AppLogger.w(TAG, "Decoder thread: song changed, exiting")
                        break
                    }

                    // Handle pending seek (non-blocking seek from main thread)
                    val seekTarget = pendingSeekMs
                    if (seekTarget >= 0) {
                        pendingSeekMs = -1L
                        FFmpegBridge.seekDecoder(handle, seekTarget)
                        rb.clear()
                        _positionMs = seekTarget
                        continue
                    }

                    val rbAvailable = rb.available()
                    val rbCapacity = decodeBuffer.size
                    if (rb.isClosed()) break

                    val decoded = FFmpegBridge.decodeChunk(handle, decodeBuffer, 0, decodeBuffer.size)
                    when {
                        decoded > 0 -> {
                            var writeData = decodeBuffer
                            var writeLen = decoded
                            if (!usbExclusiveMode && wavBitsPerSample > 16) {
                                val sampleCount = decoded / 4
                                val needed = sampleCount * 4
                                var floatBuf = decoderFloatBuf
                                if (floatBuf == null || floatBuf.size < needed) {
                                    floatBuf = ByteArray(needed)
                                    decoderFloatBuf = floatBuf
                                }
                                val sb = java.nio.ByteBuffer.wrap(decodeBuffer, 0, decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val fb = java.nio.ByteBuffer.wrap(floatBuf, 0, needed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until sampleCount) {
                                    val s32 = sb.getInt(i * 4)
                                    fb.putFloat(i * 4, s32.toFloat() / 2147483648.0f)
                                }
                                writeData = floatBuf
                                writeLen = needed
                            }
                            val written = rb.write(writeData, 0, writeLen)
                            if (written < 0) {
                                AppLogger.w(TAG, "Decoder thread: ring buffer closed")
                                break
                            }
                        }
                        decoded == -1 -> {
                            AppLogger.i(TAG, "Decoder thread: EOF reached")
                            rb.close()
                            break
                        }
                        else -> {
                            AppLogger.e(TAG, "Decoder thread: decode error: $decoded")
                            rb.close()
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
                AppLogger.w(TAG, "Decoder thread interrupted")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Decoder thread fatal error", e)
                try {
                    isPlaying.set(false)
                    rb.close()
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        onPlaybackError("解码线程异常: ${e.message}")
                    }
                } catch (notifyErr: Exception) {
                    AppLogger.e(TAG, "Error notifying decoder failure", notifyErr)
                }
            } finally {
                AppLogger.i(TAG, "Decoder thread ended, closing handle=$handle")
                try {
                    FFmpegBridge.closeDecoder(handle)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error closing decoder in thread finally", e)
                }
                // Reset shared handle only if it's still the one we were using
                if (decoderHandle == handle) {
                    decoderHandle = 0L
                }
                AppLogger.i(TAG, "Decoder thread cleanup done")
            }
        }
    }

    private fun startStreamingPlayback(sourcePath: String, generation: Int) {
        val rb = ringBuffer ?: return

        val channelConfig = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val actualEncoding = probedEncoding
        AppLogger.i(TAG, "Streaming playback: rate=$actualSampleRate, encoding=$actualEncoding, bits=$wavBitsPerSample")

        val bufSize = AudioTrack.getMinBufferSize(actualSampleRate, channelConfig, actualEncoding)
            .coerceAtLeast(PCM_BUFFER_SIZE)

        try {
            val attributes = AudioOutputManager.buildAudioAttributes(context)

            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            if (AudioOutputManager.getCurrentOutputMode(context) == com.rawsmusic.core.common.model.AudioOutputMode.DIRECT) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    try {
                        AudioOutputManager.getPreferredDeviceForDirect(context)?.let { device ->
                            track.preferredDevice = device
                            AppLogger.d(TAG, "Direct mode: preferred device set to ${device.productName}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Setting preferred device failed, using default", e)
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

            AppLogger.i(TAG, "Streaming AudioTrack created, sessionId=$_audioSessionId")

            val buffer = ByteArray(PCM_BUFFER_SIZE)
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "Streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val read = rb.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    AppLogger.w(TAG, "Streaming: EOF from ring buffer")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                processDsp(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)
                dispatchWaveformFrame(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)

                val track2 = audioTrack
                if (track2 == null) {
                    AppLogger.w(TAG, "Streaming: audioTrack is null, breaking")
                    break
                }
                if (track2.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    AppLogger.w(TAG, "Streaming: Track STOPPED, breaking")
                    break
                }

                val result = track2.write(buffer, 0, read)
                if (result < 0) {
                    AppLogger.w(TAG, "Streaming: write failed=$result, breaking")
                    break
                }

                _positionMs = ((_positionMs + (read.toDouble() / bytesPerMs))).toLong().coerceIn(0L, _durationMs)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }
            }

        } catch (e: Exception) {
            if (isStillCurrentPlayback(sourcePath, generation) && !isReleased.get()) {
                AppLogger.e(TAG, "Streaming playback EXCEPTION", e)
            }
        } finally {
            AppLogger.w(TAG, "Streaming playback END")
        }
    }

    private fun startUsbStreamingPlayback(sourcePath: String, generation: Int) {
        val rb = ringBuffer ?: return
        val engine = UsbAudioEngine

        val actualSampleRate = wavSampleRate.coerceAtLeast(44100)
        val pcmFrameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
        val expectedBytesPerSec = actualSampleRate * pcmFrameSize
        val packetSize = engine.getPacketSize().let { if (it > 0) it else pcmFrameSize }
        val readBufFrames = 2048
        val readBufSize = (pcmFrameSize * readBufFrames).let { size ->
            ((size + packetSize - 1) / packetSize) * packetSize
        }

        AppLogger.i(TAG, "========== USB Streaming Parameters ==========")
        AppLogger.i(TAG, "  sampleRate=$actualSampleRate channels=$wavChannels bits=$wavBitsPerSample")
        AppLogger.i(TAG, "  frameSize=$pcmFrameSize packetSize=$packetSize readBufSize=$readBufSize")
        AppLogger.i(TAG, "  expectedBytesPerSec=$expectedBytesPerSec")
        AppLogger.i(TAG, "  engine init=${engine.isInitialized()}, running=${engine.isRunning()}")
        AppLogger.i(TAG, "===============================================")

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

        val prefillTargetMs = 500L
        val prefillTargetBytes = (expectedBytesPerSec * prefillTargetMs / 1000).toInt()
        val prefillTimeoutMs = 5000L
        val minAcceptableBytes = (expectedBytesPerSec * 200 / 1000).toInt()

        AppLogger.i(TAG, "=== USB PREFILL START === target=${prefillTargetBytes}B (${prefillTargetMs}ms)")
        val prefillStart = System.currentTimeMillis()
        while (isPlaying.get() && !isReleased.get()) {
            if (System.currentTimeMillis() - prefillStart > prefillTimeoutMs) {
                AppLogger.w(TAG, "USB prefill timeout")
                break
            }
            val buffered = rb.available()
            if (buffered >= prefillTargetBytes) break
            if (rb.isEof()) break
            Thread.sleep(10)
        }

        if (!isStillCurrentPlayback(sourcePath, generation)) return

        val bufferedBytes = rb.available()
        AppLogger.i(TAG, "=== USB PREFILL END === buffered=${bufferedBytes}B (${bufferedBytes * 1000 / expectedBytesPerSec}ms)")

        if (bufferedBytes < minAcceptableBytes && !rb.isEof()) {
            AppLogger.e(TAG, "USB prefill insufficient: ${bufferedBytes}B < ${minAcceptableBytes}B")
            isPlaying.set(false)
            if (isStillCurrentPlayback(sourcePath, generation)) {
                setState(State.ERROR)
                listener?.onError("USB 预填 buffer 不足")
            }
            return
        }

        if (!engine.start()) {
            if (engine.isPolicyChangedSinceInit()) {
                AppLogger.w(TAG, "USB streaming: policy changed, reinit...")
                engine.release()
                val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath) ?: false
                if (reinitOk && engine.isInitialized()) {
                    if (!engine.start()) {
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
            } else {
                isPlaying.set(false)
                AppLogger.e(TAG, "USB streaming: start failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 音频流启动失败")
                }
                return
            }
        }
        onUsbPlaybackStarted?.invoke()
        AppLogger.i(TAG, "USB streaming: nativeStart OK")

        if (isStillCurrentPlayback(sourcePath, generation)) {
            setState(State.PLAYING)
        }

        val buffer = ByteArray(readBufSize)
        val lowWater = expectedBytesPerSec * 300 / 1000
        val targetWater = expectedBytesPerSec * 500 / 1000
        val highWater = expectedBytesPerSec * 1200 / 1000
        var consecutiveWriteZeros = 0
        val maxConsecutiveWriteZeros = 200

        try {
            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "USB streaming: song changed, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val bufferedBeforeRead = engine.nativeGetBufferUsedBytes()
                if (bufferedBeforeRead > highWater) {
                    java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L)
                    continue
                }

                val maxRead = minOf(buffer.size, packetSize * 16)
                val read = rb.read(buffer, 0, maxRead)
                if (read <= 0) {
                    AppLogger.w(TAG, "USB streaming: EOF from ring buffer")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                val alignedBytes = read - (read % pcmFrameSize)
                if (alignedBytes <= 0) continue

                bytesReadTotal += alignedBytes

                processDsp(buffer, alignedBytes, wavChannels, wavSampleRate, wavBitsPerSample)

                var writeOffset = 0
                while (writeOffset < alignedBytes && isPlaying.get() && !isReleased.get()) {
                    val remaining = alignedBytes - writeOffset
                    val written = engine.write(buffer, writeOffset, remaining)
                    when {
                        written > 0 -> {
                            consecutiveWriteZeros = 0
                            val alignedWritten = written - (written % pcmFrameSize)
                            if (alignedWritten <= 0) {
                                AppLogger.w(TAG, "USB streaming: non-frame write: $written frameSize=$pcmFrameSize")
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
                                AppLogger.e(TAG, "USB streaming: write returned 0 too many times")
                                isPlaying.set(false)
                                break
                            }
                            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L)
                        }
                        written == UsbAudioEngine.ERR_TRANSPORT_LOST ||
                        written == UsbAudioEngine.ERR_USB_IO -> {
                            AppLogger.e(TAG, "USB streaming: transport lost: $written")
                            isPlaying.set(false)
                            onUsbTransportLost?.invoke()
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_INITIALIZED -> {
                            // handle 突然失效，尝试一次 reinit + restart
                            AppLogger.w(TAG, "USB streaming: ERR_NOT_INITIALIZED, attempting recovery...")
                            val recovered = attemptUsbRecovery(actualSampleRate, wavBitsPerSample, wavChannels, sourcePath, generation)
                            if (recovered) {
                                AppLogger.i(TAG, "USB streaming: recovery succeeded, resuming write")
                                continue
                            }
                            AppLogger.e(TAG, "USB streaming: recovery failed, stopping")
                            isPlaying.set(false)
                            onUsbTransportLost?.invoke()
                            break
                        }
                        written == UsbAudioEngine.ERR_NOT_RUNNING -> {
                            AppLogger.e(TAG, "USB streaming: engine stopped")
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

                _positionMs = (bytesReadTotal.toDouble() / (expectedBytesPerSec.toDouble() / 1000.0)).toLong().coerceIn(0L, _durationMs)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    listener?.onPositionChanged(_positionMs, _durationMs)
                }

                val now = System.currentTimeMillis()
                if (now - lastThroughputTime >= 1000) {
                    val elapsed = (now - lastThroughputTime).toDouble() / 1000.0
                    val readRate = ((bytesReadTotal - lastBytesRead) / elapsed).toInt()
                    val writeRate = ((bytesWrittenTotal - lastBytesWritten) / elapsed).toInt()
                    val bufferUsed = engine.getBufferUsedBytes()
                    AppLogger.i(TAG, """USB Streaming Stats:
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
                AppLogger.e(TAG, "USB streaming playback EXCEPTION", e)
            }
        } finally {
            AppLogger.w(TAG, "USB streaming playback END")
        }
    }

    private fun startUsbExclusivePlayback(startByteOffset: Long, playPath: String, sampleRate: Int, generation: Int, isSeek: Boolean = false, sourcePath: String) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

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
        val usbBytesPerSec = actualSampleRate * usbFrameSize
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

        val skipReinit = isSeek && engine.isInitialized() && engine.isRunning()
        if (skipReinit) {
            AppLogger.i(TAG, "SEEK: USB already initialized and running, skipping reinit")
        } else {
            val prepareOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: true
            if (!prepareOk) {
                isPlaying.set(false)
                AppLogger.e(TAG, "USB prepareForPlayback failed")
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 设备准备失败")
                }
                return
            }
        }

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
        val expectedBytesPerSec = actualSampleRate * pcmFrameSize
        val prefillTargetBytes = expectedBytesPerSec / 5
        val prefillTimeoutMs = 3000L
        val minAcceptableBytes = expectedBytesPerSec / 5

        AppLogger.i(TAG, "=== PREFILL START ===")
        AppLogger.i(TAG, "  target=${prefillTargetBytes}B (200ms) expectedBytesPerSec=$expectedBytesPerSec")
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

            while (isPlaying.get() && !isReleased.get() && prefillBytesWritten < prefillTargetBytes) {
                if (System.currentTimeMillis() - prefillStartTime > prefillTimeoutMs) {
                    AppLogger.w(TAG, "Pre-fill timeout: wrote $prefillBytesWritten/${prefillTargetBytes}B")
                    break
                }

                val elapsed = System.currentTimeMillis() - prefillStartTime
                val buffered = engine.getBufferUsedBytes()
                val progress = if (prefillTargetBytes > 0) (buffered * 100 / prefillTargetBytes) else 0
                if (elapsed % 500 < 20) {
                    AppLogger.i(TAG, "Pre-fill: ${buffered}B/${prefillTargetBytes}B ($progress%)")
                }

                val remainingTarget = (prefillTargetBytes - prefillBytesWritten).toInt()
                val toRead = minOf(buffer.size, remainingTarget)
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
                            Thread.sleep(2)
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
            AppLogger.i(TAG, "  prefill wrote=${prefillBytesWritten}B")
            AppLogger.i(TAG, "  buffer before=$bufferBefore after=$bufferAfter")
            val bufferGrowth = bufferAfter - bufferBefore
            AppLogger.i(TAG, "  buffer growth=${bufferGrowth}B (net bytes written to native ring)")

            if (bufferAfter < minAcceptableBytes) {
                val ms = bufferAfter * 1000 / expectedBytesPerSec
                AppLogger.e(TAG, "=== PREFILL INSUFFICIENT: only ${bufferAfter}B (${ms}ms), need >= ${minAcceptableBytes}B (200ms)")
                isPlaying.set(false)
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    listener?.onError("USB 预填 buffer 不足: ${bufferAfter}B (${ms}ms)")
                }
                return
            }

            if (!isStillCurrentPlayback(sourcePath, generation)) {
                AppLogger.w(TAG, "=== Song changed after prefill, aborting")
                return
            }

            AppLogger.i(TAG, "=== CALL nativeStart ===")
            if (!engine.start()) {
                if (engine.isPolicyChangedSinceInit()) {
                    AppLogger.w(TAG, "=== nativeStart failed due to policy change, attempting full reinit via prepareForPlayback ===")
                    engine.release()
                    val reinitOk = usbPrepareForPlayback?.invoke(actualSampleRate, wavBitsPerSample, wavChannels, playPath) ?: false
                    if (reinitOk && engine.isInitialized()) {
                        AppLogger.i(TAG, "=== Reinit OK after policy change, retrying start ===")
                        if (!engine.start()) {
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
                } else {
                    isPlaying.set(false)
                    AppLogger.e(TAG, "=== nativeStart FAILED ===")
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        setState(State.ERROR)
                        listener?.onError("USB 音频流启动失败")
                    }
                    return
                }
            }
            onUsbPlaybackStarted?.invoke()
            AppLogger.i(TAG, "=== nativeStart OK ===")
            Thread.sleep(50)

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

            val lowWater = expectedBytesPerSec * 300 / 1000
            val targetWater = expectedBytesPerSec * 500 / 1000
            val highWater = expectedBytesPerSec * 1200 / 1000

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
                    LockSupport.parkNanos(10_000_000L)
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
                            if (nowBuffered >= targetWater) {
                                val durationNs = written * 1_000_000_000L / expectedBytesPerSec
                                LockSupport.parkNanos(durationNs)
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
                    val expectedRate = actualSampleRate * pcmFrameSize

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
            try { engine.stop() } catch (_: Exception) {}
            onUsbPlaybackStopped?.invoke()
            AppLogger.i(TAG, "=== USB playback END")
        }
    }

    private fun startPlaybackFromOffset(startByteOffset: Long, playPath: String, generation: Int, isSeek: Boolean = false, sourcePath: String = playPath) {
        val file = tempWavFile ?: return

        if (!isStillCurrentPlayback(sourcePath, generation)) {
            AppLogger.w(TAG, "=== Song changed before playback start, aborting: reqGen=$generation currentGen=${playGeneration.get()} currentSource=$activeSourcePath")
            return
        }

        isPlaying.set(true)
        AppLogger.w(TAG, "=== startPlaybackFromOffset($startByteOffset), path=$playPath, usbExclusive=$usbExclusiveMode, isSeek=$isSeek")

        val channelConfig = if (wavChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        var actualSampleRate = if (usbExclusiveMode) wavSampleRate.coerceAtLeast(44100) else wavSampleRate.coerceAtLeast(44100)
        val actualEncoding = if (usbExclusiveMode) getEncodingForWavData() else probedEncoding
        AppLogger.i(TAG, "AudioTrack encoding: actualEncoding=$actualEncoding, wavBits=$wavBitsPerSample, wavFormatTag=$wavFormatTag")

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

        val bufSize = AudioTrack.getMinBufferSize(actualSampleRate, channelConfig, actualEncoding)
            .coerceAtLeast(PCM_BUFFER_SIZE)

        try {
            val attributes = AudioOutputManager.buildAudioAttributes(context)

            val track = createAudioTrackWithFallback(actualSampleRate, channelConfig, actualEncoding, bufSize, attributes)
            if (track == null) {
                if (isStillCurrentPlayback(sourcePath, generation)) {
                    setState(State.ERROR)
                    onPlaybackError("AudioTrack 创建失败（所有降级参数均失败）")
                }
                return
            }

            if (AudioOutputManager.getCurrentOutputMode(context) == com.rawsmusic.core.common.model.AudioOutputMode.DIRECT) {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    try {
                        AudioOutputManager.getPreferredDeviceForDirect(context)?.let { device ->
                            track.preferredDevice = device
                            AppLogger.d(TAG, "Direct mode: preferred device set to ${device.productName}")
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Setting preferred device failed, using default", e)
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
            val frameSize = wavChannels * if (wavBitsPerSample <= 16) 2 else 4
            val bytesPerMs = (wavSampleRate * frameSize).toDouble() / 1000.0

            while (isPlaying.get() && !isReleased.get()) {
                if (!isStillCurrentPlayback(sourcePath, generation)) {
                    AppLogger.w(TAG, "=== Song changed during playback, breaking")
                    break
                }

                if (isPaused.get()) {
                    synchronized(pauseLock) { pauseLock.wait(100) }
                    continue
                }

                val read = fis.read(buffer)
                if (read <= 0) {
                    AppLogger.w(TAG, "=== play EOF")
                    isPlaying.set(false)
                    if (isStillCurrentPlayback(sourcePath, generation)) {
                        onPlaybackSuccess()
                        setState(State.COMPLETED)
                    }
                    break
                }

                totalBytesRead += read

                processDsp(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)
                dispatchWaveformFrame(buffer, read, wavChannels, wavSampleRate, wavBitsPerSample)

                val track = audioTrack
                if (track == null) {
                    AppLogger.w(TAG, "=== play: audioTrack is null, breaking")
                    break
                }
                if (track.playState == AudioTrack.PLAYSTATE_STOPPED) {
                    AppLogger.w(TAG, "=== play: Track STOPPED, breaking")
                    break
                }

                val result = track.write(buffer, 0, read)
                if (result < 0) {
                    AppLogger.w(TAG, "=== play: write failed=$result, breaking")
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
