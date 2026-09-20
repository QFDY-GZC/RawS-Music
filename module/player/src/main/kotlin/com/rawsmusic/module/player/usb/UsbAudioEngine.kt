package com.rawsmusic.module.player.usb

import android.content.Context
import android.os.Process
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicLong

object UsbAudioEngine {

    internal const val TAG = "UsbAudioEngine"

    @Volatile
    private var nativeLibraryLoaded: Boolean = false

    @Volatile
    private var hidNativeAvailable: Boolean = true

    const val ERR_NOT_INITIALIZED = -1001
    const val ERR_NOT_RUNNING = -1003
    const val ERR_TRANSPORT_LOST = -1004
    const val ERR_USB_IO = -1005
    const val ERR_START_FAILED = -1010
    const val ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED = -1006

    data class UsbRuntimeFormat(
        val sampleRate: Int,
        val channels: Int,
        val validBits: Int,
        val subslotBytes: Int,
        val frameBytes: Int,
        val bytesPerSecond: Int,
        val iface: Int,
        val alt: Int,
        val outEndpoint: Int,
        val feedbackEndpoint: Int
    ) {
        val containerBits: Int get() = subslotBytes * 8
        val isValid: Boolean get() = sampleRate > 0 && channels > 0 && subslotBytes > 0 && frameBytes > 0
    }

    enum class FeedbackState(val id: Int) {
        NONE(0),
        DISCOVERED(1),
        VALIDATING(2),
        LOCKED(3),
        SUSPECT(4),
        DEGRADED(5),
        FAILED(6);

        companion object {
            fun fromId(id: Int): FeedbackState = entries.firstOrNull { it.id == id } ?: FAILED
        }
    }

    enum class PacingMode(val id: Int) {
        NoFeedbackFixed(0),
        ExplicitFeedback(1),
        FeedbackDegradedFixed(2),
        Unknown(-1);

        val isFixedPacer: Boolean
            get() = this == NoFeedbackFixed || this == FeedbackDegradedFixed

        companion object {
            fun fromId(id: Int): PacingMode = entries.firstOrNull { it.id == id } ?: Unknown
        }
    }

    init {
        try {
            System.loadLibrary("rawscoreservice")
            nativeLibraryLoaded = true
            AppLogger.d(TAG, "rawsmusic_usb library loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeLibraryLoaded = false
            hidNativeAvailable = false
            UsbBackgroundNativeGuard.markUnavailable()
            AppLogger.e(TAG, "Failed to load rawsmusic_usb", e)
        }
    }

    fun isNativeLibraryLoaded(): Boolean = nativeLibraryLoaded

    fun isHidNativeAvailable(): Boolean = nativeLibraryLoaded && hidNativeAvailable

    fun isBackgroundPlaybackNativeAvailable(): Boolean =
        UsbBackgroundNativeGuard.isAvailable(nativeLibraryLoaded)

    /**
     * Use Android's actual fast
     * mixer sample rate and frames-per-buffer, not the USB stream rate. A 384 kHz
     * USB profile cannot itself create a normal Android fast AudioTrack.
     */
    fun configureAndroidAudioSchedulerProfile(context: Context) {
        if (!nativeLibraryLoaded) return
        UsbAndroidAudioSchedulerProfile.configure(context, ::nativeSetAndroidAudioSchedulerProfile)
    }

    private fun markHidNativeUnavailable(api: String, t: Throwable) {
        hidNativeAvailable = false
        AppLogger.w(TAG, "USB HID native bridge unavailable at $api; HID remote keys disabled", t)
    }

    /**
     * Optional native background guard used only for USB-exclusive background hardening.
     * Missing JNI symbols must never crash Activity lifecycle callbacks.
     */
    fun setBackgroundPlaybackActiveSafely(active: Boolean, reason: String = "unspecified"): Boolean {
        return UsbBackgroundNativeGuard.setActive(
            nativeLoaded = nativeLibraryLoaded,
            active = active,
            reason = reason,
            applyNative = { nativeSetBackgroundPlaybackActive(active) },
        )
    }

    // ========== 4 个核心生命周期 external 方法 ==========

    external fun nativeInitUsbDevice(
        fd: Int,
        sampleRate: Int,
        sourceSampleRate: Int,
        sourceBitsPerSample: Int,
        channels: Int,
        bitsPerSample: Int,
        iface: Int,
        alt: Int,
        outEndpoint: Int,
        feedbackEndpoint: Int,
        subslotSize: Int
    ): Long

    /**
     * Atomic next-session transaction. The IntArray uses [UsbNativeSessionPolicy] schema v1.
     * Native commits the whole policy snapshot under one transaction lock before descriptor/alt/clock init.
     */
    external fun nativeInitUsbDeviceTransactional(
        fd: Int,
        sampleRate: Int,
        sourceSampleRate: Int,
        sourceBitsPerSample: Int,
        channels: Int,
        bitsPerSample: Int,
        iface: Int,
        alt: Int,
        outEndpoint: Int,
        feedbackEndpoint: Int,
        subslotSize: Int,
        sessionPolicy: IntArray,
    ): Long

    external fun nativeStart(handle: Long): Boolean

    external fun nativeStop(handle: Long)

    external fun nativePause(handle: Long)

    external fun nativePauseToSilence(handle: Long, reason: String): Boolean

    external fun nativeResumeWritesAfterPause(handle: Long, reason: String): Boolean

    external fun nativeStopAndFlush(handle: Long)

    external fun nativeClose(handle: Long)

    /**
     * 配置 native breadcrumb 日志路径，用于突发重启后的崩溃定位。
     * Kotlin 启动 USB 独占管理器时调用。
     */
    external fun nativeSetBreadcrumbPath(path: String)

    // ========== 当前状态跟踪 ==========

    @Volatile
    var currentSampleRate = 0
    @Volatile
    var currentChannels = 0
    @Volatile
    var currentBits = 0
    @Volatile
    var currentSubslotSize = 0
    @Volatile
    var currentOutEndpoint = 0
    @Volatile
    var currentFeedbackEndpoint = 0
    @Volatile
    var currentInterfaceNumber = -1
    @Volatile
    var currentAltSetting = 0

    private val nativeHandleRef = AtomicLong(0L)
    private val writerPriorityApplied = ThreadLocal<Boolean>()

    val currentHandle: Long
        get() = nativeHandleRef.get()

    @Volatile
    var initialized = false
        private set

    @Volatile
    private var nativeSessionBroken = false

    @Volatile
    private var cachedDeviceCapabilities: UsbDeviceAudioCapabilities? = null

    @Volatile
    private var nextSessionPolicy: UsbNativeSessionPolicy = UsbNativeSessionPolicy()

    fun snapshotNextSessionPolicy(): UsbNativeSessionPolicy = nextSessionPolicy

    @Synchronized
    fun stageNextSessionPolicy(policy: UsbNativeSessionPolicy, reason: String) {
        nextSessionPolicy = policy
        AppLogger.i(TAG, "USB_SESSION_STAGE reason=$reason ${policy.conciseLogString()}")
    }

    private fun updateNextSessionPolicy(reason: String, transform: (UsbNativeSessionPolicy) -> UsbNativeSessionPolicy) {
        synchronized(this) {
            nextSessionPolicy = transform(nextSessionPolicy)
            AppLogger.d(TAG, "USB_SESSION_STAGE_PATCH reason=$reason ${nextSessionPolicy.conciseLogString()}")
        }
    }

    private val streamController by lazy {
        UsbNativeStreamController(
            currentHandle = { currentHandle },
            isInitialized = { initialized },
            isSessionBroken = { nativeSessionBroken },
            setSessionBroken = { broken -> nativeSessionBroken = broken },
            refreshRuntimeSnapshot = ::refreshRuntimeSnapshotFromNative,
            getStreamSessionId = ::getStreamSessionId,
            nativeStart = ::nativeStart,
            nativePause = ::nativePause,
            nativeStopAndFlush = ::nativeStopAndFlush,
            nativeFlushForNextTrack = ::nativeFlushForNextTrack,
            nativeRestartIsoTransfersSameProfile = ::nativeRestartIsoTransfersSameProfile,
            nativeResetSessionForPlayback = ::nativeResetSessionForPlayback,
            nativeCloseStreamForReconfigure = ::nativeCloseStreamForReconfigure,
            nativeEnterStandby = ::nativeEnterStandby,
            nativeResumeFromStandby = ::nativeResumeFromStandby,
            nativeIsSessionBroken = ::nativeIsSessionBroken,
            nativeIsActive = ::nativeIsActive,
        )
    }

    private val runtimeMetrics by lazy {
        UsbRuntimeMetricsReader(
            isInitialized = { initialized },
            currentHandle = { currentHandle },
            snapshot = {
                UsbRuntimeMetricsReader.Snapshot(
                    sampleRate = currentSampleRate,
                    channels = currentChannels,
                    bits = currentBits,
                    subslotBytes = currentSubslotSize,
                    outEndpoint = currentOutEndpoint,
                    feedbackEndpoint = currentFeedbackEndpoint,
                    interfaceNumber = currentInterfaceNumber,
                    altSetting = currentAltSetting,
                )
            },
            refreshSnapshot = ::refreshRuntimeSnapshotFromNative,
            nativeGetPacketSize = ::nativeGetPacketSize,
            nativeGetTransferCapacityBytes = ::nativeGetTransferCapacityBytes,
            nativeGetMaxPacketBytes = ::nativeGetMaxPacketBytes,
            nativeGetServiceIntervalsPerSecond = ::nativeGetServiceIntervalsPerSecond,
            nativeGetNominalBytesPerInterval = ::nativeGetNominalBytesPerInterval,
            nativeGetNominalBytesPerTransfer = ::nativeGetNominalBytesPerTransfer,
            nativeGetCompletedUsbBytesPerSecond = ::nativeGetCompletedUsbBytesPerSecond,
            nativeGetScheduledUsbBytesPerSecond = ::nativeGetScheduledUsbBytesPerSecond,
            nativeGetFeedbackState = ::nativeGetFeedbackState,
            nativeGetFeedbackSampleRateMilli = ::nativeGetFeedbackSampleRateMilli,
            nativeGetPacingMode = ::nativeGetPacingMode,
            nativeGetStatsString = ::nativeGetStatsString,
            nativeGetStreamSessionId = ::nativeGetStreamSessionId,
            nativeGetRecommendedDelayUs = ::nativeGetRecommendedDelayUs,
            nativeGetBufferUsedBytes = ::nativeGetBufferUsedBytes,
            nativeGetOutputBytesPerSecond = ::nativeGetOutputBytesPerSecond,
            nativeGetOutputSampleRate = ::nativeGetOutputSampleRate,
            nativeGetCurrentFrameBytes = ::nativeGetCurrentFrameBytes,
            currentFeedbackEndpoint = { currentFeedbackEndpoint },
        )
    }

    // ========== 统一关闭入口 ==========

    /**
     * 唯一关闭 native handle 的方法。
     * getAndSet(0L) 保证：在调用 nativeClose() 之前就把 handle 置 0，
     * 即使 nativeClose() 比较慢，其他线程也不会再拿到旧 handle。
     */
    @Synchronized
    fun closeNative(reason: String) {
        val handle = nativeHandleRef.getAndSet(0L)
        if (handle == 0L) {
            clearState()
            AppLogger.i(TAG, "closeNative ignored: already closed, reason=$reason")
            return
        }
        initialized = false
        AppLogger.i(TAG, "closeNative: handle=0x${java.lang.Long.toUnsignedString(handle, 16)}, reason=$reason")
        try {
            nativeClose(handle)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "closeNative threw", t)
        } finally {
            clearState()
        }
        AppLogger.i(TAG, "closeNative done: handle=0x${java.lang.Long.toUnsignedString(handle, 16)}")
    }

    // ========== 高层封装方法 ==========

    /**
     * 通过新架构 nativeInitUsbDevice 初始化。
     * 由 UsbExclusiveManager.prepareForPlayback 调用。
     * Java 侧只 openDevice + 持有 connection，native 统一 claim + set_alt。
     */
    @Synchronized
    fun initWithHandle(
        fd: Int,
        sampleRate: Int,
        sourceSampleRate: Int = sampleRate,
        channels: Int,
        bitsPerSample: Int,
        sourceBitsPerSample: Int = bitsPerSample,
        iface: Int,
        alt: Int,
        outEndpoint: Int,
        feedbackEndpoint: Int,
        subslotSize: Int,
        sessionPolicy: UsbNativeSessionPolicy = snapshotNextSessionPolicy(),
    ): Long {
        // 先关闭旧 handle（如果有的话）
        closeNative("before_reinit")

        AppLogger.i(TAG, "USB_SESSION_TXN begin ${sessionPolicy.conciseLogString()}")
        val handle = nativeInitUsbDeviceTransactional(
            fd, sampleRate, sourceSampleRate, sourceBitsPerSample, channels, bitsPerSample,
            iface, alt, outEndpoint, feedbackEndpoint, subslotSize, sessionPolicy.toNativeIntArray()
        )

        if (handle == 0L) {
            AppLogger.e(TAG, "nativeInitUsbDevice failed")
            return 0L
        }

        nativeHandleRef.set(handle)
        initialized = true
        nativeSessionBroken = false
        currentSampleRate = sampleRate
        currentChannels = channels
        currentBits = bitsPerSample
        currentSubslotSize = subslotSize
        currentOutEndpoint = outEndpoint
        currentFeedbackEndpoint = feedbackEndpoint
        currentInterfaceNumber = iface
        currentAltSetting = alt
        refreshRuntimeSnapshotFromNative()
        AppLogger.i(
            TAG,
            "initWithHandle ok: handle=0x${java.lang.Long.toUnsignedString(handle, 16)} " +
                "requestedDeviceSr=$sampleRate actualDeviceSr=$currentSampleRate " +
                "sourceSr=$sourceSampleRate sourceBits=$sourceBitsPerSample " +
                "deviceBits=$currentBits ch=$currentChannels iface=$currentInterfaceNumber alt=$currentAltSetting"
        )
        return handle
    }

    fun start(): Boolean = streamController.start()

    /**
     * 临时保留旧入口，但任何普通切歌/暂停都不应该再走这里。
     * 仅在设备断开、致命错误时使用。
     */
    fun stop() {
        // seek 期间禁止 hard stop，防止 session 被标 BROKEN
        if (usbSeekingFlag) {
            AppLogger.w(TAG, "stop() ignored during USB seek")
            return
        }
        AppLogger.w(TAG, "UsbAudioEngine.stop() legacy hard stop called", Throwable("stop call stack"))
        hardStopUsb("legacy_stop")
    }

    @Volatile
    var usbSeekingFlag: Boolean = false

    fun pause() = streamController.pause()

    fun pauseToSilence(reason: String): Boolean {
        val handle = currentHandle
        if (handle == 0L || !initialized) {
            AppLogger.w(TAG, "pauseToSilence skipped: reason=$reason handle=0x${handle.toString(16)} initialized=$initialized")
            return false
        }
        return nativePauseToSilence(handle, reason)
    }

    fun resumeWritesAfterPause(reason: String): Boolean {
        val handle = currentHandle
        if (handle == 0L || !initialized) {
            AppLogger.w(TAG, "resumeWritesAfterPause skipped: reason=$reason handle=0x${handle.toString(16)} initialized=$initialized")
            return false
        }
        return nativeResumeWritesAfterPause(handle, reason)
    }

    fun release() {
        closeNative("release")
        nativeSessionBroken = false
    }

    fun isActive(): Boolean = streamController.isActive()

    fun isRunning(): Boolean = isActive()

    /** Legacy ABI: returns the ISO transfer capacity, not the nominal audio packet payload. */
    fun getPacketSize(): Int = getTransferCapacityBytes()

    fun getTransferCapacityBytes(): Int = runtimeMetrics.transferCapacityBytes()

    fun getMaxPacketBytes(): Int = runtimeMetrics.maxPacketBytes()

    fun getServiceIntervalsPerSecond(): Int = runtimeMetrics.serviceIntervalsPerSecond()

    fun getNominalBytesPerInterval(): Int = runtimeMetrics.nominalBytesPerInterval()

    fun getNominalBytesPerTransfer(): Int = runtimeMetrics.nominalBytesPerTransfer()

    fun getCompletedUsbBytesPerSecond(): Long = runtimeMetrics.completedUsbBytesPerSecond()

    fun getScheduledUsbBytesPerSecond(): Long = runtimeMetrics.scheduledUsbBytesPerSecond()

    /** Age of the most recent libusb ISO completion callback, or -1 before the first callback. */
    fun getIsoCallbackAgeMs(): Long = if (initialized) nativeGetIsoCallbackAgeMs() else -1L

    fun getFeedbackState(): FeedbackState = runtimeMetrics.feedbackState()

    fun getFeedbackSampleRate(): Double = runtimeMetrics.feedbackSampleRate()

    fun getPacingMode(): PacingMode = runtimeMetrics.pacingMode()

    fun feedbackLooksUnsafeForPacer(): Boolean = runtimeMetrics.feedbackLooksUnsafeForPacer()

    fun getStreamSessionId(): Long = runtimeMetrics.streamSessionId()

    fun computeRecommendedWriteChunkBytes(deviceBytesPerSecond: Long, frameBytes: Int, targetMs: Long = 32L): Int =
        runtimeMetrics.recommendedWriteChunkBytes(deviceBytesPerSecond, frameBytes, targetMs)

    /**
     * Legacy runtime-only rate setter. This does not program the USB Clock Source and therefore
     * must not be used for a physical DAC rate transition; use [reconfigurePcmStream] instead.
     */
    fun setSampleRate(sampleRate: Int): Boolean {
        if (!initialized) return false
        if (nativeSessionBroken) {
            AppLogger.e(TAG, "setSampleRate denied: native session broken")
            return false
        }
        return nativeSetSampleRate(sampleRate) == 0
    }

    /**
     * Reconfigure a healthy PCM stream/profile while preserving the physical USB session.
     *
     * Unlike [initWithHandle], this never closes/re-wraps the Android fd. Native validates that
     * the already-committed AS alt can prove the target rate, drains the current transfer pool,
     * performs the minimum UAC2 clock transaction, then rebuilds only stream runtime state.
     */
    @Synchronized
    fun reconfigurePcmStream(
        targetDeviceRate: Int,
        sourceSampleRate: Int,
        sourceBitsPerSample: Int,
        targetChannels: Int,
        targetBits: Int,
        targetSubslot: Int,
        verifyClockReadback: Boolean,
    ): Boolean {
        val handle = currentHandle
        if (!initialized || handle == 0L || nativeSessionBroken) return false
        val ok = nativeReconfigurePcmStream(
            handle = handle,
            targetDeviceRate = targetDeviceRate,
            sourceSampleRate = sourceSampleRate,
            sourceBitsPerSample = sourceBitsPerSample,
            targetChannels = targetChannels,
            targetBits = targetBits,
            targetSubslot = targetSubslot,
            verifyClockReadback = verifyClockReadback,
        )
        if (ok) {
            currentSampleRate = targetDeviceRate
            currentChannels = targetChannels
            currentBits = targetBits
            currentSubslotSize = targetSubslot
            refreshRuntimeSnapshotFromNative()
            AppLogger.i(
                TAG,
                "USB_PERSISTENT_RECONFIG ok handle=0x${java.lang.Long.toUnsignedString(handle, 16)} " +
                    "deviceSr=$currentSampleRate sourceSr=$sourceSampleRate bits=$targetBits subslot=$targetSubslot",
            )
        } else {
            nativeSessionBroken = runCatching { nativeIsSessionBroken(handle) }.getOrDefault(true)
            AppLogger.w(
                TAG,
                "USB_PERSISTENT_RECONFIG rejected handle=0x${java.lang.Long.toUnsignedString(handle, 16)} " +
                    "broken=$nativeSessionBroken targetSr=$targetDeviceRate",
            )
        }
        return ok
    }

    /** 统一的音量设置（handle-based）
     * - 非 bit-perfect：软音量 → 写入全局 gSoftwareVolume（fillIsoTransfer 中生效）
     * - bit-perfect + 硬件音量安全：调用硬件音量，否则降级 Fixed */
    fun setVolume(volume: Float): Int {
        val v = volume.coerceIn(0f, 1f)
        val h = currentHandle
        if (h == 0L) return ERR_NOT_INITIALIZED

        // 硬件音量模式下直接忽略 legacy setVolume，防止双写
        if (nativeCanControlVolume(h)) {
            android.util.Log.w("UsbAudioEngine", "legacy setVolume ignored in hardware-volume mode: volume=$v")
            return 0
        }

        return nativeSetVolume(h, v)
    }

    fun getPlaybackMode(): Int {
        val h = currentHandle
        if (h == 0L) return 0
        return nativeGetPlaybackMode(h)
    }

    fun setPcmSoftwareGain(gain: Float): Int {
        val h = currentHandle
        if (h == 0L) return ERR_NOT_INITIALIZED
        return nativeSetPcmSoftwareGain(h, gain.coerceIn(0f, 1f))
    }

    fun setPcmOutputMode(mode: UsbPcmOutputMode) {
        updateNextSessionPolicy("pcm_output_mode") { it.copy(pcmOutputMode = mode) }
        nativeSetPcmOutputMode(mode.id)
    }

    fun armStopFade(fadeMs: Int) {
        val h = currentHandle
        if (h != 0L) nativeArmStopFade(h, fadeMs.coerceIn(3, 50))
    }

    /** 手动切歌专用淡出：在 flush 前先将输出淡到 0 */
    fun armTrackStopFade(fadeMs: Int, reason: String) {
        val h = currentHandle
        AppLogger.i(TAG, "armTrackStopFade: fadeMs=$fadeMs reason=$reason handle=0x${h.toString(16)}")
        if (h != 0L) nativeArmTrackStopFade(h, fadeMs.coerceIn(3, 50))
    }

    /**
     * Same-profile ISO transfer restart.
     *
     * This keeps the current USB handle, selected interface/alt setting, clock,
     * endpoints, volume policy, and runtime format.  It only tears down and
     * re-submits the ISO transfer queue, then clears the native PCM ring so the
     * Kotlin feeder can refill from the current decoder position.  This is used
     * for Xiaomi/HyperOS no-feedback streams that look alive but stop draining
     * after the first accepted completions.
     */
    fun restartIsoTransfersSameProfile(reason: String): Boolean =
        streamController.restartIsoTransfersSameProfile(reason)

    /** 轻量 flush，切歌用，可恢复（不设 sessionBroken） */
    fun flushForNextTrack(reason: String) = streamController.flushForNextTrack(reason)

    /** 重置 session 状态，新播放开始前调用（prefill 之前） */
    fun resetSessionForPlayback(reason: String) = streamController.resetSessionForPlayback(reason)

    /** Legacy recovery primitive: releases AS while preserving fd. Normal PCM changes use reconfigurePcmStream(). */
    fun closeStreamForReconfigure(reason: String) = streamController.closeStreamForReconfigure(reason)

    /** 进入 standby（暂停/后台/焦点丢失），释放 AS interface */
    fun enterStandby(reason: String) = streamController.enterStandby(reason)

    /** 从 standby 恢复，重新 claim AS interface */
    fun resumeFromStandby(reason: String): Boolean = streamController.resumeFromStandby(reason)

    /** Hard stop，关闭独占/设备拔出用，设 sessionBroken */
    fun hardStopUsb(reason: String) = streamController.hardStopUsb(reason)

    fun isNativeSessionBroken(): Boolean = streamController.isNativeSessionBroken()

    // ==========================
    // NativeStreamState 枚举
    // ==========================
    enum class NativeStreamState(val id: Int) {
        CLOSED(0),
        OPEN(1),
        PREPARED(2),
        STREAMING(3),
        STANDBY(4),
        BROKEN(5);

        companion object {
            fun fromId(id: Int): NativeStreamState {
                return entries.firstOrNull { it.id == id } ?: BROKEN
            }
        }
    }

    fun getNativeStreamState(): NativeStreamState {
        val h = currentHandle
        if (h == 0L) return NativeStreamState.CLOSED
        return NativeStreamState.fromId(nativeGetStreamState(h))
    }

    fun getDeviceCapabilities(): UsbDeviceAudioCapabilities? {
        val h = currentHandle
        if (h == 0L) return cachedDeviceCapabilities
        val json = runCatching { nativeGetDeviceCapabilitiesJson(h) }.getOrNull()
        val snapshot = UsbCapabilitySnapshotBridge.resolve(cachedDeviceCapabilities, json)
            ?: return cachedDeviceCapabilities
        cachedDeviceCapabilities = snapshot.cached
        return snapshot.effective
    }

    /**
     * Explicit read-only probe of standard UAC hardware controls on the active playback path.
     * This never writes the device and is intentionally not part of PCM/DoP/DSD startup.
     */
    fun probeStandardHardwareControlsJson(): String? {
        val h = currentHandle
        if (h == 0L || !initialized) return null
        return runCatching { nativeProbeStandardHardwareControlsJson(h) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** Normalized Phase-2 snapshot consumed by the future DeviceControlManager/UI. */
    fun probeStandardHardwareControls(generation: Long) =
        com.rawsmusic.module.player.devicecontrol.usb.UsbStandardControlProbeParser.parse(
            probeStandardHardwareControlsJson(),
            generation,
        )


    /**
     * Safe vendor-control inventory. Besides descriptor-only UAC/XU/interface data, HID interfaces
     * are queried with the standard read-only GET_DESCRIPTOR(Report) request so known adapters can
     * fingerprint Report IDs and payload sizes. No vendor/class SET or bulk transfer is issued.
     */
    fun probeVendorControlInventoryJson(): String? {
        val h = currentHandle
        if (h == 0L || !initialized) return null
        return runCatching { nativeProbeVendorControlInventoryJson(h) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Phase-3 standard UAC write. Native validates RW descriptor access, sends SET_CUR and
     * immediately reads CUR back. This is a device-control EP0 operation, not audio transport.
     */
    fun writeStandardHardwareControl(
        address: com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress.UsbAudioClass,
        value: Double,
    ): com.rawsmusic.module.player.devicecontrol.usb.UsbStandardControlWriteResult {
        val h = currentHandle
        if (h == 0L || !initialized) {
            return com.rawsmusic.module.player.devicecontrol.usb.UsbStandardControlWriteResult.Failed(
                "usb_engine_not_initialized",
            )
        }
        val json = runCatching {
            nativeWriteStandardHardwareControlJson(
                h,
                address.interfaceNumber,
                address.entityId,
                address.selector,
                address.channel,
                address.elementIndex ?: -1,
                value,
            )
        }.getOrNull()
        return com.rawsmusic.module.player.devicecontrol.usb.UsbStandardControlWriteParser.parse(json)
    }


    // Phase-4B bounded USB vendor control-plane primitives. These are only wrapped by a selected
    // UsbVendorDeviceAdapter; native validates each target against the live descriptors again.
    internal fun vendorExtensionUnitRead(
        target: com.rawsmusic.module.player.devicecontrol.usb.ExtensionUnitTarget,
        length: Int,
    ): Result<ByteArray> = vendorReadGuard(length, 4096) { h ->
        nativeVendorExtensionUnitRead(
            h, target.interfaceNumber, target.entityId, target.selector, target.channel,
            length, target.timeoutMs,
        )
    }

    internal fun vendorExtensionUnitWrite(
        target: com.rawsmusic.module.player.devicecontrol.usb.ExtensionUnitTarget,
        data: ByteArray,
    ): Result<Int> = vendorWriteGuard(data, 4096) { h ->
        nativeVendorExtensionUnitWrite(
            h, target.interfaceNumber, target.entityId, target.selector, target.channel,
            data, target.timeoutMs,
        )
    }

    internal fun vendorHidGetReport(
        target: com.rawsmusic.module.player.devicecontrol.usb.HidReportTarget,
        length: Int,
    ): Result<ByteArray> = vendorReadGuard(length, 4096) { h ->
        nativeVendorHidGetReport(
            h, target.interfaceNumber, target.reportType.wireValue, target.reportId,
            length, target.timeoutMs,
        )
    }

    internal fun vendorHidSetReport(
        target: com.rawsmusic.module.player.devicecontrol.usb.HidReportTarget,
        data: ByteArray,
    ): Result<Int> = vendorWriteGuard(data, 4096) { h ->
        nativeVendorHidSetReport(
            h, target.interfaceNumber, target.reportType.wireValue, target.reportId,
            data, target.timeoutMs,
        )
    }

    internal fun vendorControlIn(
        target: com.rawsmusic.module.player.devicecontrol.usb.VendorControlTarget,
        length: Int,
    ): Result<ByteArray> = vendorReadGuard(length, 4096) { h ->
        nativeVendorControlIn(
            h, target.deviceRecipient, target.interfaceNumber, target.request,
            target.value, target.index, length, target.timeoutMs,
        )
    }

    internal fun vendorControlOut(
        target: com.rawsmusic.module.player.devicecontrol.usb.VendorControlTarget,
        data: ByteArray,
    ): Result<Int> = vendorWriteGuard(data, 4096, allowEmpty = true) { h ->
        nativeVendorControlOut(
            h, target.deviceRecipient, target.interfaceNumber, target.request,
            target.value, target.index, data, target.timeoutMs,
        )
    }

    internal fun vendorBulkIn(
        target: com.rawsmusic.module.player.devicecontrol.usb.BulkTarget,
        length: Int,
    ): Result<ByteArray> = vendorReadGuard(length, 65536) { h ->
        nativeVendorBulkIn(h, target.interfaceNumber, target.endpointAddress, length, target.timeoutMs)
    }

    internal fun vendorBulkOut(
        target: com.rawsmusic.module.player.devicecontrol.usb.BulkTarget,
        data: ByteArray,
    ): Result<Int> = vendorWriteGuard(data, 65536) { h ->
        nativeVendorBulkOut(h, target.interfaceNumber, target.endpointAddress, data, target.timeoutMs)
    }

    internal fun vendorEndpointWrite(
        target: com.rawsmusic.module.player.devicecontrol.usb.EndpointPairTarget,
        request: ByteArray,
    ): Result<Int> = vendorWriteGuard(request, 65536) { h ->
        nativeVendorEndpointWrite(
            h, target.interfaceNumber, target.outEndpointAddress, target.inEndpointAddress,
            request, target.timeoutMs,
        )
    }

    internal fun vendorEndpointExchange(
        target: com.rawsmusic.module.player.devicecontrol.usb.EndpointPairTarget,
        request: ByteArray,
        responseLength: Int,
    ): Result<ByteArray> {
        val h = currentHandle
        if (h == 0L || !initialized) return Result.failure(IllegalStateException("usb_engine_not_initialized"))
        if (request.isEmpty() || request.size > 65536 || responseLength !in 1..65536 ||
            target.turnaroundDelayMs !in 0..1000) {
            return Result.failure(IllegalArgumentException("usb_vendor_endpoint_exchange_out_of_range"))
        }
        return runCatching {
            nativeVendorEndpointExchange(
                h, target.interfaceNumber, target.outEndpointAddress, target.inEndpointAddress,
                request, responseLength, target.turnaroundDelayMs, target.timeoutMs,
            ) ?: throw IllegalStateException("usb_vendor_endpoint_exchange_failed")
        }
    }

    private inline fun vendorReadGuard(
        length: Int,
        maxLength: Int,
        block: (Long) -> ByteArray?,
    ): Result<ByteArray> {
        val h = currentHandle
        if (h == 0L || !initialized) return Result.failure(IllegalStateException("usb_engine_not_initialized"))
        if (length !in 1..maxLength) return Result.failure(IllegalArgumentException("usb_vendor_length_out_of_range"))
        return runCatching { block(h) ?: throw IllegalStateException("usb_vendor_transfer_failed") }
    }

    private inline fun vendorWriteGuard(
        data: ByteArray,
        maxLength: Int,
        allowEmpty: Boolean = false,
        block: (Long) -> Int,
    ): Result<Int> {
        val h = currentHandle
        if (h == 0L || !initialized) return Result.failure(IllegalStateException("usb_engine_not_initialized"))
        if ((!allowEmpty && data.isEmpty()) || data.size > maxLength) {
            return Result.failure(IllegalArgumentException("usb_vendor_payload_out_of_range"))
        }
        return runCatching {
            val code = block(h)
            if (code < 0) throw IllegalStateException("usb_vendor_transfer_code_$code")
            code
        }
    }

    fun isInitialized(): Boolean = initialized

    fun resetBuffer() {
        val h = currentHandle
        if (h == 0L || !initialized) return
        nativeResetBuffer(h)
    }

    fun getRecommendedDelayUs(): Int = runtimeMetrics.recommendedDelayUs()

    fun getBufferUsedBytes(): Int = runtimeMetrics.bufferUsedBytes()

    fun getOutputBytesPerSecond(): Int = runtimeMetrics.outputBytesPerSecond()

    fun getRuntimeFormat(): UsbRuntimeFormat = runtimeMetrics.runtimeFormat()

    fun getOutputSampleRate(): Int = runtimeMetrics.outputSampleRate()

    fun refreshRuntimeSnapshotFromNative() {
        if (!initialized) return
        runCatching { nativeGetOutputSampleRate() }.getOrNull()?.takeIf { it > 0 }?.let {
            currentSampleRate = it
        }
        runCatching { nativeGetCurrentChannelCount() }.getOrNull()?.takeIf { it > 0 }?.let {
            currentChannels = it
        }
        runCatching { nativeGetCurrentBitDepth() }.getOrNull()?.takeIf { it > 0 }?.let {
            currentBits = it
        }
        runCatching { nativeGetCurrentSubslotSize() }.getOrNull()?.takeIf { it > 0 }?.let {
            currentSubslotSize = it
        }
        currentOutEndpoint = runCatching { nativeGetCurrentOutEndpoint() }.getOrDefault(0)
        currentFeedbackEndpoint = runCatching { nativeGetCurrentFeedbackEndpoint() }.getOrDefault(0)
        currentInterfaceNumber = runCatching { nativeGetCurrentInterfaceNumber() }.getOrDefault(-1)
        currentAltSetting = runCatching { nativeGetCurrentAltSetting() }.getOrDefault(0)
    }

    /** 改进后的 write（通过 handle 访问，完全摆脱全局 ctx）
     * 1. 检测 acceptingWrites、streaming、错误码
     * 2. nativeWriteHandle 返回 0 时用 nativeGetRecommendedDelayUs() 自适应 throttling
     * 3. 收到 -EPIPE/ERR_NOT_RUNNING/ERR_TRANSPORT_LOST 立刻退出写线程 */
    fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (writerPriorityApplied.get() != true) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                writerPriorityApplied.set(true)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Failed to raise USB writer thread priority", t)
                writerPriorityApplied.set(true)
            }
        }
        val h = currentHandle
        if (h == 0L || !initialized) {
            AppLogger.w(TAG, "write rejected: ERR_NOT_INITIALIZED handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized length=$length")
            return ERR_NOT_INITIALIZED
        }
        if (nativeSessionBroken) {
            AppLogger.w(TAG, "write denied: native session broken")
            return ERR_TRANSPORT_LOST
        }
        var total = 0
        var cur = offset
        var remain = length
        while (remain > 0) {
            val rc = nativeWriteHandle(h, data, cur, remain)
            when {
                rc > 0 -> {
                    total += rc
                    cur += rc
                    remain -= rc
                }
                rc == 0 -> {
                    if (total > 0) {
                        return total
                    }
                    val delayUs = getRecommendedDelayUs()
                    if (delayUs > 0) {
                        val boundedDelayUs = delayUs.coerceAtMost(1000)
                        Thread.sleep(boundedDelayUs / 1000L, (boundedDelayUs % 1000L).toInt())
                    } else {
                        Thread.yield()
                    }
                    return 0
                }
                rc == -32 || rc == ERR_NOT_RUNNING -> {
                    AppLogger.w(TAG, "writer thread exiting, nativeWriteHandle returned $rc")
                    return total
                }
                rc == ERR_TRANSPORT_LOST -> {
                    AppLogger.e(TAG, "USB transport lost from nativeWriteHandle")
                    return rc
                }
                rc < 0 -> {
                    AppLogger.e(TAG, "nativeWriteHandle error: $rc")
                    return if (total > 0) total else rc
                }
            }
        }
        return total
    }

    fun safeNativeWriteHandle(handle: Long, data: ByteArray, offset: Int, length: Int): Int {
        return try {
            nativeWriteHandle(handle, data, offset, length)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "nativeWriteHandle threw", t)
            ERR_NOT_INITIALIZED
        }
    }

    fun nativeIsRunning(): Boolean {
        return try { nativeIsActive() } catch (_: Throwable) { false }
    }

    fun clearState() {
        currentSampleRate = 0
        currentChannels = 0
        currentBits = 0
        currentSubslotSize = 0
        currentOutEndpoint = 0
        currentFeedbackEndpoint = 0
        currentInterfaceNumber = -1
        currentAltSetting = 0
        nativeHandleRef.set(0L)
        initialized = false
        nativeSessionBroken = false
    }

    fun isPolicyChangedSinceInit(): Boolean {
        return try {
            nativeRequiresReinit()
        } catch (_: Throwable) {
            false
        }
    }

    fun stageOutputProfile(
        profile: UsbOutputProfile,
        dsdRate: Int,
        dsdConversionType: Int,
        dsdDitherEnabled: Boolean,
    ) {
        val effectiveFeedbackEndpoint = if (profile.noFeedback) 0 else profile.lastGoodFeedbackEndpoint
        stageNextSessionPolicy(
            snapshotNextSessionPolicy().copy(
                exclusive = profile.exclusive,
                // Direct DSD is an immutable raw transport even when the user-facing PCM
                // bit-perfect policy is OFF. Commit that fixed transport property before
                // nativeInitUsbDevice() so the volume route cannot flip native policy in the
                // prepare -> nativeStart window and force an endless reinit loop.
                bitPerfect = profile.exclusive &&
                    (profile.bitPerfect || profile.fixedDigitalVolume || profile.dsdSourceDirect),
                hardwareVolumeRequested = profile.exclusive && profile.hardwareVolumeRequested,
                pcmOutputMode = profile.pcmOutputMode,
                dsdConversionEnabled = profile.dsdConversionEnabled,
                dsdRate = dsdRate,
                dsdConversionType = dsdConversionType,
                dsdDitherEnabled = profile.dsdConversionEnabled && dsdDitherEnabled,
                dsdDoPEnabled = profile.dsdConversionEnabled && profile.dsdDoPEnabled,
                force1msPacket = profile.force1msPacket,
                noClockSet = profile.noClockSet,
                noFeedback = profile.noFeedback,
                noFeatureUnit = profile.noFeatureUnit,
                preferSafeAlt = profile.preferSafeAlt,
                safeMode = profile.safeMode,
                lastGoodAlt = profile.lastGoodAlt,
                lastGoodSampleRate = profile.lastGoodSampleRate,
                lastGoodValidBits = profile.lastGoodBitDepth,
                lastGoodSubslotBytes = profile.lastGoodSubslot,
                lastGoodFeedbackEndpoint = effectiveFeedbackEndpoint,
            ),
            reason = "output_profile",
        )
    }

    fun setPolicy(exclusive: Boolean, bitPerfect: Boolean, hwVol: Boolean) {
        updateNextSessionPolicy("playback_policy") {
            it.copy(
                exclusive = exclusive,
                bitPerfect = bitPerfect && exclusive,
                hardwareVolumeRequested = hwVol && exclusive,
            )
        }
        nativeSetPolicy(exclusive, bitPerfect, hwVol)
    }

    fun setLastGoodProfile(
        alt: Int,
        sampleRate: Int,
        validBits: Int,
        subslotBytes: Int,
        feedbackEndpoint: Int,
    ) {
        updateNextSessionPolicy("last_good_profile") {
            it.copy(
                lastGoodAlt = alt.coerceAtLeast(0),
                lastGoodSampleRate = sampleRate.coerceAtLeast(0),
                lastGoodValidBits = validBits.coerceAtLeast(0),
                lastGoodSubslotBytes = subslotBytes.coerceAtLeast(0),
                lastGoodFeedbackEndpoint = feedbackEndpoint.coerceAtLeast(0),
            )
        }
        nativeSetLastGoodProfile(alt, sampleRate, validBits, subslotBytes, feedbackEndpoint)
    }

    fun setCompatFlags(
        noClockSet: Boolean,
        noFeedback: Boolean,
        noFeatureUnit: Boolean,
        preferSafeAlt: Boolean,
        safeMode: Boolean,
    ) {
        updateNextSessionPolicy("compat_flags") {
            it.copy(
                noClockSet = noClockSet,
                noFeedback = noFeedback,
                noFeatureUnit = noFeatureUnit,
                preferSafeAlt = preferSafeAlt,
                safeMode = safeMode,
            )
        }
        nativeSetCompatFlags(noClockSet, noFeedback, noFeatureUnit, preferSafeAlt, safeMode)
    }

    fun resetUsbPolicyForNewDevice() {
        synchronized(this) { nextSessionPolicy = UsbNativeSessionPolicy() }
        nativeResetUsbPolicyForNewDevice()
        AppLogger.i(TAG, "USB_SESSION_STAGE reset for new device")
    }

    /** 设置 USB DAC 高级选项。Safe Core 仅放行 force1ms 包调度，其余兼容怪癖保持关闭。 */
    fun setUsbDacSettings(noControlIface: Boolean, forceUac1: Boolean, linearVolume: Boolean, replaceVolume: Boolean, force1ms: Boolean) {
        // Safe Core intentionally suppresses the legacy quirks; stage exactly what native receives.
        updateNextSessionPolicy("dac_settings") {
            it.copy(
                noControlInterface = false,
                forceUac1 = false,
                linearVolume = false,
                replaceVolume = false,
                force1msPacket = force1ms,
            )
        }
        nativeSetUsbDacSettings(false, false, false, false, force1ms)
    }

    /** 设置 PCM→DSD / DoP / Native DSD 参数 */
    fun setDsdConversion(enabled: Boolean, rate: Int, type: Int, dither: Boolean, dop: Boolean) {
        updateNextSessionPolicy("dsd_transport") {
            it.copy(
                dsdConversionEnabled = enabled,
                dsdRate = rate,
                dsdConversionType = type,
                dsdDitherEnabled = enabled && dither,
                dsdDoPEnabled = enabled && dop,
            )
        }
        // DSD mode is process-global in native. Mutating it while a USB handle is live can make
        // the old DSD altsetting consume PCM bytes (or clear the converter under its writer),
        // which has triggered OEM USB-stack failures on some devices. Settings are therefore
        // session-scoped: close the old handle first, then apply the desired mode for the next init.
        val liveHandle = currentHandle
        if (liveHandle != 0L) {
            AppLogger.e(
                TAG,
                "setDsdConversion deferred: live USB handle=0x${java.lang.Long.toUnsignedString(liveHandle, 16)} " +
                    "requested enabled=$enabled rate=DSD$rate dop=${enabled && dop}; close USB first"
            )
            return
        }
        // DoP is an active DSD transport property, not a sticky preference bit in
        // the native engine. If DSD conversion is off, the native transport must
        // report dopEnabled=0 and the PCM path must never see stale DoP state.
        nativeSetDsdConversion(enabled, rate, type, dither, enabled && dop)
    }

    /** Native is the sole owner of whether the live USB session may shape PCM transition gain. */
    fun isSessionVolumeEnvelopeAllowed(handle: Long = currentHandle): Boolean =
        handle != 0L && nativeIsSessionVolumeEnvelopeAllowed(handle)

    /**
     * Request a session transition envelope. Native playback mode/DSD transport decides whether
     * the request is legal; rejected routes are forced to unity and return false.
     */
    fun applySessionVolumeTransition(
        handle: Long,
        linear: Float,
        fadeMs: Int,
        reason: String,
    ): Boolean {
        if (handle == 0L) return false
        return nativeApplySessionVolumeTransition(
            handle,
            linear.coerceIn(0f, 1f),
            fadeMs.coerceIn(0, 15_000),
            reason,
        )
    }

    data class HardwareVolumeCommandResult(
        val status: Int,
        val observedRaw: Int?,
    ) {
        val confirmed: Boolean get() = status == 0 && observedRaw != null
    }

    private fun decodeHardwareVolumeCommandResult(packed: Long): HardwareVolumeCommandResult {
        val status = (packed shr 32).toInt()
        val raw = packed.toInt().takeUnless { it == Int.MIN_VALUE }
        return HardwareVolumeCommandResult(status = status, observedRaw = raw)
    }

    fun setHardwareVolumeRawVerified(
        handle: Long,
        raw: Int,
        reason: String,
    ): HardwareVolumeCommandResult = decodeHardwareVolumeCommandResult(
        nativeSetHardwareVolumeRawVerified(handle, raw, reason),
    )

    fun setHardwareVolumeNormalizedVerified(
        handle: Long,
        normalized: Float,
        reason: String,
    ): HardwareVolumeCommandResult = decodeHardwareVolumeCommandResult(
        nativeSetHardwareVolumeNormalizedVerified(
            handle,
            normalized.coerceIn(0f, 1f),
            reason,
        ),
    )

    fun adjustHardwareVolumeVerified(
        handle: Long,
        direction: Int,
        appStepRaw: Int,
        reason: String,
    ): HardwareVolumeCommandResult = decodeHardwareVolumeCommandResult(
        nativeAdjustHardwareVolumeVerified(
            handle,
            direction.coerceIn(-1, 1),
            appStepRaw.coerceAtLeast(1),
            reason,
        ),
    )

    fun getPlaybackModeName(): String {
        return try {
            val mode = nativeGetPlaybackMode()
            when (mode) {
                0 -> "SafeSoftwareVolume"
                1 -> "ExclusiveSoftwareVolume"
                2 -> "ExclusiveProcessedHwVol"
                3 -> "ExclusiveBitPerfectHwVol"
                4 -> "ExclusiveBitPerfectFixed"
                else -> "Unknown($mode)"
            }
        } catch (_: Throwable) {
            "Unknown"
        }
    }

    // ========== 暴露给 pump loop 的直接 JNI 方法 ==========

    external fun nativeWriteHandle(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    external fun nativeGetRecommendedDelayUs(): Int
    external fun nativeGetBufferUsedBytes(): Int
    external fun nativeGetOutputBytesPerSecond(): Int
    external fun nativeGetOutputSampleRate(): Int
    external fun nativeGetCurrentInterfaceNumber(): Int
    external fun nativeGetCurrentAltSetting(): Int
    external fun nativeGetCurrentOutEndpoint(): Int
    external fun nativeGetCurrentFeedbackEndpoint(): Int
    external fun nativeGetCurrentChannelCount(): Int
    external fun nativeGetCurrentBitDepth(): Int
    external fun nativeGetCurrentSubslotSize(): Int
    external fun nativeGetCurrentFrameBytes(): Int
    external fun nativeSetVolume(handle: Long, volume: Float): Int
    external fun nativeRequiresReinit(): Boolean
    external fun nativeOnUsbDetached()
    external fun nativeSetUsbExclusiveActive(active: Boolean)
    external fun nativeSetAndroidAudioSchedulerProfile(sampleRate: Int, framesPerBuffer: Int)
    external fun nativeSetBackgroundPlaybackActive(active: Boolean)
    external fun nativePumpUsbEventsFromKeepAlive(): Int
    external fun nativeSetPolicy(exclusive: Boolean, bitPerfect: Boolean, hwVol: Boolean)

    external fun nativeSetLastGoodProfile(
        alt: Int,
        sampleRate: Int,
        validBits: Int,
        subslotBytes: Int,
        feedbackEndpoint: Int
    )

    external fun nativeSetCompatFlags(
        noClockSet: Boolean,
        noFeedback: Boolean,
        noFeatureUnit: Boolean,
        preferSafeAlt: Boolean,
        safeMode: Boolean
    )

    external fun nativePlanUsbRecovery(
        kind: Int,
        appBytesPerSecond: Long,
        completedUsbBytesPerSecond: Long,
        scheduledUsbBytesPerSecond: Long,
        expectedBytesPerSecond: Long,
        underrun: Int,
        submitError: Int,
        packetError: Int,
        transferError: Int,
        feedbackEnabled: Boolean,
        fixedNoFeedbackPacer: Boolean,
        feedbackDegradedFixedPacer: Boolean,
        profileNoFeedback: Boolean,
        lastGoodAlt: Int,
        runtimeLivenessStall: Boolean,
    ): IntArray

    external fun nativePlanUsbFeatureUnitRecovery(unsafe: Boolean): IntArray

    external fun nativeClassifyUsbStreamHealth(
        appBytesPerSecond: Long,
        completedUsbBytesPerSecond: Long,
        scheduledUsbBytesPerSecond: Long,
        expectedBytesPerSecond: Long,
        bufferUsedBytes: Long,
        bufferCapacityBytes: Long,
        underrun: Int,
        submitError: Int,
        packetError: Int,
        transferError: Int,
        clockRate: Int,
        targetRate: Int,
        finalVolume: Float,
        feedbackEnabled: Boolean,
        feedbackState: Int,
        feedbackInvalidCount: Int,
        feedbackEmptyCount: Int,
        fixedNoFeedbackPacer: Boolean,
        feedbackDegradedFixedPacer: Boolean,
    ): IntArray

    external fun nativeGetStatsString(handle: Long): String
    external fun nativeGetLastPcmInputDiagnosticsString(): String
    external fun nativeGetAudibleStateString(handle: Long): String
    external fun nativeGetStreamSessionId(handle: Long): Long
    external fun nativeGetIsoPipelineDepthMs(handle: Long): Int
    external fun nativeIsStreamFullyStopped(handle: Long): Boolean
    external fun nativeIssueUsbRecoveryToken(handle: Long): Long
    external fun nativeIsUsbRecoveryTokenCurrent(handle: Long, token: Long): Boolean
    external fun nativeInvalidateUsbRecoveryToken(handle: Long)
    external fun isHardwareVolumeValidated(): Boolean
    external fun nativeSetHardwareVolumeDbNoCache(handle: Long, db: Int, reason: String): Int
    /** Legacy raw write retained for compatibility; new init path uses verified native transaction. */
    external fun nativeSetHardwareVolumeRawNoCache(handle: Long, raw: Int, reason: String): Int
    external fun nativeSetHardwareBoundaryVolumeRaw(handle: Long, raw: Int, reason: String): Int
    external fun nativeSetHardwareVolumeRawVerified(
        handle: Long,
        raw: Int,
        reason: String,
    ): Long
    external fun nativeSetHardwareVolumeNormalizedVerified(
        handle: Long,
        normalized: Float,
        reason: String,
    ): Long
    external fun nativeAdjustHardwareVolumeVerified(
        handle: Long,
        direction: Int,
        appStepRaw: Int,
        reason: String,
    ): Long
    external fun nativeGetHardwareVolumeCurrentRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeMinRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeMaxRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeResRaw(handle: Long): Int
    private external fun nativeGetPlaybackMode(handle: Long): Int
    external fun nativeSetPcmOutputMode(mode: Int)
    external fun nativeArmStopFade(handle: Long, fadeMs: Int)
    external fun nativeArmTrackStopFade(handle: Long, fadeMs: Int)
    external fun nativeFlushForNextTrack(handle: Long): Boolean
    external fun nativeRestartIsoTransfersSameProfile(handle: Long): Boolean
    external fun nativeResetSessionForPlayback(handle: Long)
    external fun nativeCloseStreamForReconfigure(handle: Long)
    external fun nativeReconfigurePcmStream(
        handle: Long,
        targetDeviceRate: Int,
        sourceSampleRate: Int,
        sourceBitsPerSample: Int,
        targetChannels: Int,
        targetBits: Int,
        targetSubslot: Int,
        verifyClockReadback: Boolean,
    ): Boolean
    external fun nativeEnterStandby(handle: Long)
    external fun nativeResumeFromStandby(handle: Long): Boolean
    external fun nativeIsSessionBroken(handle: Long): Boolean
    external fun nativeGetStreamState(handle: Long): Int
    private external fun nativeGetDeviceCapabilitiesJson(handle: Long): String
    private external fun nativeProbeStandardHardwareControlsJson(handle: Long): String
    private external fun nativeProbeVendorControlInventoryJson(handle: Long): String

    private external fun nativeVendorExtensionUnitRead(
        handle: Long, interfaceNumber: Int, entityId: Int, selector: Int, channel: Int,
        length: Int, timeoutMs: Int,
    ): ByteArray?
    private external fun nativeVendorExtensionUnitWrite(
        handle: Long, interfaceNumber: Int, entityId: Int, selector: Int, channel: Int,
        data: ByteArray, timeoutMs: Int,
    ): Int
    private external fun nativeVendorHidGetReport(
        handle: Long, interfaceNumber: Int, reportType: Int, reportId: Int, length: Int, timeoutMs: Int,
    ): ByteArray?
    private external fun nativeVendorHidSetReport(
        handle: Long, interfaceNumber: Int, reportType: Int, reportId: Int, data: ByteArray, timeoutMs: Int,
    ): Int
    private external fun nativeVendorControlIn(
        handle: Long, deviceRecipient: Boolean, interfaceNumber: Int, request: Int,
        value: Int, index: Int, length: Int, timeoutMs: Int,
    ): ByteArray?
    private external fun nativeVendorControlOut(
        handle: Long, deviceRecipient: Boolean, interfaceNumber: Int, request: Int,
        value: Int, index: Int, data: ByteArray, timeoutMs: Int,
    ): Int
    private external fun nativeVendorBulkIn(
        handle: Long, interfaceNumber: Int, endpointAddress: Int, length: Int, timeoutMs: Int,
    ): ByteArray?
    private external fun nativeVendorBulkOut(
        handle: Long, interfaceNumber: Int, endpointAddress: Int, data: ByteArray, timeoutMs: Int,
    ): Int
    private external fun nativeVendorEndpointWrite(
        handle: Long, interfaceNumber: Int, outEndpointAddress: Int, inEndpointAddress: Int,
        request: ByteArray, timeoutMs: Int,
    ): Int
    private external fun nativeVendorEndpointExchange(
        handle: Long, interfaceNumber: Int, outEndpointAddress: Int, inEndpointAddress: Int,
        request: ByteArray, responseLength: Int, turnaroundDelayMs: Int, timeoutMs: Int,
    ): ByteArray?

    private external fun nativeWriteStandardHardwareControlJson(
        handle: Long,
        interfaceNumber: Int,
        entityId: Int,
        selector: Int,
        channel: Int,
        elementIndex: Int,
        value: Double,
    ): String
    external fun nativeSetUsbDacSettings(
        noControlIface: Boolean,
        forceUac1: Boolean,
        linearVolume: Boolean,
        replaceVolume: Boolean,
        force1ms: Boolean
    )
    external fun nativeSetPcmSoftwareGain(handle: Long, gain: Float): Int

    /** 全局软件增益，不需要 handle。用于音量路由层在 handle 就绪前预设增益。 */
    external fun nativeSetUsbSoftwareGain(linear: Float)

    /** seek 前软停止：停止 ISO 传输 + 清 ring + 设淡入，不标 BROKEN，不关闭 handle。 */
    external fun nativePrepareForSeek(handle: Long, rampMs: Int, reason: String)

    external fun nativeIsSessionVolumeEnvelopeAllowed(handle: Long): Boolean
    external fun nativeApplySessionVolumeTransition(handle: Long, linear: Float, fadeMs: Int, reason: String): Boolean

    external fun nativeSetDsdConversion(
        enabled: Boolean,
        rate: Int,
        type: Int,
        dither: Boolean,
        dop: Boolean
    )
    external fun nativeResetUsbPolicyForNewDevice()
    external fun nativeCanControlVolume(handle: Long): Boolean
    external fun nativeCanUseHardwareBoundaryFade(handle: Long): Boolean
    external fun nativeGetVolumeDb(handle: Long): Float
    external fun nativeValidateHardwareVolume(handle: Long): Int
    external fun nativeGetHardwareVolumePolicyString(handle: Long): String
    external fun nativeIsHardwareVolumeSafe(): Boolean

    fun getHardwareVolumePolicyString(): String {
        val h = currentHandle
        if (h == 0L) return "no-handle"
        return runCatching { nativeGetHardwareVolumePolicyString(h) }.getOrDefault("unavailable")
    }
    external fun nativeRepairHardwareVolumeBalance(safeVolume: Float): Int
    external fun nativeGetPlaybackMode(): Int

    // ========== USB HID Remote Control ==========

    /**
     * HID key event callback interface
     */
    interface HidKeyEventListener {
        fun onHidKeyEvent(keyCode: Int, pressed: Boolean)
    }

    private var hidKeyEventListener: HidKeyEventListener? = null

    /**
     * Set HID key event listener
     */
    fun setHidKeyEventListener(listener: HidKeyEventListener?) {
        hidKeyEventListener = listener
        if (!isHidNativeAvailable()) {
            return
        }
        try {
            nativeSetHidCallback(listener?.let { HidCallbackWrapper(it) })
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeSetHidCallback", e)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "setHidKeyEventListener failed", t)
        }
    }

    /**
     * Initialize HID JNI (call once at startup).
     * HID is optional; missing JNI symbols must never crash cold app launch.
     */
    fun initHidSafely(): Boolean {
        if (!isHidNativeAvailable()) {
            AppLogger.w(TAG, "Skipping HID init: rawsmusic_usb/HID bridge unavailable")
            return false
        }
        return try {
            nativeInitHid()
            true
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeInitHid", e)
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeInitHid failed", t)
            false
        }
    }

    /**
     * Initialize HID JNI (call once at startup)
     */
    external fun nativeInitHid()

    /**
     * Set HID callback object
     */
    private external fun nativeSetHidCallback(callback: Any?)

    /**
     * Start listening for HID key events
     * @return true if HID interface found and listening started
     */
    external fun nativeStartHidListening(handle: Long): Boolean

    /**
     * Stop listening for HID key events
     */
    external fun nativeStopHidListening(handle: Long)

    /**
     * Check if currently listening for HID events
     */
    external fun nativeIsHidListening(): Boolean

    /**
     * Check if device has HID interface
     */
    external fun nativeHasHidInterface(handle: Long): Boolean

    /**
     * Start HID listening with current handle
     */
    fun startHidListening(): Boolean {
        val h = currentHandle
        if (h == 0L || !initialized) {
            AppLogger.w(TAG, "Cannot start HID: not initialized")
            return false
        }
        if (!isHidNativeAvailable()) return false
        return try {
            nativeStartHidListening(h)
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeStartHidListening", e)
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeStartHidListening failed", t)
            false
        }
    }

    /**
     * Stop HID listening
     */
    fun stopHidListening() {
        if (!isHidNativeAvailable()) return
        try {
            nativeStopHidListening(currentHandle)
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeStopHidListening", e)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeStopHidListening failed", t)
        }
    }

    /**
     * Check if device supports HID
     */
    fun hasHidInterface(): Boolean {
        val h = currentHandle
        if (h == 0L || !initialized || !isHidNativeAvailable()) return false
        return try {
            nativeHasHidInterface(h)
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeHasHidInterface", e)
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeHasHidInterface failed", t)
            false
        }
    }

    fun isHidListening(): Boolean {
        if (!isHidNativeAvailable()) return false
        return try {
            nativeIsHidListening()
        } catch (e: UnsatisfiedLinkError) {
            markHidNativeUnavailable("nativeIsHidListening", e)
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeIsHidListening failed", t)
            false
        }
    }

    /**
     * Wrapper class for HID callback (called from native)
     */
    private class HidCallbackWrapper(private val listener: HidKeyEventListener) {
        /**
         * Called from native code when HID key event is received
         */
        fun onHidKeyEvent(keyCode: Int, pressed: Boolean) {
            listener.onHidKeyEvent(keyCode, pressed)
        }
    }

    /** 安全调用 JNI 方法，捕获异常防止崩溃 */
    fun <R> safeCall(tag: String, block: () -> R): R? {
        return try {
            block()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "JNI call $tag threw", t)
            null
        }
    }

    // ========== 内部 JNI 方法 ==========

    private external fun nativeIsActive(): Boolean
    private external fun nativeGetPacketSize(): Int
    private external fun nativeGetTransferCapacityBytes(): Int
    private external fun nativeGetMaxPacketBytes(): Int
    private external fun nativeGetServiceIntervalsPerSecond(): Int
    private external fun nativeGetNominalBytesPerInterval(): Int
    private external fun nativeGetNominalBytesPerTransfer(): Int
    private external fun nativeGetCompletedUsbBytesPerSecond(): Long
    private external fun nativeGetScheduledUsbBytesPerSecond(): Long
    private external fun nativeGetIsoCallbackAgeMs(): Long
    private external fun nativeGetFeedbackState(): Int
    private external fun nativeGetFeedbackSampleRateMilli(): Int
    private external fun nativeGetPacingMode(): Int
    private external fun nativeSetSampleRate(sampleRate: Int): Int
    private external fun nativeResetBuffer(handle: Long)
}
