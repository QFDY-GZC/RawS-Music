package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * 音频输出管理器
 *
 * 负责管理三种音频输出模式：
 * - OpenSL ES：传统输出，兼容性最好（Android 4.1+）
 * - AAudio：低延迟输出（Android 8.1+），自动回退到 OpenSL ES
 * - Direct HiRes：绕过系统混音器，直接输出高采样率 PCM（需有线/USB设备）
 */
object AudioOutputManager {

    private const val TAG = "AudioOutputManager"

    /** 常用采样率列表 */
    val STANDARD_SAMPLE_RATES = intArrayOf(
        44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
    )

    /** 采样率的显示名称 */
    val SAMPLE_RATE_LABELS = mapOf(
        0 to "自动",
        44100 to "44.1 kHz",
        48000 to "48 kHz",
        88200 to "88.2 kHz",
        96000 to "96 kHz",
        176400 to "176.4 kHz",
        192000 to "192 kHz",
        352800 to "352.8 kHz",
        384000 to "384 kHz"
    )

    /** 比特深度的显示名称 */
    val BIT_DEPTH_LABELS = mapOf(
        0 to "自动",
        16 to "16 bit",
        24 to "24 bit",
        32 to "32 bit"
    )

    /**
     * 获取当前输出模式
     */
    fun getCurrentOutputMode(context: Context): AudioOutputMode {
        val storedMode = AppPreferences.Player.audioOutputMode
        Log.d(TAG, "Stored mode: $storedMode")

        var result = storedMode
        if (result == AudioOutputMode.AAUDIO && Build.VERSION.SDK_INT < 27) {
            Log.d(TAG, "AAudio requires API 27+ (current ${Build.VERSION.SDK_INT}), fallback to OPENSL_ES")
            result = AudioOutputMode.OPENSL_ES
        }
        if (result == AudioOutputMode.DIRECT && !isDirectOutputAvailable(context)) {
            Log.d(TAG, "DIRECT requested but not available, fallback to AAUDIO")
            result = AudioOutputMode.AAUDIO
        }
        Log.d(TAG, "Final mode: $result")
        return result
    }

    /**
     * 检测 Direct HiRes 输出是否可用
     * 条件：Android 8+ 且有输出设备（包括扬声器，方便测试）
     */
    fun isDirectOutputAvailable(context: Context? = null): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            Log.d(TAG, "API ${Build.VERSION.SDK_INT} < 26, Direct not supported")
            return false
        }
        context ?: run {
            Log.d(TAG, "Context null, Direct not available")
            return false
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Log.d(TAG, "AudioManager null, Direct not available")
            return false
        }
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val deviceNames = devices.map { getDeviceTypeName(it.type) }
        Log.d(TAG, "Output devices (${"${devices.size}"}): $deviceNames")

        // Android 13 及以下，蓝牙连接时禁用 Direct（避免音频路由混乱）
        if (Build.VERSION.SDK_INT <= 33) {
            val hasBluetooth = devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (hasBluetooth) {
                Log.d(TAG, "API <= 33 and Bluetooth connected, Direct not available")
                return false
            }
        }
        // Android 14+ 或无蓝牙，允许 Direct
        Log.d(TAG, "Direct available: true")
        return true
    }

    /**
     * 获取设备支持的所有可用采样率
     */
    fun getAvailableSampleRates(context: Context): List<Int> {
        val rates = mutableListOf<Int>()
        // 使用 16bit 探测采样率，确保所有设备（含蓝牙）都能正确检测
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        for (rate in STANDARD_SAMPLE_RATES) {
            val bufferSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
            if (bufferSize > 0) {
                rates.add(rate)
            }
        }
        return rates
    }

    /**
     * 暴力探测设备实际支持的最高采样率和最佳编码。
     *
     * 不依赖 getMinBufferSize / getAvailableSampleRates 判断是否支持，
     * 直接创建 AudioTrack，用 getState() == STATE_INITIALIZED 验证。
     * getMinBufferSize 仅用于计算缓冲区大小。
     *
     * 策略：
     * 1. 优先尝试用户设定的采样率，从高编码到低编码（FLOAT → 24BIT → 16BIT）
     * 2. 若用户设定采样率完全不工作，按降序依次尝试其他标准采样率
     * 3. 找到可用的高采样率即停止
     *
     * @return Pair(采样率, AudioFormat.ENCODING_*) — 若完全失败返回 Pair(44100, ENCODING_PCM_16BIT)
     */
    fun probeRateAndEncoding(preferredRate: Int, channelConfig: Int, context: Context): Pair<Int, Int> {
        // 获取与实际播放相同的首选设备，确保探测路径和播放路径一致
        val preferredDevice = getPreferredDeviceForDirect(context)

        // 构建尝试顺序：用户设定优先，然后从高到低
        val tryRates = mutableListOf<Int>()
        if (preferredRate > 0) tryRates.add(preferredRate)
        // 按降序添加其他标准采样率（排除已添加的）
        for (r in STANDARD_SAMPLE_RATES.reversedArray()) {
            if (r != preferredRate) tryRates.add(r)
        }

        // 候选编码（从高到低）
        val encodingCandidates = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 26) {
            encodingCandidates.add(AudioFormat.ENCODING_PCM_FLOAT)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val enc24 = AudioFormat::class.java.getField("ENCODING_PCM_24BIT").getInt(null)
                encodingCandidates.add(enc24)
            } catch (_: Exception) {}
        }
        encodingCandidates.add(AudioFormat.ENCODING_PCM_16BIT)

        // 先用 getMinBufferSize 快速过滤：框架直接拒绝的采样率不必尝试
        // 对于 getMinBufferSize 返回负值的，仍尝试创建（可能框架不报告但硬件支持）
        val frameworkRates = mutableListOf<Int>()
        val unreportedRates = mutableListOf<Int>()
        for (rate in tryRates) {
            val minBuf = AudioTrack.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf > 0) {
                frameworkRates.add(rate)
            } else {
                unreportedRates.add(rate)
            }
        }

        // 优先尝试框架报告支持的采样率
        for (rate in frameworkRates) {
            for (encoding in encodingCandidates) {
                val encName = encodingName(encoding)
                if (tryCreateAudioTrack(rate, encoding, channelConfig, preferredDevice)) {
                    Log.i(TAG, "probeRateAndEncoding: VERIFIED rate=$rate encoding=$encName (framework-reported, device=${preferredDevice?.productName})")
                    return Pair(rate, encoding)
                }
            }
        }

        // 再尝试框架未报告但可能硬件支持的采样率
        for (rate in unreportedRates) {
            for (encoding in encodingCandidates) {
                val encName = encodingName(encoding)
                if (tryCreateAudioTrack(rate, encoding, channelConfig, preferredDevice)) {
                    Log.i(TAG, "probeRateAndEncoding: VERIFIED rate=$rate encoding=$encName (unreported-but-working, device=${preferredDevice?.productName})")
                    return Pair(rate, encoding)
                }
            }
            Log.d(TAG, "probeRateAndEncoding: rate=$rate rejected by framework, skipping")
        }

        Log.e(TAG, "probeRateAndEncoding: all rates failed, fallback to 44100/16BIT")
        return Pair(44100, AudioFormat.ENCODING_PCM_16BIT)
    }

    /**
     * 直接创建 AudioTrack 验证采样率+编码组合是否可用。
     * 不用 getMinBufferSize 判断"是否支持"，仅用于计算缓冲区大小。
     * 如果设备硬件不支持，AudioTrack.Builder 会返回非 INITIALIZED 状态或抛异常。
     */
    private fun tryCreateAudioTrack(sampleRate: Int, encoding: Int, channelConfig: Int,
                                     preferredDevice: AudioDeviceInfo? = null): Boolean {
        val encName = encodingName(encoding)
        try {
            // getMinBufferSize 仅用于缓冲区大小计算，不判断支持性
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufSize = if (minBufSize > 0) {
                minBufSize
            } else {
                // 估算：100ms 的数据量
                val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_16BIT) 4 else 8
                sampleRate / 10 * bytesPerFrame
            }

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build()
            val attrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // 设置与实际播放相同的首选设备，确保探测路径和播放路径一致
            if (preferredDevice != null && Build.VERSION.SDK_INT >= 23) {
                track.preferredDevice = preferredDevice
            }

            // play + write 验证：仅 STATE_INITIALIZED 不够，某些设备初始化成功但写入失败
            track.play()
            val frameSize = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                val chCount = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
                chCount * 2
            } else {
                val chCount = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
                chCount * 4
            }
            val testSize = (frameSize * 256).coerceIn(512, 4096)
            val silence = ByteArray(testSize)
            val writeResult = track.write(silence, 0, testSize)

            track.release()

            if (writeResult > 0) {
                Log.d(TAG, "tryCreateAudioTrack(${sampleRate}Hz/$encName): OK (wrote $writeResult bytes, device=${preferredDevice?.productName})")
                return true
            }
            Log.d(TAG, "tryCreateAudioTrack(${sampleRate}Hz/$encName): write=$writeResult (device=${preferredDevice?.productName})")
            return false
        } catch (e: IllegalArgumentException) {
            // 框架直接拒绝此采样率/编码组合（如 "Invalid sample rate 384000"）
            Log.d(TAG, "tryCreateAudioTrack(${sampleRate}Hz/$encName): framework rejected - ${e.message}")
            return false
        } catch (e: Exception) {
            Log.d(TAG, "tryCreateAudioTrack(${sampleRate}Hz/$encName): exception=${e.message}")
            return false
        }
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
        AudioFormat.ENCODING_PCM_16BIT -> "16BIT"
        else -> {
            try {
                if (Build.VERSION.SDK_INT >= 31 &&
                    encoding == AudioFormat::class.java.getField("ENCODING_PCM_24BIT").getInt(null))
                    "24BIT" else "unknown($encoding)"
            } catch (_: Exception) { "unknown($encoding)" }
        }
    }

    /**
     * 将 AudioFormat 编码映射为 FFmpeg 输出的比特深度
     * FLOAT → 32 (IEEE float WAV), 24BIT → 24 (packed s24le WAV), 16BIT → 16
     */
    fun encodingToFFmpegBits(encoding: Int): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            AudioFormat.ENCODING_PCM_16BIT -> 16
            else -> {
                // 24BIT
                try {
                    if (Build.VERSION.SDK_INT >= 31 &&
                        encoding == AudioFormat::class.java.getField("ENCODING_PCM_24BIT").getInt(null))
                        return 24
                } catch (_: Exception) {}
                16
            }
        }
    }

    /**
     * 获取当前目标采样率
     */
    fun getTargetSampleRate(): Int = AppPreferences.Player.targetSampleRate

    /**
     * 设置目标采样率
     */
    fun setTargetSampleRate(rate: Int) {
        AppPreferences.Player.targetSampleRate = rate
    }

    /**
     * 获取目标比特深度
     */
    fun getTargetBitDepth(): Int = AppPreferences.Player.targetBitDepth

    /**
     * 设置目标比特深度
     */
    fun setTargetBitDepth(depth: Int) {
        AppPreferences.Player.targetBitDepth = depth
    }

    /**
     * 设置输出模式
     */
    fun setOutputMode(mode: AudioOutputMode) {
        AppPreferences.Player.audioOutputMode = mode
    }

    /**
     * 构建 AudioAttributes，根据输出模式选择不同策略
     */
    fun buildAudioAttributes(context: Context): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)

        when (getCurrentOutputMode(context)) {
            AudioOutputMode.DIRECT -> {
                // Direct 模式：禁止其他应用截获音频，保持纯净输出
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        builder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    } catch (_: Exception) {}
                }
            }
            AudioOutputMode.AAUDIO -> {
                // AAudio 低延迟模式
            }
            AudioOutputMode.OPENSL_ES -> {
                // OpenSL ES 传统模式
            }
        }
        return builder.build()
    }

    /**
     * 获取目标音频编码格式（根据比特深度设置）
     */
    fun getTargetEncoding(): Int {
        val depth = getTargetBitDepth()
        return when {
            depth >= 32 && Build.VERSION.SDK_INT >= 26 -> AudioFormat.ENCODING_PCM_FLOAT
            depth >= 24 && Build.VERSION.SDK_INT >= 31 -> {
                // Android 12+ 支持 ENCODING_PCM_24BIT
                try {
                    AudioFormat::class.java.getField("ENCODING_PCM_24BIT").getInt(null)
                } catch (_: Exception) {
                    if (Build.VERSION.SDK_INT >= 26) AudioFormat.ENCODING_PCM_FLOAT
                    else AudioFormat.ENCODING_PCM_16BIT
                }
            }
            depth == 16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> {
                // 自动：优先使用更高精度
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioFormat.ENCODING_PCM_FLOAT
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            }
        }
    }

    /**
     * 获取输出模式的显示名称
     */
    fun getOutputModeLabel(mode: AudioOutputMode): String {
        return when (mode) {
            AudioOutputMode.OPENSL_ES -> "OpenSL ES"
            AudioOutputMode.AAUDIO -> "AAudio"
            AudioOutputMode.DIRECT -> "Direct (HiRes)"
        }
    }

    /**
     * 获取输出模式的描述
     */
    fun getOutputModeDescription(mode: AudioOutputMode): String {
        return when (mode) {
            AudioOutputMode.OPENSL_ES -> "传统输出，兼容性最佳"
            AudioOutputMode.AAUDIO -> "低延迟输出，推荐 (Android 8.1+)"
            AudioOutputMode.DIRECT -> "绕过系统混音，高采样率直出"
        }
    }

    /**
     * 获取输出模式是否可用
     */
    fun isOutputModeAvailable(mode: AudioOutputMode, context: Context? = null): Boolean {
        return when (mode) {
            AudioOutputMode.OPENSL_ES -> true
            AudioOutputMode.AAUDIO -> Build.VERSION.SDK_INT >= 27
            AudioOutputMode.DIRECT -> isDirectOutputAvailable(context)
        }
    }

    /**
     * 获取当前输出设备名称
     */
    fun getCurrentOutputDeviceName(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "Unknown"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) return "None"

        // 优先级：USB > 有线耳机 > 蓝牙 > 扬声器
        val priorityOrder = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        )

        for (type in priorityOrder) {
            val device = devices.find { it.type == type }
            if (device != null) {
                return getDeviceTypeName(device.type)
            }
        }
        return getDeviceTypeName(devices[0].type)
    }

    /**
     * 获取 Direct HiRes 模式的首选输出设备（优先有线/USB，回退到扬声器）
     */
    fun getPreferredDeviceForDirect(context: Context): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 23) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) return null

        // 优先级：USB > 有线耳机 > 蓝牙 > 扬声器
        val preferredTypes = intArrayOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        for (type in preferredTypes) {
            devices.find { it.type == type }?.let { return it }
        }
        return devices.firstOrNull()
    }

    private fun getDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "模拟线路"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "数字线路"
        AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
        else -> "设备($type)"
    }
}
