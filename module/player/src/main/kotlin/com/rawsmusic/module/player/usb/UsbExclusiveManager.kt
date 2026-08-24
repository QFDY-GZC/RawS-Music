package com.rawsmusic.module.player.usb

import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import com.rawsmusic.module.data.prefs.TransitionPreferences
import com.rawsmusic.module.player.AudioOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

class UsbExclusiveManager(private val context: Context) {

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
        private const val USB_DT_INTERFACE = 0x04
        private const val AUTH_OPEN_MAX_ATTEMPTS = 3
        private const val NATIVE_INIT_MAX_ATTEMPTS = 3
        private val AUTH_RETRY_DELAYS_MS = longArrayOf(60L, 180L)
        private val NATIVE_INIT_RETRY_DELAYS_MS = longArrayOf(120L, 420L)
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
    private val pcmFallbackProcessor = UsbPcmFallbackProcessor(context)
    private val usbDiscovery = UsbDeviceDiscovery(usbManager)
    private val trackStartCoordinator by lazy {
        UsbTrackStartCoordinator(
            currentConfig = { currentConfig },
            fadeOut = ::fadeOutIfStreaming,
            stopStreaming = ::stopStreaming,
            prepareForPlayback = { sampleRate, bits, channels ->
                prepareForPlayback(sampleRate, bits, channels)
            },
            startStreaming = ::startStreaming,
            setStreamingState = ::setStreamingState,
        )
    }

    init {
        // Seed the OpenSL scheduling probe with Android's output sample
        // rate and frames-per-buffer when the USB manager is created.
        UsbAudioEngine.configureAndroidAudioSchedulerProfile(context)
    }

    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var connectionDeviceId: Int = -1
    private var connectionDeviceName: String? = null
    private var currentConfig: UsbAudioConfig? = null
    private var currentSourceSampleRate: Int = 0
    private var currentSourceBits: Int = 0
    @Volatile
    private var currentDsdSessionKey: String? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    // 防重复弹窗：冷却与 Android 权限编排由独立 coordinator 管理。
    private val permissionGate = UsbPermissionRequestGate()

    /** 硬件音量扫描结果（Kotlin 层提前扫描，供 UI 提示） */
    private val _volumeInfo = MutableStateFlow<VolumeInfo?>(null)
    val volumeInfo: StateFlow<VolumeInfo?> = _volumeInfo

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var onDeviceAttached: ((UsbDevice) -> Unit)? = null
    var onDeviceDetached: ((UsbDevice?) -> Unit)? = null
    var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    private val permissionCoordinator by lazy {
        UsbPermissionCoordinator(
            usbManager = usbManager,
            permissionGate = permissionGate,
            transportOwner = transportOwner,
            pendingIntentFactory = { device -> UsbPermissionIntentFactory.create(context, device) },
            currentDevice = { currentDevice },
            setCurrentDevice = { device -> currentDevice = device },
            currentState = { _state.value },
            setState = { state -> _state.value = state },
            setError = { message -> _error.value = message },
            onFreshGrantBeforeResume = { device ->
                // UAPP opens the Android UsbDeviceConnection directly from the permission-result
                // path, then hands that already-authorized FD to its transport thread. Mirror that
                // ownership model here: permission is the authorization edge, not merely a flag to
                // be re-observed much later during renderer preparation. The connection remains
                // Java-owned until native teardown completes.
                val primed = ensureAuthorizedConnectionOnTransport(
                    requestedDevice = device,
                    reason = "fresh_permission_grant",
                    forceReopen = false,
                ) != null
                AppLogger.i(
                    TAG,
                    "Fresh USB permission authorization lease primed=$primed device=${device.deviceName}",
                )
            },
            onPermissionResolved = { device, granted ->
                AppLogger.i(TAG, "Permission resolved for ${device.productName}, granted=$granted")
                onPermissionResult?.invoke(device, granted)
            },
        )
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Keep Android's post-permission UsbDeviceConnection alive as an authorization lease.
     *
     * UAPP's Java layer opens the device immediately after the permission broadcast and forwards
     * that exact FD to its USB transport initializer. RawS keeps native claim/set-alt lazy, but
     * primes the Java connection at the same boundary so an OEM permission sheet cannot disappear
     * and leave the first exclusive prepare racing a second openDevice().
     */
    fun primeAuthorizedDevice(device: UsbDevice, reason: String = "explicit_prime"): Boolean =
        transportOwner.call("prime-authorized:$reason") {
            ensureAuthorizedConnectionOnTransport(device, reason, forceReopen = false) != null
        }

    private fun resolveAttachedAuthorizedDevice(requestedDevice: UsbDevice): UsbDevice? {
        val devices = usbManager.deviceList.values
        val exact = devices.firstOrNull { it.deviceName == requestedDevice.deviceName }
            ?: devices.firstOrNull { it.deviceId == requestedDevice.deviceId }
        val candidate = exact ?: devices.filter {
            it.vendorId == requestedDevice.vendorId &&
                it.productId == requestedDevice.productId &&
                usbDiscovery.isUsbAudioCandidateDevice(it)
        }.singleOrNull()
        if (candidate == null || !usbDiscovery.isUsbAudioCandidateDevice(candidate)) return null
        return candidate.takeIf { usbManager.hasPermission(it) }
    }

    private fun closeJavaConnectionOnly(reason: String) {
        val conn = connection
        connection = null
        connectionDeviceId = -1
        connectionDeviceName = null
        if (conn != null) {
            AppLogger.i(
                TAG,
                "USB_AUTH_LEASE close conn=${System.identityHashCode(conn)} reason=$reason",
            )
            runCatching { conn.close() }
                .onFailure { AppLogger.w(TAG, "USB_AUTH_LEASE connection.close failed reason=$reason", it) }
        }
    }

    /** Must run on [transportOwner]. */
    private fun ensureAuthorizedConnectionOnTransport(
        requestedDevice: UsbDevice,
        reason: String,
        forceReopen: Boolean,
    ): UsbDeviceConnection? {
        var device: UsbDevice = resolveAttachedAuthorizedDevice(requestedDevice) ?: run {
            AppLogger.e(
                TAG,
                "USB_AUTH_LEASE unavailable: attached/authorized device not found reason=$reason " +
                    "requested=${requestedDevice.deviceName}",
            )
            return null
        }
        currentDevice = device

        val existing = connection
        if (!forceReopen &&
            existing != null &&
            connectionDeviceId == device.deviceId &&
            connectionDeviceName == device.deviceName
        ) {
            val fd = runCatching { existing.fileDescriptor }.getOrDefault(-1)
            if (fd >= 0) {
                AppLogger.i(
                    TAG,
                    "USB_AUTH_LEASE reuse device=${device.deviceName} fd=$fd reason=$reason",
                )
                _state.value = State.READY
                _error.value = null
                return existing
            }
            AppLogger.w(TAG, "USB_AUTH_LEASE stale fd=$fd; reopening reason=$reason")
        }

        if (UsbAudioEngine.currentHandle != 0L) {
            AppLogger.e(
                TAG,
                "USB_AUTH_LEASE refused Java reopen while native handle is live " +
                    "handle=0x${UsbAudioEngine.currentHandle.toString(16)} reason=$reason",
            )
            return existing
        }
        closeJavaConnectionOnly("reopen:$reason")

        for (attempt in 1..AUTH_OPEN_MAX_ATTEMPTS) {
            device = resolveAttachedAuthorizedDevice(device) ?: run {
                AppLogger.e(TAG, "USB_AUTH_LEASE device disappeared before attempt=$attempt reason=$reason")
                return null
            }
            val opened = runCatching { usbManager.openDevice(device) }
                .onFailure {
                    AppLogger.w(
                        TAG,
                        "USB_AUTH_LEASE openDevice threw attempt=$attempt/$AUTH_OPEN_MAX_ATTEMPTS reason=$reason",
                        it,
                    )
                }
                .getOrNull()
            val fd = opened?.let { runCatching { it.fileDescriptor }.getOrDefault(-1) } ?: -1
            val rawBytes = opened?.let { runCatching { it.rawDescriptors?.size ?: 0 }.getOrDefault(0) } ?: 0
            if (opened != null && fd >= 0) {
                connection = opened
                connectionDeviceId = device.deviceId
                connectionDeviceName = device.deviceName
                currentDevice = device
                _state.value = State.READY
                _error.value = null
                AppLogger.i(
                    TAG,
                    "USB_AUTH_LEASE ready attempt=$attempt/$AUTH_OPEN_MAX_ATTEMPTS " +
                        "device=${device.deviceName} fd=$fd rawBytes=$rawBytes reason=$reason",
                )
                return opened
            }
            opened?.let { runCatching { it.close() } }
            AppLogger.w(
                TAG,
                "USB_AUTH_LEASE open failed attempt=$attempt/$AUTH_OPEN_MAX_ATTEMPTS " +
                    "device=${device.deviceName} fd=$fd rawBytes=$rawBytes reason=$reason",
            )
            if (attempt < AUTH_OPEN_MAX_ATTEMPTS) {
                val waitMs = AUTH_RETRY_DELAYS_MS.getOrElse(attempt - 1) { AUTH_RETRY_DELAYS_MS.last() }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        _state.value = State.ERROR
        _error.value = "USB 设备已授权，但无法打开设备连接"
        return null
    }

    /** DSD/PCM→DSD sessions must never use PCM warm pause or standby reuse. */
    fun isDsdSessionActive(): Boolean =
        currentDsdSessionKey != null && UsbAudioEngine.currentHandle != 0L

    private val usbReceiver = UsbBroadcastReceiver(
        transportOwner = transportOwner,
        onPermissionResult = ::handlePermissionResultOnTransport,
        onDeviceDetached = ::handleDeviceDetachedBroadcast,
        onDeviceAttached = ::handleDeviceAttachedBroadcast,
    )

    private fun handleDeviceDetachedBroadcast(device: UsbDevice?) {
        val detachedDevice = device ?: run {
            AppLogger.w(TAG, "USB detach broadcast without device; ignored")
            return
        }
        if (detachedDevice.deviceId != currentDevice?.deviceId) return
        AppLogger.w(TAG, "USB device detached: ${detachedDevice.deviceName}")
        permissionGate.markGranted()
        onDeviceDetached?.invoke(detachedDevice)
        runCatching { UsbAudioEngine.nativeOnUsbDetached() }
            .onFailure { AppLogger.w(TAG, "nativeOnUsbDetached failed", it) }
        closeLocked("detached")
    }

    private fun handleDeviceAttachedBroadcast(device: UsbDevice?) {
        if (device == null) return
        AppLogger.i(TAG, "USB audio device attached: ${device.deviceName}")
        handleDeviceInserted(device)
    }

    private fun handlePermissionResultOnTransport(device: UsbDevice?, granted: Boolean) {
        permissionCoordinator.handleTransportResult(device, granted)
    }

    private fun handleDeviceInserted(device: UsbDevice) {
        if (currentDevice?.deviceId == device.deviceId) {
            AppLogger.i(TAG, "Attached device already known, ignore")
            return
        }
        // ---------- ✅ ② 拒绝后冷却检查 ----------
        val now = System.currentTimeMillis()
        if (permissionGate.isDeniedCoolingDown(device.deviceId, now)) {
            AppLogger.d(TAG, "Device ${device.deviceName} was recently denied, ignore attach")
            return
        }
        // 必须确认真的是 USB audio 设备
        if (!usbDiscovery.isUsbAudioCandidateDevice(device)) {
            AppLogger.d(TAG, "Attached device is not a USB audio candidate, ignore")
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
        val device = usbDiscovery.findUsbAudioDevice()
        if (device != null) {
            currentDevice = device
            AppLogger.i(TAG, "Remembered USB audio device: ${device.productName}")
            usbDiscovery.dumpInterfaces(device)
            return true
        }
        currentDevice = null
        AppLogger.w(TAG, "No USB audio candidate found")
        return false
    }

    fun rememberDeviceOnly(device: UsbDevice, reason: String = "unknown") {
        if (!usbDiscovery.isUsbAudioCandidateDevice(device)) {
            AppLogger.d(TAG, "rememberDeviceOnly ignored non-audio device: ${device.deviceName} reason=$reason")
            return
        }
        currentDevice = device
        _state.value = if (usbManager.hasPermission(device)) State.READY else State.IDLE
        _error.value = null
        AppLogger.i(TAG, "Remembered USB audio device only: ${device.productName} reason=$reason permission=${usbManager.hasPermission(device)}")
    }

    fun findUsbAudioDevice(): UsbDevice? {
        return usbDiscovery.findUsbAudioDevice()
    }

    /**
     * 请求 USB 权限。
     * 权限获取后由 Transport owner 立即尝试建立 Java UsbDeviceConnection 授权租约。
     * native claim/set-alt 仍然只在真正 prepareForPlayback 时执行。
     */
    fun requestPermissionSafely(device: UsbDevice) = permissionCoordinator.requestSafely(device)

    suspend fun requestPermission(device: UsbDevice, force: Boolean = false): Boolean =
        permissionCoordinator.request(device, force)

    /**
     * Treat valid bit-depth and USB subslot/container as separate
     * choices.  24-bit sources should first try packed 24-in-3 when the user is
     * asking for native/bit-perfect 24-bit output; many 192k DAC alt-settings
     * expose 24/3 but reject 24/4 or 32/4.
     */
    /**
     * 播放每首歌/每次格式变化时调用。
     * 这里初始化 native；若授权回调已经预先 openDevice，则复用同一 Java connection。
     * connection 作为成员变量一直持有到 nativeClose 之后，并在失败时做有界 reopen。
     */
    fun prepareForPlayback(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        srcFilePath: String? = null,
        allowFallback: Boolean = true,
        suppressDsdForRetry: Boolean = false,
        effectiveBitPerfect: Boolean? = null,
    ): Boolean = transportOwner.call("prepare ${sampleRate}/${bits}/${channels}") {
        prepareForPlaybackOnTransport(
            sampleRate = sampleRate,
            bits = bits,
            channels = channels,
            srcFilePath = srcFilePath,
            allowFallback = allowFallback,
            suppressDsdForRetry = suppressDsdForRetry,
            effectiveBitPerfect = effectiveBitPerfect,
        )
    }

    private fun prepareForPlaybackOnTransport(
        sampleRate: Int,
        bits: Int,
        channels: Int,
        srcFilePath: String?,
        allowFallback: Boolean,
        suppressDsdForRetry: Boolean,
        effectiveBitPerfect: Boolean?,
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
        val bitPerfect = effectiveBitPerfect ?: (AppPreferences.Player.usbBitPerfectMode == UsbBitPerfectMode.STRICT)
        // STRICT is a transport contract, not a preference to silently weaken.
        // The normal Ffmpeg path rejects these geometries before reaching the USB manager;
        // keep the same invariant here for direct/recovery callers as a second safety boundary.
        if (bitPerfect && !sourceIsDsd &&
            (sampleRate <= 0 || bits !in 1..32 || channels !in 1..2)
        ) {
            AppLogger.e(
                TAG,
                "prepareForPlayback: strict bit-perfect source geometry cannot be represented exactly " +
                    "source=${sampleRate}/${bits}/${channels}",
            )
            return false
        }
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
        val dsdPcmFallbackRate = if (sourceIsDsd && sourceDsdMode == null && pcmToDsdMode == null) {
            caps?.supportedSampleRates
                ?.filter { it > 0 }
                ?.minByOrNull { kotlin.math.abs(it.toLong() - sampleRate.toLong()) }
                ?: sampleRate
        } else {
            sampleRate
        }
        if (sourceIsDsd && sourceDsdMode == null && pcmToDsdMode == null && dsdPcmFallbackRate != sampleRate) {
            AppLogger.w(
                TAG,
                "DSD unsupported by device; PCM fallback sourceRate=$sampleRate deviceRate=$dsdPcmFallbackRate"
            )
        }
        val profile = UsbPlaybackProfilePolicy.plan(
            sampleRate = sampleRate,
            sourceBits = bits,
            requestedTargetRate = requestedTargetRate,
            requestedTargetBits = requestedTargetBits,
            pcmMode = pcmMode,
            bitPerfect = bitPerfect,
            sourceIsDsd = sourceIsDsd,
            sourceDsdMode = sourceDsdMode,
            pcmToDsdMode = pcmToDsdMode,
            dsdPcmFallbackRate = dsdPcmFallbackRate,
            targetBitDepth = AudioOutputManager::usbDeviceBitResolutionForTarget,
            targetSubslot = AudioOutputManager::usbDeviceSubslotForTarget,
        )
        val dsdMode = profile.dsdMode
        val desiredDsdSessionKey = profile.desiredDsdSessionKey
        val pcmDsdActive = profile.pcmDsdActive
        val dsdTransportActive = profile.dsdTransportActive
        val sourceBitsForUsb = profile.sourceBitsForUsb
        val strictBitPerfectForUsb = profile.strictBitPerfectForUsb
        if (bits > 32) {
            AppLogger.w(
                TAG,
                "prepareForPlayback: sourceBits=$bits exceeds USB PCM max, " +
                    "use decoder/native S32LE and request 32-bit USB format"
            )
        }
        val deviceSampleRate = profile.deviceSampleRate
        val deviceBits = profile.deviceBits
        val deviceSubslot = profile.deviceSubslot
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

        var cfg = UsbAudioFormatPolicy.selectConfigForFormat(
            deviceSampleRate,
            deviceBits,
            deviceSubslot,
            channels,
            sourceBitsForUsb
        )
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
                    suppressDsdForRetry = true,
                    effectiveBitPerfect = false,
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
            val (newPath, fmt) = pcmFallbackProcessor.process(
                srcPath = srcFilePath,
                srcRate = deviceSampleRate,
                srcBits = sourceBitsForUsb,
                srcChannels = channels,
                forceFallback = false,
                supportsNative = { rate, bits, subslot, channelCount ->
                    UsbAudioFormatPolicy.selectConfigForFormat(rate, bits, subslot, channelCount) != null
                }
            )
            return prepareForPlayback(fmt.sampleRate, fmt.bitsPerSample, fmt.channels, newPath, allowFallback, effectiveBitPerfect = false)
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
            val boundaryApplied = UsbAudioEngine.flushForNextTrack("prepareForPlayback_fast_reuse_same_config")
            if (!boundaryApplied || UsbAudioEngine.isNativeSessionBroken()) {
                AppLogger.e(
                    TAG,
                    "prepareForPlayback: fast reuse rejected because native track boundary was not safe; reopening device",
                )
                closeNativeSessionPreservingAuthorization("unsafe_fast_reuse")
                return prepareForPlayback(
                    sampleRate = sampleRate,
                    bits = bits,
                    channels = channels,
                    srcFilePath = srcFilePath,
                    allowFallback = allowFallback,
                    suppressDsdForRetry = suppressDsdForRetry,
                    effectiveBitPerfect = bitPerfect,
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

        closeNativeSessionPreservingAuthorization("prepare_fresh_stream")

        // Native transport policy is committed later as one UsbNativeSessionPolicy transaction.
        // Do not mutate process-global PCM/DoP/compat flags here: Android keeps the authorization
        // lease, while native receives the complete immutable session request immediately before
        // descriptor scoring / claim / clock / alt-setting work.
        val conn = ensureAuthorizedConnectionOnTransport(
            requestedDevice = device,
            reason = "prepare_before_native",
            forceReopen = false,
        ) ?: return false.also {
            AppLogger.e(TAG, "prepareForPlayback failed: authorized openDevice lease unavailable")
        }

        // 提前扫描 Feature Unit（供 UI 提示，C++ 层会做更完整的安全验证）
        val volInfo = queryHardwareVolume(currentDevice ?: device, conn)
        _volumeInfo.value = volInfo
        if (volInfo != null) {
            AppLogger.i(TAG, "Feature Unit descriptor hint: entityId=0x${volInfo.entityId.toString(16)} " +
                       "iface=${volInfo.interfaceNo} master=${volInfo.hasMasterVolume} " +
                       "L=${volInfo.hasLeftVolume} R=${volInfo.hasRightVolume}; " +
                       "native VolumeController validation is authoritative")
        } else {
            AppLogger.i(TAG, "No Volume Feature Unit descriptor hint found; native validation may still report final state")
        }

        AppLogger.w(
            TAG,
            "USB_INIT_FINAL sourceSr=$sampleRate deviceSr=${cfg.sampleRate} " +
                "prefRate=${AppPreferences.Player.usbTargetSampleRate} " +
                "bitPerfect=$bitPerfect policy=${AppPreferences.Player.usbBitPerfectMode} " +
                "sourceBits=${cfg.sourceBits} deviceBits=${cfg.bits} deviceSubslot=${cfg.subslot} frame=${cfg.frameSize}"
        )
        val stagedPolicy = UsbAudioEngine.snapshotNextSessionPolicy()
        val sessionPolicy = stagedPolicy.copy(
            exclusive = true,
            // Fixed-digital-volume is a native playback-mode contract even when the decoder is
            // not in strict bit-perfect mode. Preserve that distinction without relying on a
            // previous nativeSetPolicy() call.
            bitPerfect = bitPerfect || AppPreferences.Player.usbVolumeMode == 2,
            hardwareVolumeRequested =
                AppPreferences.Player.usbVolumeMode == 1 && AppPreferences.Player.hardwareFeatureUnitEnabled,
            pcmOutputMode = pcmMode,
            dsdConversionEnabled = dsdMode != null,
            dsdRate = dsdMode?.multiplier ?: AppPreferences.Player.dsdRate,
            dsdConversionType = AppPreferences.Player.dsdConversionType,
            dsdDitherEnabled = pcmDsdActive && AppPreferences.Player.dsdDitherEnabled,
            dsdDoPEnabled = dsdMode?.transport == UsbDsdTransport.DOP,
        )
        UsbAudioEngine.stageNextSessionPolicy(sessionPolicy, "prepare_for_playback")
        val handle = initNativeWithAuthorizedRetry(
            requestedDevice = currentDevice ?: device,
            cfg = cfg,
            sourceSampleRate = sampleRate,
            bitPerfect = bitPerfect,
            sessionPolicy = sessionPolicy,
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
                    suppressDsdForRetry = true,
                    effectiveBitPerfect = false,
                )
            }
            if (strictBitPerfectForUsb) {
                AppLogger.e(TAG, "nativeInitUsbDevice failed in strict bit-perfect mode; soft fallback disabled")
                return false
            }
            AppLogger.e(TAG, "nativeInitUsbDevice failed, trying soft-resample fallback")
            if (srcFilePath != null && allowFallback) {
                val (newPath, fmt) = pcmFallbackProcessor.process(
                    srcPath = srcFilePath,
                    srcRate = sampleRate,
                    srcBits = bits,
                    srcChannels = channels,
                    forceFallback = true,
                    supportsNative = { rate, sourceBits, subslot, channelCount ->
                        UsbAudioFormatPolicy.selectConfigForFormat(rate, sourceBits, subslot, channelCount) != null
                    }
                )
                return prepareForPlayback(
                    sampleRate = fmt.sampleRate,
                    bits = fmt.bitsPerSample.coerceAtMost(32),
                    channels = fmt.channels,
                    srcFilePath = newPath,
                    allowFallback = false,
                    suppressDsdForRetry = suppressDsdForRetry,
                    effectiveBitPerfect = false,
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

    private fun initNativeWithAuthorizedRetry(
        requestedDevice: UsbDevice,
        cfg: UsbAudioConfig,
        sourceSampleRate: Int,
        bitPerfect: Boolean,
        sessionPolicy: UsbNativeSessionPolicy,
    ): Long {
        for (attempt in 1..NATIVE_INIT_MAX_ATTEMPTS) {
            val conn = ensureAuthorizedConnectionOnTransport(
                requestedDevice = requestedDevice,
                reason = "native_init_attempt_$attempt",
                forceReopen = attempt > 1,
            )
            if (conn == null) {
                AppLogger.w(
                    TAG,
                    "USB_NATIVE_INIT_RETRY no connection attempt=$attempt/$NATIVE_INIT_MAX_ATTEMPTS",
                )
            } else {
                val fd = runCatching { conn.fileDescriptor }.getOrDefault(-1)
                if (fd >= 0) {
                    AppLogger.i(
                        TAG,
                        "USB_NATIVE_INIT_RETRY attempt=$attempt/$NATIVE_INIT_MAX_ATTEMPTS " +
                            "fd=$fd device=${requestedDevice.deviceName} cfg=$cfg bitPerfect=$bitPerfect",
                    )
                    val handle = UsbAudioEngine.initWithHandle(
                        fd = fd,
                        sampleRate = cfg.sampleRate,
                        sourceSampleRate = sourceSampleRate,
                        sourceBitsPerSample = cfg.sourceBits,
                        channels = cfg.channels,
                        bitsPerSample = cfg.bits,
                        iface = cfg.iface,
                        alt = cfg.alt,
                        outEndpoint = cfg.outEp,
                        feedbackEndpoint = cfg.fbEp,
                        subslotSize = cfg.subslot,
                        sessionPolicy = sessionPolicy,
                    )
                    if (handle != 0L) {
                        AppLogger.i(
                            TAG,
                            "USB_NATIVE_INIT_RETRY success attempt=$attempt/$NATIVE_INIT_MAX_ATTEMPTS " +
                                "handle=0x${handle.toString(16)}",
                        )
                        return handle
                    }
                }
            }

            AppLogger.w(
                TAG,
                "USB_NATIVE_INIT_RETRY failed attempt=$attempt/$NATIVE_INIT_MAX_ATTEMPTS " +
                    "device=${requestedDevice.deviceName}; reopen Android connection before retry",
            )
            if (attempt < NATIVE_INIT_MAX_ATTEMPTS) {
                // libusb_wrap_sys_device/claim/alt failures can poison only this wrapped FD. Keep
                // the Android permission but replace the Java connection before the next attempt,
                // matching UAPP's reopen/re-enumeration recovery rather than changing PCM format.
                if (UsbAudioEngine.currentHandle == 0L) {
                    // Defensive teardown for partially-created native contexts. initWithHandle()
                    // normally unwinds them itself, but a retry boundary must never inherit a
                    // half-open libusb session.
                    UsbAudioEngine.closeNative("UsbExclusiveManager.native_init_retry_cleanup:$attempt")
                    closeJavaConnectionOnly("native_init_retry_$attempt")
                }
                val waitMs = NATIVE_INIT_RETRY_DELAYS_MS.getOrElse(attempt - 1) {
                    NATIVE_INIT_RETRY_DELAYS_MS.last()
                }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0L
                }
            }
        }
        return 0L
    }

    /**
     * Close only native stream ownership while retaining Android's authorized connection.
     *
     * This is the normal permission/cutover retry boundary. Keeping the Java FD lease prevents a
     * fresh grant from being immediately closed and reopened before libusb gets a chance to wrap it.
     */
    @Synchronized
    private fun closeNativeSessionPreservingAuthorization(reason: String) {
        stopHidListening()
        UsbAudioEngine.closeNative("UsbExclusiveManager.preserveAuth:$reason")
        currentConfig = null
        currentSourceSampleRate = 0
        currentSourceBits = 0
        currentDsdSessionKey = null
        _volumeInfo.value = null

        val device = currentDevice
        val canKeepLease = device != null &&
            connection != null &&
            connectionDeviceId == device.deviceId &&
            connectionDeviceName == device.deviceName &&
            usbManager.hasPermission(device) &&
            usbManager.deviceList.containsKey(device.deviceName)
        if (!canKeepLease) {
            closeJavaConnectionOnly("preserve-auth-invalid:$reason")
        }
        _state.value = if (device != null && usbManager.hasPermission(device)) State.READY else State.IDLE
        _error.value = null
        AppLogger.i(
            TAG,
            "USB_AUTH_LEASE native reset preserve=$canKeepLease device=${device?.deviceName} reason=$reason",
        )
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
        closeJavaConnectionOnly("close_all_now")
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
    ): Boolean = trackStartCoordinator.prepareAndStart(sampleRate, bits, channels, firstPcmChunks)

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
            val boundaryApplied = UsbAudioEngine.flushForNextTrack("stopAndFlushStreaming:$reason")
            if (!boundaryApplied) {
                AppLogger.w(TAG, "stopAndFlushStreaming: native boundary not applied reason=$reason")
            }
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

    fun resetPlaybackPipelinePreservingAuthorization(reason: String = "unknown") =
        transportOwner.call("reset-preserve-auth:$reason") {
            AppLogger.w(
                TAG,
                "resetPlaybackPipelinePreservingAuthorization requested: reason=$reason state=${_state.value}",
            )
            closeNativeSessionPreservingAuthorization(reason)
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
        closeJavaConnectionOnly("close_locked:$reason")
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
    private fun tryStartHidListening() = UsbHidSession.start()

    /**
     * Stop HID listening
     */
    private fun stopHidListening() = UsbHidSession.stop()

    /**
     * Check if device has HID interface
     */
    fun hasHidInterface(): Boolean {
        return UsbHidSession.hasInterface()
    }

    /**
     * Check if HID is currently listening
     */
    fun isHidListening(): Boolean {
        return UsbHidSession.isListening()
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbPermissionIntentFactory.ACTION_USB_PERMISSION)
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

    private fun dumpInterfaces(device: UsbDevice) = usbDiscovery.dumpInterfaces(device)

}
