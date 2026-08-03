package com.rawsmusic.module.player.usb

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicLong

object UsbAudioEngine {

    internal const val TAG = "UsbAudioEngine"

    @Volatile
    private var nativeLibraryLoaded: Boolean = false

    @Volatile
    private var hidNativeAvailable: Boolean = true

    @Volatile
    private var backgroundPlaybackNativeAvailable: Boolean = true

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
            backgroundPlaybackNativeAvailable = false
            AppLogger.e(TAG, "Failed to load rawsmusic_usb", e)
        }
    }

    fun isNativeLibraryLoaded(): Boolean = nativeLibraryLoaded

    fun isHidNativeAvailable(): Boolean = nativeLibraryLoaded && hidNativeAvailable

    fun isBackgroundPlaybackNativeAvailable(): Boolean = nativeLibraryLoaded && backgroundPlaybackNativeAvailable

    /**
     * Use Android's actual fast
     * mixer sample rate and frames-per-buffer, not the USB stream rate. A 384 kHz
     * USB profile cannot itself create a normal Android fast AudioTrack.
     */
    fun configureAndroidAudioSchedulerProfile(context: Context) {
        if (!nativeLibraryLoaded) return
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it in 44_100..192_000 }
            ?: 48_000
        val propertyFrames = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
            ?.takeIf { it >= 64 }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minBufferFrames = if (minBufferBytes > 0) minBufferBytes / 4 else 0
        val framesPerBuffer = (propertyFrames ?: minBufferFrames.takeIf { it >= 64 } ?: 192)
            .coerceIn(64, 4096)
        runCatching {
            nativeSetAndroidAudioSchedulerProfile(sampleRate, framesPerBuffer)
        }.onSuccess {
            AppLogger.i(
                TAG,
                "Android audio scheduler profile configured: sr=$sampleRate frames=$framesPerBuffer " +
                    "propertyFrames=${propertyFrames ?: 0} minBufferBytes=$minBufferBytes"
            )
        }.onFailure {
            AppLogger.w(TAG, "Unable to configure Android audio scheduler profile", it)
        }
    }

    private fun markHidNativeUnavailable(api: String, t: Throwable) {
        hidNativeAvailable = false
        AppLogger.w(TAG, "USB HID native bridge unavailable at $api; HID remote keys disabled", t)
    }

    private fun markBackgroundPlaybackNativeUnavailable(api: String, t: Throwable) {
        backgroundPlaybackNativeAvailable = false
        AppLogger.w(
            TAG,
            "USB background native guard unavailable at $api; native ColorOS/Hans guard disabled, " +
                "Java foreground service/media identity/wakelock protection remains active",
            t
        )
    }

    /**
     * Optional native background guard used only for USB-exclusive background hardening.
     * Missing JNI symbols must never crash Activity lifecycle callbacks.
     */
    fun setBackgroundPlaybackActiveSafely(active: Boolean, reason: String = "unspecified"): Boolean {
        if (!isBackgroundPlaybackNativeAvailable()) {
            return false
        }
        return try {
            nativeSetBackgroundPlaybackActive(active)
            true
        } catch (e: UnsatisfiedLinkError) {
            markBackgroundPlaybackNativeUnavailable("nativeSetBackgroundPlaybackActive($active,$reason)", e)
            false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "nativeSetBackgroundPlaybackActive($active) failed: reason=$reason", t)
            false
        }
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

    @Volatile
    private var lastNextTrackFlushMs: Long = 0L
    @Volatile
    private var lastNextTrackFlushReason: String = ""

    val currentHandle: Long
        get() = nativeHandleRef.get()

    @Volatile
    var initialized = false
        private set

    @Volatile
    private var nativeSessionBroken = false

    @Volatile
    private var cachedDeviceCapabilities: UsbDeviceAudioCapabilities? = null

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

    fun start(): Boolean {
        val h = nativeHandleRef.get()
        if (h == 0L || !initialized) {
            AppLogger.e(TAG, "Cannot start: not initialized, handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized")
            return false
        }
        if (nativeSessionBroken) {
            AppLogger.e(TAG, "start denied: native session broken, full reopen required")
            return false
        }
        val result = nativeStart(h)
        if (!result) {
            AppLogger.e(TAG, "nativeStart failed, marking session broken")
            nativeSessionBroken = true
        } else {
            refreshRuntimeSnapshotFromNative()
            AppLogger.i(TAG, "Streaming started")
        }
        return result
    }

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

    fun pause() {
        val h = nativeHandleRef.get()
        if (h == 0L || !initialized) {
            AppLogger.i(TAG, "pause() ignored: handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized")
            return
        }
        AppLogger.i(TAG, "pause() calling nativePause, handle=0x${java.lang.Long.toUnsignedString(h, 16)}")
        nativePause(h)
        AppLogger.i(TAG, "Streaming paused (buffer preserved)")
    }

    fun release() {
        closeNative("release")
        nativeSessionBroken = false
    }

    fun isActive(): Boolean {
        if (!initialized) return false
        return nativeIsActive()
    }

    fun isRunning(): Boolean = isActive()

    /** Legacy ABI: returns the ISO transfer capacity, not the nominal audio packet payload. */
    fun getPacketSize(): Int = getTransferCapacityBytes()

    fun getTransferCapacityBytes(): Int {
        if (!initialized) return 0
        return runCatching { nativeGetTransferCapacityBytes() }.getOrElse { nativeGetPacketSize() }
    }

    fun getMaxPacketBytes(): Int {
        if (!initialized) return 0
        return runCatching { nativeGetMaxPacketBytes() }.getOrDefault(0)
    }

    fun getServiceIntervalsPerSecond(): Int {
        if (!initialized) return 0
        return runCatching { nativeGetServiceIntervalsPerSecond() }.getOrDefault(0)
    }

    fun getNominalBytesPerInterval(): Int {
        if (!initialized) return 0
        return runCatching { nativeGetNominalBytesPerInterval() }.getOrDefault(0)
    }

    fun getNominalBytesPerTransfer(): Int {
        if (!initialized) return 0
        return runCatching { nativeGetNominalBytesPerTransfer() }.getOrDefault(0)
    }

    fun getCompletedUsbBytesPerSecond(): Long {
        if (!initialized) return 0L
        return runCatching { nativeGetCompletedUsbBytesPerSecond() }.getOrDefault(0L)
    }

    fun getScheduledUsbBytesPerSecond(): Long {
        if (!initialized) return 0L
        return runCatching { nativeGetScheduledUsbBytesPerSecond() }.getOrDefault(0L)
    }

    fun getFeedbackState(): FeedbackState {
        if (!initialized) return FeedbackState.NONE
        return FeedbackState.fromId(runCatching { nativeGetFeedbackState() }.getOrDefault(0))
    }

    fun getFeedbackSampleRate(): Double {
        if (!initialized) return 0.0
        return runCatching { nativeGetFeedbackSampleRateMilli() / 1000.0 }.getOrDefault(0.0)
    }

    fun getPacingMode(): PacingMode {
        if (!initialized) return PacingMode.Unknown
        val h = currentHandle
        if (h != 0L) {
            val raw = runCatching { nativeGetStatsString(h) }.getOrDefault("")
            Regex("""(?:^|\s)pacingModeId=(-?\d+)""").find(raw)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.let { id -> if (id >= 0) return PacingMode.fromId(id) }
            Regex("""(?:^|\s)pacingMode=([^\s]+)""").find(raw)
                ?.groupValues?.getOrNull(1)
                ?.let { mode ->
                    PacingMode.entries.firstOrNull { it.name == mode }?.let { return it }
                }
        }
        return PacingMode.fromId(runCatching { nativeGetPacingMode() }.getOrDefault(-1))
    }

    fun feedbackLooksUnsafeForPacer(): Boolean {
        if (!initialized) return false
        refreshRuntimeSnapshotFromNative()
        if (currentFeedbackEndpoint <= 0) return false
        return getFeedbackState() in setOf(FeedbackState.SUSPECT, FeedbackState.DEGRADED, FeedbackState.FAILED)
    }

    fun getStreamSessionId(): Long {
        val h = currentHandle
        if (h == 0L) return 0L
        return runCatching { nativeGetStreamSessionId(h) }.getOrDefault(0L)
    }

    fun computeRecommendedWriteChunkBytes(deviceBytesPerSecond: Long, frameBytes: Int, targetMs: Long = 32L): Int {
        val frame = frameBytes.coerceAtLeast(1)
        val timed = ((deviceBytesPerSecond.coerceAtLeast(1L) * targetMs) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val min = frame * 256
        val max = frame * 8192
        val bounded = timed.coerceIn(min, max)
        return bounded - (bounded % frame)
    }

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
    fun restartIsoTransfersSameProfile(reason: String): Boolean {
        val h = currentHandle
        if (h == 0L || !initialized) {
            AppLogger.w(
                TAG,
                "restartIsoTransfersSameProfile skipped: reason=$reason handle=0x${h.toString(16)} initialized=$initialized"
            )
            return false
        }
        AppLogger.w(TAG, "restartIsoTransfersSameProfile: reason=$reason handle=0x${h.toString(16)}")
        return try {
            val ok = nativeRestartIsoTransfersSameProfile(h)
            if (ok) {
                nativeSessionBroken = false
                refreshRuntimeSnapshotFromNative()
                AppLogger.i(TAG, "restartIsoTransfersSameProfile ok: reason=$reason session=${getStreamSessionId()}")
            } else {
                AppLogger.w(TAG, "restartIsoTransfersSameProfile native returned false: reason=$reason")
            }
            ok
        } catch (t: Throwable) {
            AppLogger.w(TAG, "restartIsoTransfersSameProfile failed: reason=$reason", t)
            false
        }
    }

    /** 轻量 flush，切歌用，可恢复（不设 sessionBroken） */
    fun flushForNextTrack(reason: String) {
        val h = currentHandle
        val now = SystemClock.elapsedRealtime()
        // Manual next already soft-flushed the native ring.  The subsequent
        // prepareForPlayback fast-reuse path may arrive tens of milliseconds
        // later; a second ring clear/generation edge can be audible.
        if (reason == "prepareForPlayback_fast_reuse_same_config" &&
            now - lastNextTrackFlushMs in 0..650 &&
            lastNextTrackFlushReason.contains("manual", ignoreCase = true)
        ) {
            AppLogger.w(TAG, "flushForNextTrack skipped duplicate fast-reuse flush: reason=$reason lastReason=$lastNextTrackFlushReason age=${now - lastNextTrackFlushMs}ms")
            Log.w(TAG, "flushForNextTrack skipped duplicate fast-reuse flush: reason=$reason lastReason=$lastNextTrackFlushReason age=${now - lastNextTrackFlushMs}ms")
            return
        }
        lastNextTrackFlushMs = now
        lastNextTrackFlushReason = reason
        AppLogger.i(TAG, "flushForNextTrack: reason=$reason handle=0x${h.toString(16)}")
        if (h != 0L) nativeFlushForNextTrack(h)
    }

    /** 重置 session 状态，新播放开始前调用（prefill 之前） */
    fun resetSessionForPlayback(reason: String) {
        val h = currentHandle
        AppLogger.i(TAG, "resetSessionForPlayback: reason=$reason handle=0x${h.toString(16)}")
        if (h != 0L) nativeResetSessionForPlayback(h)
    }

    /** 释放 AS interface 保留 fd，格式变化时调用 */
    fun closeStreamForReconfigure(reason: String) {
        val h = currentHandle
        AppLogger.i(TAG, "closeStreamForReconfigure: reason=$reason handle=0x${h.toString(16)}")
        if (h != 0L) nativeCloseStreamForReconfigure(h)
    }

    /** 进入 standby（暂停/后台/焦点丢失），释放 AS interface */
    fun enterStandby(reason: String) {
        val h = currentHandle
        AppLogger.i(TAG, "enterStandby: reason=$reason handle=0x${h.toString(16)}")
        if (h != 0L) nativeEnterStandby(h)
    }

    /** 从 standby 恢复，重新 claim AS interface */
    fun resumeFromStandby(reason: String): Boolean {
        val h = currentHandle
        AppLogger.i(TAG, "resumeFromStandby: reason=$reason handle=0x${h.toString(16)}")
        if (h == 0L) return false
        return nativeResumeFromStandby(h)
    }

    /** Hard stop，关闭独占/设备拔出用，设 sessionBroken */
    fun hardStopUsb(reason: String) {
        val h = currentHandle
        AppLogger.w(TAG, "hardStopUsb: reason=$reason handle=0x${h.toString(16)}", Throwable("hardStopUsb call stack"))
        if (h != 0L) nativeStopAndFlush(h)
    }

    fun isNativeSessionBroken(): Boolean {
        val h = currentHandle
        if (h == 0L) return true
        return nativeIsSessionBroken(h)
    }

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
        if (json.isNullOrBlank()) return cachedDeviceCapabilities
        val parsed = parseUsbCapabilitiesJson(json)
        if (parsed != null && parsed.formats.isNotEmpty()) {
            cachedDeviceCapabilities = mergeDeviceCapabilities(cachedDeviceCapabilities, parsed)
        }
        return parsed?.let { mergeDeviceCapabilities(cachedDeviceCapabilities, it) } ?: cachedDeviceCapabilities
    }

    private fun mergeDeviceCapabilities(
        previous: UsbDeviceAudioCapabilities?,
        latest: UsbDeviceAudioCapabilities
    ): UsbDeviceAudioCapabilities {
        if (previous == null) return latest
        if (previous.vendorId != latest.vendorId || previous.productId != latest.productId) {
            return latest
        }

        val mergedFormats = (previous.formats + latest.formats)
            .distinctBy {
                listOf(
                    it.sampleRate,
                    it.channels,
                    it.validBits,
                    it.subslotBytes,
                    it.interfaceNumber,
                    it.altSetting,
                    it.outEndpoint,
                    it.feedbackEndpoint,
                    it.isPcm,
                    it.isRawData,
                    it.outSync,
                    it.outUsage,
                    it.feedbackUsage
                ).joinToString("|")
            }

        val deviceName = if (latest.deviceName.isNotBlank()) latest.deviceName else previous.deviceName
        return UsbDeviceAudioCapabilities(
            deviceName = deviceName,
            vendorId = latest.vendorId,
            productId = latest.productId,
            formats = mergedFormats
        )
    }

    fun isInitialized(): Boolean = initialized

    fun resetBuffer() {
        val h = currentHandle
        if (h == 0L || !initialized) return
        nativeResetBuffer(h)
    }

    fun getRecommendedDelayUs(): Int {
        if (!initialized) return 5000
        return nativeGetRecommendedDelayUs()
    }

    fun getBufferUsedBytes(): Int {
        if (!initialized) return 0
        return try {
            nativeGetBufferUsedBytes()
        } catch (_: Throwable) {
            0
        }
    }

    private fun computeRuntimeBytesPerSecond(): Int {
        val sr = currentSampleRate.takeIf { it > 0 } ?: return 0
        val ch = currentChannels.takeIf { it > 0 } ?: return 0
        val subslot = currentSubslotSize.takeIf { it > 0 } ?: return 0
        return (sr.toLong() * ch.toLong() * subslot.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun getOutputBytesPerSecond(): Int {
        if (!initialized) return 0
        val nativeBps = try {
            nativeGetOutputBytesPerSecond()
        } catch (_: Throwable) {
            0
        }
        refreshRuntimeSnapshotFromNative()
        val computedBps = computeRuntimeBytesPerSecond()
        if (computedBps > 0) {
            if (nativeBps > 0 && nativeBps != computedBps) {
                AppLogger.w(
                    TAG,
                    "USB runtime BPS corrected: native=$nativeBps computed=$computedBps " +
                        "sr=$currentSampleRate ch=$currentChannels subslot=$currentSubslotSize"
                )
            }
            return computedBps
        }
        return nativeBps
    }

    fun getRuntimeFormat(): UsbRuntimeFormat {
        refreshRuntimeSnapshotFromNative()
        val frameBytesFromNative = try { nativeGetCurrentFrameBytes() } catch (_: Throwable) { 0 }
        val frameBytes = frameBytesFromNative.takeIf { it > 0 }
            ?: (currentChannels * currentSubslotSize).takeIf { it > 0 }
            ?: 0
        val bps = computeRuntimeBytesPerSecond().takeIf { it > 0 }
            ?: try { nativeGetOutputBytesPerSecond() } catch (_: Throwable) { 0 }
        return UsbRuntimeFormat(
            sampleRate = currentSampleRate,
            channels = currentChannels,
            validBits = currentBits,
            subslotBytes = currentSubslotSize,
            frameBytes = frameBytes,
            bytesPerSecond = bps,
            iface = currentInterfaceNumber,
            alt = currentAltSetting,
            outEndpoint = currentOutEndpoint,
            feedbackEndpoint = currentFeedbackEndpoint
        )
    }

    fun getOutputSampleRate(): Int {
        if (!initialized) return 0
        return try {
            nativeGetOutputSampleRate()
        } catch (_: Throwable) {
            0
        }
    }

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
