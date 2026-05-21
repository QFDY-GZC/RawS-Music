package com.rawsmusic.module.player

import android.content.Context
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbExclusiveManager
import com.rawsmusic.module.player.usb.UsbVolumeController
import java.io.File
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

class PlayerController private constructor(context: Context) {

    private val context = context.applicationContext

    companion object {
        private const val TAG = "PlayerController"

        @Volatile
        private var instance: PlayerController? = null

        fun getInstance(context: Context): PlayerController {
            return instance ?: synchronized(this) {
                instance ?: PlayerController(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "PlayerController singleton created: ${System.identityHashCode(it)}")
                }
            }
        }

        @JvmStatic
        fun getInstanceOrNull(): PlayerController? = instance
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
            ensurePEQConnected()
        }
    }

    // PEQ 控制器单例（延迟初始化，首次访问时从 DSP 引擎创建）
    private var _peqController: com.rawsmusic.module.player.dsp.ParametricEQController? = null
    val peqController: com.rawsmusic.module.player.dsp.ParametricEQController
        get() {
            if (_peqController == null) {
                val engine = ffmpegPlayer.dspEngine
                _peqController = if (engine != null) {
                    com.rawsmusic.module.player.dsp.ParametricEQController(engine)
                } else {
                    Log.w(TAG, "DSP engine not available for PEQ, creating stub")
                    com.rawsmusic.module.player.dsp.ParametricEQController(
                        com.rawsmusic.module.player.dsp.NativeDSPEngine()
                    )
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
            if (_peqController == null) {
                _peqController = ParametricEQController(engine)
            } else {
                _peqController!!.connectEngine(engine)
            }
        }
    }

    val usbExclusiveManager = UsbExclusiveManager(context)
    private val sharedUsbAudioEngine = UsbAudioEngine
    private var usbVolumeController: UsbVolumeController? = null
    private var currentUsbDevice: UsbDevice? = null
    private val _usbExclusiveActive = MutableStateFlow(false)
    val usbExclusiveActive: StateFlow<Boolean> = _usbExclusiveActive.asStateFlow()

    private val _usbOutputSampleRate = MutableStateFlow(0)
    val usbOutputSampleRate: StateFlow<Int> = _usbOutputSampleRate.asStateFlow()

    /** USB 设备硬件音量 Feature Unit 信息（供 UI 提示） */
    val usbVolumeInfo: StateFlow<UsbExclusiveManager.VolumeInfo?> = usbExclusiveManager.volumeInfo

    private val _playState = MutableStateFlow(PlayState.IDLE)
    val playState: StateFlow<PlayState> = _playState.asStateFlow()

    private val _currentSong = MutableStateFlow<AudioFile?>(null)
    val currentSong: StateFlow<AudioFile?> = _currentSong.asStateFlow()

    fun updateCurrentSongIfSamePath(song: AudioFile) {
        if (_currentSong.value?.path == song.path) {
            _currentSong.value = song
            val q = _queue.value
            val idx = q.songs.indexOfFirst { it.path == song.path }
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

    val latencyMs: Int
        get() {
            val trackLatency = ffmpegPlayer.latencyMs
            val offset = AppPreferences.Lyrics.latencyOffset
            return if (isBluetoothOutput) {
                trackLatency + offset
            } else {
                trackLatency + offset
            }
        }

    @Volatile
    var isBluetoothOutput: Boolean = false
        private set
    @Volatile
    private var lastDetectedCodecType: Int = -1
    private var bluetoothLatencyJob: Job? = null

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemaining = MutableStateFlow(0L)
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining.asStateFlow()
    private var sleepTimerEndTime: Long = 0L
    private var stopAfterCurrentSong: Boolean = false
    private var songsUntilStop: Int = 0

    private fun startBluetoothLatencyMonitor() {
        bluetoothLatencyJob?.cancel()
        bluetoothLatencyJob = scope.launch {
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

    private fun checkBluetoothOutput() {
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
                val codecType = detectBluetoothCodecTypeSync()
                lastDetectedCodecType = codecType
                Log.d(TAG, "BT connected, codecType=$codecType (${codecTypeName(codecType)}), AudioTrack latency=${ffmpegPlayer.latencyMs}ms")
            } else {
                lastDetectedCodecType = -1
                Log.d(TAG, "BT disconnected, using AudioTrack latency")
            }
        }
    }

    private fun detectBluetoothCodecTypeSync(): Int {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Log.w(TAG, "detectCodec: BluetoothAdapter is null")
                return -1
            }
            val profileProxy = arrayOfNulls<android.bluetooth.BluetoothProfile>(1)
            val lock = java.util.concurrent.CountDownLatch(1)

            adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    profileProxy[0] = proxy
                    lock.countDown()
                }
                override fun onServiceDisconnected(profile: Int) {
                    lock.countDown()
                }
            }, android.bluetooth.BluetoothProfile.A2DP)

            if (!lock.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "detectCodec: A2DP profile proxy timeout")
                return -1
            }

            val a2dp = profileProxy[0]
            if (a2dp == null) {
                Log.w(TAG, "detectCodec: A2DP proxy is null")
                return -1
            }
            val connectedDevices = a2dp.connectedDevices
            if (connectedDevices.isNullOrEmpty()) {
                Log.w(TAG, "detectCodec: no connected A2DP devices")
                try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}
                return -1
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

            try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}
            Log.d(TAG, "detectCodec: result=$codecType (${codecTypeName(codecType)})")
            codecType
        } catch (e: Exception) {
            Log.e(TAG, "detectCodec: unexpected error: ${e.message}")
            -1
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        startBluetoothLatencyMonitor()
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

    /** 随机播放袋子：存放本循环中尚未播放的索引，播完后重新填充 */
    private val shuffleBag = mutableListOf<Int>()
    /** 随机播放历史：记录已播放的索引顺序，用于"上一首"回退 */
    private val shufflePlayedHistory = ArrayDeque<Int>()

    /** 回放增益音量系数 — 由 applyReplayGain() 计算后与用户音量合成 */
    private var replayGainVolumeModifier = 1.0f
    private val recoveringUsb = java.util.concurrent.atomic.AtomicBoolean(false)
    private var wasPlayingBeforeFocusLoss = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastPlayRequestPath: String? = null
    private var lastPlayRequestTime = 0L
    /** 恢复播放位置：当 restoreLastSong() 恢复了位置后，在下次播放同一首歌时自动 seek */
    private var pendingSeekPosition: Long = -1L
    private var pendingSeekPath: String? = null

    // FFmpeg 播放器回调
    private val playerListener = object : FfmpegAudioPlayer.Listener {
        override fun onStateChanged(state: FfmpegAudioPlayer.State) {
            if (isReleased) return
            scope.launch {
                handlePlayerStateChanged(state)
            }
        }

        override fun onPositionChanged(positionMs: Long, durationMs: Long) {
            _position.value = positionMs
            _duration.value = durationMs
        }

        override fun onError(message: String) {
            lastPlayerError = message
            scope.launch {
                Log.e(TAG, "FfmpegAudioPlayer error: $message")
            }
        }
    }

    init {
        Log.i(TAG, "PlayerController created: ${System.identityHashCode(this)}")
        ffmpegPlayer.listener = playerListener
        ffmpegPlayer.onPcmWaveformFrame = { buffer, read, channels, sampleRate, bitsPerSample ->
            onPcmWaveformFrame?.invoke(buffer, read, channels, sampleRate, bitsPerSample)
        }
        initDspPipeline()
        restoreState()
        usbExclusiveManager.onDeviceReady = { device ->
            activateUsbEngine(device)
        }
        usbExclusiveManager.onDeviceDetached = { device ->
            Log.w(TAG, "USB device detached callback: ${device?.deviceName}")
            android.widget.Toast.makeText(
                context,
                "DAC 已拔出",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            handleUsbDeviceDetached()
        }
        usbExclusiveManager.register()
        scanForUsbDevice()
    }

    fun scanForUsbDevice() {
        if (_usbExclusiveActive.value || usbExclusiveManager.hasOpenConnection()) {
            Log.d(TAG, "USB already active/open, skip startup scan")
            return
        }
        val device = usbExclusiveManager.findUsbAudioDevice()
        if (device != null) {
            Log.i(TAG, "Found already-connected USB audio device: ${device.deviceName}")
            usbExclusiveManager.requestPermissionSafely(device)
        } else {
            Log.d(TAG, "No USB audio device found at startup")
        }
    }

    private fun activateUsbEngine(device: UsbDevice) {
        val sameDeviceAlreadyActive =
            _usbExclusiveActive.value &&
                    currentUsbDevice?.deviceId == device.deviceId
        if (sameDeviceAlreadyActive) {
            Log.i(TAG, "USB exclusive already prepared for same device, skip activate")
            return
        }
        if (_usbExclusiveActive.value && !sameDeviceAlreadyActive) {
            Log.w(TAG, "Another USB device became ready while one is already active, ignore")
            return
        }

        // 新设备插入：重置 USB 策略（exclusive/bitPerfect/hardwareFeatureUnit 全部归零）
        if (currentUsbDevice == null || currentUsbDevice?.deviceId != device.deviceId) {
            sharedUsbAudioEngine.nativeResetUsbPolicyForNewDevice()
        }

        // 通知 native 进入独占模式
        sharedUsbAudioEngine.nativeSetUsbExclusiveActive(true)
        // USB 独占开启时，完美比特必须默认关闭
        AppPreferences.Player.bitPerfectEnabled = false
        sharedUsbAudioEngine.nativeSetPolicy(true, false, false)
        // 同步 USB DAC 高级设置（从 MMKV 恢复到 native）
        sharedUsbAudioEngine.setUsbDacSettings(
            AppPreferences.Player.usbNoControlInterface,
            false, // forceUac1 暂不暴露到 UI
            AppPreferences.Player.usbLinearVolume,
            false, // replaceVolume 暂不暴露到 UI
            AppPreferences.Player.usbForce1MsPacket
        )
        // bitPerfect 和 hardwareFeatureUnit 需要用户手动开启
        // 即使上次偏好是 true，也不自动启用，避免未经验证的 Feature Unit 导致左右不均衡

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "Audio focus changed: $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.i(TAG, "Audio focus loss permanent, pause")
                    wasPlayingBeforeFocusLoss = _playState.value == PlayState.PLAYING
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.i(TAG, "Audio focus loss transient, pause")
                    wasPlayingBeforeFocusLoss = _playState.value == PlayState.PLAYING
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.i(TAG, "Audio focus duck (notification), continue playing")
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.i(TAG, "Audio focus gain, resume if was playing")
                    if (wasPlayingBeforeFocusLoss) {
                        resume()
                    }
                }
            }
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

        val focusResult = am.requestAudioFocus(audioFocusRequest!!)
        if (focusResult != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request failed, system sounds may mix in")
        } else {
            Log.i(TAG, "Audio focus granted")
        }

        currentUsbDevice = device
        try {
            ffmpegPlayer.releaseAudioTrackForUsb()
        } catch (_: Exception) {}
        // 不再在 scan 阶段保存 fd/iface/alt/claimedInJava
        // prepareForPlayback 会在播放时 openDevice + nativeInitUsbDevice
        ffmpegPlayer.usbPrepareForPlayback = { sr, bits, ch, srcFilePath ->
            usbExclusiveManager.prepareForPlayback(sr, bits, ch, srcFilePath)
        }
        ffmpegPlayer.onUsbTransportLost = {
            recoverUsbExclusiveAsync()
        }
        ffmpegPlayer.onUsbPlaybackStarted = {
            usbExclusiveManager.setStreamingState(true)
            _usbOutputSampleRate.value = ffmpegPlayer.usbActualOutputSampleRate
            // 在 USB 真正启动后再创建音量控制器，确保 handle 有效
            val h = UsbAudioEngine.currentHandle
            if (h != 0L) {
                usbVolumeController?.unregister()
                usbVolumeController = UsbVolumeController(context, h).also {
                    it.register()
                }
            }
        }
        ffmpegPlayer.onUsbPlaybackStopped = {
            usbExclusiveManager.setStreamingState(false)
        }
        ffmpegPlayer.usbExclusiveMode = true
        ffmpegPlayer.usbBitPerfectMode = AppPreferences.Player.bitPerfectEnabled
        _usbExclusiveActive.value = true

        // 获取 USB 专用 WakeLock（防止 OTG 被系统关闭）
        try {
            val intent = android.content.Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.ACQUIRE_USB_WAKELOCK"
            }
            context.startService(intent)
        } catch (_: Exception) {}

        Log.i(TAG, "USB exclusive prepared: ${device.productName}")

        // 通知：USB DAC 初始化成功
        android.widget.Toast.makeText(
            context,
            "USB DAC 初始化成功！",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    suspend fun enableUsbExclusive(): Boolean {
        if (_usbExclusiveActive.value && usbExclusiveManager.isDeviceConnected()) {
            Log.i(TAG, "USB exclusive already enabled")
            return true
        }

        val device = usbExclusiveManager.findUsbAudioDevice()
        if (device == null) {
            Log.w(TAG, "No USB audio device found")
            return false
        }

        val hasPermission = usbExclusiveManager.requestPermission(device, force = true)
        if (!hasPermission) {
            Log.e(TAG, "USB permission denied")
            return false
        }

        val wasPlaying = _playState.value == PlayState.PLAYING
        val currentSong = _currentSong.value

        ffmpegPlayer.releaseAudioTrackForUsb()
        activateUsbEngine(device)
        Log.i(TAG, "USB exclusive mode enabled: ${device.productName}")

        if (wasPlaying && currentSong != null) {
            try {
                play(currentSong)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume playback after USB exclusive enable", e)
            }
        }

        return true
    }

    fun disableUsbExclusive() {
        Log.i(TAG, "Disabling USB exclusive mode")

        val wasUsingUsb = ffmpegPlayer.usbExclusiveMode || _usbExclusiveActive.value
        if (wasUsingUsb) {
            try {
                ffmpegPlayer.stop()
            } catch (_: Exception) {
            }
        }

        clearUsbExclusiveState(releaseManager = true, notifyNativeDetached = true)

        Log.i(TAG, "USB exclusive mode disabled")
    }

    private fun handleUsbDeviceDetached() {
        Log.i(TAG, "Handling USB device detached")
        try {
            ffmpegPlayer.stop()
        } catch (_: Exception) {
        }
        clearUsbExclusiveState(releaseManager = false, notifyNativeDetached = false)
    }

    private fun clearUsbExclusiveState(releaseManager: Boolean, notifyNativeDetached: Boolean) {
        ffmpegPlayer.usbExclusiveMode = false
        ffmpegPlayer.usbActualOutputSampleRate = 0
        ffmpegPlayer.usbPrepareForPlayback = null
        ffmpegPlayer.onUsbTransportLost = null
        ffmpegPlayer.onUsbPlaybackStarted = null
        ffmpegPlayer.onUsbPlaybackStopped = null
        _usbOutputSampleRate.value = 0

        // 注销系统音量监听
        usbVolumeController?.unregister()
        usbVolumeController = null

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
        if (releaseManager) {
            usbExclusiveManager.release("disableUsbExclusive")
        }
        _usbExclusiveActive.value = false

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } catch (_: Exception) {
        }

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
        val hwVol = AppPreferences.Player.hardwareFeatureUnitEnabled

        // 参考实例模式：策略变更必须伴随完整的 stop → setPolicy → start
        if (sharedUsbAudioEngine.isInitialized() && currentUsbDevice != null) {
            Log.i(TAG, "Bit-perfect change requires full USB restart")
            if (_playState.value == PlayState.PLAYING) {
                pause()
            }
            val ok = restartUsbWithPolicy(exclusive, enabled, hwVol)
            if (ok && enabled) {
                // 通知：完美比特已开启
                android.widget.Toast.makeText(
                    context,
                    "完美比特！",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return if (ok) 0 else -2
        }

        // USB 未初始化，仅持久化策略，下次播放生效
        sharedUsbAudioEngine.nativeSetPolicy(exclusive, enabled, hwVol)
        AppPreferences.Player.bitPerfectEnabled = enabled
        ffmpegPlayer.usbBitPerfectMode = enabled
        if (enabled) {
            // 通知：完美比特已开启
            android.widget.Toast.makeText(
                context,
                "完美比特！",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        Log.i(TAG, "Bit-perfect set to $enabled (will take effect on next playback)")
        return 0
    }

    /** 设置硬件 Feature Unit 音量控制。开启前自动验证硬件音量安全性。 */
    fun setUsbHardwareFeatureUnitEnabled(enabled: Boolean): Int {
        if (enabled) {
            val h = sharedUsbAudioEngine.currentHandle
            val validateResult = if (h != 0L) sharedUsbAudioEngine.nativeValidateHardwareVolume(h) else -1
            if (validateResult != 0) {
                Log.w(TAG, "Hardware Feature Unit validation failed: $validateResult, refusing to enable")
                return validateResult
            }
        }
        val exclusive = _usbExclusiveActive.value
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled

        // 参考实例模式：策略变更必须伴随完整的 stop → setPolicy → start
        if (sharedUsbAudioEngine.isInitialized() && currentUsbDevice != null) {
            Log.i(TAG, "Hardware Feature Unit change requires full USB restart")
            if (_playState.value == PlayState.PLAYING) {
                pause()
            }
            val ok = restartUsbWithPolicy(exclusive, bitPerfect, enabled)
            return if (ok) 0 else -2
        }

        sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, enabled)
        AppPreferences.Player.hardwareFeatureUnitEnabled = enabled
        Log.i(TAG, "Hardware Feature Unit set to $enabled (will take effect on next playback)")
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
                Log.i(TAG, "USB hardware volume balance repaired")
            } else {
                Log.w(TAG, "USB hardware volume repair failed: $result")
            }
            result
        } catch (e: Throwable) {
            Log.e(TAG, "USB hardware volume repair exception", e)
            -99
        }
    }

    /** 当前模式下音量是否可控（bit-perfect + 无 FU = 不可控） */
    fun canControlUsbVolume(): Boolean {
        return try {
            val h = sharedUsbAudioEngine.currentHandle
            if (h == 0L) return true
            sharedUsbAudioEngine.nativeCanControlVolume(h)
        } catch (_: Throwable) {
            true
        }
    }

    /** 步进 USB 硬件音量（实体键映射）。正数增大，负数减小。 */
    fun stepUsbVolume(delta: Float) {
        usbVolumeController?.stepVolume(delta)
    }

    /** 获取当前 USB 硬件音量分贝值（从 DAC 读取） */
    fun getUsbVolumeDb(): Float {
        return try {
            val h = sharedUsbAudioEngine.currentHandle
            if (h == 0L) 0f else sharedUsbAudioEngine.nativeGetVolumeDb(h)
        } catch (_: Throwable) { 0f }
    }

    /** 直接设置 USB 硬件音量（0..1 线性值） */
    fun setUsbVolumeLinear(linear: Float) {
        usbVolumeController?.setVolumeLinear(linear)
    }

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
            Log.e(TAG, "restartUsbWithPolicy: no USB device")
            return false
        }
        val sr = sharedUsbAudioEngine.currentSampleRate.let { if (it > 0) it else 48000 }
        val ch = sharedUsbAudioEngine.currentChannels.let { if (it > 0) it else 2 }
        val bits = sharedUsbAudioEngine.currentBits.let { if (it > 0) it else 16 }

        // 1. 完全停止 ffmpeg 播放器（不只是暂停）
        try { ffmpegPlayer.stop() } catch (_: Throwable) {}
        _playState.value = PlayState.STOPPED

        // 2. 停止 USB 流
        usbExclusiveManager.stopStreaming("policy_change")

        // 3. 设置新策略
        sharedUsbAudioEngine.nativeSetUsbExclusiveActive(exclusive)
        sharedUsbAudioEngine.nativeSetPolicy(exclusive, bitPerfect, hardwareVolumeRequested)
        ffmpegPlayer.usbBitPerfectMode = bitPerfect

        // 4. 释放旧 native，通过 prepareForPlayback 重新初始化
        sharedUsbAudioEngine.release()
        usbExclusiveManager.release()
        val ok = usbExclusiveManager.prepareForPlayback(sr, bits, ch)

        if (ok) {
            Log.i(TAG, "restartUsbWithPolicy OK: exclusive=$exclusive bitPerfect=$bitPerfect hwVol=$hardwareVolumeRequested")
            // 5. 刷新音量控制器（重新注册会同步系统音量到硬件）
            usbVolumeController?.unregister()
            usbVolumeController?.register()
        }

        return ok
    }

    private fun recoverUsbExclusiveAsync() {
        if (!recoveringUsb.compareAndSet(false, true)) {
            Log.d(TAG, "USB recovery already in progress, skip")
            return
        }
        val wasPlaying = _playState.value == PlayState.PLAYING
        val currentSong = _currentSong.value
        scope.launch(Dispatchers.IO) {
            try {
                Log.e(TAG, "USB transport lost, full recovery required")
                try { ffmpegPlayer.stop() } catch (_: Throwable) {}
                try { sharedUsbAudioEngine.release() } catch (_: Throwable) {}
                try { usbExclusiveManager.release() } catch (_: Throwable) {}

                // ① 清除旧状态，否则 activateUsbEngine 的 sameDeviceAlreadyActive 检查会直接跳过
                currentUsbDevice = null
                _usbExclusiveActive.value = false

                delay(600)
                val device = usbExclusiveManager.findUsbAudioDevice()
                if (device == null) {
                    Log.e(TAG, "USB recovery failed: no USB audio device")
                    _usbExclusiveActive.value = false
                    return@launch
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                if (!usbManager.hasPermission(device)) {
                    Log.e(TAG, "USB recovery failed: no permission")
                    usbExclusiveManager.requestPermissionSafely(device)
                    return@launch
                }
                // ② 通过 requestPermissionSafely 重新设置 currentDevice（权限已有时同步完成）
                //    它会触发 onDeviceReady → activateUsbEngine，完成全部初始化
                usbExclusiveManager.requestPermissionSafely(device)
                Log.i(TAG, "USB recovery success, device ready for playback")
                if (wasPlaying && currentSong != null) {
                    try {
                        play(currentSong)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume playback after USB recovery", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "USB recovery exception", e)
                _usbExclusiveActive.value = false
            } finally {
                recoveringUsb.set(false)
            }
        }
    }

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        scope.launch {
            transportMutex.withLock {
                playInternal(song, queue, index)
            }
        }
    }

    private fun playInternal(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        Log.d(TAG, "play() called: title=${song.title}, path=${song.path}, isReleased=$isReleased")
        if (isReleased) {
            Log.w(TAG, "play() skip: isReleased")
            return
        }
        // USB recovery 进行中，等待完成后再播放
        if (recoveringUsb.get()) {
            Log.w(TAG, "play() deferred: USB recovery in progress")
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
        // 500ms 防抖：同一首歌在 PREPARING 状态下重复请求则忽略
        val now = android.os.SystemClock.elapsedRealtime()
        if (song.path == lastPlayRequestPath &&
            now - lastPlayRequestTime < 500 &&
            ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING
        ) {
            Log.w(TAG, "Duplicate play request ignored while preparing: ${song.path}")
            return
        }
        lastPlayRequestPath = song.path
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
                // 新队列，初始化随机袋子
                if (_isShuffle.value) {
                    initShuffleBag(safeIndex)
                }
            } else {
                val currentQueue = _queue.value.songs.toMutableList()
                val existingIndex = currentQueue.indexOfFirst { it.path == song.path }
                if (existingIndex >= 0) {
                    _queue.value = _queue.value.copy(currentIndex = existingIndex)
                } else {
                    currentQueue.add(song)
                    _queue.value = PlayQueue(songs = currentQueue, currentIndex = currentQueue.size - 1)
                }
            }

            _currentSong.value = song
            _position.value = 0L
            _duration.value = 0L
            applyReplayGain(song)

            enrichJob?.cancel()
            if (!isRemoteUrl) {
                enrichJob = scope.launch(Dispatchers.IO) {
                    try {
                        val enriched = com.rawsmusic.module.scanner.MediaStoreScanner.enrichSong(song)
                        val changed = enriched.sampleRate != song.sampleRate ||
                            enriched.bitRate != song.bitRate ||
                            enriched.bitsPerSample != song.bitsPerSample ||
                            enriched.channelCount != song.channelCount ||
                            enriched.encodingFormat != song.encodingFormat
                        if (changed) {
                            com.rawsmusic.module.data.repository.MusicRepository.updateSong(enriched)
                            withContext(Dispatchers.Main) {
                                _currentSong.value = enriched
                            }
                            val q = _queue.value
                            val idx = q.songs.indexOfFirst { it.path == song.path }
                            if (idx >= 0) {
                                val newList = q.songs.toMutableList()
                                newList[idx] = enriched
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
                                            val idx2 = q2.songs.indexOfFirst { it.path == nextSong.path }
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
                                            val idx4 = q4.songs.indexOfFirst { it.path == s.path }
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
            ffmpegPlayer.play(song.path)

            scope.launch {
                delay(1000)
                checkBluetoothOutput()
            }

            // 如果有待恢复的播放位置且是同一首歌，自动 seek 到保存位置
            if (pendingSeekPosition > 0 && pendingSeekPath == song.path) {
                val seekPos = pendingSeekPosition
                pendingSeekPosition = -1L
                pendingSeekPath = null
                Log.d(TAG, "Restoring playback position: ${seekPos}ms for ${song.title}")
                scope.launch {
                    delay(300) // 等待播放器初始化
                    if (ffmpegPlayer.state == FfmpegAudioPlayer.State.PLAYING ||
                        ffmpegPlayer.state == FfmpegAudioPlayer.State.PREPARING) {
                        seekTo(seekPos)
                    }
                }
            } else if (pendingSeekPosition > 0) {
                // 播放的是不同的歌，清除待恢复状态
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
        // 新队列，初始化随机袋子
        if (_isShuffle.value) {
            initShuffleBag(safeIndex)
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

    fun playPause() {
        if (isReleased) return
        when (ffmpegPlayer.state) {
            FfmpegAudioPlayer.State.PLAYING -> ffmpegPlayer.pause()
            FfmpegAudioPlayer.State.PAUSED -> ffmpegPlayer.resume()
            else -> {
                // 没有在播放，重新开始当前歌曲
                _currentSong.value?.let { play(it) }
            }
        }
    }

    fun pause() {
        if (isReleased) return
        ffmpegPlayer.pause()
        savePosition()
    }

    fun resume() {
        if (isReleased) return
        ffmpegPlayer.resume()
    }

    fun stop() {
        if (isReleased) return
        savePosition() // 停止前保存当前播放位置
        ffmpegPlayer.stop()
        _playState.value = PlayState.STOPPED
        stopProgressUpdate()
        consecutiveFailures = 0
    }

    fun seekTo(positionMs: Long) {
        if (isReleased) return
        ffmpegPlayer.seekTo(positionMs)
        _position.value = positionMs
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
            PlayMode.SHUFFLE_OFF -> (q.currentIndex + 1) % q.songs.size
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_SONG -> {
                getNextShuffledIndex()
            }
            PlayMode.SHUFFLE_BOTH -> {
                // 单曲循环：手动下一首也重播当前歌曲
                q.currentIndex
            }
        }

        if (nextIndex < 0 || nextIndex !in q.songs.indices) return
        savePosition()
        val nextSong = q.songs[nextIndex]
        _queue.value = q.copy(currentIndex = nextIndex)
        play(nextSong, q.songs, nextIndex)
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
            play(prev, q.songs, prevIdx)
            return
        }

        val prevIndex = when (_playMode.value) {
            PlayMode.SHUFFLE_OFF -> {
                if (q.currentIndex > 0) q.currentIndex - 1 else q.songs.size - 1
            }
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_SONG -> {
                getPreviousShuffledIndex()
            }
            PlayMode.SHUFFLE_BOTH -> {
                // 单曲循环：手动上一首也重播当前歌曲
                q.currentIndex
            }
        }

        if (prevIndex < 0 || prevIndex !in q.songs.indices) return
        savePosition()
        val prevSong = q.songs[prevIndex]
        _queue.value = q.copy(currentIndex = prevIndex)
        _currentSong.value = null
        play(prevSong, q.songs, prevIndex)
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
        _isShuffle.value = !_isShuffle.value
        AppPreferences.Player.isShuffle = _isShuffle.value
        if (_isShuffle.value) {
            shuffleQueue()
        }
    }

    fun cyclePlayMode() {
        val newMode = PlayMode.cycle(_playMode.value)
        _playMode.value = newMode
        AppPreferences.Player.playMode = newMode
        applyPlayMode(newMode)
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        AppPreferences.Player.playMode = mode
        applyPlayMode(mode)
    }

    private fun applyPlayMode(mode: PlayMode) {
        when (mode) {
            PlayMode.SHUFFLE_OFF -> {
                _isShuffle.value = false
                _repeatMode.value = RepeatMode.ALL
            }
            PlayMode.SHUFFLE_ALL -> {
                _isShuffle.value = true
                _repeatMode.value = RepeatMode.ALL
            }
            PlayMode.SHUFFLE_SONG -> {
                _isShuffle.value = true
                _repeatMode.value = RepeatMode.OFF
            }
            PlayMode.SHUFFLE_BOTH -> {
                _isShuffle.value = false
                _repeatMode.value = RepeatMode.ONE
            }
        }
        AppPreferences.Player.isShuffle = _isShuffle.value
        AppPreferences.Player.repeatMode = _repeatMode.value
        if (_isShuffle.value && _queue.value.songs.size > 1) {
            try { shuffleQueue() } catch (_: Exception) {}
        }
    }

    fun setVolume(volume: Float) {
        if (isReleased) return
        AppPreferences.Player.volume = volume
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
                _playState.value = PlayState.PLAYING
                startProgressUpdate()
                val sessionId = ffmpegPlayer.audioSessionId
                Log.d("VirtualizerDebug", "Player PLAYING, audioSessionId=$sessionId")
                if (sessionId != 0) {
                    onAudioSessionChanged?.invoke(sessionId)
                    initEqualizerController(sessionId)
                }
            }
            FfmpegAudioPlayer.State.PAUSED -> {
                _playState.value = PlayState.PAUSED
                stopProgressUpdate()
            }
            FfmpegAudioPlayer.State.PREPARING -> {
                _playState.value = PlayState.PREPARING
            }
            FfmpegAudioPlayer.State.STOPPED -> {
                _playState.value = PlayState.STOPPED
                stopProgressUpdate()
            }
            FfmpegAudioPlayer.State.ERROR -> {
                _playState.value = PlayState.ERROR
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
                _playState.value = PlayState.STOPPED
                stopProgressUpdate()
                handlePlaybackComplete()
            }
            FfmpegAudioPlayer.State.IDLE -> {
                _playState.value = PlayState.IDLE
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
            msg.contains("device")
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

    private fun shuffleQueue() {
        val currentSong = _currentSong.value
        val currentQueue = _queue.value.songs.toMutableList()
        if (currentQueue.size <= 1) return
        val curIdx = _queue.value.currentIndex
        if (curIdx !in currentQueue.indices) return
        val current = currentQueue.removeAt(curIdx)
        currentQueue.shuffle()
        currentQueue.add(0, current)
        _queue.value = PlayQueue(songs = currentQueue, currentIndex = 0, isShuffle = true)
        // 初始化随机袋子：除了当前歌曲外的所有索引
        initShuffleBag(0)
    }

    /**
     * 初始化随机袋子：将除 currentIdx 外的所有索引加入袋子并打乱
     */
    private fun initShuffleBag(currentIdx: Int) {
        shuffleBag.clear()
        shufflePlayedHistory.clear()
        val size = _queue.value.songs.size
        if (size <= 1) return
        for (i in 0 until size) {
            if (i != currentIdx) {
                shuffleBag.add(i)
            }
        }
        shuffleBag.shuffle()
        shufflePlayedHistory.addLast(currentIdx)
    }

    /**
     * 从随机袋子中取下一首索引。袋子为空时重新填充。
     * 返回 -1 表示队列为空或只有一首歌。
     */
    private fun getNextShuffledIndex(): Int {
        val size = _queue.value.songs.size
        if (size <= 1) return if (size == 1) 0 else -1
        if (shuffleBag.isEmpty()) {
            // 本循环结束，重新填充袋子（排除当前歌曲）
            val currentIdx = _queue.value.currentIndex
            for (i in 0 until size) {
                if (i != currentIdx) {
                    shuffleBag.add(i)
                }
            }
            shuffleBag.shuffle()
        }
        val nextIdx = shuffleBag.removeFirst()
        shufflePlayedHistory.addLast(nextIdx)
        // 限制历史长度，避免内存膨胀
        if (shufflePlayedHistory.size > size * 2) {
            repeat(size / 2) { shufflePlayedHistory.removeFirst() }
        }
        return nextIdx
    }

    /**
     * 从随机历史中取上一首索引。历史为空时返回随机索引。
     */
    private fun getPreviousShuffledIndex(): Int {
        val size = _queue.value.songs.size
        if (size <= 1) return if (size == 1) 0 else -1
        // 当前歌曲放回袋子头部（下次优先播放）
        val currentIdx = _queue.value.currentIndex
        if (currentIdx >= 0) shuffleBag.add(0, currentIdx)
        if (shufflePlayedHistory.size >= 2) {
            // 移除当前歌曲的记录
            shufflePlayedHistory.removeLast()
            return shufflePlayedHistory.last()
        }
        // 历史为空，随机选一个
        return (0 until size).filter { it != currentIdx }.random()
    }

    /** 根据歌曲 ReplayGain 标签和用户设置，计算并应用增益因子 */
    private fun applyReplayGain(song: AudioFile) {
        val prefs = AppPreferences.Player
        if (prefs.replayGainEnabled) {
            val gainDB = when (prefs.replayGainMode) {
                1 -> song.trackGain
                2 -> song.albumGain
                else -> 0f
            }
            val peak = when (prefs.replayGainMode) {
                1 -> song.trackPeak
                2 -> song.albumPeak
                else -> 1.0f
            }
            var linearGain = if (gainDB != 0f) Math.pow(10.0, gainDB / 20.0).toFloat() else 1.0f
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

    /** 合成用户音量和 ReplayGain 系数 */
    private fun applyComposedVolume() {
        val baseVolume = AppPreferences.Player.volume
        val composed = (baseVolume * replayGainVolumeModifier).coerceIn(0f, 1f)
        Log.d(TAG, "applyComposedVolume: base=$baseVolume, rgMod=$replayGainVolumeModifier, composed=$composed")
        if (!isReleased) {
            ffmpegPlayer.setVolume(composed)
        }
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressJob = scope.launch {
            var saveCounter = 0
            while (isActive && !isReleased) {
                try {
                    _position.value = ffmpegPlayer.positionMs
                    _duration.value = ffmpegPlayer.durationMs
                    saveCounter++
                    if (saveCounter >= 25) {
                        saveCounter = 0
                        savePosition()
                    }
                } catch (e: Exception) {
                    break
                }
                delay(50)
            }
        }
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
                    }
                    arr.put(obj)
                }
                AppPreferences.Player.playQueueSongsJson = arr.toString()
            } catch (_: Exception) {}
        }
    }

    private fun savePosition() {
        try {
            AppPreferences.Player.lastPosition = ffmpegPlayer.positionMs
        } catch (_: Exception) {}
    }

    private fun restoreState() {
        applyComposedVolume()
        // 恢复立体声扩展设置
        val savedVirtualizer = AppPreferences.Equalizer.virtualizer
        if (savedVirtualizer > 0) {
            ffmpegPlayer.stereoWidenFactor = savedVirtualizer / 1000f
        }
        // 恢复互馈设置
        restoreCrossfeedSettings()
    }

    /**
     * 恢复上次播放的歌曲（但不自动播放）
     */
    fun restoreLastSong(): AudioFile? {
        val lastPath = AppPreferences.Player.lastSongPath
        if (lastPath.isBlank()) return null

        val allRepoSongs = com.rawsmusic.module.data.repository.MusicRepository.getAllSongs()
        val repoSongMap = allRepoSongs.associateBy { it.path }
        val repoSong = repoSongMap[lastPath]

        val song = repoSong ?: AudioFile(
            id = AppPreferences.Player.lastSongId,
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
        val savedPosition = AppPreferences.Player.lastPosition
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
                        val repoQueueSong = repoSongMap[path]
                        if (repoQueueSong != null) {
                            savedSongs.add(repoQueueSong)
                        } else {
                            savedSongs.add(AudioFile(
                                id = obj.getLong("id"),
                                path = path,
                                title = obj.getString("title"),
                                artist = obj.getString("artist"),
                                album = obj.getString("album"),
                                albumId = obj.getLong("albumId"),
                                duration = obj.getLong("duration"),
                                albumArtPath = obj.optString("albumArtPath", "")
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

        return song
    }

    fun release() {
        if (isReleased) return
        isReleased = true
        saveState()
        stopProgressUpdate()
        scope.cancel()
        onAudioSessionChanged = null
        equalizerController?.release()
        equalizerController = null

        disableUsbExclusive()
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
        val engine = ffmpegPlayer.dspEngine ?: return
        if (!engine.isInitialized()) return
        engine.setCrossfeedEnabled(enabled)
        AppPreferences.Equalizer.crossfeedEnabled = enabled
        Log.d(TAG, "setCrossfeedEnabled: $enabled")
    }

    /**
     * 设置互馈参数
     * @param lowCutFreq 高通截止频率 (Hz)，50-1000
     * @param highCutFreq 低通截止频率 (Hz)，500-8000
     * @param attenuationDB 衰减量 (dB)，0.0-15.0
     */
    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) {
        val engine = ffmpegPlayer.dspEngine ?: return
        if (!engine.isInitialized()) return
        engine.setCrossfeedParams(lowCutFreq, highCutFreq, attenuationDB)
        AppPreferences.Equalizer.crossfeedLowCut = lowCutFreq.toInt()
        AppPreferences.Equalizer.crossfeedHighCut = highCutFreq.toInt()
        AppPreferences.Equalizer.crossfeedAttenuation = (attenuationDB * 10f).toInt()
        Log.d(TAG, "setCrossfeedParams: lowCut=$lowCutFreq, highCut=$highCutFreq, atten=$attenuationDB")
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
