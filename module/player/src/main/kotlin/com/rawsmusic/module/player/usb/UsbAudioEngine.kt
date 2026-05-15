package com.rawsmusic.module.player.usb

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

object UsbAudioEngine {

    internal const val TAG = "UsbAudioEngine"

    const val ERR_NOT_INITIALIZED = -1001
    const val ERR_NOT_RUNNING = -1003
    const val ERR_TRANSPORT_LOST = -1004
    const val ERR_USB_IO = -1005
    const val ERR_START_FAILED = -1010

    init {
        try {
            System.loadLibrary("rawsmusic_usb")
            Log.d(TAG, "rawsmusic_usb library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load rawsmusic_usb", e)
        }
    }

    // ========== 4 个核心生命周期 external 方法 ==========

    external fun nativeInitUsbDevice(
        fd: Int,
        sampleRate: Int,
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

    // ========== 当前状态跟踪 ==========

    @Volatile
    var currentSampleRate = 0
    @Volatile
    var currentChannels = 0
    @Volatile
    var currentBits = 0
    @Volatile
    var currentInterfaceNumber = 0
    @Volatile
    var currentAltSetting = 0

    private val nativeHandleRef = AtomicLong(0L)

    val currentHandle: Long
        get() = nativeHandleRef.get()

    @Volatile
    var initialized = false
        private set

    @Volatile
    private var nativeSessionBroken = false

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
            Log.i(TAG, "closeNative ignored: already closed, reason=$reason")
            return
        }
        initialized = false
        Log.i(TAG, "closeNative: handle=0x${java.lang.Long.toUnsignedString(handle, 16)}, reason=$reason")
        try {
            nativeClose(handle)
        } catch (t: Throwable) {
            Log.e(TAG, "closeNative threw", t)
        }
        Log.i(TAG, "closeNative done: handle=0x${java.lang.Long.toUnsignedString(handle, 16)}")
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
        channels: Int,
        bitsPerSample: Int,
        iface: Int,
        alt: Int,
        outEndpoint: Int,
        feedbackEndpoint: Int,
        subslotSize: Int
    ): Long {
        // 先关闭旧 handle（如果有的话）
        closeNative("before_reinit")

        val handle = nativeInitUsbDevice(
            fd, sampleRate, channels, bitsPerSample,
            iface, alt, outEndpoint, feedbackEndpoint, subslotSize
        )

        if (handle == 0L) {
            Log.e(TAG, "nativeInitUsbDevice failed")
            return 0L
        }

        nativeHandleRef.set(handle)
        initialized = true
        nativeSessionBroken = false
        currentSampleRate = sampleRate
        currentChannels = channels
        currentBits = bitsPerSample
        currentInterfaceNumber = iface
        currentAltSetting = alt
        Log.i(TAG, "initWithHandle ok: handle=0x${java.lang.Long.toUnsignedString(handle, 16)} sr=$sampleRate bits=$bitsPerSample ch=$channels iface=$iface alt=$alt")
        return handle
    }

    fun start(): Boolean {
        val h = nativeHandleRef.get()
        if (h == 0L || !initialized) {
            Log.e(TAG, "Cannot start: not initialized, handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized")
            return false
        }
        if (nativeSessionBroken) {
            Log.e(TAG, "start denied: native session broken, full reopen required")
            return false
        }
        var result = nativeStart(h)
        if (!result) {
            // 参考实例模式：start 失败后先 stopAndFlush 再重试一次
            Log.w(TAG, "nativeStart failed, attempting stopAndFlush + retry")
            nativeStopAndFlush(h)
            result = nativeStart(h)
            if (result) {
                Log.i(TAG, "Streaming started after retry")
            } else {
                Log.e(TAG, "nativeStart failed after retry, marking session broken")
                nativeSessionBroken = true
            }
        } else {
            Log.i(TAG, "Streaming started")
        }
        return result
    }

    fun stop() {
        val h = nativeHandleRef.get()
        if (h == 0L || !initialized) {
            Log.i(TAG, "stop() ignored: handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized")
            return
        }
        Log.i(TAG, "stop() calling nativeStopAndFlush, handle=0x${java.lang.Long.toUnsignedString(h, 16)}")
        nativeStopAndFlush(h)
        Log.i(TAG, "Streaming stopped and buffer flushed")
    }

    fun pause() {
        val h = nativeHandleRef.get()
        if (h == 0L || !initialized) {
            Log.i(TAG, "pause() ignored: handle=0x${java.lang.Long.toUnsignedString(h, 16)} initialized=$initialized")
            return
        }
        Log.i(TAG, "pause() calling nativePause, handle=0x${java.lang.Long.toUnsignedString(h, 16)}")
        nativePause(h)
        Log.i(TAG, "Streaming paused (buffer preserved)")
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

    fun getPacketSize(): Int {
        if (!initialized) return 0
        return nativeGetPacketSize()
    }

    fun setSampleRate(sampleRate: Int): Boolean {
        if (!initialized) return false
        if (nativeSessionBroken) {
            Log.e(TAG, "setSampleRate denied: native session broken")
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
        return nativeSetVolume(h, v)
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

    /** 改进后的 write（通过 handle 访问，完全摆脱全局 ctx）
     * 1. 检测 acceptingWrites、streaming、错误码
     * 2. nativeWriteHandle 返回 0 时用 nativeGetRecommendedDelayUs() 自适应 throttling
     * 3. 收到 -EPIPE/ERR_NOT_RUNNING/ERR_TRANSPORT_LOST 立刻退出写线程 */
    fun write(data: ByteArray, offset: Int, length: Int): Int {
        val h = currentHandle
        if (h == 0L || !initialized) return ERR_NOT_INITIALIZED
        if (nativeSessionBroken) {
            Log.w(TAG, "write denied: native session broken")
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
                    val delayUs = getRecommendedDelayUs()
                    if (delayUs > 0) {
                        Thread.sleep(delayUs / 1000L, (delayUs % 1000L).toInt())
                    } else {
                        Thread.yield()
                    }
                }
                rc == -32 || rc == ERR_NOT_RUNNING -> {
                    Log.w(TAG, "writer thread exiting, nativeWriteHandle returned $rc")
                    return total
                }
                rc == ERR_TRANSPORT_LOST -> {
                    Log.e(TAG, "USB transport lost from nativeWriteHandle")
                    return rc
                }
                rc < 0 -> {
                    Log.e(TAG, "nativeWriteHandle error: $rc")
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
            Log.e(TAG, "nativeWriteHandle threw", t)
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
        currentInterfaceNumber = 0
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

    /** 设置 USB DAC 高级选项（参考 Neutron Player） */
    fun setUsbDacSettings(noControlIface: Boolean, forceUac1: Boolean, linearVolume: Boolean, replaceVolume: Boolean, force1ms: Boolean) {
        nativeSetUsbDacSettings(noControlIface, forceUac1, linearVolume, replaceVolume, force1ms)
    }

    fun getPlaybackModeName(): String {
        return try {
            val mode = nativeGetPlaybackMode()
            when (mode) {
                0 -> "SafeSoftwareVolume"
                1 -> "ExclusiveSoftwareVolume"
                2 -> "ExclusiveBitPerfectHwVol"
                3 -> "ExclusiveBitPerfectFixed"
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
    external fun nativeSetVolume(handle: Long, volume: Float): Int
    external fun nativeRequiresReinit(): Boolean
    external fun nativeOnUsbDetached()
    external fun nativeSetUsbExclusiveActive(active: Boolean)
    external fun nativeSetPolicy(exclusive: Boolean, bitPerfect: Boolean, hwVol: Boolean)
    external fun nativeSetUsbDacSettings(noControlIface: Boolean, forceUac1: Boolean, linearVolume: Boolean, replaceVolume: Boolean, force1ms: Boolean)
    external fun nativeResetUsbPolicyForNewDevice()
    external fun nativeCanControlVolume(handle: Long): Boolean
    external fun nativeGetVolumeDb(handle: Long): Float
    external fun nativeValidateHardwareVolume(handle: Long): Int
    external fun nativeIsHardwareVolumeSafe(): Boolean
    external fun nativeRepairHardwareVolumeBalance(safeVolume: Float): Int
    external fun nativeGetPlaybackMode(): Int

    /** 安全调用 JNI 方法，捕获异常防止崩溃 */
    fun <R> safeCall(tag: String, block: () -> R): R? {
        return try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "JNI call $tag threw", t)
            null
        }
    }

    // ========== 内部 JNI 方法 ==========

    private external fun nativeIsActive(): Boolean
    private external fun nativeGetPacketSize(): Int
    private external fun nativeSetSampleRate(sampleRate: Int): Int
    private external fun nativeResetBuffer(handle: Long)
}
