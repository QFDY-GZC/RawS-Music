package com.rawsmusic.module.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.player.AudioOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class UsbExclusiveManager(private val context: Context) {

    data class AudioFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )

    data class UsbAudioConfig(
        val iface: Int,
        val alt: Int,
        val outEp: Int,
        val fbEp: Int,
        val sampleRate: Int,
        val bits: Int,
        val channels: Int,
        val subslot: Int,
        val sourceBits: Int = bits
    ) {
        val frameSize: Int get() = channels * subslot
    }

    /**
     * 硬件音量 Feature Unit 信息。
     * Kotlin 层通过扫描 Configuration Descriptor 提前获取，供 UI 提示。
     * C++ 层会在 nativeInit 时做更完整的拓扑解析 + GET_RANGE/GET_CUR 安全验证。
     */
    data class VolumeInfo(
        val entityId: Int,       // Feature Unit Entity ID
        val interfaceNo: Int,    // AudioControl interface number
        val channel: Int,        // 0 = master, 1 = left, 2 = right …
        val hasMasterVolume: Boolean = false,
        val hasLeftVolume: Boolean = false,
        val hasRightVolume: Boolean = false
    )

    companion object {
        private const val TAG = "UsbExclusiveManager"
        private const val ACTION_USB_PERMISSION = "com.rawsmusic.USB_PERMISSION"
        private const val USB_CLASS_AUDIO = UsbConstants.USB_CLASS_AUDIO
        private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02
        private const val USB_DT_INTERFACE = 0x04
    }

    enum class State {
        IDLE,
        SEARCHING,
        REQUESTING_PERMISSION,
        READY,
        STREAMING,
        ERROR
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var lastResampledCacheTrimMs = 0L

    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var currentConfig: UsbAudioConfig? = null
    private var currentSourceSampleRate: Int = 0
    private var currentSourceBits: Int = 0

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    // 防重复弹窗：记录上次权限请求时间和被拒绝的设备
    private var lastPermissionRequestTime = 0L
    private var lastDeniedDeviceId = -1
    private val PERMISSION_COOLDOWN_MS = 3000L  // 3秒内不重复请求同一设备

    /** 硬件音量扫描结果（Kotlin 层提前扫描，供 UI 提示） */
    private val _volumeInfo = MutableStateFlow<VolumeInfo?>(null)
    val volumeInfo: StateFlow<VolumeInfo?> = _volumeInfo

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var permissionCallback: ((Boolean) -> Unit)? = null
    var onDeviceReady: ((UsbDevice) -> Unit)? = null
    var onDeviceAttached: ((UsbDevice) -> Unit)? = null
    var onDeviceDetached: ((UsbDevice?) -> Unit)? = null
    var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this@UsbExclusiveManager) {
                        val device = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLogger.d(TAG, "Permission result: device=${device?.deviceName}, granted=$granted")
                        if (granted && device != null) {
                            currentDevice = device
                            lastDeniedDeviceId = -1
                            _state.value = State.READY
                            permissionCallback?.invoke(true)
                            onPermissionResult?.invoke(device, true)
                            AppLogger.i(TAG, "Permission granted for ${device.productName}, notifying UI")
                            onDeviceReady?.invoke(device)
                        } else {
                            _state.value = State.ERROR
                            _error.value = "USB 权限被拒绝"
                            if (device != null) {
                                lastDeniedDeviceId = device.deviceId
                                lastPermissionRequestTime = System.currentTimeMillis()
                                onPermissionResult?.invoke(device, false)
                            }
                            permissionCallback?.invoke(false)
                        }
                        permissionCallback = null
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device?.deviceId == currentDevice?.deviceId) {
                        AppLogger.w(TAG, "USB device detached: ${device?.deviceName}")
                        // 设备拔掉时一定清掉 "拒绝" 标记，这样重新插拔后可以再次请求
                        lastDeniedDeviceId = -1
                        releaseForDetachedDevice()
                        onDeviceDetached?.invoke(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    AppLogger.i(TAG, "USB device attached: ${device?.deviceName}")
                    if (device != null) handleDeviceInserted(device)
                }
            }
        }
    }

    private fun handleDeviceInserted(device: UsbDevice) {
        if (currentDevice?.deviceId == device.deviceId) {
            AppLogger.i(TAG, "Attached device already known, ignore")
            return
        }
        // ---------- ✅ ② 拒绝后冷却检查 ----------
        val now = System.currentTimeMillis()
        if (device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            AppLogger.d(TAG, "Device ${device.deviceName} was recently denied, ignore attach")
            return
        }
        // 必须确认真的是 USB audio 设备
        if (!isUsbAudioOutputDevice(device)) {
            AppLogger.d(TAG, "Attached device is not USB audio, ignore")
            return
        }
        AppLogger.i(TAG, "USB audio device confirmed, dumping descriptor:")
        dumpInterfaces(device)
        // Attach handling: never request permission or auto-activate
        // from the BroadcastReceiver.  On MIUI/Android 16 this receiver runs on
        // the main thread during cold launch / task restore; starting the USB
        // permission/exclusive flow here can white-screen the app until the DAC
        // is unplugged.  Remember the DAC only.  Explicit user action or a real
        // playback request will call requestPermissionSafely()/requestPermission().
        rememberDeviceOnly(device, reason = "attach_broadcast_remember_only")
        onDeviceAttached?.invoke(device)
    }

    // ========== 公开 API ==========

    /**
     * 只扫描并记住 USB 音频设备，不 openDevice。
     */
    fun scanAndRememberDevice(): Boolean {
        val devices = usbManager.deviceList.values
        AppLogger.d(TAG, "Scanning ${devices.size} USB devices...")
        for (device in devices) {
            AppLogger.d(
                TAG,
                "Device: ${device.deviceName}, VID=${device.vendorId.toString(16)}, " +
                        "PID=${device.productId.toString(16)}, interfaces=${device.interfaceCount}"
            )
            if (isUsbAudioOutputDevice(device)) {
                currentDevice = device
                AppLogger.i(TAG, "Remembered USB audio device: ${device.productName}")
                dumpInterfaces(device)
                return true
            }
        }
        currentDevice = null
        AppLogger.w(TAG, "No USB audio output device found")
        return false
    }

    fun rememberDeviceOnly(device: UsbDevice, reason: String = "unknown") {
        if (!isUsbAudioOutputDevice(device)) {
            AppLogger.d(TAG, "rememberDeviceOnly ignored non-audio device: ${device.deviceName} reason=$reason")
            return
        }
        currentDevice = device
        _state.value = if (usbManager.hasPermission(device)) State.READY else State.IDLE
        _error.value = null
        AppLogger.i(TAG, "Remembered USB audio device only: ${device.productName} reason=$reason permission=${usbManager.hasPermission(device)}")
    }

    fun findUsbAudioDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        AppLogger.d(TAG, "Scanning ${deviceList.size} USB devices...")

        for (device in deviceList.values) {
            AppLogger.d(
                TAG,
                "Device: ${device.deviceName}, VID=${String.format("%04X", device.vendorId)}, " +
                        "PID=${String.format("%04X", device.productId)}, interfaces=${device.interfaceCount}"
            )

            if (isUsbAudioOutputDevice(device)) {
                AppLogger.i(TAG, "Found USB audio device: ${device.productName}")
                return device
            }
        }

        AppLogger.w(TAG, "No USB audio device with ISO OUT endpoint found")
        return null
    }

    /**
     * 请求 USB 权限。
     * 权限获取后只记住设备，不 openDevice。
     * openDevice 在 prepareForPlayback 中完成。
     */
    /**
     * 创建 USB 权限 PendingIntent。
     * Android 12+ 必须使用 FLAG_MUTABLE，否则系统无法填充 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED。
     */
    private fun usbPermissionPendingIntent(device: UsbDevice): PendingIntent {
        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
        return PendingIntent.getBroadcast(context, device.deviceId, permissionIntent, flags)
    }

    fun requestPermissionSafely(device: UsbDevice) {
        // 如果已经在请求同一个设备的权限，跳过（防止重复请求导致弹窗消失）
        val now = System.currentTimeMillis()
        if (_state.value == State.REQUESTING_PERMISSION &&
            currentDevice?.deviceId == device.deviceId) {
            if (now - lastPermissionRequestTime < 1500L) {
                AppLogger.d(TAG, "Already requesting permission for ${device.deviceName}, skip duplicate")
                return
            }
            AppLogger.w(TAG, "USB permission request appears stale, retrying for ${device.deviceName}")
        }
        // 避免在短时间内因上一次拒绝而再次请求（手动请求时可以强制 bypass）
        if (device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            AppLogger.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
            return
        }
        currentDevice = device
        if (usbManager.hasPermission(device)) {
            // 已经拥有权限 → 立刻切成 READY，并同时走 permission/device-ready 两个回调。
            // enableUsbExclusive() 的 pending 续接可能挂在 onPermissionResult 上；启动扫描则由 onDeviceReady 只做预准备。
            AppLogger.d(TAG, "Already have permission, notifying UI")
            currentDevice = device
            lastDeniedDeviceId = -1
            _state.value = State.READY
            onPermissionResult?.invoke(device, true)
            onDeviceReady?.invoke(device)
            return
        }
        // 进入 "请求中" 状态，让 UI 能显示 Loading
        _state.value = State.REQUESTING_PERMISSION
        lastPermissionRequestTime = now
        permissionCallback = { granted ->
            // 这里一般已经在 onReceive 中处理完毕，仅作防御
            if (!granted) {
                _state.value = State.ERROR
                _error.value = "USB 权限被拒绝"
            }
        }
        val pendingIntent = usbPermissionPendingIntent(device)
        AppLogger.d(TAG, "Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pendingIntent)
    }

    suspend fun requestPermission(device: UsbDevice, force: Boolean = false): Boolean {
        if (usbManager.hasPermission(device)) {
            AppLogger.d(TAG, "Already have permission for ${device.deviceName}")
            currentDevice = device
            lastDeniedDeviceId = -1
            _state.value = State.READY
            return true
        }
        // 冷却检查（除非 force 为 true）
        val now = System.currentTimeMillis()
        if (!force && device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            AppLogger.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
            return false
        }
        _state.value = State.REQUESTING_PERMISSION
        lastPermissionRequestTime = now
        return suspendCancellableCoroutine { cont ->
            permissionCallback = { granted ->
                if (granted) {
                    currentDevice = device
                    lastDeniedDeviceId = -1
                    _state.value = State.READY
                } else {
                    _state.value = State.ERROR
                    _error.value = "USB 权限被拒绝"
                    lastDeniedDeviceId = device.deviceId
                }
                if (cont.isActive) cont.resume(granted)
            }
            val pendingIntent = usbPermissionPendingIntent(device)
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    /**
     * Treat valid bit-depth and USB subslot/container as separate
     * choices.  24-bit sources should first try packed 24-in-3 when the user is
     * asking for native/bit-perfect 24-bit output; many 192k DAC alt-settings
     * expose 24/3 but reject 24/4 or 32/4.
     */
    private fun preferredUsbSubslotFor(
        sourceBits: Int,
        requestedTargetBits: Int,
        pcmMode: UsbPcmOutputMode,
        strictBitPerfect: Boolean,
        pcmDsdActive: Boolean
    ): Int {
        if (sourceBits <= 16) return 2
        if (pcmMode == UsbPcmOutputMode.PCM_24_PACKED) return 3
        if (pcmMode == UsbPcmOutputMode.PCM_24_IN_32 || pcmMode == UsbPcmOutputMode.PCM_32) return 4
        if (sourceBits == 24 && (strictBitPerfect || pcmDsdActive || requestedTargetBits <= 0 || requestedTargetBits == AudioOutputManager.BIT_DEPTH_24)) {
            return 3
        }
        return 4
    }

    /**
     * 播放每首歌/每次格式变化时调用。
     * 这里才 openDevice + 初始化 native。
     * connection 作为成员变量一直持有到 nativeClose 之后。
     */
    @Synchronized
    fun prepareForPlayback(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        srcFilePath: String? = null,
        allowFallback: Boolean = true,
        suppressDsdForRetry: Boolean = false
    ): Boolean {
        // 配置 native breadcrumb 日志路径（用于突发重启后的崩溃定位）
        try {
            val logPath = context.filesDir.absolutePath + "/usb_native_breadcrumb.log"
            UsbAudioEngine.nativeSetBreadcrumbPath(logPath)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to set breadcrumb path: ${e.message}")
        }

        val requestedTargetRate = AppPreferences.Player.usbTargetSampleRate
        val requestedTargetBits = AppPreferences.Player.usbTargetBitDepth
        val pcmMode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode)
        val sourceIsDsd = isLikelyDsdSource(srcFilePath, bits, sampleRate)
        val sourceDsdRateHz = if (sourceIsDsd) {
            val probed = srcFilePath?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) } ?: 0
            when {
                probed > 0 -> normalizeProbedDsdSourceRateHz(probed)
                sampleRate > 0 -> normalizeDsdSourceRateHz(sampleRate)
                else -> 2_822_400
            }
        } else {
            0
        }
        val dsdTransport = UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode)
        val caps = UsbAudioEngine.getDeviceCapabilities()
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled
        val sourceDsdMode = if (sourceIsDsd && !suppressDsdForRetry) {
            buildSupportedDsdSourceDirectModeConfig(
                sourceDsdRateHz = sourceDsdRateHz,
                requestedTransport = dsdTransport,
                capabilities = caps
            )
        } else {
            null
        }
        val pcmToDsdMode = if (!sourceIsDsd) {
            buildSupportedPcmToDsdModeConfig(
                // PCM→DSD is an explicit output transform. Do not let a stale
                // bit-perfect preference disable it; the active output profile
                // will turn PCM bit-perfect off for this session.
                enabled = AppPreferences.Player.dsdConversionEnabled &&
                    !suppressDsdForRetry,
                multiplier = AppPreferences.Player.dsdRate,
                requestedTransport = dsdTransport,
                capabilities = caps,
                sourceSampleRate = sampleRate
            )
        } else {
            null
        }
        val dsdMode = sourceDsdMode ?: pcmToDsdMode
        val pcmDsdActive = pcmToDsdMode != null
        val dsdTransportActive = dsdMode != null
        val sourceBitsForUsb = bits.coerceAtMost(32)
        val strictBitPerfectForUsb = bitPerfect && bits <= 32 && !pcmDsdActive
        if (bits > 32) {
            AppLogger.w(
                TAG,
                "prepareForPlayback: sourceBits=$bits exceeds USB PCM max, " +
                    "use decoder/native S32LE and request 32-bit USB format"
            )
        }
        if (bitPerfect && !strictBitPerfectForUsb) {
            AppLogger.w(TAG, "prepareForPlayback: strict bit-perfect disabled for >32-bit source")
        }
        UsbAudioEngine.setPcmOutputMode(pcmMode)
        val dsdPcmFallbackRate = if (sourceIsDsd && dsdMode == null) {
            caps?.supportedSampleRates
                ?.filter { it > 0 }
                ?.minByOrNull { kotlin.math.abs(it.toLong() - sampleRate.toLong()) }
                ?: sampleRate
        } else {
            sampleRate
        }
        if (sourceIsDsd && dsdMode == null && dsdPcmFallbackRate != sampleRate) {
            AppLogger.w(
                TAG,
                "DSD unsupported by device; PCM fallback sourceRate=$sampleRate deviceRate=$dsdPcmFallbackRate"
            )
        }
        val deviceSampleRate = when {
            dsdMode != null -> dsdMode.deviceSampleRate
            sourceIsDsd -> dsdPcmFallbackRate
            strictBitPerfectForUsb || dsdTransportActive || requestedTargetRate <= 0 -> sampleRate
            else -> requestedTargetRate
        }
        val requestedFormat = when {
            dsdMode != null -> Pair(dsdMode.deviceBits, dsdMode.deviceSubslot)
            strictBitPerfectForUsb || pcmDsdActive -> Pair(
                sourceBitsForUsb,
                preferredUsbSubslotFor(sourceBitsForUsb, requestedTargetBits, pcmMode, strictBitPerfectForUsb, pcmDsdActive)
            )
            pcmMode == UsbPcmOutputMode.PCM_16 -> Pair(16, 2)
            pcmMode == UsbPcmOutputMode.PCM_24_PACKED -> Pair(24, 3)
            pcmMode == UsbPcmOutputMode.PCM_24_IN_32 -> Pair(24, 4)
            pcmMode == UsbPcmOutputMode.PCM_32 -> Pair(32, 4)
            requestedTargetBits <= 0 -> Pair(
                sourceBitsForUsb,
                preferredUsbSubslotFor(sourceBitsForUsb, requestedTargetBits, pcmMode, strictBitPerfectForUsb, pcmDsdActive)
            )
            else -> Pair(
                AudioOutputManager.usbDeviceBitResolutionForTarget(requestedTargetBits, sourceBitsForUsb).coerceAtMost(32),
                AudioOutputManager.usbDeviceSubslotForTarget(requestedTargetBits, sourceBitsForUsb).coerceAtMost(4)
            )
        }
        val deviceBits = requestedFormat.first.coerceAtMost(32)
        val deviceSubslot = requestedFormat.second.coerceAtMost(4)
        AppLogger.w(TAG, "prepareForPlayback CHAIN: sourceSr=$sampleRate requestedTargetRate=$requestedTargetRate pcmMode=$pcmMode bitPerfect=$bitPerfect strictUsb=$strictBitPerfectForUsb sourceDsd=$sourceIsDsd sourceDsdRateHz=$sourceDsdRateHz pcmToDsd=$pcmDsdActive dsdMode=$dsdMode suppressDsdForRetry=$suppressDsdForRetry -> deviceSr=$deviceSampleRate deviceBits=$deviceBits deviceSubslot=$deviceSubslot")
        AppLogger.i(TAG, "prepareForPlayback: sourceSr=$sampleRate sourceDsdRateHz=$sourceDsdRateHz deviceSr=$deviceSampleRate sourceBits=$sourceBitsForUsb rawSourceBits=$bits deviceBits=$deviceBits deviceSubslot=$deviceSubslot ch=$channels fallback=$allowFallback bitPerfect=$bitPerfect sourceDsd=$sourceIsDsd pcmToDsd=$pcmDsdActive dsdMode=$dsdMode suppressDsdForRetry=$suppressDsdForRetry targetRatePref=$requestedTargetRate targetBitsPref=$requestedTargetBits pcmMode=$pcmMode")
        val device = currentDevice ?: findUsbAudioDevice()?.also {
            rememberDeviceOnly(it, reason = "prepare_fallback_find")
            AppLogger.w(TAG, "prepareForPlayback recovered missing currentDevice by rescanning USB devices")
        } ?: run {
            AppLogger.e(TAG, "prepareForPlayback failed: currentDevice=null and fallback scan found nothing")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            AppLogger.e(TAG, "prepareForPlayback failed: no USB permission")
            return false
        }

        var cfg = selectConfigForFormat(deviceSampleRate, deviceBits, deviceSubslot, channels, sourceBitsForUsb)
        if (cfg == null) {
            if (dsdMode != null) {
                AppLogger.w(
                    TAG,
                    "DSD transport unsupported by USB descriptors: sourceDsd=$sourceIsDsd " +
                        "mode=$dsdMode; session fallback to PCM path, user preference preserved"
                )
                UsbAudioEngine.setDsdConversion(false, AppPreferences.Player.dsdRate, AppPreferences.Player.dsdConversionType, false, false)
                return prepareForPlayback(
                    sampleRate = sampleRate,
                    bits = bits,
                    channels = channels,
                    srcFilePath = srcFilePath,
                    allowFallback = false,
                    suppressDsdForRetry = true
                )
            }
            if (strictBitPerfectForUsb) {
                AppLogger.e(
                    TAG,
                    "prepareForPlayback: strict bit-perfect requested but no exact USB config " +
                        "sourceSr=$sampleRate sourceBits=$sourceBitsForUsb ch=$channels " +
                        "deviceSr=$deviceSampleRate deviceBits=$deviceBits subslot=$deviceSubslot"
                )
                return false
            }
            // ------------------- bit-perfect strict mode must not soft-resample -------------------
            if (srcFilePath == null) {
                AppLogger.e(TAG, "No native USB config and no source file for soft-resample")
                return false
            }
            val (newPath, fmt) = softResampleIfNeeded(
                srcPath = srcFilePath,
                srcRate = deviceSampleRate,
                srcBits = sourceBitsForUsb,
                srcCh = channels,
                forceFallback = false
            )
            return prepareForPlayback(fmt.sampleRate, fmt.bitsPerSample, fmt.channels, newPath, allowFallback)
        }
        val runtimeForFastReuse = runCatching { UsbAudioEngine.getRuntimeFormat() }.getOrNull()
        val runtimeFeedbackEndpoint = runtimeForFastReuse?.feedbackEndpoint ?: -1
        val feedbackEndpointChangedForReuse = runtimeForFastReuse?.isValid == true &&
            cfg.fbEp >= 0 &&
            runtimeFeedbackEndpoint != cfg.fbEp
        val runtimeIsFeedbackDegradedForReuse = runCatching {
            UsbAudioEngine.getFeedbackState() == UsbAudioEngine.FeedbackState.DEGRADED ||
                UsbAudioEngine.getPacingMode() == UsbAudioEngine.PacingMode.FeedbackDegradedFixed
        }.getOrDefault(false)
        val mustReinitForFeedbackPolicy = feedbackEndpointChangedForReuse ||
            (cfg.fbEp == 0 && runtimeIsFeedbackDegradedForReuse)
        val sameConfigReady =
            currentConfig == cfg &&
            currentSourceSampleRate == sampleRate &&
            currentSourceBits == cfg.sourceBits &&
            connection != null &&
            UsbAudioEngine.currentHandle != 0L &&
            UsbAudioEngine.isInitialized() &&
            !UsbAudioEngine.isPolicyChangedSinceInit() &&
            !mustReinitForFeedbackPolicy &&
            isDeviceConnected()
        if (sameConfigReady) {
            val wasRunning = UsbAudioEngine.isRunning()
            AppLogger.i(TAG, "prepareForPlayback: fast reuse existing USB handle for cfg=$cfg wasRunning=$wasRunning")
            // 同格式切歌不要 close/open/重新枚举 USB。只 flush ring 并重置 session，
            // 让下一首直接预填并 nativeStart，避免 1~3 秒的 release/reclaim 延迟。
            UsbAudioEngine.flushForNextTrack("prepareForPlayback_fast_reuse_same_config")
            _state.value = State.READY
            return true
        } else if (mustReinitForFeedbackPolicy) {
            AppLogger.w(
                TAG,
                "prepareForPlayback: skip fast reuse because feedback policy/runtime changed " +
                    "cfgFb=0x${cfg.fbEp.toString(16)} runtimeFb=0x${runtimeFeedbackEndpoint.toString(16)} " +
                    "runtimeDegraded=$runtimeIsFeedbackDegradedForReuse cfg=$cfg runtime=$runtimeForFastReuse"
            )
        }

        closeAllNow()
        val conn = usbManager.openDevice(device)
            ?: return false.also { AppLogger.e(TAG, "openDevice failed") }
        connection = conn

        // 提前扫描 Feature Unit（供 UI 提示，C++ 层会做更完整的安全验证）
        val volInfo = queryHardwareVolume(device, conn)
        _volumeInfo.value = volInfo
        if (volInfo != null) {
            AppLogger.i(TAG, "Feature Unit descriptor hint: entityId=0x${volInfo.entityId.toString(16)} " +
                       "iface=${volInfo.interfaceNo} master=${volInfo.hasMasterVolume} " +
                       "L=${volInfo.hasLeftVolume} R=${volInfo.hasRightVolume}; " +
                       "native VolumeController validation is authoritative")
        } else {
            AppLogger.i(TAG, "No Volume Feature Unit descriptor hint found; native validation may still report final state")
        }

        val fd = conn.fileDescriptor
        AppLogger.w(
            TAG,
            "USB_INIT_FINAL sourceSr=$sampleRate deviceSr=${cfg.sampleRate} " +
                "prefRate=${AppPreferences.Player.usbTargetSampleRate} " +
                "bitPerfect=${AppPreferences.Player.bitPerfectEnabled} " +
                "sourceBits=${cfg.sourceBits} deviceBits=${cfg.bits} deviceSubslot=${cfg.subslot} frame=${cfg.frameSize}"
        )
        val handle = UsbAudioEngine.initWithHandle(
            fd = fd,
            sampleRate = cfg.sampleRate,
            sourceSampleRate = sampleRate,
            sourceBitsPerSample = cfg.sourceBits,
            channels = cfg.channels,
            bitsPerSample = cfg.bits,
            iface = cfg.iface,
            alt = cfg.alt,
            outEndpoint = cfg.outEp,
            feedbackEndpoint = cfg.fbEp,
            subslotSize = cfg.subslot
        )
        if (handle == 0L) {
            AppLogger.e(TAG, "nativeInitUsbDevice failed")
            closeLocked("nativeInitUsbDevice failed")
            if (dsdMode != null) {
                AppLogger.w(
                    TAG,
                    "native DSD/DoP init failed for sourceDsd=$sourceIsDsd mode=$dsdMode; " +
                        "session fallback to PCM path, user preference preserved"
                )
                UsbAudioEngine.setDsdConversion(false, AppPreferences.Player.dsdRate, AppPreferences.Player.dsdConversionType, false, false)
                return prepareForPlayback(
                    sampleRate = sampleRate,
                    bits = bits,
                    channels = channels,
                    srcFilePath = srcFilePath,
                    allowFallback = false,
                    suppressDsdForRetry = true
                )
            }
            if (strictBitPerfectForUsb) {
                AppLogger.e(TAG, "nativeInitUsbDevice failed in strict bit-perfect mode; soft fallback disabled")
                return false
            }
            AppLogger.e(TAG, "nativeInitUsbDevice failed, trying soft-resample fallback")
            if (srcFilePath != null && allowFallback) {
                val (newPath, fmt) = softResampleIfNeeded(srcFilePath, sampleRate, bits, channels, forceFallback = true)
                return prepareForPlayback(
                    sampleRate = fmt.sampleRate,
                    bits = fmt.bitsPerSample.coerceAtMost(32),
                    channels = fmt.channels,
                    srcFilePath = newPath,
                    allowFallback = false,
                    suppressDsdForRetry = suppressDsdForRetry
                )
            }
            return false
        }
        currentConfig = cfg
        currentSourceSampleRate = sampleRate
        currentSourceBits = cfg.sourceBits
        val preheatMs = AppPreferences.Player.usbDacPreheatMs
        if (preheatMs > 0) {
            AppLogger.i(TAG, "USB DAC preheat delay: ${preheatMs}ms before first playback event")
            try {
                Thread.sleep(preheatMs.toLong())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        _state.value = State.READY

        // 初始化完成后先同步一次无数据窗口安全音量，避免 USB DAC 在空缓冲时突发大声。
        try {
            UsbAudioEngine.nativeSetUsbSoftwareGain(0.0178f)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "sync USB base volume after init failed", t)
        }

        // Try to start HID listening for remote control support
        tryStartHidListening()
        
        return true
    }

    /**
     * 强制关闭所有 native / Java 资源，确保下次播放是干净状态。
     */
    private fun closeAllNow() {
        UsbAudioEngine.closeNative("prepareForPlayback fresh start")
        currentConfig = null
        currentSourceSampleRate = 0
        currentSourceBits = 0
        val conn = connection
        connection = null
        conn?.let {
            try { it.close() } catch (e: Exception) { AppLogger.w(TAG, "close failed", e) }
        }
        _state.value = State.IDLE
        _error.value = null
        _volumeInfo.value = null
    }

    /**
     * 切歌时：根据格式是否变化决定是否 stop/reinit
     * 同格式：保持 streaming，直接写入新数据
     * 不同格式：fade out → stop → close → open/init → prebuffer → start
     */
    @Synchronized
    fun prepareAndStartForTrack(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        firstPcmChunks: List<ByteArray> = emptyList()
    ): Boolean {
        AppLogger.i(TAG, "prepareAndStartForTrack: sr=$sampleRate bits=$bits ch=$channels chunks=${firstPcmChunks.size}")

        val subslot = preferredUsbSubslotFor(
            sourceBits = bits.coerceAtMost(32),
            requestedTargetBits = AppPreferences.Player.usbTargetBitDepth,
            pcmMode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode),
            strictBitPerfect = AppPreferences.Player.bitPerfectEnabled && bits <= 32,
            pcmDsdActive = AppPreferences.Player.dsdConversionEnabled
        )
        val config = selectConfigForFormat(sampleRate, bits.coerceAtMost(32), subslot, channels, sourceBits = bits.coerceAtMost(32))
        if (config == null) {
            AppLogger.e(TAG, "No USB config for ${sampleRate}/${bits}/${channels}")
            return false
        }

        val oldConfig = currentConfig
        val sameFormat = UsbAudioEngine.currentHandle != 0L && oldConfig == config

        if (sameFormat) {
            AppLogger.i(TAG, "Same format, keeping USB streaming running, just write new data")
            val handle = UsbAudioEngine.currentHandle
            if (handle == 0L) {
                AppLogger.e(TAG, "handle=0 unexpectedly")
                return false
            }
            for (chunk in firstPcmChunks) {
                UsbAudioEngine.safeNativeWriteHandle(handle, chunk, 0, chunk.size)
            }
            return true
        }

        AppLogger.i(TAG, "Format changed: old=$oldConfig new=$config, need stop/reinit")

        // 1. fade out 当前播放（如果有）
        fadeOutIfStreaming(durationMs = TransitionPreferences.transportDurationOrZero())

        // 2. stop
        stopStreaming("format_change")

        // 3. 重新初始化
        val ok = prepareForPlayback(sampleRate, bits, channels)
        if (!ok) {
            AppLogger.e(TAG, "prepareForPlayback failed")
            return false
        }
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L) {
            AppLogger.e(TAG, "handle=0 after prepare")
            return false
        }

        // 4. 先预填充，不要马上 start
        for (chunk in firstPcmChunks) {
            UsbAudioEngine.safeNativeWriteHandle(handle, chunk, 0, chunk.size)
        }

        // 5. 再启动
        val started = startStreaming()
        if (started) {
            setStreamingState(true)
        }
        return started
    }

    /**
     * 简单软件 fade out：通过 SoftwareVolume 渐变。
     * 如果当前不在 streaming 或 handle 已失效则跳过。
     */
    private fun fadeOutIfStreaming(durationMs: Int = 80) {
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L || !UsbAudioEngine.isInitialized()) return
        if (durationMs <= 0) return

        val steps = 8
        val stepMs = durationMs / steps
        try {
            for (i in steps downTo 0) {
                val vol = i.toFloat() / steps.toFloat()
                UsbAudioEngine.nativeSetVolume(handle, vol)
                Thread.sleep(stepMs.toLong())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 忽略 fade 失败
        }
    }

    /**
     * Fade in：streaming 已启动后渐增音量。
     */
    fun fadeInAfterStart(durationMs: Int = TransitionPreferences.transportDurationOrZero()) {
        val steps = 8
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L || !UsbAudioEngine.isInitialized()) return
        if (durationMs <= 0) {
            runCatching { UsbAudioEngine.nativeSetVolume(handle, 1.0f) }
            return
        }
        val stepMs = durationMs / steps
        try {
            for (i in 0..steps) {
                val vol = i.toFloat() / steps.toFloat()
                UsbAudioEngine.nativeSetVolume(handle, vol)
                Thread.sleep(stepMs.toLong())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 忽略 fade 失败
        }
    }

    @Synchronized
    fun startStreaming(): Boolean {
        val h = UsbAudioEngine.currentHandle
        if (h == 0L) {
            AppLogger.e(TAG, "startStreaming failed: currentHandle=0")
            return false
        }
        AppLogger.i(TAG, "startStreaming: calling nativeStart, handle=0x${h.toString(16)}")
        val ok = UsbAudioEngine.nativeStart(h)
        AppLogger.i(TAG, "nativeStart returned $ok")
        return ok
    }

    @Synchronized
    fun stopStreaming(reason: String = "unknown") {
        AppLogger.i(TAG, "stopStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L) {
            AppLogger.i(TAG, "stopStreaming: calling nativeStop, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativeStop(h)
        } else {
            AppLogger.i(TAG, "stopStreaming: handle=0, skipping nativeStop")
        }
    }

    @Synchronized
    fun pauseStreaming(reason: String = "pause") {
        AppLogger.i(TAG, "pauseStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L) {
            AppLogger.i(TAG, "pauseStreaming: calling nativePause, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativePause(h)
        } else {
            AppLogger.i(TAG, "pauseStreaming: handle=0, skipping nativePause")
        }
    }

    @Synchronized
    fun stopAndFlushStreaming(reason: String = "track_change") {
        AppLogger.i(TAG, "stopAndFlushStreaming called, reason=$reason")
        // 切歌/seek/设置变化：用 flushForNextTrack，不走 hard stop
        UsbAudioEngine.flushForNextTrack("stopAndFlushStreaming:$reason")
    }

    fun release(reason: String = "unknown") {
        AppLogger.w(TAG, "release requested: reason=$reason, state=${_state.value}")
        closeLocked("release:$reason")
    }

    @Synchronized
    fun resetPlaybackPipeline(reason: String = "unknown") {
        AppLogger.w(TAG, "resetPlaybackPipeline requested: reason=$reason, state=${_state.value}")
        closeAllNow()
    }

    fun releaseForDetachedDevice() {
        AppLogger.w(TAG, "releaseForDetachedDevice requested, state=${_state.value}")
        try {
            UsbAudioEngine.nativeOnUsbDetached()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeOnUsbDetached failed", t)
        }
        closeLocked("detached")
    }

    /**
     * 关闭 native handle + Java connection。
     * 统一走 UsbAudioEngine.closeNative()，不再直接调 nativeClose。
     */
    private fun closeLocked(reason: String) {
        // Stop HID listening before closing
        stopHidListening()
        
        UsbAudioEngine.closeNative("UsbExclusiveManager.closeLocked:$reason")
        currentConfig = null
        currentSourceSampleRate = 0
        currentSourceBits = 0
        val shouldForgetDevice = reason.contains("detached", ignoreCase = true)
        if (shouldForgetDevice) {
            currentDevice = null
        } else if (currentDevice != null) {
            AppLogger.i(
                TAG,
                "closeLocked: preserving remembered USB device ${currentDevice?.deviceName} reason=$reason"
            )
        }
        val conn = connection
        connection = null
        if (conn != null) {
            AppLogger.i(TAG, "UsbDeviceConnection.close conn=${System.identityHashCode(conn)}")
            try {
                conn.close()
            } catch (e: Exception) {
                AppLogger.w(TAG, "connection.close failed", e)
            }
        }
        val rememberedDevice = currentDevice
        _state.value = when {
            rememberedDevice == null -> State.IDLE
            usbManager.hasPermission(rememberedDevice) -> State.READY
            else -> State.IDLE
        }
        _error.value = null
        _volumeInfo.value = null
    }

    /**
     * Try to start HID listening for remote control support
     */
    private fun tryStartHidListening() {
        try {
            if (UsbAudioEngine.hasHidInterface()) {
                AppLogger.i(TAG, "Device has HID interface, starting HID listening")
                val started = UsbAudioEngine.startHidListening()
                if (started) {
                    AppLogger.i(TAG, "HID listening started successfully")
                } else {
                    AppLogger.w(TAG, "Failed to start HID listening")
                }
            } else {
                AppLogger.d(TAG, "Device does not have HID interface")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "HID initialization failed", t)
        }
    }

    /**
     * Stop HID listening
     */
    private fun stopHidListening() {
        try {
            if (UsbAudioEngine.isHidListening()) {
                AppLogger.i(TAG, "Stopping HID listening")
                UsbAudioEngine.stopHidListening()
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to stop HID listening", t)
        }
    }

    /**
     * Check if device has HID interface
     */
    fun hasHidInterface(): Boolean {
        return try {
            UsbAudioEngine.hasHidInterface()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Check if HID is currently listening
     */
    fun isHidListening(): Boolean {
        return try {
            UsbAudioEngine.isHidListening()
        } catch (_: Throwable) {
            false
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        AppLogger.d(TAG, "USB receiver registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
    }

    fun setStreamingState(streaming: Boolean) {
        _state.value = if (streaming) State.STREAMING else State.READY
        AppLogger.i(TAG, "setStreamingState: $streaming -> state=${_state.value}")
    }

    fun hasOpenConnection(): Boolean = connection != null

    fun isDeviceConnected(): Boolean {
        val device = currentDevice ?: return false
        return usbManager.deviceList.containsKey(device.deviceName)
    }

    fun getCurrentDeviceName(): String? = currentDevice?.productName
    fun getCurrentDeviceVendorId(): Int = currentDevice?.vendorId ?: 0
    fun getCurrentDeviceProductId(): Int = currentDevice?.productId ?: 0
    fun hasCurrentDevicePermission(): Boolean = currentDevice?.let { usbManager.hasPermission(it) } == true
    fun getCurrentConfig(): UsbAudioConfig? = currentConfig
    fun getCurrentSourceSampleRate(): Int = currentSourceSampleRate
    fun getCurrentSourceBits(): Int = currentSourceBits
    fun getCurrentState(): State = _state.value

    fun getRawDescriptorsSafely(): ByteArray? {
        val conn = connection ?: run {
            AppLogger.w(TAG, "getRawDescriptorsSafely: connection=null")
            return null
        }
        return try {
            val raw = conn.rawDescriptors
            AppLogger.i(TAG, "rawDescriptors size=${raw?.size ?: 0}")
            raw
        } catch (t: Throwable) {
            AppLogger.w(TAG, "connection.rawDescriptors failed", t)
            null
        }
    }

    fun getRawDescriptors(): ByteArray? = getRawDescriptorsSafely()

    // ========== 内部方法 ==========

    // ====================== 新增：扫描硬件音量 Feature Unit ======================
    /**
     * 扫描 Configuration Descriptor，查找播放路径上的 Volume Feature Unit。
     * 结果供 UI 提前提示；真正的安全验证（GET_RANGE/GET_CUR/写后读回）
     * 仍由 C++ 层 [nativeValidateHardwareVolume] 完成。
     */
    fun queryHardwareVolume(dev: UsbDevice, conn: UsbDeviceConnection): VolumeInfo? {
        // 找出 AudioControl interface（class = AUDIO, subclass = AUDIOCONTROL = 0x01）
        var acInterface: UsbInterface? = null
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == 0x01
            ) {
                acInterface = intf
                break
            }
        }
        if (acInterface == null) {
            AppLogger.d(TAG, "queryHardwareVolume: no AudioControl interface found")
            return null
        }

        val rawDesc = ByteArray(4096)
        val nRead = conn.controlTransfer(
            0x80 or 0x00 or 0x00, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
            0x06,                  // USB_REQ_GET_DESCRIPTOR
            (0x02 shl 8),          // USB_DT_CONFIG << 8
            0,
            rawDesc, rawDesc.size, 2000
        )
        if (nRead <= 0) {
            AppLogger.w(TAG, "queryHardwareVolume: GET_DESCRIPTOR failed, nRead=$nRead")
            return null
        }

        fun u8(i: Int): Int = rawDesc[i].toInt() and 0xFF

        fun readLe(offset: Int, size: Int): Int {
            var v = 0
            val n = size.coerceIn(1, 4)
            for (i in 0 until n) {
                v = v or (u8(offset + i) shl (8 * i))
            }
            return v
        }

        // 先读取 AC Header 的 bcdADC，用它判断 UAC1/UAC2。
        // UAC2 Feature Unit 没有 bControlSize 字段；offset+5 开始就是 ch0 的 32-bit bmControls。
        var audioControlVersion = 0
        var offset = 0
        while (offset + 2 <= nRead) {
            val length = u8(offset)
            if (length < 2 || offset + length > nRead) break
            val dtype = u8(offset + 1)

            if (dtype == USB_DT_INTERFACE && length >= 9) {
                val ifaceNo = u8(offset + 2)
                val cls = u8(offset + 5)
                val sub = u8(offset + 6)
                if (ifaceNo == acInterface.id &&
                    cls == UsbConstants.USB_CLASS_AUDIO &&
                    sub == 0x01
                ) {
                    var cs = offset + length
                    while (cs + 5 <= nRead) {
                        val csLen = u8(cs)
                        if (csLen < 2 || cs + csLen > nRead) break
                        val csType = u8(cs + 1)
                        if (csType == USB_DT_INTERFACE) break
                        if (csType == 0x24 && csLen >= 5 && u8(cs + 2) == 0x01) {
                            audioControlVersion = readLe(cs + 3, 2)
                            AppLogger.i(
                                TAG,
                                "queryHardwareVolume: AC iface=${acInterface.id} bcdADC=0x${audioControlVersion.toString(16)}"
                            )
                            break
                        }
                        cs += csLen
                    }
                }
            }
            if (audioControlVersion != 0) break
            offset += length
        }

        val isUac2ByHeader = audioControlVersion >= 0x0200

        // 裸解析 Feature Unit descriptor。
        offset = 9 // 跳过 Configuration Descriptor (9 bytes)
        while (offset + 2 <= nRead) {
            val length = u8(offset)
            if (length < 2 || offset + length > nRead) break
            val dtype = u8(offset + 1)

            // CS_INTERFACE (0x24) + FEATURE_UNIT (0x06 in UAC2 entity subtype;
            // 项目旧代码用 0x02 命中过一批设备，这里两者都兼容，避免回归。)
            val subtype = if (length >= 3) u8(offset + 2) else -1
            if (dtype == 0x24 && (subtype == 0x06 || subtype == 0x02) && length >= 6) {
                val bUnitID = u8(offset + 3)

                var hasMaster = false
                var hasLeft = false
                var hasRight = false
                val rawControls = StringBuilder()

                // UAC2 Feature Unit: bUnitID, bSourceID, then 4 bytes bmControls per logical channel.
                // UAC1 Feature Unit: bUnitID, bSourceID, bControlSize, then bControlSize bytes per channel.
                // 当 AC header 没拿到时，用 offset+5 > 4 作为 UAC2 启发式；UAC1 的 bControlSize 正常只会是 1/2/4。
                val treatAsUac2 = isUac2ByHeader || u8(offset + 5) > 4

                if (treatAsUac2) {
                    val controlStart = offset + 5
                    val channelCount = ((length - 5) / 4).coerceAtMost(8)
                    for (ch in 0 until channelCount) {
                        val ctrlOffset = controlStart + ch * 4
                        if (ctrlOffset + 4 > offset + length || ctrlOffset + 4 > nRead) break
                        val ctrl = readLe(ctrlOffset, 4)
                        if (rawControls.isNotEmpty()) rawControls.append(' ')
                        rawControls.append("ch").append(ch).append("=0x")
                            .append(ctrl.toUInt().toString(16).padStart(8, '0'))

                        // UAC2 bmControls uses 2 bits per control selector.
                        // Volume Control selector = 0x02 -> bits [3:2] -> mask 0x0000000C.
                        val hasVolume = (ctrl and 0x0000000C) != 0
                        when (ch) {
                            0 -> hasMaster = hasVolume
                            1 -> hasLeft = hasVolume
                            2 -> hasRight = hasVolume
                        }
                    }
                } else {
                    val bControlSize = u8(offset + 5)
                    if (bControlSize < 1) {
                        offset += length
                        continue
                    }
                    val channelCount = ((length - 7) / bControlSize).coerceAtMost(8)
                    for (ch in 0 until channelCount) {
                        val ctrlOffset = offset + 6 + ch * bControlSize
                        if (ctrlOffset >= nRead || ctrlOffset + bControlSize > offset + length) break
                        val ctrl = readLe(ctrlOffset, bControlSize)
                        if (rawControls.isNotEmpty()) rawControls.append(' ')
                        rawControls.append("ch").append(ch).append("=0x")
                            .append(ctrl.toUInt().toString(16).padStart(bControlSize * 2, '0'))

                        // UAC1 bmaControls: bit1 means Volume Control is present.
                        val hasVolume = (ctrl and 0x02) != 0
                        when (ch) {
                            0 -> hasMaster = hasVolume
                            1 -> hasLeft = hasVolume
                            2 -> hasRight = hasVolume
                        }
                    }
                }

                AppLogger.i(
                    TAG,
                    "queryHardwareVolume descriptor hint: FeatureUnit 0x${bUnitID.toString(16)} " +
                        "uac=${if (treatAsUac2) 2 else 1} raw=[$rawControls] " +
                        "master=$hasMaster L=$hasLeft R=$hasRight"
                )

                if (hasMaster || hasLeft || hasRight) {
                    return VolumeInfo(
                        entityId = bUnitID,
                        interfaceNo = acInterface.id,
                        channel = 0,
                        hasMasterVolume = hasMaster,
                        hasLeftVolume = hasLeft,
                        hasRightVolume = hasRight
                    )
                }
            }
            offset += length
        }
        AppLogger.i(TAG, "queryHardwareVolume: no Volume Feature Unit descriptor hint found")
        return null
    }

    // ====================== 新增：软重采样入口 ======================
    private fun softResampleIfNeeded(
        srcPath: String,
        srcRate: Int,
        srcBits: Int,
        srcCh: Int,
        forceFallback: Boolean = false
    ): Pair<String, AudioFormat> {
        if (!forceFallback) {
            // 1. 优先尝试原始采样率 + 原始位深
            val srcSubslot = if (srcBits > 16) 4 else 2
            if (selectConfigForFormat(srcRate, srcBits, srcSubslot, srcCh) != null) {
                AppLogger.i(TAG, "Soft-resample bypass: device supports native $srcRate/${srcBits}b")
                return Pair(srcPath, AudioFormat(srcRate, srcCh, srcBits))
            }
        }
        // 2. 尝试原始采样率 + 降级位深（24bit → 16bit）—— 无论 forceFallback 与否
        if (srcBits > 16) {
            AppLogger.i(TAG, "Soft-resample: try downgrade bits $srcRate/${srcBits}b → $srcRate/16b")
            val cacheDir = File(context.cacheDir, "resampled_pcm").apply { mkdirs() }
            trimCacheDirThrottled(cacheDir, 500L * 1024 * 1024) // 限制缓存 500MB
            val hash = (srcPath + "_r${srcRate}_b16_c${srcCh}").hashCode().toString(16)
            val outFile = File(cacheDir, "$hash.pcm")
            if (!outFile.exists() || outFile.length() <= 0) {
                FFmpegBridge.convertToRawPcm(
                    inputPath = srcPath,
                    outputPath = outFile.absolutePath,
                    targetSampleRate = srcRate,
                    bitsPerSample = 16,
                    channels = srcCh
                )
            }
            return Pair(outFile.absolutePath, AudioFormat(srcRate, srcCh, 16))
        }
        // 3. Fallback to nearest standard rate
        val targetRate = when {
            srcRate <= 48000 -> {
                val d44 = kotlin.math.abs(srcRate - 44100)
                val d48 = kotlin.math.abs(srcRate - 48000)
                if (d44 <= d48) 44100 else 48000
            }
            srcRate <= 96000 -> {
                val d88 = kotlin.math.abs(srcRate - 88200)
                val d96 = kotlin.math.abs(srcRate - 96000)
                if (d88 <= d96) 88200 else 96000
            }
            else -> {
                val d176 = kotlin.math.abs(srcRate - 176400)
                val d192 = kotlin.math.abs(srcRate - 192000)
                if (d176 <= d192) 176400 else 192000
            }
        }
        val targetBits = if (srcBits <= 16) 16 else 24
        AppLogger.i(TAG, "Soft-resampling $srcPath ${srcRate}Hz/${srcBits}b → $targetRate/$targetBits")
        val cacheDir = File(context.cacheDir, "resampled_pcm").apply { mkdirs() }
        trimCacheDirThrottled(cacheDir, 500L * 1024 * 1024) // 限制缓存 500MB
        val hash = (srcPath + "_r${targetRate}_b${targetBits}_c${srcCh}").hashCode().toString(16)
        val outFile = File(cacheDir, "$hash.pcm")
        if (!outFile.exists() || outFile.length() <= 0) {
            FFmpegBridge.convertToRawPcm(
                inputPath = srcPath,
                outputPath = outFile.absolutePath,
                targetSampleRate = targetRate,
                bitsPerSample = targetBits,
                channels = srcCh
            )
        }
        return Pair(outFile.absolutePath, AudioFormat(targetRate, srcCh, targetBits))
    }

    /** 清理目录使其不超过 maxSize，删除最旧的文件 */
    private fun trimCacheDirThrottled(dir: File, maxSize: Long) {
        val now = System.currentTimeMillis()
        if (now - lastResampledCacheTrimMs < 60_000L) return
        lastResampledCacheTrimMs = now
        trimCacheDir(dir, maxSize)
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

    private fun isUsbAudioOutputDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == USB_CLASS_AUDIO && intf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    val isIso = ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    val isOut = ep.direction == UsbConstants.USB_DIR_OUT
                    if (isIso && isOut) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun findStreamingInterface(device: UsbDevice): Pair<UsbInterface, Int>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO &&
                iface.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
            ) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    val isIsoOut = ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                    if (isIsoOut) {
                        return iface to ep.address
                    }
                }
            }
        }
        return null
    }

    /**
     * 配置选择。
     * 不再硬编码 iface/alt/ep —— C++ 层 parseAudioInterfaceFromConfig 会从实际描述符
     * 扫描并选择最佳接口。iface=0, alt=0 表示"让 native 层自动决定"。
     * 采样率由 C++ 层通过 GET_RANGE 验证设备真实支持。
     *
     * 只返回常见格式（16/24bit stereo），其他格式返回 null 触发软重采样。
     */
    private fun selectConfigForFormat(
        sampleRate: Int,
        bits: Int,
        subslot: Int,
        channels: Int
    ): UsbAudioConfig? = selectConfigForFormat(sampleRate, bits, subslot, channels, sourceBits = bits)

    private fun selectConfigForFormat(
        sampleRate: Int,
        bits: Int,
        subslot: Int,
        channels: Int,
        sourceBits: Int
    ): UsbAudioConfig? {
        // 只对常见格式返回配置，其他格式交给软重采样
        return when {
            channels == 2 && bits == 16 && subslot == 2 -> UsbAudioConfig(
                iface = 0,      // 0 = 让 native 自动选择
                alt = 0,        // 0 = 让 native 自动选择
                outEp = 0,      // 0 = 让 native 从描述符解析
                fbEp = 0,       // 0 = 让 native 从描述符解析
                sampleRate = sampleRate,
                bits = 16,
                channels = 2,
                subslot = 2,
                sourceBits = sourceBits
            )
            channels == 2 && bits == 24 && subslot == 3 -> UsbAudioConfig(
                iface = 0,
                alt = 0,
                outEp = 0,
                fbEp = 0,
                sampleRate = sampleRate,
                bits = 24,
                channels = 2,
                subslot = 3,
                sourceBits = sourceBits
            )
            channels == 2 && bits == 24 && subslot == 4 -> UsbAudioConfig(
                iface = 0,
                alt = 0,
                outEp = 0,
                fbEp = 0,
                sampleRate = sampleRate,
                bits = 24,
                channels = 2,
                subslot = 4,
                sourceBits = 32
            )
            channels == 2 && bits == 32 && subslot == 4 -> UsbAudioConfig(
                iface = 0,
                alt = 0,
                outEp = 0,
                fbEp = 0,
                sampleRate = sampleRate,
                bits = 32,
                channels = 2,
                subslot = 4,
                sourceBits = sourceBits
            )
            else -> null // 没有匹配的格式 → 交给外层软重采样
        }
    }

    private fun dumpInterfaces(device: UsbDevice) {
        AppLogger.i(TAG, "========== USB Interfaces (all) ==========")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            AppLogger.i(
                TAG,
                "  Interface[$i]: id=${intf.id} alt=${intf.alternateSetting} " +
                        "class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} " +
                        "protocol=${intf.interfaceProtocol} eps=${intf.endpointCount}"
            )
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                AppLogger.i(
                    TAG,
                    "    Endpoint[$e]: addr=0x${ep.address.toString(16)} dir=$dir " +
                            "type=${ep.type} attr=0x${ep.attributes.toString(16)} " +
                            "maxPacket=${ep.maxPacketSize} interval=${ep.interval}"
                )
            }
        }
        AppLogger.i(TAG, "==========================================")
    }

    // ========== UAC20 v2 diagnostic probe (shadow mode, no production audio) ==========

    data class Uac20DebugProbeResult(
        val ok: Boolean,
        val reason: String,
        val deviceName: String? = null,
        val vendorProductId: String? = null,
        val sampleRate: Int = 0,
        val bits: Int = 0,
        val channels: Int = 0,
        val requestedSubslotBytes: Int = 0,
        val started: Boolean = false,
        val shadowWriteCalls: Int = 0,
        val shadowWriteInputBytes: Long = 0L,
        val shadowWriteAcceptedBytes: Long = 0L,
        val pumpDurationMs: Int = 0,
        val pumpChunkMs: Int = 0,
        val pumpWriteCalls: Int = 0,
        val pumpInputBytes: Long = 0L,
        val pumpAcceptedBytes: Long = 0L,
        val pumpSource: String = "none",
        val pumpStopReason: String = "none",
        val decodedSourcePath: String = "",
        val decodedOpen: Boolean = false,
        val decodedEof: Boolean = false,
        val decodedErrorCode: Int = 0,
        val decodedReadCalls: Int = 0,
        val decodedInputBytes: Long = 0L,
        val guardPassed: Boolean = false,
        val guardReason: String = "not-evaluated",
        val guardBlocksPromotion: Boolean = true,
        val graySwitchEligible: Boolean = false,
        val preStartRuntimeJson: String? = null,
        val postStartRuntimeJson: String? = null,
        val finalRuntimeJson: String? = null,
        val elapsedMs: Long = 0L,
        val summary: String? = null
    )

    @Volatile
    private var lastUac20DebugProbeResult: Uac20DebugProbeResult? = null
    @Volatile private var uac20DebugPlaybackSwitchEnabled: Boolean = false
    private val uac20DebugRunInFlight = AtomicBoolean(false)

    fun getLastUac20DebugProbeResult(): Uac20DebugProbeResult? = lastUac20DebugProbeResult

    fun getLastUac20DebugProbeRuntimeJson(): String? = lastUac20DebugProbeResult?.finalRuntimeJson

    fun runDefaultUac20DebugProbe(): Uac20DebugProbeResult {
        return runUac20DebugProbe(
            sampleRate = 192000,
            bits = 24,
            channels = 2,
            requestedSubslotBytes = 4
        )
    }

    fun getLastUac20DebugProbeReport(): String {
        val result = lastUac20DebugProbeResult ?: return "no-uac20-debug-probe-result"
        return toUac20DebugProbeReportBlock(result)
    }

    fun setUac20DebugPlaybackSwitchEnabled(enabled: Boolean) {
        uac20DebugPlaybackSwitchEnabled = enabled
        AppLogger.w(TAG, "UAC20 debug playback switch set to $enabled; legacy path is unchanged unless caller uses debug entry")
    }

    fun isUac20DebugPlaybackSwitchEnabled(): Boolean = uac20DebugPlaybackSwitchEnabled

    /**
     * 0043: Debug-only v2 playback smoke test. Opens a UAC20 session with the
     * debug real-OUT submitter gate enabled (192000/24/2/subslot=4). This does
     * NOT replace legacy playback and is never called automatically.
     *
     * Safety: rejected if legacy playback is active.
     */
    @Synchronized
    fun runDefaultUac20DebugPlaybackSmoke(
        pumpDurationMs: Int = 1200,
        pumpChunkMs: Int = 5
    ): String {
        if (UsbAudioEngine.currentHandle != 0L || UsbAudioEngine.isInitialized()) {
            return "rejected: legacy playback active"
        }
        val device = findUsbAudioDevice() ?: return "rejected: no-usb-audio-device"
        if (!usbManager.hasPermission(device)) {
            return "rejected: no-permission"
        }
        val conn = usbManager.openDevice(device) ?: return "rejected: openDevice-failed"
        try {
            val fd = conn.fileDescriptor
            if (fd <= 0) return "rejected: invalid-fd"
            val engine = UsbAudioEngine
            val session = engine.openUac20DebugPlaybackSession(
                fd = fd,
                sourceSampleRate = 192000,
                sourceBits = 24,
                sourceChannels = 2,
                requestedSubslotBytes = 4,
                feedFromWriteRing = true,
                autoResubmit = true
            )
            val started = engine.nativeUac20Start(session.handle)

            // 0047: Prebuffer ~50ms silence before pump
            val frameBytes = 4 * 2 // 24-in-32 * 2ch
            val prebufferBytes = 192000 * frameBytes / 1000 * 50
            val prebuffer = ByteArray(prebufferBytes)
            engine.writeDebugPlayback(session.handle, prebuffer, 0, prebuffer.size)

            // 0047: Continuous PCM pump
            val chunkBytes = 192000 * frameBytes / 1000 * pumpChunkMs
            val chunk = ByteArray(chunkBytes)
            var pumpCalls = 0
            var pumpInputBytes = 0L
            var pumpAcceptedBytes = 0L
            val pumpStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - pumpStart < pumpDurationMs) {
                val accepted = engine.writeDebugPlayback(session.handle, chunk, 0, chunk.size)
                pumpCalls++
                pumpInputBytes += chunk.size
                if (accepted > 0) pumpAcceptedBytes += accepted
                Thread.sleep(pumpChunkMs.toLong())
            }
            val pumpElapsed = System.currentTimeMillis() - pumpStart

            val runtimeJson = engine.nativeUac20RuntimeJson(session.handle)
            engine.nativeUac20Stop(session.handle)
            engine.nativeUac20Close(session.handle)
            val pumpReport = "UAC20PreviewPlaybackPump=durationMs=$pumpElapsed, chunkMs=$pumpChunkMs, " +
                "calls=$pumpCalls, inputBytes=$pumpInputBytes, acceptedBytes=$pumpAcceptedBytes"
            return "ok started=$started\n$pumpReport\njson=${singleLineJsonForLog(runtimeJson)}"
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun String.jsonBool(name: String): Boolean =
        contains("\"$name\":true")

    private fun String.jsonLong(name: String): Long {
        val pattern = "\"" + Regex.escape(name) + "\"\\s*:\\s*(-?\\d+)"
        val match = Regex(pattern).find(this)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun String.jsonDouble(name: String): Double {
        val pattern = "\"" + Regex.escape(name) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)"
        val match = Regex(pattern).find(this)
        return match?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun String.jsonRawValue(name: String): String {
        val pattern = "\"" + Regex.escape(name) + "\"\\s*:\\s*(\"[^\"]*\"|true|false|-?\\d+(?:\\.\\d+)?)"
        val match = Regex(pattern).find(this)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun String.uac20RuntimeSummaryForLog(): String {
        if (isBlank()) return "{}"
        val fields = listOf(
            "running",
            "sampleRate",
            "subslotBytes",
            "outEndpoint",
            "feedbackEndpoint",
            "eventLoopTicks",
            "realOutSubmitterSubmitted",
            "realOutSubmitterActive",
            "realOutSubmitterCompleteCount",
            "realOutSubmitterResubmitCount",
            "realOutSubmitterSubmitOkCount",
            "realOutSubmitterSubmitFailCount",
            "realOutSubmitterActiveTransferCount",
            "realOutSubmitterCallbackCount",
            "realOutSubmitterLastSubmitResult",
            "realOutSubmitterLastTransferStatus",
            "realOutSubmitterFirstCallbackMs",
            "realOutSubmitterNoCompletionMs",
            "realOutSubmitterLayoutValid",
            "realOutSubmitterLayoutError",
            "realOutSubmitterPacketLengthTotal",
            "realOutSubmitterPacketLengthMax",
            "realOutSubmitterEndpointMaxPacketSize",
            "realOutSubmitterCompletedBytesPerSecond",
            "realOutSubmitterExpectedBytesPerSecond",
            "realOutSubmitterCompletionRatio",
            "realOutSubmitterZeroFilledBytes",
            "writeRingLevelBytes",
            "writeRingTotalDroppedBytes",
            "pcmPipelineConfigured",
            "pcmPipelineSourceFrameBytes",
            "pcmPipelineDeviceFrameBytes",
            "pcmPipelineTotalInputBytes",
            "pcmPipelineTotalConsumedBytes",
            "pcmPipelineTotalProducedBytes",
            "pcmPipelineLastErrorCode",
            "playbackGuardPassed",
            "playbackGuardReason"
        )
        return fields.joinToString(prefix = "{", postfix = "}") { name ->
            val raw = jsonRawValue(name).takeIf { it.isNotBlank() } ?: "n/a"
            "\"$name\":$raw"
        }
    }

    private fun evaluateUac20DebugRuntimeGuard(
        started: Boolean,
        pumpSource: String,
        pumpInputBytes: Long,
        pumpAcceptedBytes: Long,
        decodedOpen: Boolean,
        decodedErrorCode: Int,
        decodedInputBytes: Long,
        runtimeJson: String
    ): Triple<Boolean, Boolean, String> {
        val acceptedRatioOk = pumpInputBytes <= 0L || pumpAcceptedBytes * 100L >= pumpInputBytes * 90L
        val decodedOk = pumpSource != "decoded-pcm" || (decodedOpen && decodedErrorCode == 0 && decodedInputBytes > 0L)
        val transportSubmitted = runtimeJson.jsonBool("realOutSubmitterSubmitted")
        val completeCount = runtimeJson.jsonLong("realOutSubmitterCompleteCount")
        val completedBps = runtimeJson.jsonLong("realOutSubmitterCompletedBytesPerSecond")
        val expectedBps = runtimeJson.jsonLong("realOutSubmitterExpectedBytesPerSecond")
            .takeIf { it > 0L } ?: runtimeJson.jsonLong("expectedBytesPerSecond")
        val completionRatio = runtimeJson.jsonDouble("realOutSubmitterCompletionRatio")
            .takeIf { it > 0.0 } ?: if (expectedBps > 0L) completedBps.toDouble() / expectedBps.toDouble() else 0.0
        val droppedBytes = runtimeJson.jsonLong("writeRingTotalDroppedBytes")
        val zeroFillBytes = runtimeJson.jsonLong("realOutSubmitterZeroFilledBytes")
        val submittedBytes = runtimeJson.jsonLong("realOutSubmitterSubmittedBytes")
        val zeroFillRatio = if (submittedBytes > 0L) zeroFillBytes.toDouble() / submittedBytes.toDouble() else 0.0
        val nativeGuardPassed = runtimeJson.jsonBool("playbackGuardPassed")
        val throughputOk = !transportSubmitted || expectedBps <= 0L || completionRatio >= 0.85
        val writeRingOk = droppedBytes <= 0L
        val zeroFillOk = zeroFillRatio < 0.20
        val transportOk = transportSubmitted && completeCount > 0L && throughputOk && zeroFillOk
        val guardPassed = started && acceptedRatioOk && decodedOk && transportOk && writeRingOk && nativeGuardPassed
        val reasons = ArrayList<String>()
        if (!started) reasons.add("not-started")
        if (!acceptedRatioOk) reasons.add("low-accepted-ratio")
        if (!decodedOk) reasons.add("decoded-pcm-not-ready")
        if (!transportSubmitted) reasons.add("real-out-not-submitted")
        if (completeCount <= 0L) reasons.add("real-out-no-completion")
        if (!throughputOk) reasons.add("real-out-under-output:${"%.3f".format(completionRatio)}")
        if (!writeRingOk) reasons.add("write-ring-dropping:$droppedBytes")
        if (!zeroFillOk) reasons.add("real-out-zero-fill-high:${"%.3f".format(zeroFillRatio)}")
        if (!nativeGuardPassed) reasons.add("native-guard-failed")
        if (reasons.isEmpty()) reasons.add("pass")
        val blocksPromotion = !guardPassed
        return Triple(guardPassed, blocksPromotion, reasons.joinToString(";"))
    }

    fun runUac20DebugDecodedPlaybackIfSwitchEnabled(
        sourcePath: String,
        startPositionMs: Long = 0L,
        maxDurationMs: Int = 5_000
    ): Uac20DebugProbeResult {
        if (!uac20DebugPlaybackSwitchEnabled) {
            return Uac20DebugProbeResult(
                ok = false,
                reason = "debug-v2-switch-disabled",
                decodedSourcePath = sourcePath,
                pumpSource = "decoded-pcm",
                pumpStopReason = "switch-disabled"
            ).also { lastUac20DebugProbeResult = it; logUac20DebugProbeReport(it) }
        }
        return runUac20DebugDecodedPlaybackSmoke(
            sourcePath = sourcePath,
            startPositionMs = startPositionMs,
            maxDurationMs = maxDurationMs
        )
    }

    /**
     * 0107: Deterministic generated-tone smoke path. This bypasses FFmpeg and
     * the file decoder entirely, while still using the same native UAC20
     * session / write ring / RealOutSubmitter path.  Use this to separate
     * "USB packet/clock/OUT path is bad" from "decoded PCM content is bad".
     */
    fun runUac20DebugGeneratedToneSmoke(
        sampleRate: Int = 44_100,
        bits: Int = 16,
        channels: Int = 2,
        requestedSubslotBytes: Int = 2,
        frequencyHz: Int = 1_000,
        maxDurationMs: Int = 5_000,
        pumpChunkMs: Int = 5,
        amplitude: Float = 0.20f,
        toneChannelMode: String = "dual"
    ): Uac20DebugProbeResult {
        val sr = sampleRate.coerceIn(8_000, 384_000)
        val ch = channels.coerceIn(1, 8)
        val normalizedBits = when {
            bits <= 16 -> 16
            bits <= 24 -> 24
            else -> 32
        }
        val subslot = requestedSubslotBytes.takeIf { it > 0 } ?: when {
            normalizedBits <= 16 -> 2
            normalizedBits == 24 -> 3
            else -> 4
        }
        val bytesPerSample = when {
            normalizedBits <= 16 -> 2
            subslot >= 4 -> 4
            else -> 3
        }
        val frameBytes = ch * bytesPerSample
        val chunkMs = pumpChunkMs.coerceIn(1, 50)
        val amp = amplitude.coerceIn(0.02f, 0.80f)
        val freq = frequencyHz.coerceIn(20, sr / 2 - 1)
        val channelMode = when (toneChannelMode.lowercase()) {
            "left", "l" -> "left"
            "right", "r" -> "right"
            "alternate", "alternating" -> "alternate"
            else -> "dual"
        }
        var frameIndex = 0L
        var frameRemainder = 0

        fun nextFrameCount(): Int {
            val total = sr * chunkMs + frameRemainder
            val frames = (total / 1000).coerceAtLeast(1)
            frameRemainder = total % 1000
            return frames
        }

        fun makeToneChunk(): ByteArray {
            val frames = nextFrameCount()
            val out = ByteArray(frames * frameBytes)
            var o = 0
            for (i in 0 until frames) {
                val phase = (2.0 * PI * freq.toDouble() * frameIndex.toDouble()) / sr.toDouble()
                val s16 = (sin(phase) * amp * 32767.0).roundToInt().coerceIn(-32768, 32767)
                frameIndex++
                for (c in 0 until ch) {
                    val sample = when (channelMode) {
                        "left" -> if (c == 0) s16 else 0
                        "right" -> if (c == 1) s16 else 0
                        "alternate" -> if (((frameIndex + c.toLong()) and 1L) == 0L) s16 else -s16
                        else -> s16
                    }
                    when {
                        bytesPerSample == 2 -> {
                            out[o++] = (sample and 0xff).toByte()
                            out[o++] = ((sample ushr 8) and 0xff).toByte()
                        }
                        bytesPerSample == 3 -> {
                            val s24 = sample shl 8
                            out[o++] = (s24 and 0xff).toByte()
                            out[o++] = ((s24 ushr 8) and 0xff).toByte()
                            out[o++] = ((s24 ushr 16) and 0xff).toByte()
                        }
                        else -> {
                            val s32 = sample shl 16
                            out[o++] = (s32 and 0xff).toByte()
                            out[o++] = ((s32 ushr 8) and 0xff).toByte()
                            out[o++] = ((s32 ushr 16) and 0xff).toByte()
                            out[o++] = ((s32 ushr 24) and 0xff).toByte()
                        }
                    }
                }
            }
            return out
        }


        fun previewGeneratedToneChunk(chunk: ByteArray): String {
            if (chunk.isEmpty()) return "empty"
            val hex = chunk.take(32).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xff) }
            val samplePreview = ArrayList<String>()
            val previewFrames = minOf(8, chunk.size / frameBytes)
            for (f in 0 until previewFrames) {
                val values = ArrayList<Int>()
                for (c in 0 until ch) {
                    val off = f * frameBytes + c * bytesPerSample
                    val v = when (bytesPerSample) {
                        2 -> {
                            val raw = (chunk[off].toInt() and 0xff) or ((chunk[off + 1].toInt() and 0xff) shl 8)
                            if (raw >= 0x8000) raw - 0x10000 else raw
                        }
                        3 -> {
                            val raw = (chunk[off].toInt() and 0xff) or
                                ((chunk[off + 1].toInt() and 0xff) shl 8) or
                                ((chunk[off + 2].toInt() and 0xff) shl 16)
                            val signed = if ((raw and 0x800000) != 0) raw or -0x1000000 else raw
                            signed shr 8
                        }
                        else -> {
                            val raw = (chunk[off].toInt() and 0xff) or
                                ((chunk[off + 1].toInt() and 0xff) shl 8) or
                                ((chunk[off + 2].toInt() and 0xff) shl 16) or
                                ((chunk[off + 3].toInt() and 0xff) shl 24)
                            raw shr 16
                        }
                    }
                    values.add(v)
                }
                samplePreview.add(values.joinToString(prefix = "[", postfix = "]", separator = ","))
            }
            return "bytes=${chunk.size} frameBytes=$frameBytes hex32=$hex frames=${samplePreview.joinToString(" ")}"
        }

        val prebufferChunks = ArrayList<ByteArray>()
        val prebufferChunkCount = (50 / chunkMs).coerceIn(1, 20)
        repeat(prebufferChunkCount) { prebufferChunks.add(makeToneChunk()) }
        AppLogger.i(
            TAG,
            "UAC20 generated tone smoke source: sr=$sr bits=$normalizedBits ch=$ch " +
                "subslot=$subslot frameBytes=$frameBytes freq=$freq amp=$amp chunkMs=$chunkMs channelMode=$channelMode"
        )
        AppLogger.i(TAG, "UAC20 generated tone preview: ${previewGeneratedToneChunk(prebufferChunks.firstOrNull() ?: ByteArray(0))}")
        return runUac20DebugProbe(
            sampleRate = sr,
            bits = normalizedBits,
            channels = ch,
            requestedSubslotBytes = subslot,
            flags = UsbAudioEngine.UAC20_DEFAULT_FLAGS or
                UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_SUBMITTER or
                UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_FEEDER or
                UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_AUTO_RESUBMIT or
                UsbAudioEngine.UAC20_FLAG_DEBUG_RUNTIME_GUARD,
            shadowWriteChunks = prebufferChunks,
            debugPlaybackPumpDurationMs = maxDurationMs.coerceIn(250, 30_000),
            debugPlaybackPumpChunkMs = chunkMs,
            debugPlaybackChunkProvider = { makeToneChunk() },
            debugPlaybackSourceLabel = "generated-tone",
            decodedSourcePath = "generated-tone:${freq}Hz/${sr}Hz/${normalizedBits}bit/$channelMode",
            decodedOpen = true,
            decodedEofProvider = { false },
            decodedErrorCodeProvider = { 0 },
            decodedReadCallsProvider = { 0 },
            decodedInputBytesProvider = { 0L },
            label = "uac20_debug_generated_tone"
        )
    }

    /**
     * 0048-0051: Debug-only decoded PCM bridge. Opens an FFmpeg streaming decoder,
     * prebuffers real PCM into the v2 write ring, starts the v2 debug real-OUT
     * session, then continuously pumps decoded PCM for a bounded duration.
     *
     * This still does NOT replace legacy playback. Callers must invoke manually.
     */
    fun runUac20DebugDecodedPlaybackSmoke(
        sourcePath: String,
        startPositionMs: Long = 0L,
        maxDurationMs: Int = 5_000,
        targetSampleRate: Int = 0,
        // 0105: 0 means "use the probed source bit depth".  The old default
        // hard-coded 24-bit / 24-in-32 and made 16-bit files run through an
        // unnecessary widened USB alt, which can sound wrong even when OUT
        // throughput is perfect.
        targetBits: Int = 0,
        targetChannels: Int = 2,
        // 0 means native format matcher chooses the exact/lossless USB subslot
        // for the decoded source.  Do not force 24-in-32 for 16-bit sources.
        requestedSubslotBytes: Int = 0,
        pumpChunkMs: Int = 5
    ): Uac20DebugProbeResult {
        if (!uac20DebugRunInFlight.compareAndSet(false, true)) {
            return Uac20DebugProbeResult(
                ok = false,
                reason = "uac20-debug-already-running",
                decodedSourcePath = sourcePath,
                pumpSource = "decoded-pcm",
                pumpStopReason = "already-running"
            ).also { lastUac20DebugProbeResult = it; logUac20DebugProbeReport(it) }
        }
        try {
        if (sourcePath.isBlank()) {
            val r = Uac20DebugProbeResult(
                ok = false,
                reason = "decoded-source-empty",
                pumpSource = "decoded-pcm",
                pumpStopReason = "invalid-source"
            )
            lastUac20DebugProbeResult = r
            logUac20DebugProbeReport(r)
            return r
        }
        if (!sourcePath.startsWith("http://") && !sourcePath.startsWith("https://") && !File(sourcePath).exists()) {
            val r = Uac20DebugProbeResult(
                ok = false,
                reason = "decoded-source-not-found",
                decodedSourcePath = sourcePath,
                pumpSource = "decoded-pcm",
                pumpStopReason = "invalid-source"
            )
            lastUac20DebugProbeResult = r
            logUac20DebugProbeReport(r)
            return r
        }

        val probedRate = targetSampleRate.takeIf { it > 0 }
            ?: FFmpegBridge.probeSampleRate(sourcePath).takeIf { it > 0 }
            ?: 192000
        val probedBits = FFmpegBridge.probeBitsPerSample(sourcePath).takeIf { it > 0 }
        val decodeBits = targetBits.takeIf { it > 0 }
            ?: probedBits
            ?: 16
        val decodeChannels = targetChannels.takeIf { it > 0 } ?: 2
        val decodeContainerBytes = when {
            decodeBits <= 16 -> 2
            decodeBits == 24 -> 4 // FFmpeg decoded 24-bit PCM is carried as S32LE.
            else -> ((decodeBits + 7) / 8).coerceAtLeast(1)
        }
        val usbRequestedSubslotBytes = requestedSubslotBytes.takeIf { it > 0 }
            ?: when {
                decodeBits <= 16 -> 2
                decodeBits == 24 -> 3 // test packed24 first; 24-in-32 remains an explicit override.
                else -> decodeContainerBytes.coerceAtMost(4)
            }
        val sourceFrameBytes = decodeChannels * decodeContainerBytes
        val decodeChunkMs = pumpChunkMs.coerceIn(1, 50)
        // 0087: 44.1k-family rates are fractional at 5 ms (220.5 frames).
        // Use a frame accumulator so the decoded feeder emits 220/221-frame
        // chunks instead of truncating every chunk to 220 frames and slowly
        // starving native OUT.
        val maxChunkFrames = (((probedRate.toLong() * decodeChunkMs.toLong()) + 999L) / 1000L)
            .coerceAtLeast(1L)
            .toInt()
        val decodeBuffer = ByteArray((maxChunkFrames * sourceFrameBytes).coerceAtLeast(sourceFrameBytes))
        AppLogger.i(TAG, "UAC20 decoded smoke source format: path=${sourcePath.shortForDebugReport()} probedRate=$probedRate probedBits=${probedBits ?: 0} decodeBits=$decodeBits decodeContainerBytes=$decodeContainerBytes channels=$decodeChannels usbSubslot=$usbRequestedSubslotBytes")
        var decodeChunkIndex = 0L
        var decodeFrameCursor = 0L

        var decoderHandle = 0L
        var decodedOpen = false
        var decodedEof = false
        var decodedError = 0
        var decodedReads = 0
        var decodedBytes = 0L

        try {
            decoderHandle = FFmpegBridge.openDecoder(sourcePath, probedRate, decodeBits, decodeChannels)
            decodedOpen = decoderHandle != 0L
            if (!decodedOpen) {
                val r = Uac20DebugProbeResult(
                    ok = false,
                    reason = "decoded-open-failed",
                    sampleRate = probedRate,
                    bits = decodeBits,
                    channels = decodeChannels,
                    requestedSubslotBytes = usbRequestedSubslotBytes,
                    pumpSource = "decoded-pcm",
                    pumpStopReason = "decoder-open-failed",
                    decodedSourcePath = sourcePath,
                    decodedOpen = false
                )
                lastUac20DebugProbeResult = r
                logUac20DebugProbeReport(r)
                return r
            }
            if (startPositionMs > 0L) {
                FFmpegBridge.seekDecoder(decoderHandle, startPositionMs)
            }

            val provider = {
                val nextFrameCursor = (((decodeChunkIndex + 1L) * probedRate.toLong() * decodeChunkMs.toLong()) / 1000L)
                    .coerceAtLeast(decodeFrameCursor + 1L)
                val framesThisChunk = (nextFrameCursor - decodeFrameCursor)
                    .coerceAtLeast(1L)
                    .toInt()
                decodeChunkIndex += 1L
                decodeFrameCursor = nextFrameCursor
                val requestBytes = (framesThisChunk * sourceFrameBytes)
                    .coerceIn(sourceFrameBytes, decodeBuffer.size)
                val decoded = FFmpegBridge.decodeChunk(decoderHandle, decodeBuffer, 0, requestBytes)
                when {
                    decoded > 0 -> {
                        decodedReads++
                        decodedBytes += decoded.toLong()
                        decodeBuffer.copyOf(decoded)
                    }
                    decoded == -1 -> {
                        decodedEof = true
                        null
                    }
                    else -> {
                        decodedError = decoded
                        null
                    }
                }
            }

            // Prebuffer ~50ms of decoded PCM
            val prebufferChunks = ArrayList<ByteArray>()
            val prebufferChunkCount = (50 / pumpChunkMs.coerceIn(1, 50)).coerceIn(1, 20)
            repeat(prebufferChunkCount) {
                val chunk = provider.invoke() ?: return@repeat
                if (chunk.isNotEmpty()) prebufferChunks.add(chunk)
            }

            return runUac20DebugProbe(
                sampleRate = probedRate,
                bits = decodeBits,
                channels = decodeChannels,
                requestedSubslotBytes = usbRequestedSubslotBytes,
                flags = UsbAudioEngine.UAC20_DEFAULT_FLAGS or
                    UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_SUBMITTER or
                    UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_FEEDER or
                    UsbAudioEngine.UAC20_FLAG_DEBUG_REAL_OUT_AUTO_RESUBMIT or
                    UsbAudioEngine.UAC20_FLAG_DEBUG_RUNTIME_GUARD,
                shadowWriteChunks = prebufferChunks,
                debugPlaybackPumpDurationMs = maxDurationMs.coerceIn(250, 30_000),
                debugPlaybackPumpChunkMs = pumpChunkMs,
                debugPlaybackChunkProvider = provider,
                debugPlaybackSourceLabel = "decoded-pcm",
                decodedSourcePath = sourcePath,
                decodedOpen = decodedOpen,
                decodedEofProvider = { decodedEof },
                decodedErrorCodeProvider = { decodedError },
                decodedReadCallsProvider = { decodedReads },
                decodedInputBytesProvider = { decodedBytes },
                label = "uac20_debug_decoded_playback"
            )
        } finally {
            runCatching { if (decoderHandle != 0L) FFmpegBridge.closeDecoder(decoderHandle) }
        }
        } finally {
            uac20DebugRunInFlight.set(false)
            if (uac20DebugPlaybackSwitchEnabled) {
                uac20DebugPlaybackSwitchEnabled = false
                AppLogger.w(TAG, "UAC20 debug playback switch auto-disabled after smoke")
            }
        }
    }

    private fun toUac20DebugProbeReportBlock(result: Uac20DebugProbeResult): String {
        val lines = StringBuilder()
        lines.append("UAC20PreviewDiagnostics=")
            .append("ok=${result.ok}")
            .append(" reason=${result.reason}")
            .append(" device=${result.deviceName ?: "null"}")
            .append(" ${result.vendorProductId ?: ""}")
            .append(" sr=${result.sampleRate} bits=${result.bits} ch=${result.channels} subslot=${result.requestedSubslotBytes}")
            .append(" started=${result.started}")
            .append(" shadowWrites=${result.shadowWriteCalls}")
            .append(" shadowInputBytes=${result.shadowWriteInputBytes}")
            .append(" shadowAcceptedBytes=${result.shadowWriteAcceptedBytes}")
            .append(" elapsedMs=${result.elapsedMs}")
            .append('\n')

        lines.append("UAC20PreviewShadowWrite=")
            .append("calls=${result.shadowWriteCalls}")
            .append(" inputBytes=${result.shadowWriteInputBytes}")
            .append(" acceptedBytes=${result.shadowWriteAcceptedBytes}")
            .append(" droppedBytes=${result.shadowWriteInputBytes - result.shadowWriteAcceptedBytes}")
            .append('\n')

        if (result.pumpDurationMs > 0 || result.pumpWriteCalls > 0) {
            lines.append("UAC20PreviewPlaybackPump=")
                .append("durationMs=${result.pumpDurationMs}")
                .append(", chunkMs=${result.pumpChunkMs}")
                .append(", source=${result.pumpSource}")
                .append(", stop=${result.pumpStopReason}")
                .append(", calls=${result.pumpWriteCalls}")
                .append(", inputBytes=${result.pumpInputBytes}")
                .append(", acceptedBytes=${result.pumpAcceptedBytes}")
                .append('\n')

        lines.append("UAC20PreviewRuntimeGuard=")
            .append("passed=${result.guardPassed}")
            .append(", blocksPromotion=${result.guardBlocksPromotion}")
            .append(", switchEligible=${result.graySwitchEligible}")
            .append(", reason=${result.guardReason}")
            .append('\n')
        }

        if (result.decodedSourcePath.isNotBlank() || result.decodedOpen || result.decodedReadCalls > 0 || result.decodedInputBytes > 0L || result.decodedErrorCode != 0) {
            lines.append("UAC20PreviewDecodedPcm=")
                .append("source=${result.decodedSourcePath.shortForDebugReport()}")
                .append(", open=${result.decodedOpen}")
                .append(", eof=${result.decodedEof}")
                .append(", error=${result.decodedErrorCode}")
                .append(", reads=${result.decodedReadCalls}")
                .append(", bytes=${result.decodedInputBytes}")
                .append('\n')
        }

        val runtimeJson = selectBestRuntimeJson(result)
        lines.append("UAC20PreviewRuntimeJsonSummary=")
            .append(runtimeJson.uac20RuntimeSummaryForLog())
            .append('\n')
        lines.append("UAC20PreviewRuntimeJsonLen=")
            .append(runtimeJson.length)
        return lines.toString()
    }

    private fun selectBestRuntimeJson(result: Uac20DebugProbeResult): String {
        return result.finalRuntimeJson
            ?: result.postStartRuntimeJson
            ?: result.preStartRuntimeJson
            ?: "{}"
    }

    private fun singleLineJsonForLog(json: String): String {
        return json.replace("\n", "").replace("\r", "")
    }

    private fun String.shortForDebugReport(maxLen: Int = 96): String {
        val compact = replace('\n', ' ').replace('\r', ' ').trim()
        if (compact.length <= maxLen) return compact
        val tail = compact.takeLast(maxLen - 3)
        return "...$tail"
    }

    private fun logUac20DebugProbeReport(result: Uac20DebugProbeResult) {
        val report = toUac20DebugProbeReportBlock(result)
        for (line in report.split("\n")) {
            if (line.isNotBlank()) {
                AppLogger.i(TAG, line)
            }
        }
    }

    /**
     * 运行 v2 UAC20 诊断 probe（shadow mode）。
     *
     * 安全保护：
     * - 如果 legacy USB 独占播放正在活跃（currentHandle != 0 或 initialized），直接拒绝。
     * - 不替换 legacy usb_audio_engine.cpp 播放路径。
     * - 不把真实 PCM 路由到 OUT transfer。
     *
     * 流程：
     *   openDevice → openUac20PreviewSession → start → optional shadow write → runtimeJson → close
     */
    @Synchronized
    fun runUac20DebugProbe(
        sampleRate: Int = 192000,
        bits: Int = 24,
        channels: Int = 2,
        requestedSubslotBytes: Int = 4,
        flags: Int = UsbAudioEngine.UAC20_DEFAULT_FLAGS,
        shadowWriteChunks: List<ByteArray> = emptyList(),
        debugPlaybackPumpDurationMs: Int = 0,
        debugPlaybackPumpChunkMs: Int = 5,
        debugPlaybackChunkProvider: (() -> ByteArray?)? = null,
        debugPlaybackSourceLabel: String = if (debugPlaybackPumpDurationMs > 0) "silence" else "none",
        decodedSourcePath: String = "",
        decodedOpen: Boolean = false,
        decodedEofProvider: (() -> Boolean)? = null,
        decodedErrorCodeProvider: (() -> Int)? = null,
        decodedReadCallsProvider: (() -> Int)? = null,
        decodedInputBytesProvider: (() -> Long)? = null,
        label: String = "uac20_debug_probe"
    ): Uac20DebugProbeResult {
        val startTime = System.currentTimeMillis()

        // 安全检查：不要和 legacy live USB handle 并发运行
        if (UsbAudioEngine.currentHandle != 0L || UsbAudioEngine.isInitialized()) {
            val result = Uac20DebugProbeResult(
                ok = false,
                reason = "legacy-usb-active",
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                elapsedMs = System.currentTimeMillis() - startTime
            )
            lastUac20DebugProbeResult = result
            AppLogger.w(TAG, "runUac20DebugProbe skipped: legacy USB is active")
            return result
        }

        // 查找 USB 音频设备
        val device = findUsbAudioDevice()
        if (device == null) {
            val result = Uac20DebugProbeResult(
                ok = false,
                reason = "no-usb-audio-device",
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                elapsedMs = System.currentTimeMillis() - startTime
            )
            lastUac20DebugProbeResult = result
            AppLogger.w(TAG, "runUac20DebugProbe: no USB audio device found")
            return result
        }

        val deviceName = device.productName ?: device.deviceName
        val vidPid = "VID=${String.format("%04X", device.vendorId)} PID=${String.format("%04X", device.productId)}"

        // 请求权限（如果还没有）
        if (!usbManager.hasPermission(device)) {
            AppLogger.w(TAG, "runUac20DebugProbe: no USB permission for $deviceName, requesting...")
            usbManager.requestPermission(device, usbPermissionPendingIntent(device))
            val result = Uac20DebugProbeResult(
                ok = false,
                reason = "no-permission",
                deviceName = deviceName,
                vendorProductId = vidPid,
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                elapsedMs = System.currentTimeMillis() - startTime
            )
            lastUac20DebugProbeResult = result
            return result
        }

        // openDevice
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            val result = Uac20DebugProbeResult(
                ok = false,
                reason = "openDevice-failed",
                deviceName = deviceName,
                vendorProductId = vidPid,
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                elapsedMs = System.currentTimeMillis() - startTime
            )
            lastUac20DebugProbeResult = result
            AppLogger.e(TAG, "runUac20DebugProbe: openDevice failed for $deviceName")
            return result
        }

        val fd = conn.fileDescriptor
        var session: UsbAudioEngine.Uac20PreviewSession? = null
        var preStartJson: String? = null
        var postStartJson: String? = null
        var finalJson: String? = null
        var started = false
        var shadowCalls = 0
        var shadowInputBytes = 0L
        var shadowAcceptedBytes = 0L

        try {
            session = UsbAudioEngine.openUac20PreviewSession(
                fd = fd,
                sourceSampleRate = sampleRate,
                sourceBits = bits,
                sourceChannels = channels,
                requestedSampleRate = sampleRate,
                requestedBits = bits,
                requestedSubslotBytes = requestedSubslotBytes,
                flags = flags
            )

            if (!session.isValid) {
                val createError = UsbAudioEngine.getUac20LastCreateError()
                AppLogger.e(TAG, "runUac20DebugProbe: createUac20Session failed: $createError")
                val result = Uac20DebugProbeResult(
                    ok = false,
                    reason = "create-session-failed:$createError",
                    deviceName = deviceName,
                    vendorProductId = vidPid,
                    sampleRate = sampleRate,
                    bits = bits,
                    channels = channels,
                    requestedSubslotBytes = requestedSubslotBytes,
                    elapsedMs = System.currentTimeMillis() - startTime,
                    summary = createError
                )
                lastUac20DebugProbeResult = result
                return result
            }

            // pre-start runtimeJson (descriptor snapshot, clock, plan)
            preStartJson = session.runtimeJson()
            AppLogger.i(TAG, "runUac20DebugProbe preStart runtimeJson=$preStartJson")

            // start (descriptor → clock → feedback → silent OUT probe)
            started = session.start()
            AppLogger.i(TAG, "runUac20DebugProbe start result=$started")

            // post-start runtimeJson
            postStartJson = session.runtimeJson()
            AppLogger.i(TAG, "runUac20DebugProbe postStart runtimeJson=$postStartJson")

            // optional shadow write: feed chunks to validate write ring
            if (started) {
                if (shadowWriteChunks.isNotEmpty()) {
                    for (chunk in shadowWriteChunks) {
                        val written = session.writeShadow(chunk, 0, chunk.size)
                        if (written > 0) {
                            shadowCalls++
                            shadowInputBytes += chunk.size
                            shadowAcceptedBytes += written
                        }
                    }
                } else {
                    val frameBytes = channels * requestedSubslotBytes
                    val shadowChunkBytes = frameBytes * 256
                    val shadowData = ByteArray(shadowChunkBytes)
                    val shadowWriteCount = 4
                    for (i in 0 until shadowWriteCount) {
                        val written = session.writeShadow(shadowData, 0, shadowChunkBytes)
                        if (written > 0) {
                            shadowCalls++
                            shadowInputBytes += shadowChunkBytes
                            shadowAcceptedBytes += written
                        }
                    }
                }
                AppLogger.i(TAG, "runUac20DebugProbe shadow writes=$shadowCalls inputBytes=$shadowInputBytes acceptedBytes=$shadowAcceptedBytes")
            }

            // 0046-0047: optional continuous PCM pump
            var pumpCalls = 0
            var pumpInputBytes = 0L
            var pumpAcceptedBytes = 0L
            var pumpStopReason = "not-started"
            var pumpElapsedMs = 0

            if (started && debugPlaybackPumpDurationMs > 0) {
                val chunkMs = debugPlaybackPumpChunkMs.coerceIn(1, 50)
                val frameBytes = channels * if (bits == 24) 4 else ((bits + 7) / 8).coerceAtLeast(1)
                val silenceChunk by lazy { ByteArray(sampleRate * frameBytes / 1000 * chunkMs) }
                val deadline = System.currentTimeMillis() + debugPlaybackPumpDurationMs.coerceAtMost(30_000)
                var nextPumpAt = System.currentTimeMillis()
                pumpStopReason = "duration"
                val pumpStart = System.currentTimeMillis()
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (now < nextPumpAt) {
                        Thread.sleep((nextPumpAt - now).coerceAtMost(chunkMs.toLong()))
                    }
                    val chunk = debugPlaybackChunkProvider?.invoke() ?: run {
                        if (debugPlaybackChunkProvider != null) {
                            pumpStopReason = "provider-eof-or-error"
                            null
                        } else {
                            silenceChunk
                        }
                    } ?: break
                    if (chunk.isEmpty()) {
                        pumpStopReason = "empty-chunk"
                        break
                    }
                    pumpCalls++
                    pumpInputBytes += chunk.size
                    val accepted = session.writeShadow(chunk, 0, chunk.size)
                    if (accepted > 0) pumpAcceptedBytes += accepted
                    if (accepted < 0) {
                        pumpStopReason = "write-error:$accepted"
                        break
                    }
                    val elapsedSincePumpStart = SystemClock.elapsedRealtime() - (deadline - debugPlaybackPumpDurationMs.coerceAtMost(30_000))
                    if (elapsedSincePumpStart >= 450L && pumpCalls % 8 == 0) {
                        val liveJson = runCatching { session.runtimeJson() }.getOrDefault("")
                        val realCompleted = liveJson.jsonLong("realOutSubmitterCompleteCount")
                        val realSubmitted = liveJson.jsonBool("realOutSubmitterSubmitted") ||
                            liveJson.contains("\"realOutSubmitterSubmitted\":true")
                        val submitOk = liveJson.jsonLong("realOutSubmitterSubmitOkCount")
                        val activeTransfers = liveJson.jsonLong("realOutSubmitterActiveTransferCount")
                        val callbacks = liveJson.jsonLong("realOutSubmitterCallbackCount")
                        val noCompletionMs = liveJson.jsonLong("realOutSubmitterNoCompletionMs")
                        val dropped = liveJson.jsonLong("writeRingTotalDroppedBytes")
                        val layoutValid = liveJson.jsonRawValue("realOutSubmitterLayoutValid") != "false"
                        if (!layoutValid) {
                            pumpStopReason = "early-stop-real-out-layout-invalid"
                            break
                        }
                        if ((realSubmitted || submitOk > 0L || activeTransfers > 0L) && realCompleted <= 0L &&
                            (callbacks <= 0L || noCompletionMs >= 350L || elapsedSincePumpStart >= 650L)) {
                            pumpStopReason = "early-stop-real-out-no-completion-timeout:${noCompletionMs.coerceAtLeast(elapsedSincePumpStart)}"
                            break
                        }
                        // Hard fallback for the exact failure seen in logs: submit succeeds,
                        // callback count remains zero, and activeTransferCount may be reported
                        // as zero because native ownership bookkeeping was lost. Do not wait
                        // for the full 5s pump in that state.
                        if (elapsedSincePumpStart >= 650L && submitOk > 0L && callbacks <= 0L && realCompleted <= 0L) {
                            pumpStopReason = "early-stop-real-out-submitted-without-callback:${elapsedSincePumpStart}"
                            break
                        }
                        if (dropped > 0L) {
                            pumpStopReason = "early-stop-write-ring-dropping:$dropped"
                            break
                        }
                    }
                    val afterWrite = System.currentTimeMillis()
                    // 0087: Keep the pump on a fixed wall-clock cadence. The old
                    // maxOf(next + chunk, afterWrite + chunk) added decode/write
                    // overhead to every period; the MOONDROP log showed only 926
                    // five-millisecond writes in 5 seconds, starving native OUT.
                    nextPumpAt += chunkMs.toLong()
                    if (afterWrite < nextPumpAt) {
                        Thread.sleep((nextPumpAt - afterWrite).coerceAtMost(chunkMs.toLong()))
                    } else if (afterWrite - nextPumpAt > chunkMs.toLong() * 4L) {
                        // Avoid a long burst after GC/scheduler stalls; resume from now.
                        nextPumpAt = afterWrite
                    }
                }
                pumpElapsedMs = (System.currentTimeMillis() - pumpStart).toInt()
            }

            // final runtimeJson
            finalJson = session.runtimeJson()

            session.stop()

            val guard = evaluateUac20DebugRuntimeGuard(
                started = started,
                pumpSource = debugPlaybackSourceLabel,
                pumpInputBytes = pumpInputBytes,
                pumpAcceptedBytes = pumpAcceptedBytes,
                decodedOpen = decodedOpen,
                decodedErrorCode = decodedErrorCodeProvider?.invoke() ?: 0,
                decodedInputBytes = decodedInputBytesProvider?.invoke() ?: 0L,
                runtimeJson = finalJson ?: ""
            )

            val ok = started && (!uac20DebugPlaybackSwitchEnabled || guard.first)
            val result = Uac20DebugProbeResult(
                ok = ok,
                reason = when {
                    !started -> "start-failed"
                    uac20DebugPlaybackSwitchEnabled && !guard.first -> "runtime-guard-blocked:${guard.third}"
                    else -> "ok"
                },
                deviceName = deviceName,
                vendorProductId = vidPid,
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                started = started,
                shadowWriteCalls = shadowCalls,
                shadowWriteInputBytes = shadowInputBytes,
                shadowWriteAcceptedBytes = shadowAcceptedBytes,
                pumpDurationMs = pumpElapsedMs,
                pumpChunkMs = debugPlaybackPumpChunkMs,
                pumpWriteCalls = pumpCalls,
                pumpInputBytes = pumpInputBytes,
                pumpAcceptedBytes = pumpAcceptedBytes,
                pumpSource = debugPlaybackSourceLabel,
                pumpStopReason = pumpStopReason,
                decodedSourcePath = decodedSourcePath,
                decodedOpen = decodedOpen,
                decodedEof = decodedEofProvider?.invoke() == true,
                decodedErrorCode = decodedErrorCodeProvider?.invoke() ?: 0,
                decodedReadCalls = decodedReadCallsProvider?.invoke() ?: 0,
                decodedInputBytes = decodedInputBytesProvider?.invoke() ?: 0L,
                guardPassed = guard.first,
                guardBlocksPromotion = guard.second,
                guardReason = guard.third,
                graySwitchEligible = guard.first && uac20DebugPlaybackSwitchEnabled,
                preStartRuntimeJson = preStartJson,
                postStartRuntimeJson = postStartJson,
                finalRuntimeJson = finalJson,
                elapsedMs = System.currentTimeMillis() - startTime,
                summary = "device=$deviceName $vidPid sr=$sampleRate bits=$bits ch=$channels subslot=$requestedSubslotBytes started=$started shadowWrites=$shadowCalls pumpCalls=$pumpCalls pumpSource=$debugPlaybackSourceLabel guard=$guard.first guardReason=$guard.third switchEligible=${guard.first && uac20DebugPlaybackSwitchEnabled}"
            )
            lastUac20DebugProbeResult = result
            AppLogger.i(TAG, "runUac20DebugProbe done: ${result.summary}")
            logUac20DebugProbeReport(result)
            return result

        } catch (t: Throwable) {
            AppLogger.e(TAG, "runUac20DebugProbe exception", t)
            try { session?.stop() } catch (_: Throwable) {}
            val result = Uac20DebugProbeResult(
                ok = false,
                reason = "exception: ${t.message}",
                deviceName = deviceName,
                vendorProductId = vidPid,
                sampleRate = sampleRate,
                bits = bits,
                channels = channels,
                requestedSubslotBytes = requestedSubslotBytes,
                started = started,
                shadowWriteCalls = shadowCalls,
                shadowWriteInputBytes = shadowInputBytes,
                shadowWriteAcceptedBytes = shadowAcceptedBytes,
                preStartRuntimeJson = preStartJson,
                postStartRuntimeJson = postStartJson,
                finalRuntimeJson = finalJson,
                elapsedMs = System.currentTimeMillis() - startTime
            )
            lastUac20DebugProbeResult = result
            logUac20DebugProbeReport(result)
            return result
        } finally {
            try { session?.close() } catch (_: Throwable) {}
            try { conn.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 0060-0063: gray playback routing decision for the UAC20 v2 path.
     */
    data class Uac20GrayPlaybackDecision(
        val enabled: Boolean,
        val attempted: Boolean,
        val route: String,
        val reason: String,
        val sourcePath: String = "",
        val startPositionMs: Long = 0L,
        val sampleRate: Int = 0,
        val bits: Int = 0,
        val channels: Int = 0,
        val requestedSubslotBytes: Int = 0,
        val previewOk: Boolean = false,
        val guardPassed: Boolean = false,
        val guardBlocksPromotion: Boolean = true,
        val fallbackToLegacy: Boolean = true,
        val fallbackReason: String = "not-attempted",
        val probeSummary: String = "",
        val elapsedMs: Long = 0L
    ) {
        val consumedByV2: Boolean
            get() = enabled && attempted && route == "v2-debug" && !fallbackToLegacy

        val reportLine: String
            get() = "UAC20GrayPlaybackDecision=enabled=$enabled, attempted=$attempted, route=$route, " +
                "reason=$reason, fallbackToLegacy=$fallbackToLegacy, fallbackReason=$fallbackReason, " +
                "fmt=${sampleRate}/${bits}/${channels}/subslot=$requestedSubslotBytes, " +
                "previewOk=$previewOk, guardPassed=$guardPassed, guardBlocksPromotion=$guardBlocksPromotion, " +
                "elapsedMs=$elapsedMs, source=$sourcePath"
    }

    @Volatile
    private var lastUac20GrayPlaybackDecision: Uac20GrayPlaybackDecision? = null

    fun getLastUac20GrayPlaybackDecision(): Uac20GrayPlaybackDecision? = lastUac20GrayPlaybackDecision

    fun getLastUac20GrayPlaybackDecisionReport(): String =
        lastUac20GrayPlaybackDecision?.reportLine ?: "UAC20GrayPlaybackDecision=not-run"

    fun runUac20GrayPlaybackDecision(
        sourcePath: String,
        startPositionMs: Long = 0L,
        maxDurationMs: Int = 5_000,
        targetSampleRate: Int = 0,
        // 0106: default gray preview to auto/probed source bit depth.
        targetBits: Int = 0,
        targetChannels: Int = 2,
        // 0 means native matcher selects exact/lossless subslot.
        requestedSubslotBytes: Int = 0,
        pumpChunkMs: Int = 5
    ): Uac20GrayPlaybackDecision {
        val startedAt = SystemClock.elapsedRealtime()
        fun remember(decision: Uac20GrayPlaybackDecision): Uac20GrayPlaybackDecision {
            val withElapsed = decision.copy(elapsedMs = SystemClock.elapsedRealtime() - startedAt)
            lastUac20GrayPlaybackDecision = withElapsed
            AppLogger.w(TAG, withElapsed.reportLine)
            return withElapsed
        }

        if (!uac20DebugPlaybackSwitchEnabled) {
            return remember(
                Uac20GrayPlaybackDecision(
                    enabled = false,
                    attempted = false,
                    route = "legacy",
                    reason = "debug-v2-switch-disabled",
                    sourcePath = sourcePath,
                    startPositionMs = startPositionMs,
                    sampleRate = targetSampleRate,
                    bits = targetBits,
                    channels = targetChannels,
                    requestedSubslotBytes = requestedSubslotBytes,
                    fallbackToLegacy = true,
                    fallbackReason = "switch-disabled"
                )
            )
        }

        val probe = runUac20DebugDecodedPlaybackSmoke(
            sourcePath = sourcePath,
            startPositionMs = startPositionMs,
            maxDurationMs = maxDurationMs,
            targetSampleRate = targetSampleRate,
            targetBits = targetBits,
            targetChannels = targetChannels,
            requestedSubslotBytes = requestedSubslotBytes,
            pumpChunkMs = pumpChunkMs
        )
        val promote = probe.ok && probe.graySwitchEligible && probe.guardPassed && !probe.guardBlocksPromotion
        val fallbackReason = when {
            promote -> "none"
            !probe.ok -> "preview-failed:${probe.reason}"
            probe.guardBlocksPromotion -> "guard-blocked:${probe.guardReason}"
            !probe.graySwitchEligible -> "not-switch-eligible:${probe.guardReason}"
            else -> "not-promoted:${probe.reason}"
        }
        return remember(
            Uac20GrayPlaybackDecision(
                enabled = true,
                attempted = true,
                route = if (promote) "v2-debug" else "legacy",
                reason = if (promote) "debug-v2-guard-passed" else "debug-v2-fallback-legacy",
                sourcePath = sourcePath,
                startPositionMs = startPositionMs,
                sampleRate = probe.sampleRate.takeIf { it > 0 } ?: targetSampleRate,
                bits = probe.bits.takeIf { it > 0 } ?: targetBits,
                channels = probe.channels.takeIf { it > 0 } ?: targetChannels,
                requestedSubslotBytes = probe.requestedSubslotBytes.takeIf { it > 0 } ?: requestedSubslotBytes,
                previewOk = probe.ok,
                guardPassed = probe.guardPassed,
                guardBlocksPromotion = probe.guardBlocksPromotion,
                fallbackToLegacy = !promote,
                fallbackReason = fallbackReason,
                probeSummary = probe.summary ?: ""
            )
        )
    }
}
