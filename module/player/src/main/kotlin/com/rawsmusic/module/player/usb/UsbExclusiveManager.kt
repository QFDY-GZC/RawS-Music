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
import android.util.Log
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

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
        val subslot: Int
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

    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var currentConfig: UsbAudioConfig? = null

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
    var onDeviceDetached: ((UsbDevice?) -> Unit)? = null

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
                        Log.d(TAG, "Permission result: device=${device?.deviceName}, granted=$granted")
                        if (granted && device != null) {
                            // ---------- ✅ ① 成功后立即切成 READY ----------
                            currentDevice = device
                            lastDeniedDeviceId = -1
                            _state.value = State.READY
                            permissionCallback?.invoke(true)
                            Log.i(TAG, "Permission granted for ${device.productName}, notifying UI")
                            onDeviceReady?.invoke(device)
                        } else {
                            // ---------- ✅ ② 记录拒绝，防止自动循环 ----------
                            _state.value = State.ERROR
                            _error.value = "USB 权限被拒绝"
                            if (device != null) {
                                lastDeniedDeviceId = device.deviceId
                                lastPermissionRequestTime = System.currentTimeMillis()
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
                        Log.w(TAG, "USB device detached: ${device?.deviceName}")
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
                    Log.i(TAG, "USB device attached: ${device?.deviceName}")
                    if (device != null) handleDeviceInserted(device)
                }
            }
        }
    }

    private fun handleDeviceInserted(device: UsbDevice) {
        if (currentDevice?.deviceId == device.deviceId) {
            Log.i(TAG, "Attached device already known, ignore")
            return
        }
        // ---------- ✅ ② 拒绝后冷却检查 ----------
        val now = System.currentTimeMillis()
        if (device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            Log.d(TAG, "Device ${device.deviceName} was recently denied, ignore attach")
            return
        }
        // 必须确认真的是 USB audio 设备
        if (!isUsbAudioOutputDevice(device)) {
            Log.d(TAG, "Attached device is not USB audio, ignore")
            return
        }
        Log.i(TAG, "USB audio device confirmed, dumping descriptor:")
        dumpInterfaces(device)
        // ---------- ✅ ③ 立即进入授权流程 ----------
        Log.i(TAG, "USB audio device detected, starting permission flow...")
        requestPermissionSafely(device)
    }

    // ========== 公开 API ==========

    /**
     * 只扫描并记住 USB 音频设备，不 openDevice。
     */
    fun scanAndRememberDevice(): Boolean {
        val devices = usbManager.deviceList.values
        Log.d(TAG, "Scanning ${devices.size} USB devices...")
        for (device in devices) {
            Log.d(
                TAG,
                "Device: ${device.deviceName}, VID=${device.vendorId.toString(16)}, " +
                        "PID=${device.productId.toString(16)}, interfaces=${device.interfaceCount}"
            )
            if (isUsbAudioOutputDevice(device)) {
                currentDevice = device
                Log.i(TAG, "Remembered USB audio device: ${device.productName}")
                dumpInterfaces(device)
                return true
            }
        }
        currentDevice = null
        Log.w(TAG, "No USB audio output device found")
        return false
    }

    fun findUsbAudioDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "Scanning ${deviceList.size} USB devices...")

        for (device in deviceList.values) {
            Log.d(
                TAG,
                "Device: ${device.deviceName}, VID=${String.format("%04X", device.vendorId)}, " +
                        "PID=${String.format("%04X", device.productId)}, interfaces=${device.interfaceCount}"
            )

            if (isUsbAudioOutputDevice(device)) {
                Log.i(TAG, "Found USB audio device: ${device.productName}")
                return device
            }
        }

        Log.w(TAG, "No USB audio device with ISO OUT endpoint found")
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
        // 避免在短时间内因上一次拒绝而再次请求（手动请求时可以强制 bypass）
        val now = System.currentTimeMillis()
        if (device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            Log.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
            return
        }
        currentDevice = device
        if (usbManager.hasPermission(device)) {
            // 已经拥有权限 → 立刻把状态切成 READY 并回调 UI
            Log.d(TAG, "Already have permission, notifying UI")
            _state.value = State.READY
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
        Log.d(TAG, "Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pendingIntent)
    }

    suspend fun requestPermission(device: UsbDevice, force: Boolean = false): Boolean {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already have permission for ${device.deviceName}")
            currentDevice = device
            lastDeniedDeviceId = -1
            _state.value = State.READY
            return true
        }
        // 冷却检查（除非 force 为 true）
        val now = System.currentTimeMillis()
        if (!force && device.deviceId == lastDeniedDeviceId &&
            (now - lastPermissionRequestTime) < PERMISSION_COOLDOWN_MS) {
            Log.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
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
        allowFallback: Boolean = true
    ): Boolean {
        Log.i(TAG, "prepareForPlayback: sr=$sampleRate bits=$bits ch=$channels fallback=$allowFallback")
        val device = currentDevice ?: run {
            Log.e(TAG, "prepareForPlayback failed: currentDevice=null")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "prepareForPlayback failed: no USB permission")
            return false
        }

        var cfg = selectConfigForFormat(sampleRate, bits, channels)
        if (cfg == null) {
            // ------------------- ✅ ④ 软重采样永不返回 false -------------------
            if (srcFilePath == null) {
                Log.e(TAG, "No native USB config and no source file for soft‑resample")
                return false
            }
            val (newPath, fmt) = softResampleIfNeeded(
                srcPath = srcFilePath,
                srcRate = sampleRate,
                srcBits = bits,
                srcCh = channels,
                forceFallback = false
            )
            return prepareForPlayback(fmt.sampleRate, fmt.bitsPerSample, fmt.channels, newPath, allowFallback)
        }
        // 每次都重建，不再复用
        closeAllNow()
        val conn = usbManager.openDevice(device)
            ?: return false.also { Log.e(TAG, "openDevice failed") }
        connection = conn

        // 提前扫描 Feature Unit（供 UI 提示，C++ 层会做更完整的安全验证）
        val volInfo = queryHardwareVolume(device, conn)
        _volumeInfo.value = volInfo
        if (volInfo != null) {
            Log.i(TAG, "Feature Unit found: entityId=0x${volInfo.entityId.toString(16)} " +
                       "iface=${volInfo.interfaceNo} master=${volInfo.hasMasterVolume} " +
                       "L=${volInfo.hasLeftVolume} R=${volInfo.hasRightVolume}")
        } else {
            Log.i(TAG, "No Volume Feature Unit found in descriptors")
        }

        val fd = conn.fileDescriptor
        val handle = UsbAudioEngine.initWithHandle(
            fd = fd,
            sampleRate = cfg.sampleRate,
            channels = cfg.channels,
            bitsPerSample = cfg.bits,
            iface = cfg.iface,
            alt = cfg.alt,
            outEndpoint = cfg.outEp,
            feedbackEndpoint = cfg.fbEp,
            subslotSize = cfg.subslot
        )
        if (handle == 0L) {
            Log.e(TAG, "nativeInitUsbDevice failed, trying soft-resample fallback")
            closeLocked("nativeInitUsbDevice failed")
            if (srcFilePath != null && allowFallback) {
                val (newPath, fmt) = softResampleIfNeeded(srcFilePath, sampleRate, bits, channels, forceFallback = true)
                return prepareForPlayback(fmt.sampleRate, fmt.bitsPerSample, fmt.channels, newPath, allowFallback = false)
            }
            return false
        }
        currentConfig = cfg
        _state.value = State.READY
        return true
    }

    /**
     * 强制关闭所有 native / Java 资源，确保下次播放是干净状态。
     */
    private fun closeAllNow() {
        UsbAudioEngine.closeNative("prepareForPlayback fresh start")
        currentConfig = null
        val conn = connection
        connection = null
        conn?.let {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "close failed", e) }
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
        Log.i(TAG, "prepareAndStartForTrack: sr=$sampleRate bits=$bits ch=$channels chunks=${firstPcmChunks.size}")

        val config = selectConfigForFormat(sampleRate, bits, channels)
        if (config == null) {
            Log.e(TAG, "No USB config for ${sampleRate}/${bits}/${channels}")
            return false
        }

        val oldConfig = currentConfig
        val sameFormat = UsbAudioEngine.currentHandle != 0L && oldConfig == config

        if (sameFormat) {
            Log.i(TAG, "Same format, keeping USB streaming running, just write new data")
            val handle = UsbAudioEngine.currentHandle
            if (handle == 0L) {
                Log.e(TAG, "handle=0 unexpectedly")
                return false
            }
            for (chunk in firstPcmChunks) {
                UsbAudioEngine.safeNativeWriteHandle(handle, chunk, 0, chunk.size)
            }
            return true
        }

        Log.i(TAG, "Format changed: old=$oldConfig new=$config, need stop/reinit")

        // 1. fade out 当前播放（如果有）
        fadeOutIfStreaming(durationMs = 80)

        // 2. stop
        stopStreaming("format_change")

        // 3. 重新初始化
        val ok = prepareForPlayback(sampleRate, bits, channels)
        if (!ok) {
            Log.e(TAG, "prepareForPlayback failed")
            return false
        }
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L) {
            Log.e(TAG, "handle=0 after prepare")
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
    fun fadeInAfterStart(durationMs: Int = 80) {
        val steps = 8
        val stepMs = durationMs / steps
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L || !UsbAudioEngine.isInitialized()) return
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
            Log.e(TAG, "startStreaming failed: currentHandle=0")
            return false
        }
        Log.i(TAG, "startStreaming: calling nativeStart, handle=0x${h.toString(16)}")
        val ok = UsbAudioEngine.nativeStart(h)
        Log.i(TAG, "nativeStart returned $ok")
        return ok
    }

    @Synchronized
    fun stopStreaming(reason: String = "unknown") {
        Log.i(TAG, "stopStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L) {
            Log.i(TAG, "stopStreaming: calling nativeStop, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativeStop(h)
        } else {
            Log.i(TAG, "stopStreaming: handle=0, skipping nativeStop")
        }
    }

    @Synchronized
    fun pauseStreaming(reason: String = "pause") {
        Log.i(TAG, "pauseStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L) {
            Log.i(TAG, "pauseStreaming: calling nativePause, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativePause(h)
        } else {
            Log.i(TAG, "pauseStreaming: handle=0, skipping nativePause")
        }
    }

    @Synchronized
    fun stopAndFlushStreaming(reason: String = "track_change") {
        Log.i(TAG, "stopAndFlushStreaming called, reason=$reason")
        val h = UsbAudioEngine.currentHandle
        if (h != 0L) {
            Log.i(TAG, "stopAndFlushStreaming: calling nativeStopAndFlush, handle=0x${h.toString(16)}")
            UsbAudioEngine.nativeStopAndFlush(h)
        } else {
            Log.i(TAG, "stopAndFlushStreaming: handle=0, skipping nativeStopAndFlush")
        }
    }

    fun release(reason: String = "unknown") {
        Log.w(TAG, "release requested: reason=$reason, state=${_state.value}")
        closeLocked("release:$reason")
    }

    fun releaseForDetachedDevice() {
        Log.w(TAG, "releaseForDetachedDevice requested, state=${_state.value}")
        try {
            UsbAudioEngine.nativeOnUsbDetached()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeOnUsbDetached failed", t)
        }
        closeLocked("detached")
    }

    /**
     * 关闭 native handle + Java connection。
     * 统一走 UsbAudioEngine.closeNative()，不再直接调 nativeClose。
     */
    private fun closeLocked(reason: String) {
        UsbAudioEngine.closeNative("UsbExclusiveManager.closeLocked:$reason")
        currentConfig = null
        currentDevice = null
        val conn = connection
        connection = null
        if (conn != null) {
            Log.i(TAG, "UsbDeviceConnection.close conn=${System.identityHashCode(conn)}")
            try {
                conn.close()
            } catch (e: Exception) {
                Log.w(TAG, "connection.close failed", e)
            }
        }
        _state.value = State.IDLE
        _error.value = null
        _volumeInfo.value = null
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
        Log.d(TAG, "USB receiver registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
    }

    fun setStreamingState(streaming: Boolean) {
        _state.value = if (streaming) State.STREAMING else State.READY
        Log.i(TAG, "setStreamingState: $streaming -> state=${_state.value}")
    }

    fun hasOpenConnection(): Boolean = connection != null

    fun isDeviceConnected(): Boolean {
        val device = currentDevice ?: return false
        return usbManager.deviceList.containsKey(device.deviceName)
    }

    fun getCurrentDeviceName(): String? = currentDevice?.productName

    fun getRawDescriptorsSafely(): ByteArray? {
        val conn = connection ?: run {
            Log.w(TAG, "getRawDescriptorsSafely: connection=null")
            return null
        }
        return try {
            val raw = conn.rawDescriptors
            Log.i(TAG, "rawDescriptors size=${raw?.size ?: 0}")
            raw
        } catch (t: Throwable) {
            Log.w(TAG, "connection.rawDescriptors failed", t)
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
        // 1️⃣ 找出 AudioControl interface（class = AUDIO, subclass = AUDIOCONTROL = 0x01）
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
            Log.d(TAG, "queryHardwareVolume: no AudioControl interface found")
            return null
        }

        // 2️⃣ 读取整个 Configuration Descriptor
        val rawDesc = ByteArray(4096)
        val nRead = conn.controlTransfer(
            0x80 or 0x00 or 0x00, // USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE
            0x06,                  // USB_REQ_GET_DESCRIPTOR
            (0x02 shl 8),          // USB_DT_CONFIG << 8
            0,
            rawDesc, rawDesc.size, 2000
        )
        if (nRead <= 0) {
            Log.w(TAG, "queryHardwareVolume: GET_DESCRIPTOR failed, nRead=$nRead")
            return null
        }

        // 3️⃣ 裸解析 Feature Unit descriptor
        var offset = 9 // 跳过 Configuration Descriptor (9 bytes)
        while (offset + 2 <= nRead) {
            val length = rawDesc[offset].toInt() and 0xFF
            val dtype = rawDesc[offset + 1].toInt() and 0xFF
            if (length == 0) break

            // CS_INTERFACE (0x24) + FEATURE_UNIT (0x02)
            if (dtype == 0x24 && rawDesc[offset + 2].toInt() and 0xFF == 0x02) {
                val bUnitID = rawDesc[offset + 3].toInt() and 0xFF
                val bControlSize = rawDesc[offset + 5].toInt() and 0xFF
                if (bControlSize < 1) {
                    offset += length
                    continue
                }

                // UAC2: 每个通道有 bControlSize 字节 controls。
                // 通道数 = (bLength - 7) / bControlSize（包含 master ch0）
                val channelCount = (length - 7) / bControlSize
                var hasMaster = false
                var hasLeft = false
                var hasRight = false

                for (ch in 0 until channelCount.coerceAtMost(8)) {
                    val ctrlOffset = offset + 6 + ch * bControlSize
                    if (ctrlOffset >= nRead) break
                    val ctrl = rawDesc[ctrlOffset].toInt() and 0xFF
                    // bit0 == 1 => VOLUME supported
                    if ((ctrl and 0x01) != 0) {
                        when (ch) {
                            0 -> hasMaster = true
                            1 -> hasLeft = true
                            2 -> hasRight = true
                        }
                    }
                }

                if (hasMaster || hasLeft || hasRight) {
                    Log.i(TAG, "queryHardwareVolume: FeatureUnit 0x${bUnitID.toString(16)} " +
                               "on iface=${acInterface.id} master=$hasMaster L=$hasLeft R=$hasRight")
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
        Log.i(TAG, "queryHardwareVolume: no Volume Feature Unit found")
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
            if (selectConfigForFormat(srcRate, srcBits, srcCh) != null) {
                Log.i(TAG, "Soft-resample bypass: device supports native $srcRate/${srcBits}b")
                return Pair(srcPath, AudioFormat(srcRate, srcCh, srcBits))
            }
        }
        // 2. 尝试原始采样率 + 降级位深（24bit → 16bit）—— 无论 forceFallback 与否
        if (srcBits > 16) {
            Log.i(TAG, "Soft-resample: try downgrade bits $srcRate/${srcBits}b → $srcRate/16b")
            val cacheDir = File(context.cacheDir, "resampled_pcm").apply { mkdirs() }
            trimCacheDir(cacheDir, 500L * 1024 * 1024) // 限制缓存 500MB
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
        Log.i(TAG, "Soft-resampling $srcPath ${srcRate}Hz/${srcBits}b → $targetRate/$targetBits")
        val cacheDir = File(context.cacheDir, "resampled_pcm").apply { mkdirs() }
        trimCacheDir(cacheDir, 500L * 1024 * 1024) // 限制缓存 500MB
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
        channels: Int
    ): UsbAudioConfig? {
        // 只对常见格式返回配置，其他格式交给软重采样
        return when {
            channels == 2 && bits == 16 -> UsbAudioConfig(
                iface = 0,      // 0 = 让 native 自动选择
                alt = 0,        // 0 = 让 native 自动选择
                outEp = 0,      // 0 = 让 native 从描述符解析
                fbEp = 0,       // 0 = 让 native 从描述符解析
                sampleRate = sampleRate,
                bits = 16,
                channels = 2,
                subslot = 2
            )
            channels == 2 && bits == 24 -> UsbAudioConfig(
                iface = 0,
                alt = 0,
                outEp = 0,
                fbEp = 0,
                sampleRate = sampleRate,
                bits = 24,
                channels = 2,
                subslot = 3
            )
            channels == 2 && bits == 32 -> UsbAudioConfig(
                iface = 0,
                alt = 0,
                outEp = 0,
                fbEp = 0,
                sampleRate = sampleRate,
                bits = 32,
                channels = 2,
                subslot = 4
            )
            else -> null // 没有匹配的格式 → 交给外层软重采样
        }
    }

    private fun dumpInterfaces(device: UsbDevice) {
        Log.i(TAG, "========== USB Interfaces (all) ==========")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.i(
                TAG,
                "  Interface[$i]: id=${intf.id} alt=${intf.alternateSetting} " +
                        "class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} " +
                        "protocol=${intf.interfaceProtocol} eps=${intf.endpointCount}"
            )
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                Log.i(
                    TAG,
                    "    Endpoint[$e]: addr=0x${ep.address.toString(16)} dir=$dir " +
                            "type=${ep.type} attr=0x${ep.attributes.toString(16)} " +
                            "maxPacket=${ep.maxPacketSize} interval=${ep.interval}"
                )
            }
        }
        Log.i(TAG, "==========================================")
    }
}
