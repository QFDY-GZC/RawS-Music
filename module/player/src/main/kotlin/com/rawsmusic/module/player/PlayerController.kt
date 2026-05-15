package com.rawsmusic.module.player

import android.content.Context
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

    // FFmpeg + AudioTrack 播放器
    private var ffmpegPlayer = FfmpegAudioPlayer(context)
    val ffmpegPlayerRef: FfmpegAudioPlayer get() = ffmpegPlayer

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

    private val _repeatMode = MutableStateFlow(AppPreferences.Player.repeatMode)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(AppPreferences.Player.isShuffle)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _playMode = MutableStateFlow(AppPreferences.Player.playMode)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isReleased = false
    private var consecutiveFailures = 0
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

    /** 回放增益音量系数 — 由 applyReplayGain() 计算后与用户音量合成 */
    private var replayGainVolumeModifier = 1.0f
    private val recoveringUsb = java.util.concurrent.atomic.AtomicBoolean(false)
    private var wasPlayingBeforeFocusLoss = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastPlayRequestPath: String? = null
    private var lastPlayRequestTime = 0L

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
                activateUsbEngine(device)
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

        if (song.path.isBlank() || !java.io.File(song.path).exists()) {
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

            CoroutineScope(Dispatchers.IO).launch {
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
                } catch (_: Exception) {
                }
            }

            Log.d(TAG, "Starting FFmpeg playback: ${song.path}")
            ffmpegPlayer.play(song.path)

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
        precacheJob = CoroutineScope(Dispatchers.IO).launch {
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
            PlayMode.SHUFFLE_ALL, PlayMode.SHUFFLE_SONG, PlayMode.SHUFFLE_BOTH -> {
                if (q.songs.size <= 1) 0
                else q.songs.indices.filter { it != q.currentIndex }.random()
            }
        }

        if (nextIndex !in q.songs.indices) return
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
            else -> {
                if (q.songs.size <= 1) 0
                else q.songs.indices.filter { it != q.currentIndex }.random()
            }
        }

        if (prevIndex !in q.songs.indices) return
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
                // 最后一首播放完毕，停止
            }
        }
    }

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
                delay(200)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
    }

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
        // 保存队列信息
        try {
            val q = _queue.value
            AppPreferences.Player.currentQueueIndex = q.currentIndex
            val arr = org.json.JSONArray()
            for (s in q.songs) {
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
    }

    /**
     * 恢复上次播放的歌曲（但不自动播放）
     */
    fun restoreLastSong(): AudioFile? {
        val lastPath = AppPreferences.Player.lastSongPath
        if (lastPath.isBlank()) return null

        val allRepoSongs = com.rawsmusic.module.data.repository.MusicRepository.getAllSongs()
        val repoSong = allRepoSongs.find { it.path == lastPath }

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
                        val repoQueueSong = allRepoSongs.find { it.path == path }
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
