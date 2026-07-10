package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import androidx.annotation.Keep
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.usb.UsbAudioEngine
import kotlin.math.max

/**
 * Android 系统音频身份层。
 *
 * USB 独占真实声音仍然只走 native USB UAC2；这一层只负责让 AudioFlinger / AudioPolicy /
 * 厂商音频管控持续看到一个合法的 USAGE_MEDIA / STREAM_MUSIC AudioTrack。
 *
 * v6u 修正：
 * - native ABI 固定为 createTrack/startTrack/writeTrack/destroyTrack/log。
 * - v6v: identity 线程不再重复 pump USB events；USB event loop 由 UsbAudioEngine USB 线程独占驱动。
 * - AudioTrack 必须避开 USB DAC 路由，防止抢占 native libusb claim。
 * - nativeStart 抛异常时只禁用 identity，不拖死播放主流程。
 */
internal class AndroidAudioIdentityTrack(context: Context) {
    private val appContext = context.applicationContext
    private val callback = IdentityCallback(this)
    private val lock = Any()

    private var audioTrack: AudioTrack? = null
    private var writeBuffer = ByteArray(0)
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channelCount = DEFAULT_CHANNEL_COUNT
    private var bitsPerSample = DEFAULT_BITS_PER_SAMPLE
    private var bytesPerFrame = DEFAULT_CHANNEL_COUNT * (DEFAULT_BITS_PER_SAMPLE / 8)
    private var startReason = ""
    private var running = false
    private var lastHeartbeatElapsedMs = 0L
    private var totalWrittenBytes = 0L
    private var writeErrors = 0L
    private var zeroWriteStreak = 0
    private var lastDisabledLogElapsedMs = 0L

    fun start(reason: String) {
        if (!ANDROID_AUDIO_IDENTITY_TRACK_ENABLED) {
            val shouldLog = synchronized(lock) {
                running = false
                startReason = ""
                val now = SystemClock.elapsedRealtime()
                val log = now - lastDisabledLogElapsedMs > DISABLED_LOG_INTERVAL_MS
                if (log) lastDisabledLogElapsedMs = now
                log
            }
            if (AndroidAudioIdentityNativeBridge.nativeIsRunning()) {
                AndroidAudioIdentityNativeBridge.nativeStop("disabled_no_external_speaker:$reason")
            }
            if (shouldLog) {
                AppLogger.i(TAG, "Android audio identity AudioTrack disabled: reason=$reason; USB zero-fill/MediaSession handle keepalive, avoid speaker fallback")
            }
            return
        }

        synchronized(lock) {
            startReason = reason
            if (running && AndroidAudioIdentityNativeBridge.nativeIsRunning()) {
                AndroidAudioIdentityNativeBridge.nativePulse(reason)
                return
            }
            running = true
            lastHeartbeatElapsedMs = 0L
            totalWrittenBytes = 0L
            writeErrors = 0L
            zeroWriteStreak = 0
        }

        val ok = runCatching {
            AndroidAudioIdentityNativeBridge.nativeStart(
                callback = callback,
                sampleRate = DEFAULT_SAMPLE_RATE,
                channelCount = DEFAULT_CHANNEL_COUNT,
                bitsPerSample = DEFAULT_BITS_PER_SAMPLE,
                framesPerTick = DEFAULT_FRAMES_PER_TICK,
                reason = reason
            )
        }.getOrElse { throwable ->
            AppLogger.e(TAG, "Android audio identity nativeStart threw; identity disabled for this session", throwable)
            false
        }

        if (!ok) {
            synchronized(lock) { running = false }
            AppLogger.w(TAG, "Android audio identity native start failed: reason=$reason")
        } else {
            AppLogger.i(TAG, "Android audio identity native started: reason=$reason")
        }
    }

    fun stop(reason: String) {
        val shouldStop = synchronized(lock) {
            val active = running || audioTrack != null || AndroidAudioIdentityNativeBridge.nativeIsRunning()
            running = false
            startReason = ""
            active
        }
        if (!shouldStop) return
        AndroidAudioIdentityNativeBridge.nativeStop(reason)
    }

    fun pause(reason: String) {
        AppLogger.i(TAG, "Android audio identity pause requested: reason=$reason")
        synchronized(lock) {
            runCatching { audioTrack?.pause() }
        }
    }

    fun resume(reason: String) {
        AppLogger.i(TAG, "Android audio identity resume requested: reason=$reason")
        synchronized(lock) {
            runCatching { audioTrack?.play() }
        }
        AndroidAudioIdentityNativeBridge.nativePulse("resume:$reason")
    }

    fun isRunning(): Boolean = synchronized(lock) {
        running && AndroidAudioIdentityNativeBridge.nativeIsRunning()
    }

    fun statsString(): String = AndroidAudioIdentityNativeBridge.nativeStats()

    private fun createAudioTrack(
        requestedSampleRate: Int,
        requestedChannelCount: Int,
        requestedBitsPerSample: Int,
        requestedBufferSizeBytes: Int
    ): Boolean = synchronized(lock) {
        destroyAudioTrackLocked("recreate")

        sampleRate = requestedSampleRate.coerceIn(8_000, 384_000)
        channelCount = requestedChannelCount.coerceIn(1, 2)
        bitsPerSample = if (requestedBitsPerSample == 16) 16 else DEFAULT_BITS_PER_SAMPLE
        bytesPerFrame = channelCount * (bitsPerSample / 8)

        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            AppLogger.w(TAG, "Android audio identity unavailable: minBuffer=$minBuffer sr=$sampleRate ch=$channelCount")
            return@synchronized false
        }

        val halfSecondBytes = (sampleRate * bytesPerFrame / 2).coerceAtLeast(16)
        val bufferSize = maxOf(
            requestedBufferSizeBytes,
            minBuffer * 4,
            halfSecondBytes,
            DEFAULT_WRITE_BUFFER_BYTES
        )
        writeBuffer = ByteArray(max(bufferSize, DEFAULT_WRITE_BUFFER_BYTES))
        fillIdentityBuffer(writeBuffer)

        val created = runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelMask,
                    encoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
        }.onFailure {
            AppLogger.w(TAG, "Android audio identity create AudioTrack failed", it)
        }.getOrNull()

        if (created == null || created.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { created?.release() }
            AppLogger.w(TAG, "Android audio identity AudioTrack not initialized: sr=$sampleRate ch=$channelCount buffer=$bufferSize")
            return@synchronized false
        }

        val routeResult = routeIdentityTrackAwayFromUsbLocked(created)
        if (routeResult.startsWith("blocked")) {
            runCatching { created.release() }
            AppLogger.w(TAG, "Android audio identity disabled to avoid stealing USB route: $routeResult")
            return@synchronized false
        }

        audioTrack = created
        runCatching { created.setVolume(IDENTITY_OUTPUT_GAIN) }
        AppLogger.i(
            TAG,
            "Android audio identity AudioTrack created: sr=$sampleRate ch=$channelCount bits=$bitsPerSample buffer=$bufferSize route=$routeResult reason=$startReason"
        )
        true
    }

    private fun routeIdentityTrackAwayFromUsbLocked(track: AudioTrack): String {
        if (Build.VERSION.SDK_INT < 23) {
            return "blocked:api<23-no-preferred-device"
        }

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "blocked:no-audio-manager"

        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrElse {
            AppLogger.w(TAG, "Android audio identity failed to query output devices", it)
            emptyList()
        }

        fun AudioDeviceInfo.isUsbLike(): Boolean {
            return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                productName?.contains("usb", ignoreCase = true) == true
        }

        fun AudioDeviceInfo.isBluetoothLike(): Boolean {
            return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        fun AudioDeviceInfo.isBuiltinSpeaker(): Boolean {
            return type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        fun AudioDeviceInfo.isBuiltinEarpiece(): Boolean {
            return type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }

        // v7c: 后台保活仍然需要 Android 音频身份轨。v7b 完全禁用身份轨后，
        // ColorOS/OPlus 在后台会再次停止/降级 USB 独占播放。这里恢复 UAPP 式
        // "在 Android 音频上播放静音"：AudioTrack 可以路由到内置扬声器/听筒，
        // 但 buffer 永远填 0，且不再使用 19kHz carrier，因此不会外放真实声音。
        // 仍然严禁路由到 USB DAC 或蓝牙，避免抢占 native USB 独占链路。
        val preferred = outputs.firstOrNull { !it.isUsbLike() && !it.isBluetoothLike() && it.isBuiltinSpeaker() }
            ?: outputs.firstOrNull { !it.isUsbLike() && !it.isBluetoothLike() && it.isBuiltinEarpiece() }
            ?: outputs.firstOrNull { !it.isUsbLike() && !it.isBluetoothLike() }

        if (preferred == null) {
            return "blocked:no-non-usb-silent-output outputs=${outputs.joinToString { "${it.type}:${it.productName}" }}"
        }
        if (preferred.isUsbLike() || preferred.isBluetoothLike()) {
            return "blocked:selected-usb-or-bt-output type=${preferred.type} name=${preferred.productName}"
        }

        val ok = runCatching { track.setPreferredDevice(preferred) }
            .onFailure { AppLogger.w(TAG, "Android audio identity setPreferredDevice failed", it) }
            .getOrDefault(false)

        return if (ok) {
            "preferred type=${preferred.type} name=${preferred.productName}"
        } else {
            "blocked:setPreferredDevice-failed type=${preferred.type} name=${preferred.productName}"
        }
    }

    private fun startAudioTrack() = synchronized(lock) {
        val track = audioTrack ?: return@synchronized
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            primeAudioTrackLocked(track)
        }.onFailure {
            writeErrors++
            AppLogger.w(TAG, "Android audio identity AudioTrack start failed", it)
        }
    }

    private fun primeAudioTrackLocked(track: AudioTrack) {
        if (writeBuffer.isEmpty()) return
        // 先非阻塞预填一小段静音数据，保持 AudioFlinger / AudioPolicy 的媒体播放身份。
        val primeBytes = minOf(writeBuffer.size, max(DEFAULT_WRITE_BUFFER_BYTES, sampleRate * bytesPerFrame / 4))
        var offset = 0
        while (offset < primeBytes) {
            val chunk = minOf(DEFAULT_WRITE_CHUNK_BYTES, primeBytes - offset)
            val written = if (Build.VERSION.SDK_INT >= 23) {
                track.write(writeBuffer, offset, chunk, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                track.write(writeBuffer, offset, chunk)
            }
            if (written <= 0) break
            offset += written
        }
        if (offset > 0) {
            totalWrittenBytes += offset.toLong()
            AppLogger.i(TAG, "Android audio identity primed: bytes=$offset")
        }
    }

    private fun writeIdentityBytes(byteCount: Int): Int = synchronized(lock) {
        val track = audioTrack ?: return@synchronized -1
        if (byteCount <= 0) return@synchronized 0
        val writeBytes = byteCount.coerceAtMost(writeBuffer.size)
        return@synchronized try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }

            // v6y: 后台保活身份轨不能用 WRITE_BLOCKING。
            // ColorOS/Hans 在后台可能让 AudioTrack sink 暂停消费，blocking write 会卡住
            // AndroidAudioIdentity native worker 十几秒；日志表现为 identity heartbeat 和 USB event loop
            // 同时出现大 gap。这里改为非阻塞写，AudioTrack 没空间时不阻塞进程。
            val written = if (Build.VERSION.SDK_INT >= 23) {
                track.write(writeBuffer, 0, writeBytes, AudioTrack.WRITE_NON_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                track.write(writeBuffer, 0, writeBytes)
            }

            when {
                written > 0 -> {
                    zeroWriteStreak = 0
                    totalWrittenBytes += written.toLong()
                    maybeLogHeartbeatLocked(track, written)
                    written
                }
                written == 0 -> {
                    zeroWriteStreak++
                    if (zeroWriteStreak == ZERO_WRITE_WARN_STREAK) {
                        AppLogger.w(TAG, "Android audio identity non-blocking write has no sink space: streak=$zeroWriteStreak")
                    }
                    if (zeroWriteStreak >= ZERO_WRITE_REPAIR_STREAK) {
                        writeErrors++
                        zeroWriteStreak = 0
                        AppLogger.w(TAG, "Android audio identity sink appears stalled; request native repair")
                        -1
                    } else {
                        // 0 代表 AudioTrack 暂时满，不是错误；返回 1 防止 native 立即 repair 风暴。
                        1
                    }
                }
                else -> {
                    writeErrors++
                    zeroWriteStreak = 0
                    written
                }
            }
        } catch (t: Throwable) {
            writeErrors++
            zeroWriteStreak = 0
            AppLogger.w(TAG, "Android audio identity write failed", t)
            -1
        }
    }

    private fun pumpUsbEvents(): Int {
        // 保留给旧 native ABI 兜底；v6v 起 raw_android_audio_identity.cpp 不再调用它。
        return runCatching { UsbAudioEngine.nativePumpUsbEventsFromKeepAlive() }
            .getOrElse {
                AppLogger.w(TAG, "Android audio identity USB pump failed", it)
                -1
            }
    }

    private fun destroyAudioTrack(reason: String) = synchronized(lock) {
        destroyAudioTrackLocked(reason)
    }

    private fun destroyAudioTrackLocked(reason: String) {
        val old = audioTrack ?: return
        audioTrack = null
        runCatching { old.pause() }
        runCatching { old.flush() }
        runCatching { old.release() }
        AppLogger.i(TAG, "Android audio identity AudioTrack destroyed: reason=$reason")
    }

    private fun fillIdentityBuffer(buffer: ByteArray) {
        // v7c: 保留 Android AudioTrack 媒体身份，但永远只写全 0 PCM。
        // 这对应 UAPP 的"在 Android 音频上播放静音"，不是 v6y 的 19kHz carrier。
        // 因此即使系统把这条身份轨路由到内置扬声器，也不会外放；真实声音只走 native USB。
        buffer.fill(0)
    }

    private fun maybeLogHeartbeatLocked(track: AudioTrack, written: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeartbeatElapsedMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatElapsedMs = now
        AppLogger.i(
            TAG,
            "Android audio identity heartbeat: playState=${track.playState} written=$written total=$totalWrittenBytes errors=$writeErrors stats=${AndroidAudioIdentityNativeBridge.nativeStats()}"
        )
    }

    @Keep
    private class IdentityCallback(private val owner: AndroidAudioIdentityTrack) {
        @Keep
        @Suppress("unused")
        fun createTrack(sampleRate: Int, channelCount: Int, bitsPerSample: Int, bufferSizeBytes: Int): Boolean =
            owner.createAudioTrack(sampleRate, channelCount, bitsPerSample, bufferSizeBytes)

        @Keep
        @Suppress("unused")
        fun startTrack() = owner.startAudioTrack()

        @Keep
        @Suppress("unused")
        fun writeTrack(byteCount: Int): Int = owner.writeIdentityBytes(byteCount)

        @Keep
        @Suppress("unused")
        fun pumpUsbEvents(): Int = owner.pumpUsbEvents()

        @Keep
        @Suppress("unused")
        fun destroyTrack() = owner.destroyAudioTrack("native_destroy")

        @Keep
        @Suppress("unused")
        fun log(level: Int, message: String) {
            when {
                level >= 6 -> AppLogger.e(TAG, message)
                level >= 5 -> AppLogger.w(TAG, message)
                else -> AppLogger.i(TAG, message)
            }
        }
    }

    private companion object {
        private const val TAG = "AndroidAudioIdentity"
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_CHANNEL_COUNT = 2
        private const val DEFAULT_BITS_PER_SAMPLE = 16
        private const val DEFAULT_FRAMES_PER_TICK = 384 // 8 ms @ 48 kHz
        private const val DEFAULT_WRITE_BUFFER_BYTES = 64 * 1024
        private const val DEFAULT_WRITE_CHUNK_BYTES = 4096
        private const val ZERO_WRITE_WARN_STREAK = 125
        private const val ZERO_WRITE_REPAIR_STREAK = 750
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        // v7c: 不能完全关闭 Android AudioTrack 身份轨，否则后台保活会退化。
        // 恢复身份轨，但只写全 0 PCM，禁止任何 carrier/非零样本；真实声音仍只走 USB。
        private const val ANDROID_AUDIO_IDENTITY_TRACK_ENABLED = true
        private const val IDENTITY_OUTPUT_GAIN = 1.0f
        private const val DISABLED_LOG_INTERVAL_MS = 30_000L
    }
}

@Keep
internal object AndroidAudioIdentityNativeBridge {
    init {
        runCatching { System.loadLibrary("rawsmusic_usb") }
    }

    @Keep
    external fun nativeStart(
        callback: Any,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        framesPerTick: Int,
        reason: String
    ): Boolean

    @Keep
    external fun nativeStop(reason: String)
    @Keep
    external fun nativePulse(reason: String): Boolean
    @Keep
    external fun nativeIsRunning(): Boolean
    @Keep
    external fun nativeStats(): String
}
