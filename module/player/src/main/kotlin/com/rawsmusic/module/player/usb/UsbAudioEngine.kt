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
        subslotSize: Int
    ): Long {
        // 先关闭旧 handle（如果有的话）
        closeNative("before_reinit")

        val handle = nativeInitUsbDevice(
            fd, sampleRate, sourceSampleRate, sourceBitsPerSample, channels, bitsPerSample,
            iface, alt, outEndpoint, feedbackEndpoint, subslotSize
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
                "deviceSr=$sampleRate sourceSr=$sourceSampleRate sourceBits=$sourceBitsPerSample " +
                "deviceBits=$bitsPerSample ch=$channels iface=$currentInterfaceNumber alt=$currentAltSetting"
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

    fun getFeedbackState(): FeedbackState = runtimeMetrics.feedbackState()

    fun getFeedbackSampleRate(): Double = runtimeMetrics.feedbackSampleRate()

    fun getPacingMode(): PacingMode = runtimeMetrics.pacingMode()

    fun feedbackLooksUnsafeForPacer(): Boolean = runtimeMetrics.feedbackLooksUnsafeForPacer()

    fun getStreamSessionId(): Long = runtimeMetrics.streamSessionId()

    fun computeRecommendedWriteChunkBytes(deviceBytesPerSecond: Long, frameBytes: Int, targetMs: Long = 32L): Int =
        runtimeMetrics.recommendedWriteChunkBytes(deviceBytesPerSecond, frameBytes, targetMs)

    fun setSampleRate(sampleRate: Int): Boolean {
        if (!initialized) return false
        if (nativeSessionBroken) {
            AppLogger.e(TAG, "setSampleRate denied: native session broken")
            return false
        }
        return nativeSetSampleRate(sampleRate) == 0
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

    /** 释放 AS interface 保留 fd，格式变化时调用 */
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

    fun setPolicy(exclusive: Boolean, bitPerfect: Boolean, useHardwareVolume: Boolean) {
        nativeSetPolicy(exclusive, bitPerfect, useHardwareVolume)
    }

    /** 设置 USB DAC 高级选项。Safe Core 仅放行 force1ms 包调度，其余兼容怪癖保持关闭。 */
    fun setUsbDacSettings(noControlIface: Boolean, forceUac1: Boolean, linearVolume: Boolean, replaceVolume: Boolean, force1ms: Boolean) {
        nativeSetUsbDacSettings(false, false, false, false, force1ms)
    }

    /** 设置 PCM→DSD / DoP / Native DSD 参数 */
    fun setDsdConversion(enabled: Boolean, rate: Int, type: Int, dither: Boolean, dop: Boolean) {
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

    /** Native session volume envelope */
    fun setSessionVolumeScale(handle: Long, linear: Float, fadeMs: Int) {
        if (handle == 0L) return
        nativeSetSessionVolumeScale(handle, linear.coerceIn(0f, 1f), fadeMs.coerceAtLeast(0))
    }

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

    external fun nativeGetStatsString(handle: Long): String
    external fun nativeGetAudibleStateString(handle: Long): String
    external fun nativeGetStreamSessionId(handle: Long): Long
    external fun isHardwareVolumeValidated(): Boolean
    external fun nativeSetHardwareVolumeDbNoCache(handle: Long, db: Int, reason: String): Int
    /** Reattach-only write that bypasses the per-handle SET_CUR dedup cache. */
    external fun nativeSetHardwareVolumeRawNoCache(handle: Long, raw: Int, reason: String): Int
    external fun nativeSetHardwareVolumeRaw(handle: Long, raw: Int, reason: String): Int
    external fun nativeAdjustHardwareVolume(handle: Long, direction: Int, reason: String): Int
    external fun nativeGetHardwareVolumeCurrentRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeMinRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeMaxRaw(handle: Long): Int
    external fun nativeGetHardwareVolumeResRaw(handle: Long): Int
    private external fun nativeGetPlaybackMode(handle: Long): Int
    external fun nativeSetPcmOutputMode(mode: Int)
    external fun nativeArmStopFade(handle: Long, fadeMs: Int)
    external fun nativeArmTrackStopFade(handle: Long, fadeMs: Int)
    external fun nativeFlushForNextTrack(handle: Long)
    external fun nativeRestartIsoTransfersSameProfile(handle: Long): Boolean
    external fun nativeResetSessionForPlayback(handle: Long)
    external fun nativeCloseStreamForReconfigure(handle: Long)
    external fun nativeEnterStandby(handle: Long)
    external fun nativeResumeFromStandby(handle: Long): Boolean
    external fun nativeIsSessionBroken(handle: Long): Boolean
    external fun nativeGetStreamState(handle: Long): Int
    private external fun nativeGetDeviceCapabilitiesJson(handle: Long): String
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

    /** Native session volume envelope: linear + fadeMs */
    external fun nativeSetSessionVolumeScale(handle: Long, linear: Float, fadeMs: Int)

    external fun nativeSetDsdConversion(
        enabled: Boolean,
        rate: Int,
        type: Int,
        dither: Boolean,
        dop: Boolean
    )
    external fun nativeResetUsbPolicyForNewDevice()
    external fun nativeCanControlVolume(handle: Long): Boolean
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
    private external fun nativeGetFeedbackState(): Int
    private external fun nativeGetFeedbackSampleRateMilli(): Int
    private external fun nativeGetPacingMode(): Int
    private external fun nativeSetSampleRate(sampleRate: Int): Int
    private external fun nativeResetBuffer(handle: Long)
}
