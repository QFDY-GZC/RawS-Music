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
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.UsbStatusNoticeBus
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
    private val transportOwner = UsbTransportCommandQueue()
    private var lastResampledCacheTrimMs = 0L

    init {
        // Seed the OpenSL scheduling probe with Android's output sample
        // rate and frames-per-buffer when the USB manager is created.
        UsbAudioEngine.configureAndroidAudioSchedulerProfile(context)
    }

    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var currentConfig: UsbAudioConfig? = null
    private var currentSourceSampleRate: Int = 0
    private var currentSourceBits: Int = 0
    @Volatile
    private var currentDsdSessionKey: String? = null

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

    private val permissionCallbackLock = Any()
    private var permissionCallback: ((Boolean) -> Unit)? = null
    var onDeviceAttached: ((UsbDevice) -> Unit)? = null
    var onDeviceDetached: ((UsbDevice?) -> Unit)? = null
    var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** DSD/PCM→DSD sessions must never use PCM warm pause or standby reuse. */
    fun isDsdSessionActive(): Boolean =
        currentDsdSessionKey != null && UsbAudioEngine.currentHandle != 0L

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // Android dispatches these broadcasts on the process main thread.
            // The receiver only publishes a transport message and returns; it
            // never waits for open/claim/init/cancel/reap while system_server is
            // waiting for the receiver. Keep this callback strictly O(1).
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    transportOwner.post("broadcast-permission-result") {
                        handlePermissionResultOnTransport(device, granted)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    transportOwner.post("broadcast-detach") {
                        val detachedDevice = device ?: run {
                            AppLogger.w(TAG, "USB detach broadcast without device; ignored")
                            return@post
                        }
                        if (detachedDevice.deviceId != currentDevice?.deviceId) return@post
                        AppLogger.w(TAG, "USB device detached: ${detachedDevice.deviceName}")
                        lastDeniedDeviceId = -1
                        // Controller notification and native teardown now run on
                        // the same serialized owner, never on BroadcastReceiver/main.
                        onDeviceDetached?.invoke(detachedDevice)
                        runCatching { UsbAudioEngine.nativeOnUsbDetached() }
                            .onFailure { AppLogger.w(TAG, "nativeOnUsbDetached failed", it) }
                        closeLocked("detached")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        transportOwner.post("broadcast-attach") {
                            AppLogger.i(TAG, "USB device attached: ${device.deviceName}")
                            handleDeviceInserted(device)
                        }
                    }
                }
            }
        }
    }

    private fun handlePermissionResultOnTransport(device: UsbDevice?, granted: Boolean) {
        val callback = synchronized(permissionCallbackLock) {
            permissionCallback.also { permissionCallback = null }
        }
        AppLogger.d(TAG, "Permission result on Transport: device=${device?.deviceName}, granted=$granted")
        if (granted && device != null) {
            currentDevice = device
            lastDeniedDeviceId = -1
            _state.value = State.READY
            _error.value = null
            // Post before resuming any permission continuation or controller callback. Those
            // callbacks can immediately rebuild/release the route and must not be able to skip
            // the one guaranteed fresh-grant notification event. Every result handled here came
            // from this app's UsbManager.requestPermission() PendingIntent.
            AppLogger.i(TAG, "Fresh USB permission granted; queue DAC initialization notice")
            UsbStatusNoticeBus.post("USB DAC 初始化成功！")
            callback?.invoke(true)
            AppLogger.i(TAG, "Permission granted for ${device.productName}, notifying controller once off-main")
            onPermissionResult?.invoke(device, true)
        } else {
            _state.value = State.ERROR
            _error.value = "USB 权限被拒绝"
            if (device != null) {
                lastDeniedDeviceId = device.deviceId
                lastPermissionRequestTime = System.currentTimeMillis()
                onPermissionResult?.invoke(device, false)
            }
            callback?.invoke(false)
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

            // Keep descriptor diagnostics even when the Android-side filter rejects the device.
            // Some DACs expose the streaming endpoint on an alternate interface and otherwise
            // look like a generic USB device in the one-line scan log.
            if ((0 until device.interfaceCount).any {
                    val intf = device.getInterface(it)
                    intf.interfaceClass == USB_CLASS_AUDIO ||
                        intf.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING
                }) {
                dumpInterfaces(device)
            }

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
            // State can be updated immediately, but lifecycle/controller callbacks
            // must still go through the Transport owner so a main/UI caller never
            // enters close/open/init synchronously.
            AppLogger.d(TAG, "Already have permission, publishing Transport message")
            currentDevice = device
            lastDeniedDeviceId = -1
            _state.value = State.READY
            _error.value = null
            transportOwner.post("permission-already-granted") {
                // One permission result must produce one lifecycle command. The
                // previous onPermissionResult + onDeviceReady fan-out could queue
                // two concurrent activation attempts for the same grant.
                onPermissionResult?.invoke(device, true)
            }
            return
        }
        // 进入 "请求中" 状态，让 UI 能显示 Loading
        _state.value = State.REQUESTING_PERMISSION
        lastPermissionRequestTime = now
        synchronized(permissionCallbackLock) {
            permissionCallback = { granted ->
                // Permission result state is owned by handlePermissionResultOnTransport;
                // this callback only preserves the legacy failure observation.
                if (!granted) {
                    _state.value = State.ERROR
                    _error.value = "USB 权限被拒绝"
                }
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
            val callback: (Boolean) -> Unit = { granted ->
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
            synchronized(permissionCallbackLock) {
                permissionCallback = callback
            }
            cont.invokeOnCancellation {
                synchronized(permissionCallbackLock) {
                    if (permissionCallback === callback) permissionCallback = null
                }
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
    fun prepareForPlayback(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        srcFilePath: String? = null,
        allowFallback: Boolean = true,
        suppressDsdForRetry: Boolean = false
    ): Boolean = transportOwner.call("prepare ${sampleRate}/${bits}/${channels}") {
        prepareForPlaybackOnTransport(
            sampleRate = sampleRate,
            bits = bits,
            channels = channels,
            srcFilePath = srcFilePath,
            allowFallback = allowFallback,
            suppressDsdForRetry = suppressDsdForRetry
        )
    }

    private fun prepareForPlaybackOnTransport(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        srcFilePath: String?,
        allowFallback: Boolean,
        suppressDsdForRetry: Boolean
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
            // PCM→DSD never inherits the source-DSD DoP preference.  DSD256 DoP
            // would require a 705.6/768 kHz PCM alt and caused the explicit P2D
            // request to collapse to enabled=0 before native init.  Build the
            // Native-DSD session identity optimistically; the full native scan of
            // RAW_DATA + clock ranges is the authoritative capability check.
            buildUsbDsdModeConfig(
                enabled = AppPreferences.Player.dsdConversionEnabled &&
                    !suppressDsdForRetry,
                multiplier = AppPreferences.Player.dsdRate,
                transport = UsbDsdTransport.NATIVE,
                sourceRateHz = sampleRate,
                sourceIsAlreadyDsd = false
            )
        } else {
            null
        }
        val dsdMode = sourceDsdMode ?: pcmToDsdMode
        // A null key is the stable PCM session.  Keep it distinct from DSD
        // session identities: the lifecycle below uses null to allow warm
        // reuse and to keep pause/stop on the native PCM route.  Marking PCM
        // as a fake DSD key forces every track to close/reopen the USB handle
        // and can leave the exclusive route unavailable after a fast retry.
        val desiredDsdSessionKey: String? = when {
            dsdMode == null -> null
            sourceIsDsd -> "SOURCE:${dsdMode.multiplier}:${dsdMode.transport}:${dsdMode.deviceSampleRate}"
            else -> "P2D:${dsdMode.multiplier}:${dsdMode.transport}:${dsdMode.deviceSampleRate}"
        }
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
                // Do not mutate process-global DSD state while an old USB handle may be live.
                // The recursive PCM prepare will close the old handle, then apply PCM mode before init.
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
        // DSD transport
        // sessions do not use the normal PCM warm-reuse/standby contract. A
        // fresh handle is required even for the same DSD profile so stale RAW
        // altsetting, marker/packer state or converter history cannot leak.
        val sameConfigReady =
            desiredDsdSessionKey == null &&
            currentConfig == cfg &&
            currentSourceSampleRate == sampleRate &&
            currentSourceBits == cfg.sourceBits &&
            currentDsdSessionKey == desiredDsdSessionKey &&
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
            if (UsbAudioEngine.isNativeSessionBroken()) {
                AppLogger.e(
                    TAG,
                    "prepareForPlayback: fast reuse rejected because cancelled USB transfers remain; reopening device",
                )
                closeAllNow()
                return prepareForPlayback(
                    sampleRate = sampleRate,
                    bits = bits,
                    channels = channels,
                    srcFilePath = srcFilePath,
                    allowFallback = allowFallback,
                    suppressDsdForRetry = suppressDsdForRetry,
                )
            }
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

        // Transaction boundary: only after the old writer/handle/connection are fully closed may
        // process-global PCM/DoP/Native-DSD state change. This prevents an old DSD altsetting from
        // observing a new PCM configuration during settings changes or recovery.
        UsbAudioEngine.setDsdConversion(
            enabled = dsdMode != null,
            rate = dsdMode?.multiplier ?: AppPreferences.Player.dsdRate,
            type = AppPreferences.Player.dsdConversionType,
            dither = if (pcmDsdActive) AppPreferences.Player.dsdDitherEnabled else false,
            dop = dsdMode?.transport == UsbDsdTransport.DOP,
        )
        AppLogger.i(
            TAG,
            "DSD session config applied after old USB close: key=$desiredDsdSessionKey handle=${UsbAudioEngine.currentHandle}",
        )

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
                // Old handle is already closed by closeLocked(). The recursive PCM prepare owns
                // the next session configuration and applies it immediately before native init.
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
        currentDsdSessionKey = desiredDsdSessionKey
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

        // 初始化完成后仅设置软件 PCM 无数据窗口保护；Feature Unit 由 Controller 在 ISO 前按设备初始化一次。
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
    @Synchronized
    private fun closeAllNow() {
        UsbAudioEngine.closeNative("prepareForPlayback fresh start")
        currentConfig = null
        currentSourceSampleRate = 0
        currentSourceBits = 0
        currentDsdSessionKey = null
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
    fun prepareAndStartForTrack(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        firstPcmChunks: List<ByteArray> = emptyList()
    ): Boolean = transportOwner.call("prepare-start ${sampleRate}/${bits}/${channels}") {
        prepareAndStartForTrackOnTransport(sampleRate, bits, channels, firstPcmChunks)
    }

    private fun prepareAndStartForTrackOnTransport(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        firstPcmChunks: List<ByteArray>
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

    fun startStreaming(): Boolean = transportOwner.call("start") {
        val h = UsbAudioEngine.currentHandle
        if (h == 0L) {
            AppLogger.e(TAG, "startStreaming failed: currentHandle=0")
            return@call false
        }
        // Keep the Kotlin session poison flag in the same path as every other
        // native start. Calling JNI directly here left a failed handle looking
        // healthy and caused repeated starts against a dead USB session.
        AppLogger.i(TAG, "startStreaming: starting managed native session, handle=0x${h.toString(16)}")
        val ok = UsbAudioEngine.start()
        AppLogger.i(TAG, "managed native start returned $ok broken=${UsbAudioEngine.isNativeSessionBroken()}")
        ok
    }

    fun stopStreaming(reason: String = "unknown") = transportOwner.call("stop:$reason") {
        AppLogger.i(TAG, "stopStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L && currentDsdSessionKey != null) {
            AppLogger.w(
                TAG,
                "stopStreaming: DSD session requires hard destroy key=$currentDsdSessionKey " +
                    "handle=0x${h.toString(16)} reason=$reason"
            )
            runCatching { UsbAudioEngine.nativeStopAndFlush(h) }
                .onFailure { AppLogger.w(TAG, "DSD nativeStopAndFlush failed", it) }
            closeLocked("dsd_stop:$reason")
        } else if (h != 0L) {
            AppLogger.i(TAG, "stopStreaming: calling nativeStop, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativeStop(h)
        } else {
            AppLogger.i(TAG, "stopStreaming: handle=0, skipping nativeStop")
        }
    }

    fun pauseStreaming(reason: String = "pause") = transportOwner.call("pause:$reason") {
        AppLogger.i(TAG, "pauseStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L && currentDsdSessionKey != null) {
            AppLogger.w(
                TAG,
                "pauseStreaming: DSD session requires hard destroy key=$currentDsdSessionKey " +
                    "handle=0x${h.toString(16)} reason=$reason"
            )
            runCatching { UsbAudioEngine.nativeStopAndFlush(h) }
                .onFailure { AppLogger.w(TAG, "DSD pause hard-stop failed", it) }
            closeLocked("dsd_pause:$reason")
        } else if (h != 0L) {
            AppLogger.i(TAG, "pauseStreaming: calling nativePause, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativePause(h)
        } else {
            AppLogger.i(TAG, "pauseStreaming: handle=0, skipping nativePause")
        }
    }

    fun stopAndFlushStreaming(reason: String = "track_change") =
        transportOwner.call("flush:$reason") {
            AppLogger.i(TAG, "stopAndFlushStreaming called, reason=$reason")
            UsbAudioEngine.flushForNextTrack("stopAndFlushStreaming:$reason")
        }

    fun release(reason: String = "unknown") = transportOwner.call("release:$reason") {
        AppLogger.w(TAG, "release requested: reason=$reason, state=${_state.value}")
        closeLocked("release:$reason")
    }

    fun resetPlaybackPipeline(reason: String = "unknown") =
        transportOwner.call("reset:$reason") {
            AppLogger.w(TAG, "resetPlaybackPipeline requested: reason=$reason, state=${_state.value}")
            closeAllNow()
        }

    fun notifyNativeDetached(reason: String = "detached") =
        transportOwner.call("notify-detached:$reason") {
            try {
                UsbAudioEngine.nativeOnUsbDetached()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "nativeOnUsbDetached failed: reason=$reason", t)
            }
        }

    fun releaseForDetachedDevice() {
        transportOwner.post("detach") {
            AppLogger.w(TAG, "releaseForDetachedDevice requested, state=${_state.value}")
            try {
                UsbAudioEngine.nativeOnUsbDetached()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "nativeOnUsbDetached failed", t)
            }
            closeLocked("detached")
        }
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
        currentDsdSessionKey = null
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

}
