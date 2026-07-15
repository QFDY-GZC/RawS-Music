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

    const val BIT_DEPTH_AUTO = 0
    const val BIT_DEPTH_16 = 16
    const val BIT_DEPTH_24 = 24
    const val BIT_DEPTH_32 = 32
    const val BIT_DEPTH_FLOAT32 = 3201
    const val BIT_DEPTH_32_8_24 = 3224

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
        BIT_DEPTH_AUTO to "自动",
        BIT_DEPTH_16 to "16 bit",
        BIT_DEPTH_24 to "24 bit",
        BIT_DEPTH_32 to "32 bit",
        BIT_DEPTH_FLOAT32 to "Float32",
        BIT_DEPTH_32_8_24 to "32 (8.24)"
    )

    val STANDARD_BIT_DEPTH_OPTIONS = intArrayOf(
        BIT_DEPTH_AUTO,
        BIT_DEPTH_16,
        BIT_DEPTH_24,
        BIT_DEPTH_32,
        BIT_DEPTH_FLOAT32,
        BIT_DEPTH_32_8_24
    )

    // ==========================
    // v6d: 按输出引擎区分的采样率/位深范围
    // ==========================

    /** OpenSL ES 上限：16 bit · 44.1–96 kHz */
    const val OPENSL_MAX_SAMPLE_RATE = 96_000

    /** AAudio 上限：16–32 bit · 44.1–384 kHz */
    const val AAUDIO_MAX_SAMPLE_RATE = 384_000

    /** Direct Hi-Res 上限：16–32(8.24) bit · 44.1–384 kHz */
    const val DIRECT_MAX_SAMPLE_RATE = 384_000

    /** AudioTrack 普通输出上限：系统混音，高兼容，PCM ≤ 48 kHz / 24 bit / Stereo */
    const val AUDIO_TRACK_MAX_SAMPLE_RATE = 48_000

    fun getMaxSampleRateForMode(mode: AudioOutputMode): Int = when (mode) {
        AudioOutputMode.OPENSL_ES -> OPENSL_MAX_SAMPLE_RATE
        AudioOutputMode.AAUDIO -> AAUDIO_MAX_SAMPLE_RATE
        AudioOutputMode.DIRECT -> DIRECT_MAX_SAMPLE_RATE
        AudioOutputMode.AUDIO_TRACK -> AUDIO_TRACK_MAX_SAMPLE_RATE
    }

    /** Regular Android PCM backends are opened at 44.1 kHz or above. */
    fun getMinSampleRateForMode(mode: AudioOutputMode): Int = 44_100

    /** 位深选项按输出引擎过滤 */
    fun getBitDepthOptionsForMode(mode: AudioOutputMode): IntArray = when (mode) {
        AudioOutputMode.OPENSL_ES -> intArrayOf(BIT_DEPTH_AUTO, BIT_DEPTH_16)
        AudioOutputMode.AAUDIO -> intArrayOf(BIT_DEPTH_AUTO, BIT_DEPTH_16, BIT_DEPTH_24, BIT_DEPTH_32, BIT_DEPTH_FLOAT32)
        AudioOutputMode.DIRECT -> intArrayOf(BIT_DEPTH_AUTO, BIT_DEPTH_16, BIT_DEPTH_24, BIT_DEPTH_32, BIT_DEPTH_FLOAT32, BIT_DEPTH_32_8_24)
        AudioOutputMode.AUDIO_TRACK -> intArrayOf(BIT_DEPTH_AUTO, BIT_DEPTH_16, BIT_DEPTH_24, BIT_DEPTH_FLOAT32)
    }

    /** 采样率选项按输出引擎过滤（含 0=自动） */
    fun getSampleRateOptionsForMode(mode: AudioOutputMode): IntArray {
        val minRate = getMinSampleRateForMode(mode)
        val maxRate = getMaxSampleRateForMode(mode)
        return intArrayOf(0) + STANDARD_SAMPLE_RATES.filter { it in minRate..maxRate }.toIntArray()
    }

    fun normalizeTargetBitDepth(depth: Int): Int {
        return when (depth) {
            BIT_DEPTH_AUTO,
            BIT_DEPTH_16,
            BIT_DEPTH_24,
            BIT_DEPTH_32,
            BIT_DEPTH_FLOAT32,
            BIT_DEPTH_32_8_24 -> depth
            else -> BIT_DEPTH_AUTO
        }
    }

    fun ffmpegBitsForTarget(depth: Int): Int {
        return when (normalizeTargetBitDepth(depth)) {
            BIT_DEPTH_16 -> 16
            BIT_DEPTH_24,
            BIT_DEPTH_32_8_24 -> 24
            BIT_DEPTH_32,
            BIT_DEPTH_FLOAT32 -> 32
            else -> 0
        }
    }

    fun usbDeviceBitResolutionForTarget(depth: Int, sourceBits: Int): Int {
        return when (normalizeTargetBitDepth(depth)) {
            BIT_DEPTH_16 -> 16
            BIT_DEPTH_24,
            BIT_DEPTH_32_8_24 -> 24
            BIT_DEPTH_32,
            BIT_DEPTH_FLOAT32 -> 32
            else -> sourceBits
        }
    }

    fun usbDeviceSubslotForTarget(depth: Int, sourceBits: Int): Int {
        return when (normalizeTargetBitDepth(depth)) {
            BIT_DEPTH_16 -> 2
            BIT_DEPTH_24 -> 3
            BIT_DEPTH_32,
            BIT_DEPTH_FLOAT32,
            BIT_DEPTH_32_8_24 -> 4
            else -> if (sourceBits > 16) 4 else 2
        }
    }

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
        if (Build.VERSION.SDK_INT < 27) {
            Log.d(TAG, "API ${Build.VERSION.SDK_INT} < 27, Direct not supported")
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

        val hasBtOutput = devices.any { isBluetoothOutput(it.type) }
        val hasPhysicalDirectOutput = devices.any { isPhysicalDirectOutput(it.type) }

        // Android 12/13 上 AudioTrack.isDirectPlaybackSupported() 对 USB preferredDevice 经常返回 false，
        // 但实际 AudioTrack + preferredDevice 可以创建 96/192k PCM_32BIT 直出。
        // 因此设置页不能用 isDirectPlaybackSupported 作为唯一硬门槛；只要有 USB/有线输出，允许用户选择 Direct。
        if (hasPhysicalDirectOutput) {
            Log.d(TAG, "Direct available by physical output route: $deviceNames")
            return true
            }

        // Android 13 及以下，纯蓝牙连接时禁用 Direct（避免音频路由混乱）。
        if (Build.VERSION.SDK_INT <= 33 && hasBtOutput) {
            Log.d(TAG, "API <= 33 and only Bluetooth output present, Direct not available")
                return false
            }

        if (Build.VERSION.SDK_INT >= 29) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val preferredRate = getTargetSampleRate()
            val rates = buildList {
                if (preferredRate > 0) add(preferredRate)
                addAll(STANDARD_SAMPLE_RATES.reversedArray().toList())
            }.distinct()
            val encodings = targetEncodingCandidates()

            for (rate in rates) {
                for (encoding in encodings) {
                    try {
                        val format = AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(encoding)
                            .build()
                        if (AudioTrack.isDirectPlaybackSupported(format, attrs)) {
                            Log.d(TAG, "Direct available by AudioTrack probe: rate=$rate encoding=${encodingName(encoding)}")
                            return true
                        }
                    } catch (t: Throwable) {
                        Log.d(TAG, "Direct probe skipped invalid format: rate=$rate encoding=${encodingName(encoding)} error=${t.message}")
                    }
                }
            }
            Log.d(TAG, "Direct not available: no physical output and AudioTrack direct probe rejected all candidates")
            return false
        }

        return false
        }

    private fun isBluetoothOutput(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_HEARING_AID
    }

    private fun isPhysicalDirectOutput(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
            type == AudioDeviceInfo.TYPE_LINE_DIGITAL
    }

    /**
     * 获取设备支持的所有可用采样率
     */
    fun getAvailableSampleRates(context: Context): List<Int> {
        val rates = mutableListOf<Int>()
        // 使用 16bit 探测采样率，确保所有设备（含蓝牙）都能正确检测
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        for (rate in STANDARD_SAMPLE_RATES) {
            val bufferSize = try {
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
            } catch (t: Throwable) {
                Log.d(TAG, "getAvailableSampleRates: skip invalid rate=$rate error=${t.message}")
                -1
            }
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
        val currentMode = getCurrentOutputMode(context)
        // Direct 才绑定首选硬件设备；AudioTrack 普通模式保持系统 mixer/route 自己选择。
        val preferredDevice = if (currentMode == AudioOutputMode.DIRECT) getPreferredDeviceForDirect(context) else null
        val maxRateForMode = getMaxSampleRateForMode(currentMode)

        // 构建尝试顺序：用户设定优先，然后从高到低。
        // AudioTrack 普通模式固定为系统混音高兼容 lane，不探测 96k/192k/DSD/DoP。
        val tryRates = mutableListOf<Int>()
        val minRateForMode = getMinSampleRateForMode(currentMode)
        val preferred = if (preferredRate > 0) {
            preferredRate.coerceIn(minRateForMode, maxRateForMode)
        } else {
            0
        }
        if (preferred > 0) tryRates.add(preferred)
        // 按降序添加其他标准采样率（排除已添加的）
        for (r in STANDARD_SAMPLE_RATES.reversedArray()) {
            if (r in minRateForMode..maxRateForMode && r != preferred) tryRates.add(r)
        }

        val encodingCandidates = targetEncodingCandidates(currentMode)

        // 先用 getMinBufferSize 快速过滤：框架直接拒绝的采样率不必尝试
        // 对于 getMinBufferSize 返回负值的，仍尝试创建（可能框架不报告但硬件支持）
        val frameworkRates = mutableListOf<Int>()
        val unreportedRates = mutableListOf<Int>()
        for (rate in tryRates) {
            val minBuf = try {
                AudioTrack.getMinBufferSize(rate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            } catch (t: Throwable) {
                Log.d(TAG, "probeRateAndEncoding: getMinBufferSize rejected rate=$rate error=${t.message}")
                -1
            }
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
                // 估算：100ms 的数据量。按编码精确计算 frame size，
                // 不能把 24-bit packed 当作 32-bit，否则 24/192 的探测会失真。
                val channelCount = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
                val bytesPerFrame = channelCount * bytesPerSampleForEncoding(encoding)
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
            val chCount = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
            val frameSize = chCount * bytesPerSampleForEncoding(encoding)
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

    private fun encodingName(encoding: Int): String {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
            AudioFormat.ENCODING_PCM_16BIT -> "16BIT"
            else -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val enc32 = AudioFormat::class.java.getField("ENCODING_PCM_32BIT").getInt(null)
                        if (encoding == enc32) return "32BIT"
                    } catch (_: Exception) {}
                }
                pcm24PackedEncodingOrNull()?.let { enc24 ->
                    if (encoding == enc24) return "24BIT_PACKED"
                }
                "unknown($encoding)"
            }
        }
    }

    /**
     * 将 AudioFormat 编码映射为 FFmpeg 输出的比特深度
     * FLOAT → 32 (IEEE float WAV), 32BIT → 32 (S32LE), 24BIT → 24 (packed s24le WAV), 16BIT → 16
     */
    fun encodingToFFmpegBits(encoding: Int): Int {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            AudioFormat.ENCODING_PCM_16BIT -> 16
            else -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val enc32 = AudioFormat::class.java.getField("ENCODING_PCM_32BIT").getInt(null)
                        if (encoding == enc32) return 32
                    } catch (_: Exception) {}
                }
                pcm24PackedEncodingOrNull()?.let { enc24 ->
                    if (encoding == enc24) return 24
                }
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
        AppPreferences.Player.targetBitDepth = normalizeTargetBitDepth(depth)
    }

    /**
     * 获取 USB DAC 独占模式目标采样率
     */
    fun getUsbTargetSampleRate(): Int = AppPreferences.Player.usbTargetSampleRate

    /**
     * 设置 USB DAC 独占模式目标采样率
     */
    fun setUsbTargetSampleRate(rate: Int) {
        AppPreferences.Player.usbTargetSampleRate = rate
    }

    /**
     * 获取 USB DAC 独占模式目标比特深度
     */
    fun getUsbTargetBitDepth(): Int = AppPreferences.Player.usbTargetBitDepth

    /**
     * 设置 USB DAC 独占模式目标比特深度
     */
    fun setUsbTargetBitDepth(depth: Int) {
        AppPreferences.Player.usbTargetBitDepth = normalizeTargetBitDepth(depth)
    }

    fun pcm24PackedEncodingOrNull(): Int? {
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            AudioFormat::class.java.getField("ENCODING_PCM_24BIT_PACKED").getInt(null)
        } catch (_: Exception) {
            try {
                AudioFormat::class.java.getField("ENCODING_PCM_24BIT").getInt(null)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun bytesPerSampleForEncoding(encoding: Int): Int {
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) return 2
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) return 4
        pcm24PackedEncodingOrNull()?.let { if (encoding == it) return 3 }
        pcm32EncodingOrNull()?.let { if (encoding == it) return 4 }
        return 2
    }

    private fun pcm32EncodingOrNull(): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            AudioFormat::class.java.getField("ENCODING_PCM_32BIT").getInt(null)
        } catch (_: Exception) {
            null
        }
    }

    private fun targetEncodingCandidates(mode: AudioOutputMode = AppPreferences.Player.audioOutputMode): List<Int> {
        val depth = normalizeTargetBitDepth(getTargetBitDepth())
        val candidates = mutableListOf<Int>()
        fun add(encoding: Int?) {
            if (encoding != null && !candidates.contains(encoding)) {
                candidates.add(encoding)
            }
        }
        if (mode == AudioOutputMode.AUDIO_TRACK) {
            when (depth) {
                BIT_DEPTH_16 -> add(AudioFormat.ENCODING_PCM_16BIT)
                BIT_DEPTH_24 -> {
                    if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                    add(pcm24PackedEncodingOrNull())
                    add(AudioFormat.ENCODING_PCM_16BIT)
                }
                BIT_DEPTH_FLOAT32,
                BIT_DEPTH_AUTO -> {
                    if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                    add(AudioFormat.ENCODING_PCM_16BIT)
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                    add(AudioFormat.ENCODING_PCM_16BIT)
                }
            }
            return candidates
        }
        when (depth) {
            BIT_DEPTH_16 -> add(AudioFormat.ENCODING_PCM_16BIT)
            BIT_DEPTH_FLOAT32 -> {
                if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                add(pcm32EncodingOrNull())
                add(AudioFormat.ENCODING_PCM_16BIT)
            }
            BIT_DEPTH_24 -> {
                add(pcm24PackedEncodingOrNull())
                add(pcm32EncodingOrNull())
                if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                add(AudioFormat.ENCODING_PCM_16BIT)
            }
            BIT_DEPTH_32,
            BIT_DEPTH_32_8_24 -> {
                add(pcm32EncodingOrNull())
                add(pcm24PackedEncodingOrNull())
                if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                add(AudioFormat.ENCODING_PCM_16BIT)
            }
            else -> {
                if (Build.VERSION.SDK_INT >= 26) add(AudioFormat.ENCODING_PCM_FLOAT)
                add(pcm32EncodingOrNull())
                add(pcm24PackedEncodingOrNull())
                add(AudioFormat.ENCODING_PCM_16BIT)
            }
        }
        return candidates
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
        // 如果启用 SCO 模式，使用通话信道属性
        val scoMode = AppPreferences.Player.bluetoothScoMode
        val shouldUseSco = shouldUseScoMode(context)
        Log.d(TAG, "buildAudioAttributes: scoMode=$scoMode, shouldUseSco=$shouldUseSco")
        
        if (shouldUseSco) {
            Log.i(TAG, "buildAudioAttributes: using SCO mode (USAGE_VOICE_COMMUNICATION)")
            return buildScoAudioAttributes()
        }

        return buildMediaAudioAttributes(context)
    }

    /**
     * 构建纯媒体输出 AudioAttributes，不会自动切换到 SCO。
     * 用于 SCO 已配置但尚未激活时的 AudioTrack 创建，避免外放阶段被降级为通话音频格式。
     */
    fun buildMediaAudioAttributes(context: Context): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)

        when (getCurrentOutputMode(context)) {
            AudioOutputMode.DIRECT -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        builder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    } catch (_: Exception) {}
                }
            }
            AudioOutputMode.AAUDIO -> {
            }
            AudioOutputMode.AUDIO_TRACK -> {
            }
            AudioOutputMode.OPENSL_ES -> {
            }
        }
        return builder.build()
    }

    /**
     * 获取目标音频编码格式（根据比特深度设置）
     * 优先级：Float32 → 32bit → 24bit → 16bit
     */
    fun getTargetEncoding(): Int {
        return targetEncodingCandidates().firstOrNull() ?: AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * 获取输出模式的显示名称
     */
    fun getOutputModeLabel(mode: AudioOutputMode): String {
        return when (mode) {
            AudioOutputMode.OPENSL_ES -> "OpenSL ES"
            AudioOutputMode.AAUDIO -> "AAudio"
            AudioOutputMode.DIRECT -> "Direct (HiRes)"
            AudioOutputMode.AUDIO_TRACK -> "AudioTrack"
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
            AudioOutputMode.AUDIO_TRACK -> "系统混音，高兼容，PCM ≤ 48kHz / 24bit / Stereo"
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
            AudioOutputMode.AUDIO_TRACK -> true
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

    // ========== 蓝牙 SCO 通话信道管理 ==========

    /**
     * 检测当前蓝牙设备是否仅支持 HFP/HSP（无 A2DP）
     * 用于判断是否需要启用 SCO 通话信道输出
     */
    fun isBluetoothHfpOnlyDevice(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasA2dp = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val hasSco = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        // 仅有 SCO 而无 A2DP，说明是 HFP-only 设备
        return hasSco && !hasA2dp
    }

    /**
     * 检测当前是否有蓝牙 SCO 设备连接
     */
    fun isScoDeviceConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    /**
     * 检测 SCO 是否可用（需要蓝牙设备支持 HFP/HSP）
     */
    fun isScoAvailable(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.isBluetoothScoAvailableOffCall
    }

    /**
     * 启动蓝牙 SCO 连接
     * 注意：需要在主线程调用，且需要 RECORD_AUDIO 权限
     * @return true 如果成功启动
     */
    fun startBluetoothSco(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            if (!am.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "startBluetoothSco: SCO not available off call")
                return false
            }
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            Log.i(TAG, "startBluetoothSco: SCO started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startBluetoothSco failed: ${e.message}")
            false
        }
    }

    /**
     * 停止蓝牙 SCO 连接
     */
    fun stopBluetoothSco(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            Log.i(TAG, "stopBluetoothSco: SCO stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stopBluetoothSco failed: ${e.message}")
        }
    }

    /**
     * 获取 SCO 模式的 AudioAttributes
     * SCO 模式必须使用 USAGE_VOICE_COMMUNICATION 才能通过通话信道输出
     */
    fun buildScoAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * 检测当前是否有任何蓝牙音频设备连接（A2DP 或 SCO）
     */
    fun isAnyBluetoothDeviceConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    /**
     * 判断当前是否应该启用 SCO 模式
     * 根据用户设置和设备状态综合判断
     */
    fun shouldUseScoMode(context: Context): Boolean {
        val scoMode = AppPreferences.Player.bluetoothScoMode
        return when (scoMode) {
            0 -> false  // 关闭
            1 -> isBluetoothHfpOnlyDevice(context)  // 自动检测：仅 HFP 设备时启用
            2 -> isAnyBluetoothDeviceConnected(context)  // 强制开启：有蓝牙设备就启用
            else -> false
        }
    }

    /**
     * 获取蓝牙 SCO 模式标签
     */
    fun getScoModeLabel(mode: Int): String = when (mode) {
        0 -> "关闭"
        1 -> "自动检测"
        2 -> "强制开启"
        else -> "未知"
    }

    /**
     * 获取蓝牙 SCO 模式描述
     */
    fun getScoModeDescription(mode: Int): String = when (mode) {
        0 -> "不使用蓝牙通话信道"
        1 -> "仅在设备不支持蓝牙音乐时自动切换"
        2 -> "始终通过蓝牙通话信道输出"
        else -> ""
    }
}
